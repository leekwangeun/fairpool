package kr.carescape.multiverse.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.carescape.multiverse.game.EpisodeGenerator
import kr.carescape.multiverse.game.GameEngine
import kr.carescape.multiverse.game.GameState
import kr.carescape.multiverse.game.GimmickKind
import kr.carescape.multiverse.game.Stage
import kr.carescape.multiverse.game.StageJudge
import kr.carescape.multiverse.vehicle.Seat
import kr.carescape.multiverse.vehicle.Signals
import kr.carescape.multiverse.vehicle.Turn

/** The middle of E2: one drawing per gimmick, driven by the live vehicle signals. */
@Composable
fun GimmickArt(s: GameState, stage: Stage, signals: Signals, accent: Color, revealAnswer: Boolean, modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(28.dp)).background(C.card).padding(32.dp), contentAlignment = Alignment.Center) {
        when (stage.kind) {
            GimmickKind.DEFROST -> Defrost(stage, signals.defrostOn || s.progress.done, accent)
            GimmickKind.LIGHT_SHARD -> LightShard(stage, accent, revealAnswer)
            GimmickKind.PURIFY -> Purify(s, signals, accent)
            GimmickKind.FLASHLIGHT -> Flashlight(s, stage, signals, accent)
            GimmickKind.SAFE_DIAL -> SafeDial(s, stage, signals, accent)
            GimmickKind.TEMPERATURE -> Temperature(stage, signals, s.progress.done, accent)
            GimmickKind.FINAL_CODE -> FinalCode(s, accent)
        }
        if (s.justPassed) {
            Box(Modifier.fillMaxSize().clip(RoundedCornerShape(28.dp)).background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("통과", style = T.title.copy(fontSize = 120.sp, color = C.success, fontWeight = FontWeight.Black))
                    if (stage.digit >= 0) Text("단서 숫자 ${stage.digit}", style = T.title.copy(color = Color.White))
                }
            }
        }
    }
}

@Composable
private fun Defrost(stage: Stage, clear: Boolean, accent: Color) {
    val fog by animateFloatAsState(if (clear) 0f else 0.9f, tween(2200), label = "fog")
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Box(Modifier.width(720.dp).height(380.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val glass = Path().apply {
                    moveTo(size.width * 0.12f, 0f); lineTo(size.width * 0.88f, 0f)
                    lineTo(size.width, size.height); lineTo(0f, size.height); close()
                }
                drawPath(glass, Color(0xFF0D2236))
                drawPath(glass, accent, style = Stroke(6f))
            }
            Text("${stage.digit}", style = T.title.copy(fontSize = 220.sp, color = accent, fontWeight = FontWeight.Black))
            Canvas(Modifier.fillMaxSize().alpha(fog)) {
                // misty blobs over the glass
                for (i in 0 until 26) {
                    val x = (i * 73 % 100) / 100f * size.width
                    val y = (i * 41 % 100) / 100f * size.height
                    drawCircle(Color(0xFFDDE6F0), radius = size.minDimension * (0.18f + (i % 5) * 0.03f), center = Offset(x, y), alpha = 0.55f)
                }
            }
        }
        Text(if (clear) "김이 걷혔어요!" else "앞 유리가 뿌옇게 김이 서렸어요", style = T.big)
    }
}

@Composable
private fun LightShard(stage: Stage, accent: Color, reveal: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text("밝은 창문을 누르세요", style = T.big)
        Box(
            Modifier.width(420.dp).height(560.dp).clip(RoundedCornerShape(120.dp)).background(Color(0xFF10162A))
                .border(4.dp, C.line, RoundedCornerShape(120.dp)).padding(36.dp)
        ) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceEvenly) {
                Text("앞", style = T.sub, modifier = Modifier.align(Alignment.CenterHorizontally))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Window(Seat.DRIVER, stage, accent, reveal); Window(Seat.FRONT_RIGHT, stage, accent, reveal)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Window(Seat.REAR_LEFT, stage, accent, reveal); Window(Seat.REAR_RIGHT, stage, accent, reveal)
                }
                Text("뒤", style = T.sub, modifier = Modifier.align(Alignment.CenterHorizontally))
            }
        }
    }
}

@Composable
private fun Window(seat: Seat, stage: Stage, accent: Color, reveal: Boolean) {
    val lit = reveal && stage.window == seat
    Box(
        Modifier.width(150.dp).height(150.dp).clip(RoundedCornerShape(20.dp))
            .background(if (lit) accent.copy(alpha = 0.85f) else Color(0xFF05070F))
            .border(3.dp, if (lit) Color.White else C.line, RoundedCornerShape(20.dp))
            .clickable { GameEngine.answerWindow(seat) },
        contentAlignment = Alignment.Center
    ) { Text(seat.label, style = T.chip.copy(color = if (lit) Color(0xFF0B1020) else C.sub)) }
}

@Composable
private fun Purify(s: GameState, signals: Signals, accent: Color) {
    val clean by animateFloatAsState(s.progress.ratio, tween(800), label = "clean")
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(28.dp)) {
        Text(if (s.progress.done) "공기가 깨끗해졌어요!" else "독가스가 들어와요!", style = T.title)
        Box(Modifier.width(720.dp).height(64.dp).clip(RoundedCornerShape(32.dp)).background(Color(0xFF6B7A2A))) {
            Box(Modifier.fillMaxWidth(clean).height(64.dp).clip(RoundedCornerShape(32.dp)).background(C.success))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            Switch("내기 순환", signals.recirculation, accent)
            Switch("공기청정", signals.airCleaner, accent)
        }
    }
}

@Composable
private fun Switch(label: String, on: Boolean, accent: Color) {
    Box(
        Modifier.width(300.dp).height(140.dp).clip(RoundedCornerShape(24.dp)).background(if (on) accent else C.cardSoft),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = T.big.copy(color = if (on) Color(0xFF0B1020) else C.text))
            Text(if (on) "켜짐" else "꺼짐", style = T.body.copy(color = if (on) Color(0xFF0B1020) else C.sub))
        }
    }
}

@Composable
private fun Flashlight(s: GameState, stage: Stage, signals: Signals, accent: Color) {
    val on = signals.readingLight[s.childSeat] == true
    val count = if (s.progress.done) stage.blinks else s.progress.count
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(28.dp)) {
        Box(Modifier.size(260.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                if (on) drawCircle(accent.copy(alpha = 0.35f), radius = size.minDimension / 2)
                drawCircle(if (on) Color(0xFFFFF3B0) else Color(0xFF3A4060), radius = size.minDimension / 4)
            }
        }
        Text("${s.childSeat.label} 독서등 ${if (on) "켜짐" else "꺼짐"}", style = T.big)
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            repeat(stage.blinks) { i ->
                Box(Modifier.size(64.dp).clip(CircleShape).background(if (i < count) accent else C.cardSoft))
            }
        }
        Text("$count / ${stage.blinks} 번", style = T.body)
    }
}

@Composable
private fun SafeDial(s: GameState, stage: Stage, signals: Signals, accent: Color) {
    val armed = s.progress.armed || s.progress.done
    val angle by animateFloatAsState(signals.steeringAngle, tween(250), label = "wheel")
    Row(horizontalArrangement = Arrangement.spacedBy(48.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(440.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val r = size.minDimension / 2 - 12
                drawCircle(Color(0xFF10162A), r)
                drawCircle(C.line, r, style = Stroke(8f))
                // the green target arc (0 degrees is up)
                drawArc(
                    C.success, startAngle = -90f + stage.angle - StageJudge.ANGLE_TOLERANCE, sweepAngle = StageJudge.ANGLE_TOLERANCE * 2,
                    useCenter = false, topLeft = Offset(center.x - r, center.y - r), size = Size(r * 2, r * 2), style = Stroke(28f)
                )
                rotate(angle) {
                    drawLine(accent, center, Offset(center.x, center.y - r + 30), strokeWidth = 14f)
                }
                drawCircle(accent, 22f)
            }
            Text("${angle.toInt()}°", style = T.big, modifier = Modifier.align(Alignment.BottomCenter))
        }
        Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Text(if (armed) "다이얼 고정! 이제 깜빡이" else "바늘을 초록 칸에 맞추고 브레이크", style = T.big)
            Chip("브레이크 ${if (signals.brake) "밟음" else "뗌"}", filled = if (signals.brake) C.warn else null)
            Chip("깜빡이 ${when (signals.turn) { Turn.LEFT -> "왼쪽"; Turn.RIGHT -> "오른쪽"; Turn.OFF -> "꺼짐" }}", filled = if (signals.turn != Turn.OFF) accent else null)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                repeat(stage.turnCount) { i ->
                    Box(Modifier.size(52.dp).clip(CircleShape).background(if (armed && i < s.progress.count || s.progress.done) accent else C.cardSoft))
                }
            }
        }
    }
}

@Composable
private fun Temperature(stage: Stage, signals: Signals, done: Boolean, accent: Color) {
    val t = signals.hvacTempC
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Text("쪽지: '${EpisodeGenerator.temperatureWords(stage.temperature)}'", style = T.title.copy(color = accent))
        Text(t?.let { "%.1f°C".format(it) } ?: "--.-°C", style = T.title.copy(fontSize = 160.sp, color = if (done) C.success else C.text, fontWeight = FontWeight.Black))
        Text(if (done) "온도가 맞았어요!" else "공조 온도를 바꿔 보세요", style = T.big)
    }
}

@Composable
private fun FinalCode(s: GameState, accent: Color) {
    val code = s.episode?.code ?: ""
    var typed by remember(s.stageIndex) { mutableStateOf("") }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            repeat(code.length) { i ->
                Box(
                    Modifier.size(width = 110.dp, height = 130.dp).clip(RoundedCornerShape(20.dp)).background(C.cardSoft)
                        .border(3.dp, if (i < typed.length) accent else C.line, RoundedCornerShape(20.dp)),
                    contentAlignment = Alignment.Center
                ) { Text(typed.getOrNull(i)?.toString() ?: "", style = T.title.copy(fontSize = 72.sp)) }
            }
        }
        if (s.progress.wrongAnswers > 0) Text("틀렸어요 (${s.progress.wrongAnswers}번). 모은 단서를 다시 보세요.", style = T.body.copy(color = C.danger))
        for (row in listOf("123", "456", "789", "<0>")) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                for (k in row) {
                    val label = when (k) { '<' -> "지움"; '>' -> "확인"; else -> k.toString() }
                    Box(
                        Modifier.size(width = 150.dp, height = 84.dp).clip(RoundedCornerShape(16.dp))
                            .background(if (k == '>') accent else C.cardSoft)
                            .clickable {
                                when (k) {
                                    '<' -> typed = typed.dropLast(1)
                                    '>' -> { GameEngine.answerCode(typed); typed = "" }
                                    else -> if (typed.length < code.length) typed += k
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) { Text(label, style = T.big.copy(color = if (k == '>') Color(0xFF0B1020) else C.text)) }
                }
            }
        }
    }
}
