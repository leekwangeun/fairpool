package kr.carescape.multiverse.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.carescape.multiverse.game.ContextReader
import kr.carescape.multiverse.game.GameEngine
import kr.carescape.multiverse.game.GameState
import kr.carescape.multiverse.game.PlaceKind
import kr.carescape.multiverse.game.Theme
import kr.carescape.multiverse.vehicle.Seat
import kr.carescape.multiverse.vehicle.Signals

/** E0: pick a theme, put the treasure in, say where the child sits. */
@Composable
fun HomeScreen(s: GameState, signals: Signals, onSettings: () -> Unit) {
    val recommended = ContextReader.recommended(s.context)
    Column(Modifier.fillMaxSize().background(C.bg).padding(32.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Header(
            left = { Tile(C.primary, null); Text("차량탈출 : AI 멀티버스", style = T.title.copy(fontSize = 30.sp)) },
            right = { ContextChips(s.context); StatusPill(if (signals.parked) "주차 중" else "주행 중", if (signals.parked) C.success else C.danger); GhostButton("설정", height = 64.dp, onClick = onSettings) }
        )
        s.notice?.let { NoticeBar(it) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Mascot(Theme.SUBMARINE.accent.asColor(), 100.dp)
            Column {
                Text("오늘은 어떤 모험을 할까?", style = T.hero)
                Text("카드를 하나 눌러요. 보호자가 고르고, 아이와 함께 풀어요.", style = T.sub.copy(fontSize = 24.sp))
            }
        }
        Row(Modifier.fillMaxWidth().weight(1.25f), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            for (theme in Theme.entries) {
                ThemeCard(theme, recommendTag(theme, s), theme == recommended, Modifier.weight(1f).fillMaxHeight()) { GameEngine.chooseTheme(theme) }
            }
        }
        Row(Modifier.fillMaxWidth().weight(0.6f), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Card(Modifier.weight(1f).fillMaxHeight(), color = C.warnSoft) {
                GiftIcon()
                Text("글로브박스에 보물을 넣어요", style = T.title)
                Text("간식이나 작은 선물을 넣고 닫아 주세요. 시작하면 잠기고, 마지막 암호를 풀면 철컥 열려요.", style = T.sub.copy(color = C.sub))
            }
            Card(Modifier.weight(1f).fillMaxHeight()) {
                Text("우리 크루", style = T.title)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Chip("1열 기관장", filled = C.primary)
                    Text("운전석 보호자, 차 장치 담당", style = T.body.copy(fontWeight = FontWeight.Medium))
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Chip("2열 탐정", filled = C.success)
                    Text("아이 자리:", style = T.body.copy(fontWeight = FontWeight.Medium))
                    for (seat in listOf(Seat.FRONT_RIGHT, Seat.REAR_LEFT, Seat.REAR_RIGHT)) {
                        val sitting = signals.occupied[seat] == true || signals.belted[seat] == true
                        Chip(seat.label + if (sitting) " (앉음)" else "", filled = if (s.childSeat == seat) C.success else null) { GameEngine.setChildSeat(seat) }
                    }
                }
                Text("아이는 단서를 보고 말해요. 운전석 장치는 보호자만 만져요.", style = T.sub)
                Text("AI 게임마스터: ${GameEngine.masterName}", style = T.sub)
            }
        }
    }
}

private fun recommendTag(theme: Theme, s: GameState): String = when (theme) {
    Theme.SUBMARINE -> if (s.context.placeKind == PlaceKind.SEA) "지금 바다 근처" else "휴게소, 바다 추천"
    Theme.CODE_RED -> if (s.context.soc != null) "충전 중 추천" else "충전 기다릴 때"
    Theme.GHOST_TAXI -> if (s.context.rain) "비 오는 밤 특별편" else "밤에 추천"
    Theme.DETECTIVE -> "온 가족 추천"
}

@Composable
private fun ThemeCard(theme: Theme, tag: String, highlight: Boolean, modifier: Modifier, onPick: () -> Unit) {
    val shape = RoundedCornerShape(32.dp)
    val accent = theme.accent.asColor()
    Column(
        modifier.then(if (highlight) Modifier.border(6.dp, accent, shape) else Modifier).clip(shape).background(C.card).clickable(onClick = onPick)
    ) {
        Box(Modifier.fillMaxWidth().weight(1f).background(accent), contentAlignment = Alignment.Center) { ThemeIcon(theme, 120.dp) }
        Column(Modifier.fillMaxWidth().weight(1.2f).padding(28.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("테마 ${theme.number}", style = T.chip.copy(color = accent, fontWeight = FontWeight.Bold))
            Text(theme.title, style = T.hero.copy(fontSize = 38.sp))
            Text(theme.genre, style = T.body.copy(fontWeight = FontWeight.Medium))
            Text(theme.audience, style = T.sub)
            Spacer(Modifier.weight(1f))
            Chip(tag, filled = if (highlight) accent else null)
        }
    }
}

/** Simple white pictograms on the theme colour: submarine, rocket, ghost, magnifier. */
@Composable
fun ThemeIcon(theme: Theme, size: Dp) {
    val accent = theme.accent.asColor()
    Canvas(Modifier.size(size)) {
        val s = this.size.width / 120f
        val w = Color.White
        when (theme) {
            Theme.SUBMARINE -> {
                drawRoundRect(w, Offset(14 * s, 48 * s), Size(92 * s, 44 * s), CornerRadius(22 * s))
                drawRoundRect(w, Offset(48 * s, 30 * s), Size(22 * s, 22 * s), CornerRadius(6 * s))
                drawRoundRect(w, Offset(58 * s, 16 * s), Size(6 * s, 18 * s), CornerRadius(3 * s))
                for (x in listOf(40f, 62f, 84f)) drawCircle(accent, 8 * s, Offset(x * s, 70 * s))
                drawCircle(w.copy(alpha = 0.7f), 6 * s, Offset(100 * s, 30 * s)); drawCircle(w.copy(alpha = 0.7f), 4 * s, Offset(90 * s, 18 * s))
            }
            Theme.CODE_RED -> {
                val body = Path().apply { moveTo(60 * s, 10 * s); cubicTo(80 * s, 26 * s, 86 * s, 52 * s, 80 * s, 82 * s); lineTo(40 * s, 82 * s); cubicTo(34 * s, 52 * s, 40 * s, 26 * s, 60 * s, 10 * s); close() }
                drawPath(body, w)
                drawCircle(accent, 10 * s, Offset(60 * s, 46 * s))
                drawPath(Path().apply { moveTo(40 * s, 70 * s); lineTo(24 * s, 92 * s); lineTo(42 * s, 88 * s); close() }, w)
                drawPath(Path().apply { moveTo(80 * s, 70 * s); lineTo(96 * s, 92 * s); lineTo(78 * s, 88 * s); close() }, w)
                drawPath(Path().apply { moveTo(50 * s, 86 * s); lineTo(60 * s, 110 * s); lineTo(70 * s, 86 * s); close() }, Color(0xFFFFD54F))
            }
            Theme.GHOST_TAXI -> {
                val g = Path().apply {
                    moveTo(24 * s, 104 * s); lineTo(24 * s, 56 * s); cubicTo(24 * s, 32 * s, 40 * s, 16 * s, 60 * s, 16 * s)
                    cubicTo(80 * s, 16 * s, 96 * s, 32 * s, 96 * s, 56 * s); lineTo(96 * s, 104 * s)
                    lineTo(84 * s, 94 * s); lineTo(72 * s, 104 * s); lineTo(60 * s, 94 * s); lineTo(48 * s, 104 * s); lineTo(36 * s, 94 * s); close()
                }
                drawPath(g, w)
                drawOval(accent, Offset(41 * s, 46 * s), Size(14 * s, 20 * s)); drawOval(accent, Offset(65 * s, 46 * s), Size(14 * s, 20 * s))
                drawOval(accent, Offset(52 * s, 70 * s), Size(16 * s, 12 * s))
            }
            Theme.DETECTIVE -> {
                drawCircle(w, 30 * s, Offset(50 * s, 50 * s), style = Stroke(12 * s))
                drawCircle(w.copy(alpha = 0.45f), 20 * s, Offset(50 * s, 50 * s))
                drawLine(w, Offset(72 * s, 72 * s), Offset(100 * s, 100 * s), 16 * s, StrokeCap.Round)
            }
        }
    }
}

@Composable
private fun GiftIcon() {
    Canvas(Modifier.size(72.dp)) {
        val s = size.width / 72f
        drawRoundRect(Color(0xFFF08C00), Offset(8 * s, 28 * s), Size(56 * s, 38 * s), CornerRadius(8 * s))
        drawRoundRect(Color(0xFFFFB547), Offset(4 * s, 18 * s), Size(64 * s, 16 * s), CornerRadius(6 * s))
        drawRect(Color.White, Offset(31 * s, 18 * s), Size(10 * s, 48 * s))
    }
}

@Composable
fun NoticeBar(text: String) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(C.warnSoft).padding(horizontal = 28.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, style = T.body, modifier = Modifier.weight(1f))
        Chip("닫기", bg = C.surface) { GameEngine.dismissNotice() }
    }
}
