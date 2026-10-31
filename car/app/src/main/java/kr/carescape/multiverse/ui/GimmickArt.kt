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
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
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
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** The middle card of E2: one drawing per gimmick, driven by the live vehicle signals. */
@Composable
fun GimmickArt(s: GameState, stage: Stage, signals: Signals, accent: Color, soft: Color, revealAnswer: Boolean, modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(32.dp)).background(C.card).padding(28.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp)) {
            when (stage.kind) {
                GimmickKind.DEFROST -> Defrost(stage, signals.defrostOn || s.progress.done, accent)
                GimmickKind.LIGHT_SHARD -> LightShard(stage, accent, revealAnswer)
                GimmickKind.PURIFY -> Purify(s, signals, accent)
                GimmickKind.FLASHLIGHT -> Flashlight(s, stage, signals, accent, soft)
                GimmickKind.SAFE_DIAL -> SafeDial(s, stage, signals, accent, soft)
                GimmickKind.TEMPERATURE -> Temperature(stage, signals, s.progress.done, accent, soft)
                GimmickKind.FINAL_CODE -> FinalCode(s, accent)
            }
        }
        if (s.justPassed) {
            Box(Modifier.fillMaxSize().clip(RoundedCornerShape(28.dp)).background(Color.White.copy(alpha = 0.9f)), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    StarShape(140.dp, C.star, null)
                    Text("통과!", style = T.hero.copy(fontSize = 96.sp, color = C.success))
                    if (stage.digit >= 0) Text("새 숫자 ${stage.digit}", style = T.title.copy(color = accent, fontSize = 44.sp))
                }
            }
        }
    }
}

@Composable
private fun Caption(text: String) = Text(text, style = T.hero.copy(fontSize = 40.sp))

@Composable
private fun Hint(text: String) = Text(text, style = T.sub.copy(fontSize = 24.sp))

@Composable
private fun Defrost(stage: Stage, clear: Boolean, accent: Color) {
    val fog by animateFloatAsState(if (clear) 0f else 0.92f, tween(2200), label = "fog")
    Box(Modifier.width(640.dp).height(360.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val glass = Path().apply { moveTo(size.width * 0.12f, 0f); lineTo(size.width * 0.88f, 0f); lineTo(size.width, size.height); lineTo(0f, size.height); close() }
            drawPath(glass, Color(0xFF0D3B5C))
            drawPath(glass, accent, style = Stroke(10f, join = StrokeJoin.Round))
            for ((x, y, r) in listOf(Triple(0.25f, 0.25f, 10f), Triple(0.3f, 0.4f, 6f), Triple(0.2f, 0.5f, 8f))) drawCircle(Color.White.copy(alpha = 0.5f), r, Offset(size.width * x, size.height * y))
        }
        Text("${stage.digit}", style = T.hero.copy(fontSize = 240.sp, color = accent, fontWeight = FontWeight.Black))
        Canvas(Modifier.fillMaxSize().alpha(fog)) {
            for ((x, y, r) in listOf(Triple(0.6f, 0.33f, 0.2f), Triple(0.78f, 0.58f, 0.24f), Triple(0.47f, 0.7f, 0.18f), Triple(0.88f, 0.22f, 0.13f), Triple(0.35f, 0.3f, 0.16f)))
                drawCircle(Color(0xFFE8F1F8), radius = size.width * r, center = Offset(size.width * x, size.height * y))
        }
    }
    Caption(if (clear) "안개가 걷혔어요! 숫자를 말해요" else "앞 유리에 안개가 꼈어요")
    Hint("앞 유리 성애 제거 또는 사이드미러 열선을 켜면 통과")
}

@Composable
private fun LightShard(stage: Stage, accent: Color, reveal: Boolean) {
    Caption("밝은 창문을 찾아요!")
    Column(
        Modifier.clip(RoundedCornerShape(140.dp)).background(C.cardSoft).border(6.dp, C.line, RoundedCornerShape(140.dp)).padding(horizontal = 44.dp, vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Text("앞", style = T.chip.copy(color = C.sub))
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) { Window(Seat.DRIVER, stage, accent, reveal); Window(Seat.FRONT_RIGHT, stage, accent, reveal) }
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) { Window(Seat.REAR_LEFT, stage, accent, reveal); Window(Seat.REAR_RIGHT, stage, accent, reveal) }
        Text("뒤", style = T.chip.copy(color = C.sub))
    }
}

@Composable
private fun Window(seat: Seat, stage: Stage, accent: Color, reveal: Boolean) {
    val lit = reveal && stage.window == seat
    Column(
        Modifier.size(width = 200.dp, height = 160.dp).clip(RoundedCornerShape(32.dp)).background(if (lit) accent else C.text)
            .clickable { GameEngine.answerWindow(seat) },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
    ) {
        Canvas(Modifier.size(56.dp)) {
            val c = Offset(size.width / 2, size.height / 2)
            if (lit) {
                drawCircle(Color.White, size.width * 0.22f, c)
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    drawLine(Color.White, Offset(c.x + (size.width * 0.34f * cos(a)).toFloat(), c.y + (size.width * 0.34f * sin(a)).toFloat()),
                        Offset(c.x + (size.width * 0.48f * cos(a)).toFloat(), c.y + (size.width * 0.48f * sin(a)).toFloat()), 5f, StrokeCap.Round)
                }
            } else {
                drawCircle(C.sub, size.width * 0.38f, c)
                drawCircle(C.text, size.width * 0.32f, Offset(c.x + size.width * 0.16f, c.y - size.width * 0.1f))
            }
        }
        Text(seat.label, style = T.chip.copy(color = Color.White, fontWeight = FontWeight.Bold))
    }
}

@Composable
private fun Purify(s: GameState, signals: Signals, accent: Color) {
    val clean by animateFloatAsState(s.progress.ratio, tween(800), label = "clean")
    Caption(if (s.progress.done) "공기가 깨끗해졌어요!" else "먼지 막기 ${(clean * 100).toInt()}%")
    Box(Modifier.width(640.dp).height(72.dp).clip(RoundedCornerShape(36.dp)).background(Color(0xFFA89E70))) {
        Box(Modifier.fillMaxWidth(clean.coerceIn(0.001f, 1f)).height(72.dp).clip(RoundedCornerShape(36.dp)).background(C.success))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        SwitchTile("내기 순환", signals.recirculation, accent)
        SwitchTile("공기청정", signals.airCleaner, accent)
    }
}

@Composable
private fun SwitchTile(label: String, on: Boolean, accent: Color) {
    Column(
        Modifier.size(width = 290.dp, height = 200.dp).clip(RoundedCornerShape(32.dp)).background(if (on) accent else C.cardSoft),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
    ) {
        Text(label, style = T.title.copy(color = if (on) Color.White else C.text))
        Text(if (on) "켜짐" else "꺼짐", style = T.big.copy(color = if (on) Color.White else C.sub))
    }
}

@Composable
private fun Flashlight(s: GameState, stage: Stage, signals: Signals, accent: Color, soft: Color) {
    val on = signals.readingLight[s.childSeat] == true
    val count = if (s.progress.done) stage.blinks else s.progress.count
    Box(Modifier.size(280.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(soft, size.minDimension / 2)
            drawCircle(if (on) Color(0xFFFFF3B0) else C.cardSoft, size.minDimension * 0.35f)
            drawCircle(if (on) Color(0xFFFFD54F) else C.line, size.minDimension * 0.21f)
            if (on) for (i in 0 until 8) {
                val a = Math.toRadians(i * 45.0); val c = center
                drawLine(Color(0xFFFFD54F), Offset(c.x + (size.width * 0.38f * cos(a)).toFloat(), c.y + (size.width * 0.38f * sin(a)).toFloat()),
                    Offset(c.x + (size.width * 0.47f * cos(a)).toFloat(), c.y + (size.width * 0.47f * sin(a)).toFloat()), 10f, StrokeCap.Round)
            }
        }
    }
    Caption("${s.childSeat.label} 독서등 ${if (on) "켜짐!" else "꺼짐"}")
    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        repeat(stage.blinks) { i ->
            Box(Modifier.size(88.dp).clip(CircleShape).background(if (i < count) accent else C.cardSoft), contentAlignment = Alignment.Center) {
                Text("${i + 1}", style = T.title.copy(color = if (i < count) Color.White else C.sub, fontSize = 36.sp))
            }
        }
    }
    Text("$count / ${stage.blinks} 번", style = T.big.copy(color = C.sub))
}

@Composable
private fun SafeDial(s: GameState, stage: Stage, signals: Signals, accent: Color, soft: Color) {
    val armed = s.progress.armed || s.progress.done
    val angle by animateFloatAsState(signals.steeringAngle, tween(250), label = "wheel")
    Box(Modifier.size(400.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2 - 10
            drawCircle(soft, r)
            drawCircle(accent, r, style = Stroke(14f))
            for (i in 0 until 12) rotate(i * 30f) { drawLine(Color(0xFFC9CFDB), Offset(center.x, center.y - r + 8), Offset(center.x, center.y - r + 32), 5f, StrokeCap.Round) }
            val tr = r - 14
            drawArc(C.success, startAngle = -90f + stage.angle - StageJudge.ANGLE_TOLERANCE, sweepAngle = StageJudge.ANGLE_TOLERANCE * 2,
                useCenter = false, topLeft = Offset(center.x - tr, center.y - tr), size = Size(tr * 2, tr * 2), style = Stroke(30f, cap = StrokeCap.Round))
            rotate(angle) { drawLine(accent, center, Offset(center.x, center.y - r + 50), 16f, StrokeCap.Round) }
            drawCircle(accent, 26f)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val onMark = abs(signals.steeringAngle - stage.angle) <= StageJudge.ANGLE_TOLERANCE
        StepChip(EpisodeGenerator.angleWords(stage.angle), armed || onMark)
        StepChip("브레이크 꾹", armed)
        StepChip("${EpisodeGenerator.turnWords(stage.turn)} 깜빡이 ${if (s.progress.done) stage.turnCount else s.progress.count}/${stage.turnCount}", s.progress.done)
    }
    Text(
        "${angle.toInt()}°  " + if (armed) "다이얼 고정! 이제 깜빡이" else "바늘을 초록 칸에 맞추고 브레이크",
        style = T.title.copy(fontSize = 32.sp)
    )
    if (signals.turn != Turn.OFF) Chip("깜빡이 ${if (signals.turn == Turn.LEFT) "왼쪽" else "오른쪽"} 켜짐", filled = C.warn)
}

@Composable
private fun StepChip(label: String, done: Boolean) {
    Row(
        Modifier.clip(RoundedCornerShape(24.dp)).background(if (done) C.successSoft else C.cardSoft).padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(30.dp).clip(CircleShape).background(if (done) C.success else Color(0xFFC9CFDB)), contentAlignment = Alignment.Center) {
            if (done) Text("✓", style = T.chip.copy(color = Color.White, fontWeight = FontWeight.Black))
        }
        Spacer(Modifier.width(10.dp))
        Text(label, style = T.chip.copy(fontSize = 24.sp, fontWeight = FontWeight.Bold))
    }
}

@Composable
private fun Temperature(stage: Stage, signals: Signals, done: Boolean, accent: Color, soft: Color) {
    val t = signals.hvacTempC
    Column(
        Modifier.rotate(-2f).clip(RoundedCornerShape(28.dp)).background(C.warnSoft).padding(horizontal = 40.dp, vertical = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("비밀 쪽지", style = T.chip.copy(color = C.warn, fontWeight = FontWeight.Bold))
        Text("\"${EpisodeGenerator.temperatureWords(stage.temperature)}\"", style = T.hero.copy(fontSize = 52.sp))
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        Canvas(Modifier.size(width = 90.dp, height = 280.dp)) {
            val k = size.width / 90f
            drawRoundRect(C.cardSoft, Offset(27 * k, 10 * k), Size(36 * k, 220 * k), androidx.compose.ui.geometry.CornerRadius(18 * k))
            val level = (((t ?: 22f) - 15f) / 15f).coerceIn(0.05f, 1f)
            val h = 200 * k * level
            drawRoundRect(accent, Offset(35 * k, 230 * k - h), Size(20 * k, h), androidx.compose.ui.geometry.CornerRadius(10 * k))
            drawCircle(accent, 36 * k, Offset(45 * k, 245 * k))
        }
        Column {
            Text("지금", style = T.big.copy(color = C.sub))
            Text(t?.let { "%.1f°C".format(it) } ?: "--.-°C", style = T.hero.copy(fontSize = 112.sp, color = if (done) C.success else C.text))
            val diff = if (t == null) null else stage.temperature - t
            Text(
                when {
                    done -> "온도가 맞았어요!"
                    diff == null -> "공조 온도를 바꿔 보세요"
                    diff < 0 -> "더 차갑게! %.1f도 내려요".format(-diff)
                    else -> "더 따뜻하게! %.1f도 올려요".format(diff)
                },
                style = T.big.copy(color = if (done) C.success else accent)
            )
        }
    }
}

@Composable
private fun FinalCode(s: GameState, accent: Color) {
    val code = s.episode?.code ?: ""
    var typed by remember(s.stageIndex) { mutableStateOf("") }
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        repeat(code.length) { i ->
            Box(
                Modifier.size(width = 104.dp, height = 124.dp).clip(RoundedCornerShape(24.dp)).background(C.cardSoft)
                    .border(4.dp, if (i < typed.length) accent else C.line, RoundedCornerShape(24.dp)),
                contentAlignment = Alignment.Center
            ) { Text(typed.getOrNull(i)?.toString() ?: "", style = T.hero.copy(fontSize = 68.sp)) }
        }
    }
    if (s.progress.wrongAnswers > 0) Text("앗, 다시 해 봐요! 모은 숫자를 순서대로 눌러요.", style = T.big.copy(color = C.danger))
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        for (row in listOf("123", "456", "789", "<0>")) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                for (k in row) {
                    val go = k == '>'
                    val label = when (k) { '<' -> "지움"; '>' -> "열기!"; else -> k.toString() }
                    Box(
                        Modifier.size(width = 160.dp, height = 88.dp).clip(RoundedCornerShape(44.dp)).background(if (go) accent else C.cardSoft)
                            .clickable {
                                when (k) {
                                    '<' -> typed = typed.dropLast(1)
                                    '>' -> { GameEngine.answerCode(typed); typed = "" }
                                    else -> if (typed.length < code.length) typed += k
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) { Text(label, style = T.title.copy(fontSize = if (label.length > 1) 30.sp else 42.sp, color = if (go) Color.White else C.text)) }
                }
            }
        }
    }
}
