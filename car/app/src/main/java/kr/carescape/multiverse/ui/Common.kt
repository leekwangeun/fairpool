package kr.carescape.multiverse.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.carescape.multiverse.game.CarContext
import kr.carescape.multiverse.game.CheckState

/** The colour tokens of docs/화면_설계.md. */
object C {
    val bg = Color(0xFF0B1020)
    val surface = Color(0xFF151B2E)
    val card = Color(0xFF1C2440)
    val cardSoft = Color(0xFF242D4F)
    val line = Color(0xFF2F3A61)
    val text = Color(0xFFF2F4FF)
    val sub = Color(0xFF9AA3C0)
    val primary = Color(0xFF5B7CFF)
    val success = Color(0xFF2ED47A)
    val warn = Color(0xFFFFB020)
    val danger = Color(0xFFFF4D4F)
}

object T {
    val title = TextStyle(fontSize = 44.sp, fontWeight = FontWeight.Bold, color = C.text)
    val big = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.SemiBold, color = C.text)
    val body = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Medium, color = C.text, lineHeight = 36.sp)
    val sub = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Normal, color = C.sub, lineHeight = 30.sp)
    val chip = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Medium, color = C.text)
}

@Composable
fun Card(modifier: Modifier = Modifier, color: Color = C.card, border: Color? = null, content: @Composable ColumnScope.() -> Unit) {
    var m = modifier.clip(RoundedCornerShape(28.dp)).background(color)
    if (border != null) m = m.border(2.dp, border, RoundedCornerShape(28.dp))
    Column(m.padding(28.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
}

@Composable
fun Chip(text: String, filled: Color? = null, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    var m = modifier.clip(RoundedCornerShape(16.dp)).background(filled ?: C.cardSoft)
    if (onClick != null) m = m.clickable(onClick = onClick)
    Box(m.padding(horizontal = 18.dp, vertical = 10.dp)) {
        Text(text, style = T.chip.copy(color = if (filled != null) Color(0xFF0B1020) else C.text))
    }
}

@Composable
fun MainButton(text: String, modifier: Modifier = Modifier, color: Color = C.primary, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier.height(96.dp).clip(RoundedCornerShape(16.dp)).background(if (enabled) color else C.cardSoft)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 36.dp),
        contentAlignment = Alignment.Center
    ) { Text(text, style = T.big.copy(color = if (enabled) Color.White else C.sub)) }
}

@Composable
fun GhostButton(text: String, modifier: Modifier = Modifier, color: Color = C.text, onClick: () -> Unit) {
    Box(
        modifier.height(96.dp).clip(RoundedCornerShape(16.dp)).border(2.dp, C.line, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick).padding(horizontal = 32.dp),
        contentAlignment = Alignment.Center
    ) { Text(text, style = T.big.copy(color = color)) }
}

/** The 112-tall top bar: title left, context chips in the middle, an action right. */
@Composable
fun TopBar(left: @Composable RowScope.() -> Unit, right: @Composable RowScope.() -> Unit, middle: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().height(112.dp).background(C.surface).padding(horizontal = 48.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, content = left)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically, content = middle)
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically, content = right)
    }
}

@Composable
fun ContextChips(ctx: CarContext) {
    Chip(ctx.place)
    Chip(if (ctx.rain) "비 (와이퍼 작동)" else "맑음")
    Chip(ctx.soc?.let { "배터리 $it%" } ?: "배터리 정보 없음")
    Chip("%s %02d시".format(if (ctx.night) "밤" else "낮", ctx.hour))
}

@Composable
fun StateDot(state: CheckState) {
    val color = when (state) {
        CheckState.PASS -> C.success; CheckState.WAIT -> C.primary; CheckState.FAIL -> C.danger; CheckState.SKIPPED -> C.sub
    }
    Box(Modifier.size(18.dp).clip(CircleShape).background(color))
}

@Composable
fun Gap(w: Int = 0, h: Int = 0) = Box(Modifier.width(w.dp).height(h.dp))

fun Long.asColor() = Color(this)
