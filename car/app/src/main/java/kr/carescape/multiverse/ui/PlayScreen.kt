package kr.carescape.multiverse.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kr.carescape.multiverse.game.GameEngine
import kr.carescape.multiverse.game.GameState
import kr.carescape.multiverse.game.PauseReason
import kr.carescape.multiverse.game.Phase
import kr.carescape.multiverse.vehicle.Seat
import kr.carescape.multiverse.vehicle.Signals
import kr.carescape.multiverse.vehicle.Turn
import kr.carescape.multiverse.vehicle.VehicleBus

/** E2 with its overlays E3 (pause) and E6 (NPC talk). */
@Composable
fun PlayScreen(s: GameState, signals: Signals, showTools: Boolean) {
    val episode = s.episode ?: return
    val theme = episode.theme
    val stage = s.stage ?: return
    val accent = theme.accent.asColor()
    val effects by VehicleBus.effects.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    val elapsed = ((now - s.startedAt) / 1000).coerceAtLeast(0)

    Box(Modifier.fillMaxSize().background(C.bg)) {
        Column(Modifier.fillMaxSize()) {
            TopBar(
                left = {
                    Text(theme.title, style = T.big.copy(color = accent))
                    Spacer(Modifier.width(24.dp))
                    Text("단계 ${s.stageIndex + 1} / ${s.stageCount}", style = T.body)
                },
                middle = {
                    Box(Modifier.width(300.dp).height(16.dp).clip(RoundedCornerShape(8.dp)).background(C.cardSoft)) {
                        Box(Modifier.fillMaxWidth((s.stageIndex + s.progress.ratio) / s.stageCount).height(16.dp).clip(RoundedCornerShape(8.dp)).background(accent))
                    }
                    Text("경과 %02d:%02d".format(elapsed / 60, elapsed % 60), style = T.body)
                    if (theme == kr.carescape.multiverse.game.Theme.CODE_RED && signals.evSoc != null) Chip("에너지 ${signals.evSoc}%", filled = accent)
                },
                right = { MainButton("비상 전체 해제", color = C.danger) { GameEngine.emergencyAbort() } }
            )
            Row(Modifier.weight(1f).padding(24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                // left: the NPC and the clues so far
                Card(Modifier.width(460.dp).fillMaxHeight()) {
                    Box(Modifier.size(120.dp).clip(CircleShape).background(accent.copy(alpha = 0.25f)), contentAlignment = Alignment.Center) {
                        Text(theme.npcName.take(1), style = T.title.copy(color = accent))
                    }
                    Text("${theme.npcRole} ${theme.npcName}", style = T.big)
                    Text(stage.title, style = T.body.copy(color = accent))
                    Text("\"${stage.line}\"", style = T.body, modifier = Modifier.weight(1f))
                    Text("모은 단서", style = T.sub)
                    val total = episode.stages.count { it.digit >= 0 }
                    Text((0 until total).joinToString(" ") { s.revealed.getOrNull(it)?.toString() ?: "_" }, style = T.title.copy(fontSize = 52.sp, letterSpacing = 4.sp))
                }
                // middle: the gimmick
                GimmickArt(s, stage, signals, accent, revealAnswer = showTools || "smartglass" !in signals.supported || "demo" in signals.supported,
                    modifier = Modifier.weight(1f).fillMaxHeight())
                // right: the crew
                Card(Modifier.width(400.dp).fillMaxHeight()) {
                    Text("1열 기관장 (보호자)", style = T.sub.copy(color = accent))
                    Text(stage.parentTask, style = T.body)
                    Spacer(Modifier.height(8.dp))
                    Text("2열 탐정 (아이, ${s.childSeat.label})", style = T.sub.copy(color = accent))
                    Text(stage.childTask, style = T.body, modifier = Modifier.weight(1f))
                    if (s.showHint) Text("힌트: ${stage.hint}", style = T.body.copy(color = C.warn))
                    MainButton(if (s.listening) "듣는 중..." else "말하기", Modifier.fillMaxWidth(), color = accent) { GameEngine.talk() }
                    GhostButton("힌트", Modifier.fillMaxWidth()) { GameEngine.hint() }
                    GhostButton("대화 보기", Modifier.fillMaxWidth()) { GameEngine.openChat(true) }
                }
            }
            // 4D effects strip
            Row(
                Modifier.fillMaxWidth().background(C.surface).padding(horizontal = 48.dp, vertical = 14.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically
            ) {
                Text("4D 연출", style = T.chip.copy(color = C.sub))
                if (effects.isEmpty()) Text("없음", style = T.chip.copy(color = C.sub))
                for ((k, v) in effects) Chip("$k $v")
            }
            if (showTools) DemoTools(s.childSeat)
        }
        if (s.chatOpen) NpcPanel(s, accent)
        if (s.phase == Phase.PAUSED) PauseOverlay(s)
    }
}

@Composable
private fun PauseOverlay(s: GameState) {
    val reason = s.pauseReason ?: return
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.8f)).clickable(enabled = false) {}, contentAlignment = Alignment.Center) {
        Card(Modifier.width(900.dp), border = if (reason == PauseReason.DRIVING) C.danger else C.warn) {
            Text("잠깐 멈췄어요", style = T.title)
            Text(reason.title, style = T.big.copy(color = if (reason == PauseReason.DRIVING) C.danger else C.warn))
            Text(s.pauseDetail, style = T.body)
            if (reason != PauseReason.DRIVING) Text("해결되면 자동으로 이어집니다.", style = T.sub)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (reason == PauseReason.DRIVING) MainButton("홈으로") { GameEngine.home() }
                else GhostButton("게임 종료") { GameEngine.emergencyAbort() }
            }
        }
    }
}

@Composable
private fun NpcPanel(s: GameState, accent: Color) {
    val theme = s.theme ?: return
    var text by remember { mutableStateOf("") }
    val list = rememberLazyListState()
    LaunchedEffect(s.chat.size) { if (s.chat.isNotEmpty()) list.animateScrollToItem(s.chat.size - 1) }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)).clickable { GameEngine.openChat(false) }) {
        Column(
            Modifier.align(Alignment.CenterEnd).width(640.dp).fillMaxHeight().background(C.surface).clickable(enabled = false) {}.padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(theme.npcName, style = T.big.copy(color = accent))
                    Text(theme.npcRole, style = T.sub)
                }
                Chip("닫기") { GameEngine.openChat(false) }
            }
            LazyColumn(Modifier.weight(1f), state = list, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (s.chat.isEmpty()) item { Text("말하기 버튼을 누르고 ${theme.npcName}에게 물어보세요. 예: \"힌트 줘\", \"넌 누구야?\"", style = T.sub) }
                items(s.chat) { line ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (line.fromPlayer) Arrangement.End else Arrangement.Start) {
                        Box(
                            Modifier.clip(RoundedCornerShape(20.dp)).background(if (line.fromPlayer) C.primary else C.card).padding(18.dp)
                        ) { Text(line.text, style = T.body) }
                    }
                }
            }
            if (s.listening) Text("듣는 중... 말해 주세요", style = T.body.copy(color = accent, fontWeight = FontWeight.Bold))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = text, onValueChange = { text = it }, modifier = Modifier.weight(1f), singleLine = true,
                    textStyle = T.body, placeholder = { Text("자판으로 묻기", style = T.sub) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { GameEngine.ask(text); text = "" }),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent, unfocusedBorderColor = C.line, cursorColor = accent)
                )
                Chip("보내기", filled = accent) { GameEngine.ask(text); text = "" }
                Chip(if (s.listening) "그만" else "말하기") { GameEngine.talk() }
            }
        }
    }
}

/**
 * The demo strip (settings: 시연 도구). Writes straight into the signal bus, the same place the SDK listeners
 * write, so it can show gimmicks the emulator's Virtual Console has no control for.
 */
@Composable
fun DemoTools(child: Seat) {
    val sig by VehicleBus.signals.collectAsState()
    fun set(change: (Signals) -> Signals) = VehicleBus.update(change)
    Row(
        Modifier.fillMaxWidth().background(Color(0xFF0F1426)).padding(horizontal = 24.dp, vertical = 10.dp).horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically
    ) {
        Text("시연 도구", style = T.chip.copy(color = C.warn))
        Chip("성애 제거 ${if (sig.defroster) "끄기" else "켜기"}") { set { it.copy(defroster = !it.defroster) } }
        Chip("내기 순환") { set { it.copy(recirculation = !it.recirculation) } }
        Chip("공기청정") { set { it.copy(airCleaner = !it.airCleaner) } }
        Chip("독서등 ${child.label}") { set { it.copy(readingLight = it.readingLight + (child to !(it.readingLight[child] ?: false))) } }
        Chip("핸들 -90") { set { it.copy(steeringAngle = -90f) } }
        Chip("핸들 -45") { set { it.copy(steeringAngle = -45f) } }
        Chip("핸들 0") { set { it.copy(steeringAngle = 0f) } }
        Chip("핸들 +45") { set { it.copy(steeringAngle = 45f) } }
        Chip("핸들 +90") { set { it.copy(steeringAngle = 90f) } }
        Chip("브레이크 ${if (sig.brake) "떼기" else "밟기"}") { set { it.copy(brake = !it.brake) } }
        Chip("왼쪽 깜빡이") { set { it.copy(turn = if (it.turn == Turn.LEFT) Turn.OFF else Turn.LEFT) } }
        Chip("오른쪽 깜빡이") { set { it.copy(turn = if (it.turn == Turn.RIGHT) Turn.OFF else Turn.RIGHT) } }
        Chip("온도 -0.5") { set { it.copy(hvacTempC = (it.hvacTempC ?: 22f) - 0.5f) } }
        Chip("온도 +0.5") { set { it.copy(hvacTempC = (it.hvacTempC ?: 22f) + 0.5f) } }
        Chip("문 ${if (sig.doorOpen[child] == true) "닫기" else "열기"}") { set { it.copy(doorOpen = it.doorOpen + (child to !(it.doorOpen[child] ?: false))) } }
        Chip("벨트 ${if (sig.belted[child] == true) "풀기" else "매기"}") { set { it.copy(belted = it.belted + (child to !(it.belted[child] ?: false))) } }
        Chip("기어 ${if (sig.gear == "P") "D" else "P"}") { set { it.copy(gear = if (it.gear == "P") "D" else "P") } }
    }
}
