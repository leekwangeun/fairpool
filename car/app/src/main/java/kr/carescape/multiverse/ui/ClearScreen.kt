package kr.carescape.multiverse.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.carescape.multiverse.game.GameEngine
import kr.carescape.multiverse.game.GameState

/** E4: the glove box opens, then the car is put back. */
@Composable
fun ClearScreen(s: GameState) {
    val episode = s.episode ?: return
    val theme = episode.theme
    val accent = theme.accent.asColor()
    var opened by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { opened = true }
    val lid by animateFloatAsState(if (opened) -35f else 0f, tween(1400), label = "lid")
    val secs = ((s.clearedAt - s.startedAt) / 1000).coerceAtLeast(0)

    Column(Modifier.fillMaxSize().background(C.bg).padding(32.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Header(
            left = { Tile(accent, "${theme.number}"); Text("${theme.title} 탈출 성공!", style = T.title.copy(fontSize = 30.sp)); Stars(s.stageCount, s.stageCount) },
            right = { StatusPill(if (s.restored.isEmpty()) "설정 돌려놓는 중" else "설정 복구 완료", if (s.restored.isEmpty()) C.primary else C.success) }
        )
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(
                Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(40.dp)).background(C.warnSoft).padding(40.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically)
            ) {
                Confetti()
                Box(Modifier.size(width = 380.dp, height = 300.dp)) {
                    Canvas(Modifier.fillMaxSize()) {
                        val k = size.width / 380f
                        drawOval(Color(0xFFFFE29A), Offset(10 * k, 30 * k), Size(360 * k, 240 * k))
                        drawRoundRect(Color(0xFF3A3F55), Offset(60 * k, 140 * k), Size(260 * k, 140 * k), CornerRadius(20 * k))
                        drawRoundRect(Color(0xFFFFD54F), Offset(80 * k, 156 * k), Size(220 * k, 100 * k), CornerRadius(12 * k))
                        drawRoundRect(Color(0xFFFF6B3D), Offset(164 * k, 186 * k), Size(52 * k, 70 * k), CornerRadius(8 * k))
                        drawRoundRect(Color(0xFFFF9A76), Offset(150 * k, 176 * k), Size(80 * k, 20 * k), CornerRadius(6 * k))
                        drawRect(Color.White, Offset(186 * k, 176 * k), Size(8 * k, 80 * k))
                        rotate(lid, pivot = Offset(60 * k, 140 * k)) {
                            drawRoundRect(accent, Offset(60 * k, 100 * k), Size(260 * k, 40 * k), CornerRadius(10 * k))
                        }
                    }
                }
                Text("철컥!", style = T.hero.copy(fontSize = 120.sp, color = C.warn))
                Text("글로브박스가 열렸어요. 보물을 꺼내요!", style = T.hero.copy(fontSize = 40.sp))
            }
            Column(Modifier.width(560.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Card(Modifier.fillMaxWidth()) {
                    Text(episode.title, style = T.title.copy(color = accent, fontSize = 30.sp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Stat("걸린 시간", "%d분 %02d초".format(secs / 60, secs % 60))
                        Stat("힌트", "${s.hintsUsed}개")
                        Stat("암호", episode.code)
                    }
                }
                Card(Modifier.fillMaxWidth()) {
                    Text("차량 설정을 원래대로", style = T.title.copy(fontSize = 30.sp))
                    if (s.restored.isEmpty()) Text("보물을 꺼낼 시간을 드린 뒤 돌려놓아요...", style = T.sub)
                    for (item in s.restored) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(22.dp).clip(RoundedCornerShape(11.dp)).background(C.success))
                            Spacer(Modifier.width(14.dp))
                            Text(item, style = T.body.copy(fontSize = 24.sp))
                        }
                    }
                    if (s.restored.isNotEmpty()) Text("글로브박스는 보물을 꺼내도록 열어 둬요.", style = T.sub)
                }
                Spacer(Modifier.weight(1f))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    MainButton("새 이야기로 또 하기", Modifier.weight(1f), color = accent) { GameEngine.replay() }
                    GhostButton("홈으로", height = 104.dp) { GameEngine.home() }
                }
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column(Modifier.clip(RoundedCornerShape(24.dp)).background(C.cardSoft).padding(horizontal = 20.dp, vertical = 14.dp)) {
        Text(label, style = T.chip.copy(color = C.sub, fontSize = 20.sp))
        Text(value, style = T.title.copy(fontSize = 30.sp))
    }
}

@Composable
private fun Confetti() {
    val colors = listOf(0xFFFF6B3D, 0xFF1EA7E1, 0xFF7B61FF, 0xFF1FA463, 0xFFF5A623, 0xFFE5484D).map { Color(it) }
    Canvas(Modifier.fillMaxWidth().height(150.dp)) {
        for (i in 0 until 26) {
            val x = ((i * 97) % 700) / 700f * size.width
            val y = ((i * 53) % 150) / 150f * size.height
            val c = colors[i % colors.size]
            if (i % 2 == 0) drawCircle(c, 9f, Offset(x, y))
            else rotate((i * 37 % 180).toFloat(), Offset(x, y)) { drawRoundRect(c, Offset(x - 8, y - 14), Size(16f, 28f), CornerRadius(4f)) }
        }
    }
}
