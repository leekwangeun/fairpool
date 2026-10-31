package kr.carescape.multiverse.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
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
import kr.carescape.multiverse.game.Theme
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
    val soft = theme.soft.asColor()
    val effects by VehicleBus.effects.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    val elapsed = ((now - s.startedAt) / 1000).coerceAtLeast(0)

    Box(Modifier.fillMaxSize().background(C.bg)) {
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).padding(start = 32.dp, end = 32.dp, top = 32.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Header(
                    left = {
                        Tile(accent, "${theme.number}")
                        Column {
                            Text(theme.title, style = T.title.copy(fontSize = 30.sp))
                            Text("${s.stageIndex + 1} / ${s.stageCount} 단계  ${stage.title}", style = T.chip.copy(color = accent, fontWeight = FontWeight.Bold, fontSize = 20.sp))
                        }
                        Stars(s.stageIndex, s.stageCount)
                    },
                    right = {
                        if (theme == Theme.CODE_RED && signals.evSoc != null) Chip("에너지 ${signals.evSoc}%", filled = accent)
                        Chip("%02d:%02d".format(elapsed / 60, elapsed % 60), bg = C.surface)
                        StatusPill("주차 중, 잠금")
                        MainButton("비상 전체 해제", color = C.danger, height = 64.dp, fontSize = 24) { GameEngine.emergencyAbort() }
                    }
                )
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    // left: the NPC and the digits collected so far
                    Card(Modifier.width(430.dp).fillMaxHeight(), color = soft, pad = 28.dp) {
                        Mascot(accent, 128.dp)
                        Text("${theme.npcRole} ${theme.npcName}", style = T.title.copy(fontSize = 28.sp))
                        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(C.card).padding(22.dp)) {
                            Text(stage.line, style = T.body)
                        }
                        Spacer(Modifier.weight(1f))
                        Text("모은 숫자", style = T.chip.copy(color = C.sub, fontWeight = FontWeight.Bold))
                        val total = episode.stages.count { it.digit >= 0 }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            for (i in 0 until total) {
                                val d = s.revealed.getOrNull(i)
                                Box(
                                    Modifier.size(width = 64.dp, height = 76.dp).clip(RoundedCornerShape(18.dp)).background(if (d != null) accent else C.card)
                                        .then(if (d == null) Modifier.border(3.dp, C.line, RoundedCornerShape(18.dp)) else Modifier),
                                    contentAlignment = Alignment.Center
                                ) { Text(d?.toString() ?: "?", style = T.title.copy(fontSize = 40.sp, color = if (d != null) Color.White else C.line)) }
                            }
                        }
                    }
                    // middle: the gimmick
                    GimmickArt(s, stage, signals, accent, soft,
                        revealAnswer = showTools || "smartglass" !in signals.supported || "demo" in signals.supported,
                        modifier = Modifier.weight(1f).fillMaxHeight())
                    // right: the crew
                    Column(Modifier.width(420.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        RoleCard("1열 기관장 (보호자)", C.primary, C.cardSoft, stage.parentTask, Modifier.fillMaxWidth())
                        RoleCard("2열 탐정 (아이, ${s.childSeat.label})", C.success, C.successSoft, stage.childTask, Modifier.fillMaxWidth())
                        if (s.showHint) {
                            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(C.warnSoft).padding(horizontal = 22.dp, vertical = 16.dp)) {
                                Text("힌트: ${stage.hint}", style = T.body.copy(color = C.warn, fontSize = 22.sp))
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        MainButton(if (s.listening) "듣는 중..." else "말하기", Modifier.fillMaxWidth(), color = accent, height = 112.dp, fontSize = 36) { GameEngine.talk() }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            GhostButton(if (s.showHint) "힌트 닫기" else "힌트 보기", Modifier.weight(1f)) { GameEngine.hint() }
                            GhostButton("대화", Modifier.weight(1f)) { GameEngine.openChat(true) }
                        }
                    }
                }
                // 4D effects strip
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("4D 연출", style = T.chip.copy(color = C.sub, fontWeight = FontWeight.Bold, fontSize = 20.sp))
                    if (effects.isEmpty()) Text("없음", style = T.chip.copy(color = C.sub))
                    for ((k, v) in effects) Chip("$k $v", bg = C.surface)
                }
            }
            if (showTools) DemoTools(s.childSeat)
        }
        if (s.chatOpen) NpcPanel(s, accent, soft)
        if (s.phase == Phase.PAUSED) PauseOverlay(s)
    }
}

@Composable
private fun PauseOverlay(s: GameState) {
    val reason = s.pauseReason ?: return
    val driving = reason == PauseReason.DRIVING
    val tone = if (driving) C.danger else C.warn
    Box(Modifier.fillMaxSize().background(Color(0xB8191F28)).clickable(enabled = false) {}, contentAlignment = Alignment.Center) {
        Card(Modifier.width(980.dp), border = tone, radius = 40.dp, pad = 48.dp, spacing = 20.dp) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Mascot(tone, 160.dp)
                Text("잠깐 멈췄어요!", style = T.hero.copy(fontSize = 64.sp))
                Box(Modifier.clip(RoundedCornerShape(50)).background(if (driving) C.dangerSoft else C.warnSoft).padding(horizontal = 32.dp, vertical = 16.dp)) {
                    Text(reason.title, style = T.title.copy(color = tone, fontSize = 34.sp))
                }
                Text(s.pauseDetail, style = T.big)
                if (!driving) Text("차가 움직이면 게임을 끝내고 차량 설정을 원래대로 돌려놓아요.", style = T.sub.copy(fontSize = 24.sp))
                if (driving) MainButton("홈으로", color = C.primary) { GameEngine.home() }
                else GhostButton("게임 끝내기", height = 104.dp) { GameEngine.emergencyAbort() }
            }
        }
    }
}

@Composable
private fun NpcPanel(s: GameState, accent: Color, soft: Color) {
    val theme = s.theme ?: return
    var text by remember { mutableStateOf("") }
    val list = rememberLazyListState()
    LaunchedEffect(s.chat.size) { if (s.chat.isNotEmpty()) list.animateScrollToItem(s.chat.size - 1) }
    Box(Modifier.fillMaxSize().background(Color(0x66191F28)).clickable { GameEngine.openChat(false) }) {
        Column(
            Modifier.align(Alignment.CenterEnd).width(700.dp).fillMaxHeight()
                .clip(RoundedCornerShape(topStart = 40.dp, bottomStart = 40.dp)).background(C.surface).clickable(enabled = false) {}.padding(36.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Mascot(accent, 88.dp)
                Column(Modifier.weight(1f)) {
                    Text("${theme.npcRole} ${theme.npcName}", style = T.title)
                    Text(theme.title, style = T.sub)
                }
                GhostButton("닫기", height = 64.dp) { GameEngine.openChat(false) }
            }
            LazyColumn(Modifier.weight(1f), state = list, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (s.chat.isEmpty()) item { Text("말하기를 누르고 ${theme.npcName}에게 물어봐요. \"힌트 줘!\", \"넌 누구야?\"", style = T.sub.copy(fontSize = 24.sp)) }
                items(s.chat) { line ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (line.fromPlayer) Arrangement.End else Arrangement.Start) {
                        val shape = if (line.fromPlayer) RoundedCornerShape(28.dp, 28.dp, 8.dp, 28.dp) else RoundedCornerShape(28.dp, 28.dp, 28.dp, 8.dp)
                        Box(Modifier.width(520.dp).clip(shape).background(if (line.fromPlayer) C.primary else soft).padding(horizontal = 24.dp, vertical = 18.dp)) {
                            Text(line.text, style = T.body.copy(fontSize = 27.sp, color = if (line.fromPlayer) Color.White else C.text))
                        }
                    }
                }
            }
            if (s.listening) {
                Box(Modifier.clip(RoundedCornerShape(50)).background(soft).padding(horizontal = 24.dp, vertical = 14.dp)) {
                    Text("듣고 있어요... 말해 보세요", style = T.big.copy(color = accent))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = text, onValueChange = { text = it }, modifier = Modifier.weight(1f), singleLine = true,
                    textStyle = T.body, placeholder = { Text("자판으로 묻기 (보호자)", style = T.sub) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { GameEngine.ask(text); text = "" }),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent, unfocusedBorderColor = C.line, cursorColor = accent,
                        focusedTextColor = C.text, unfocusedTextColor = C.text)
                )
                Chip("보내기", filled = accent, big = true) { GameEngine.ask(text); text = "" }
            }
            MainButton(if (s.listening) "그만 듣기" else "말하기", Modifier.fillMaxWidth(), color = accent, height = 120.dp, fontSize = 40) { GameEngine.talk() }
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
        Modifier.fillMaxWidth().background(C.text).padding(horizontal = 24.dp, vertical = 10.dp).horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically
    ) {
        Text("시연 도구", style = T.chip.copy(color = C.warn, fontWeight = FontWeight.Bold))
        Chip("성애 제거 ${if (sig.defroster) "끄기" else "켜기"}") { set { it.copy(defroster = !it.defroster) } }
        Chip("내기 순환") { set { it.copy(recirculation = !it.recirculation) } }
        Chip("공기청정") { set { it.copy(airCleaner = !it.airCleaner) } }
        Chip("독서등 ${child.label}") { set { it.copy(readingLight = it.readingLight + (child to !(it.readingLight[child] ?: false))) } }
        for (a in listOf(-90, -45, 0, 45, 90)) Chip("핸들 ${if (a > 0) "+" else ""}$a") { set { it.copy(steeringAngle = a.toFloat()) } }
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
