package kr.carescape.multiverse.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.carescape.multiverse.game.CarContext
import kr.carescape.multiverse.game.CheckState
import kotlin.math.cos
import kotlin.math.sin

/** The colour tokens of docs/화면_설계.md (same names as the Figma variables). */
object C {
    val bg = Color(0xFFF3F4F7)
    val surface = Color.White
    val card = Color.White
    val cardSoft = Color(0xFFEEF2FA)
    val line = Color(0xFFE5E8EB)
    val text = Color(0xFF191F28)
    val sub = Color(0xFF6B7684)
    val primary = Color(0xFF3478F6)
    val success = Color(0xFF1FA463)
    val warn = Color(0xFFF08C00)
    val danger = Color(0xFFF04452)
    val onAccent = Color.White
    val successSoft = Color(0xFFE5F6EC)
    val warnSoft = Color(0xFFFFF1DC)
    val dangerSoft = Color(0xFFFDE8EA)
    val star = Color(0xFFF08C00)
}

object T {
    val hero = TextStyle(fontSize = 44.sp, fontWeight = FontWeight.Black, color = C.text, lineHeight = 54.sp)
    val title = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.Black, color = C.text, lineHeight = 40.sp)
    val big = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold, color = C.text, lineHeight = 36.sp)
    val body = TextStyle(fontSize = 25.sp, fontWeight = FontWeight.Bold, color = C.text, lineHeight = 34.sp)
    val sub = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Normal, color = C.sub, lineHeight = 30.sp)
    val chip = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Medium, color = C.text)
}

fun Long.asColor() = Color(this)

@Composable
fun Card(modifier: Modifier = Modifier, color: Color = C.card, border: Color? = null, radius: Dp = 32.dp, pad: Dp = 32.dp,
         spacing: Dp = 14.dp, content: @Composable ColumnScope.() -> Unit) {
    var m = modifier.clip(RoundedCornerShape(radius)).background(color)
    if (border != null) m = m.border(5.dp, border, RoundedCornerShape(radius))
    Column(m.padding(pad), verticalArrangement = Arrangement.spacedBy(spacing), content = content)
}

/** Pill-shaped chip. [filled] colours it with white text, otherwise [bg] with dark text. */
@Composable
fun Chip(text: String, modifier: Modifier = Modifier, filled: Color? = null, bg: Color = C.cardSoft, big: Boolean = false, onClick: (() -> Unit)? = null) {
    var m = modifier.clip(RoundedCornerShape(50)).background(filled ?: bg)
    if (onClick != null) m = m.clickable(onClick = onClick)
    Box(m.padding(horizontal = if (big) 24.dp else 18.dp, vertical = if (big) 14.dp else 10.dp)) {
        Text(text, style = T.chip.copy(color = if (filled != null) C.onAccent else C.text, fontSize = if (big) 24.sp else 21.sp,
            fontWeight = if (filled != null) FontWeight.Bold else FontWeight.Medium))
    }
}

@Composable
fun MainButton(text: String, modifier: Modifier = Modifier, color: Color = C.primary, enabled: Boolean = true, height: Dp = 104.dp,
               fontSize: Int = 30, onClick: () -> Unit) {
    Box(
        modifier.height(height).clip(RoundedCornerShape(24.dp)).background(if (enabled) color else C.line)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 32.dp),
        contentAlignment = Alignment.Center
    ) { Text(text, style = T.big.copy(color = if (enabled) C.onAccent else C.sub, fontSize = fontSize.sp, fontWeight = FontWeight.Black)) }
}

@Composable
fun GhostButton(text: String, modifier: Modifier = Modifier, height: Dp = 84.dp, onClick: () -> Unit) {
    Box(
        modifier.height(height).clip(RoundedCornerShape(24.dp)).background(C.surface).border(2.dp, C.line, RoundedCornerShape(24.dp))
            .clickable(onClick = onClick).padding(horizontal = 28.dp),
        contentAlignment = Alignment.Center
    ) { Text(text, style = T.big) }
}

/** The FairPool-style header row: left content, a flexible gap, then right content. */
@Composable
fun Header(left: @Composable RowScope.() -> Unit, right: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        left()
        Spacer(Modifier.weight(1f))
        right()
    }
}

/** White pill with a coloured dot: the always-visible safety state. */
@Composable
fun StatusPill(text: String, dot: Color = C.success) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(C.surface).padding(horizontal = 22.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(14.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(10.dp))
        Text(text, style = T.chip.copy(fontWeight = FontWeight.Bold))
    }
}

/** Coloured rounded square with the theme number (or a star on home). */
@Composable
fun Tile(color: Color, label: String?) {
    Box(Modifier.size(60.dp).clip(RoundedCornerShape(18.dp)).background(color), contentAlignment = Alignment.Center) {
        if (label != null) Text(label, style = T.title.copy(color = C.onAccent))
        else StarShape(36.dp, Color.White, null)
    }
}

@Composable
fun ContextChips(ctx: CarContext) {
    Chip(ctx.place, bg = C.surface)
    Chip(if (ctx.rain) "비 오는 ${if (ctx.night) "밤" else "날"}" else if (ctx.night) "맑은 밤" else "맑은 날", bg = C.surface)
    Chip(ctx.soc?.let { "배터리 $it%" } ?: "배터리 정보 없음", bg = C.surface)
}

@Composable
fun StateDot(state: CheckState, size: Dp = 24.dp) {
    val color = when (state) {
        CheckState.PASS -> C.success; CheckState.WAIT -> C.primary; CheckState.FAIL -> C.danger; CheckState.SKIPPED -> C.sub
    }
    Box(Modifier.size(size).clip(CircleShape).background(color))
}

/** A five-point star; [outline] draws a ring of that colour around it. */
@Composable
fun StarShape(size: Dp, fill: Color, outline: Color?) {
    Canvas(Modifier.size(size)) {
        val cx = this.size.width / 2; val cy = this.size.height / 2
        val r = this.size.minDimension / 2 * 0.92f; val ri = r * 0.45f
        val p = Path()
        for (i in 0 until 10) {
            val a = Math.toRadians(-90.0 + i * 36.0)
            val rr = if (i % 2 == 0) r else ri
            val x = cx + (rr * cos(a)).toFloat(); val y = cy + (rr * sin(a)).toFloat()
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        p.close()
        drawPath(p, fill)
        if (outline != null) drawPath(p, outline, style = Stroke(width = this.size.minDimension * 0.07f))
    }
}

/** Stage progress as stars: done (filled), now (ring), next (white with a line). */
@Composable
fun Stars(done: Int, total: Int, size: Dp = 44.dp) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (i in 0 until total) when {
            i < done -> StarShape(size, C.star, null)
            i == done -> StarShape(size, C.surface, C.star)
            else -> StarShape(size, C.surface, C.line)
        }
    }
}

/** The friendly round NPC face (eyes, cheeks, smile) in the theme colour. */
@Composable
fun Mascot(color: Color, size: Dp = 128.dp) {
    Canvas(Modifier.size(size)) {
        val s = this.size.width / 160f
        drawCircle(color, radius = 80 * s, center = Offset(80 * s, 80 * s))
        for (x in listOf(56f, 104f)) {
            drawOval(Color.White, topLeft = Offset((x - 15) * s, 50 * s), size = Size(30 * s, 36 * s))
            drawCircle(C.text, radius = 8 * s, center = Offset((x + 3) * s, 72 * s))
            drawCircle(Color.White, radius = 3 * s, center = Offset((x + 6) * s, 68 * s))
        }
        for (x in listOf(38f, 122f)) drawOval(Color(0xB3FF8FA3), topLeft = Offset((x - 11) * s, 91 * s), size = Size(22 * s, 14 * s))
        val smile = Path().apply { moveTo(58 * s, 104 * s); quadraticBezierTo(80 * s, 126 * s, 102 * s, 104 * s) }
        drawPath(smile, C.text, style = Stroke(width = 7 * s, cap = StrokeCap.Round))
    }
}

/** A small titled tag + task card used for the parent and child roles. */
@Composable
fun RoleCard(tag: String, tagColor: Color, bg: Color, task: String, modifier: Modifier = Modifier) {
    Card(modifier, color = bg, radius = 28.dp, pad = 24.dp, spacing = 10.dp) {
        Chip(tag, filled = tagColor)
        Text(task, style = T.body)
    }
}
