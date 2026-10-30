package kr.carescape.multiverse.game

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kr.carescape.multiverse.ai.BuiltinMaster
import kr.carescape.multiverse.ai.GameMaster
import kr.carescape.multiverse.ai.PleosLlmMaster
import kr.carescape.multiverse.data.Prefs
import kr.carescape.multiverse.vehicle.Seat
import kr.carescape.multiverse.vehicle.Signals
import kr.carescape.multiverse.vehicle.Snapshot
import kr.carescape.multiverse.vehicle.VehicleBus
import kr.carescape.multiverse.voice.Voice
import java.util.Calendar

/**
 * The game as a state machine: HOME → SAFETY → PLAYING ⇄ PAUSED → CLEARED.
 *
 * It listens to [VehicleBus.signals] for two things on every reading: the safety guard (doors, belt, driving)
 * and the current puzzle. The screens only read [state] and call the actions below.
 */
object GameEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(GameState())
    val state: StateFlow<GameState> = _state

    lateinit var prefs: Prefs
        private set
    private var appContext: Context? = null
    private var llm: PleosLlmMaster? = null
    private var snapshot: Snapshot? = null
    private var watcher: Job? = null
    private var setupJob: Job? = null
    private var advancing = false
    private val effects = Effects(scope) { VehicleBus.controls }

    private val controls get() = VehicleBus.controls

    /** Called once by the service (and the activity, whichever starts first). */
    fun attach(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        prefs = Prefs(context.applicationContext)
        watcher = scope.launch {
            var before = VehicleBus.signals.value
            VehicleBus.signals.collect { now ->
                onSignals(before, now)
                before = now
            }
        }
        // a game that ended with the app killed left the car changed: put it back once the SDK is up
        scope.launch {
            delay(3000)
            val saved = prefs.savedSnapshot
            if (saved != null && _state.value.phase == Phase.HOME) {
                controls.restore(saved)
                prefs.savedSnapshot = null
                _state.update { it.copy(notice = "지난번에 끝나지 않은 게임의 차량 설정을 원래대로 돌려놓았어요.") }
            }
        }
        refreshContext()
    }

    private fun master(): GameMaster {
        if (prefs.aiMode != "llm") return BuiltinMaster
        val ctx = appContext ?: return BuiltinMaster
        return llm ?: PleosLlmMaster(ctx).also { llm = it }
    }

    val masterName: String get() = if (appContext != null && prefs.aiMode == "llm") "차량 LLM (Pleos)" else "내장 생성기"

    private fun hour() = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)

    fun refreshContext() {
        if (appContext == null) return
        _state.update { it.copy(context = ContextReader.read(VehicleBus.signals.value, it.childSeat, hour(), prefs.place)) }
    }

    fun setChildSeat(seat: Seat) {
        if (seat == Seat.DRIVER) return
        _state.update { it.copy(childSeat = seat) }
        refreshContext()
    }

    fun dismissNotice() = _state.update { it.copy(notice = null) }

    // ---- E1: safety sandbox ------------------------------------------------------------------------------

    fun chooseTheme(theme: Theme) {
        refreshContext()
        val child = _state.value.childSeat
        val ctx = _state.value.context
        _state.value = GameState(
            phase = Phase.SAFETY, theme = theme, childSeat = child, context = ctx, generating = true,
            checklist = SafetyGuard.checklist(VehicleBus.signals.value, child, prefs.manualPass)
        )
        setupJob?.cancel()
        setupJob = scope.launch {
            // 1) back up what we are about to change, before changing anything
            val snap = runCatching { controls.snapshot() }.getOrDefault(Snapshot())
            snapshot = snap
            prefs.savedSnapshot = snap
            // 2) the sandbox: doors, windows, glove box (the parent has put the treasure in by now)
            val c = controls
            c.lockDoors(true)
            c.closeWindows()
            c.lockWindows(true)
            c.lockGloveBox(true)
            // 3) the episode for this car, right now
            val episode = runCatching { master().episode(theme, ctx, System.currentTimeMillis()) }
                .getOrElse { BuiltinMaster.episode(theme, ctx, System.currentTimeMillis()) }
            _state.update { it.copy(episode = episode, generating = false) }
        }
    }

    fun begin() {
        val s = _state.value
        val episode = s.episode ?: return
        if (s.phase != Phase.SAFETY || !s.allChecksPassed) return
        _state.update {
            it.copy(phase = Phase.PLAYING, stageIndex = 0, progress = StageProgress(), revealed = emptyList(),
                startedAt = System.currentTimeMillis(), hintsUsed = 0, chat = emptyList())
        }
        effects.enterTheme(episode.theme)
        val first = episode.stages.first()
        effects.enterStage(episode.theme, first, s.childSeat)
        Voice.say(episode.intro + " " + first.line)
    }

    /** Back from E1 without playing: undo the locks. */
    fun cancelSetup() {
        setupJob?.cancel()
        restore(keepGloveOpen = false)
        _state.update { GameState(childSeat = it.childSeat, context = it.context) }
    }

    // ---- signals -----------------------------------------------------------------------------------------

    private fun onSignals(before: Signals, now: Signals) {
        val s = _state.value
        when (s.phase) {
            Phase.HOME -> if (before.wiperOn != now.wiperOn || before.lat != now.lat || before.evSoc != now.evSoc) refreshContext()
            Phase.SAFETY -> {
                if (!now.parked || now.speedKmh > SafetyGuard.MOVING_KMH) {
                    cancelSetup()
                    _state.update { it.copy(notice = "차가 움직여서 준비를 멈추고 잠금을 풀었어요.") }
                } else {
                    _state.update { it.copy(checklist = SafetyGuard.checklist(now, it.childSeat, prefs.manualPass)) }
                }
            }
            Phase.PLAYING -> {
                val reason = SafetyGuard.pauseReason(now, s.childSeat)
                if (reason != null) pause(reason.first, reason.second)
                else judge(before, now)
            }
            Phase.PAUSED -> {
                if (s.pauseReason == PauseReason.DRIVING) return
                val reason = SafetyGuard.pauseReason(now, s.childSeat)
                when {
                    reason == null -> resume()
                    reason.first == PauseReason.DRIVING -> pause(reason.first, reason.second)
                    reason.first != s.pauseReason -> _state.update { it.copy(pauseReason = reason.first, pauseDetail = reason.second) }
                }
            }
            Phase.CLEARED -> {}
        }
    }

    private fun judge(before: Signals, now: Signals) {
        val s = _state.value
        val stage = s.stage ?: return
        if (advancing) return
        val next = StageJudge.step(stage, s.childSeat, before, now, s.progress)
        if (next != s.progress) _state.update { it.copy(progress = next) }
        if (next.done) passed()
    }

    private fun pause(reason: PauseReason, detail: String) {
        effects.stopAll()
        Voice.stopSpeaking()
        Voice.stopListening()
        if (reason == PauseReason.DRIVING) {
            // the game ends for good: give the car back before anything else
            restore(keepGloveOpen = false)
        } else {
            Voice.say("잠깐 멈출게요. ${reason.title}")
        }
        _state.update { it.copy(phase = Phase.PAUSED, pauseReason = reason, pauseDetail = detail, listening = false, chatOpen = false) }
    }

    private fun resume() {
        val s = _state.value
        val episode = s.episode ?: return
        _state.update { it.copy(phase = Phase.PLAYING, pauseReason = null, pauseDetail = "") }
        s.stage?.let { effects.enterStage(episode.theme, it, s.childSeat) }
        Voice.say("다시 시작할게요. " + (s.stage?.line ?: ""))
    }

    // ---- stages ------------------------------------------------------------------------------------------

    private fun passed() {
        val s = _state.value
        val episode = s.episode ?: return
        val stage = s.stage ?: return
        advancing = true
        effects.passed(episode.theme, stage)
        val revealed = if (stage.digit >= 0) s.revealed + stage.digit else s.revealed
        _state.update { it.copy(progress = StageProgress(done = true, ratio = 1f), justPassed = true, revealed = revealed, showHint = false) }
        if (stage.kind == GimmickKind.FINAL_CODE) {
            advancing = false
            clear()
            return
        }
        Voice.say("통과! 단서 숫자는 ${EpisodeGenerator.digitWord(stage.digit)}.")
        scope.launch {
            delay(1800)
            val nextIndex = _state.value.stageIndex + 1
            val next = episode.stages.getOrNull(nextIndex)
            _state.update { it.copy(stageIndex = nextIndex, progress = StageProgress(), justPassed = false) }
            advancing = false
            if (next != null && _state.value.phase == Phase.PLAYING) {
                effects.enterStage(episode.theme, next, _state.value.childSeat)
                Voice.say(next.line)
            }
        }
    }

    /** LIGHT_SHARD: the parent taps the window the child named. */
    fun answerWindow(seat: Seat) {
        val s = _state.value
        val stage = s.stage ?: return
        if (s.phase != Phase.PLAYING || stage.kind != GimmickKind.LIGHT_SHARD || advancing) return
        val p = StageJudge.answerWindow(stage, seat, s.progress)
        _state.update { it.copy(progress = p) }
        if (p.done) passed() else Voice.say("${seat.label}은 어두워요. 다시 찾아봐요!")
    }

    /** FINAL_CODE: typed on the keypad or said aloud. */
    fun answerCode(text: String) {
        val s = _state.value
        val episode = s.episode ?: return
        val stage = s.stage ?: return
        if (s.phase != Phase.PLAYING || stage.kind != GimmickKind.FINAL_CODE || advancing) return
        val p = StageJudge.answerCode(episode.code, text, s.progress)
        _state.update { it.copy(progress = p) }
        if (p.done) passed() else Voice.say("땡! 숫자 순서를 다시 확인해 봐요.")
    }

    fun hint() {
        val stage = _state.value.stage ?: return
        _state.update { it.copy(hintsUsed = it.hintsUsed + if (it.showHint) 0 else 1, showHint = !it.showHint) }
        if (_state.value.showHint) Voice.say(stage.hint)
    }

    // ---- E6: talking to the NPC --------------------------------------------------------------------------

    fun openChat(open: Boolean) = _state.update { it.copy(chatOpen = open) }

    /** A question to the NPC. Answers to the current puzzle inside the sentence count too. */
    fun ask(question: String) {
        val q = question.trim()
        if (q.isEmpty()) return
        val s = _state.value
        val episode = s.episode ?: return
        val stage = s.stage ?: return
        _state.update { it.copy(chat = it.chat + ChatLine(true, q)) }
        when (stage.kind) {
            GimmickKind.FINAL_CODE -> if (StageJudge.digitsIn(q).length >= episode.code.length) { answerCode(q); return }
            GimmickKind.LIGHT_SHARD -> StageJudge.windowIn(q)?.let { answerWindow(it); return }
            else -> {}
        }
        scope.launch {
            val answer = runCatching { master().reply(episode, stage, s.context, s.chat, q) }
                .getOrElse { BuiltinMaster.reply(episode, stage, s.context, s.chat, q) }
            _state.update { it.copy(chat = it.chat + ChatLine(false, answer)) }
            Voice.say(answer)
        }
    }

    /** The "말하기" button: one sentence from the car microphone goes to [ask]. */
    fun talk() {
        if (_state.value.listening) {
            Voice.stopListening()
            return
        }
        if (!Voice.canListen) {
            _state.update { it.copy(chatOpen = true, notice = "이 차에서는 음성 인식을 쓸 수 없어요. 자판으로 물어보세요.") }
            return
        }
        _state.update { it.copy(listening = true, chatOpen = true) }
        Voice.listen(onText = { ask(it) }, onEnd = { _state.update { s -> s.copy(listening = false) } })
    }

    // ---- E4: clear, and every way out --------------------------------------------------------------------

    private fun clear() {
        val episode = _state.value.episode ?: return
        effects.finale(episode.theme)
        controls.lockGloveBox(false)
        _state.update { it.copy(phase = Phase.CLEARED, clearedAt = System.currentTimeMillis(), justPassed = false, chatOpen = false) }
        Voice.say("철컥! " + episode.outro)
        scope.launch {
            delay(6000)
            // the glove box stays open: the treasure is still inside
            val items = restore(keepGloveOpen = true)
            _state.update { it.copy(restored = items) }
        }
    }

    /** Puts the car back as the snapshot found it. Returns what was restored, for E4. */
    private fun restore(keepGloveOpen: Boolean): List<String> {
        val snap = snapshot ?: prefs.savedSnapshot ?: return emptyList()
        effects.stopAll()
        val target = if (keepGloveOpen) snap.copy(gloveBoxLocked = null) else snap
        controls.restore(target)
        snapshot = null
        prefs.savedSnapshot = null
        return target.restoredItems()
    }

    /** The red button on every play screen: stop, unlock everything, put the car back. */
    fun emergencyAbort() {
        setupJob?.cancel()
        Voice.stopSpeaking()
        Voice.stopListening()
        restore(keepGloveOpen = false)
        val c = controls
        c.lockDoors(false)
        c.lockWindows(false)
        c.lockGloveBox(false)
        advancing = false
        _state.update {
            GameState(childSeat = it.childSeat, context = it.context,
                notice = "비상 전체 해제: 모든 잠금을 풀고 차량 설정을 원래대로 돌려놓았어요.")
        }
    }

    /** "홈으로" on E3 (after driving) and E4. */
    fun home() {
        if (_state.value.phase != Phase.HOME && (snapshot != null || prefs.savedSnapshot != null)) restore(keepGloveOpen = _state.value.phase == Phase.CLEARED)
        advancing = false
        _state.update { GameState(childSeat = it.childSeat, context = it.context) }
        refreshContext()
    }

    /** "다시 하기": the same theme with a new episode. */
    fun replay() {
        val theme = _state.value.theme ?: return
        home()
        chooseTheme(theme)
    }
}
