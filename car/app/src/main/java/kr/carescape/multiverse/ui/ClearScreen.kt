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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.carescape.multiverse.game.GameEngine
import kr.carescape.multiverse.game.GameState

/** E4: the glove box opens, then the car is put back. */
@Composable
fun ClearScreen(s: GameState) {
    val episode = s.episode ?: return
    val accent = episode.theme.accent.asColor()
    var opened by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { opened = true }
    val lid by animateFloatAsState(if (opened) -70f else 0f, tween(1400), label = "lid")
    val secs = ((s.clearedAt - s.startedAt) / 1000).coerceAtLeast(0)

    Column(Modifier.fillMaxSize().background(C.bg)) {
        TopBar(
            left = { Text(episode.theme.fullTitle, style = T.big.copy(color = accent)) },
            right = { Text("탈출 성공", style = T.big.copy(color = C.success)) }
        )
        Row(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(48.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Box(Modifier.size(520.dp, 380.dp)) {
                    Canvas(Modifier.fillMaxSize()) {
                        val w = size.width; val h = size.height
                        // glow from inside
                        drawCircle(Color(0x66FFD54F), radius = w * 0.45f, center = Offset(w / 2, h * 0.55f))
                        drawRect(Color(0xFF3A3F55), topLeft = Offset(w * 0.1f, h * 0.45f), size = Size(w * 0.8f, h * 0.45f))
                        drawRect(Color(0xFFFFD54F), topLeft = Offset(w * 0.18f, h * 0.5f), size = Size(w * 0.64f, h * 0.3f), alpha = 0.6f)
                        rotate(lid, pivot = Offset(w * 0.1f, h * 0.45f)) {
                            drawRect(accent, topLeft = Offset(w * 0.1f, h * 0.33f), size = Size(w * 0.8f, h * 0.12f))
                        }
                    }
                }
                Text("철컥!", style = T.title.copy(fontSize = 96.sp, color = Color(0xFFFFD54F), fontWeight = FontWeight.Black))
                Text("글로브박스가 열렸어요. 보물을 꺼내세요!", style = T.big)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Card(Modifier.fillMaxWidth()) {
                    Text(episode.title, style = T.big.copy(color = accent))
                    Text(episode.outro, style = T.body)
                    Text("걸린 시간 %d분 %02d초".format(secs / 60, secs % 60), style = T.body)
                    Text("쓴 힌트 ${s.hintsUsed}개", style = T.body)
                    Text("암호 ${episode.code}", style = T.sub)
                }
                Card(Modifier.fillMaxWidth()) {
                    Text("차량 설정 원상 복구", style = T.big)
                    if (s.restored.isEmpty()) Text("보물을 꺼낼 시간을 드린 뒤 돌려놓아요...", style = T.sub)
                    for (item in s.restored) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(18.dp).background(C.success))
                            Spacer(Modifier.width(14.dp))
                            Text("$item 통과", style = T.body)
                        }
                    }
                    if (s.restored.isNotEmpty()) Text("글로브박스는 보물을 꺼내도록 열어 둡니다.", style = T.sub)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    MainButton("다시 하기 (새 에피소드)", Modifier.weight(1f), color = accent) { GameEngine.replay() }
                    GhostButton("홈으로", Modifier.weight(1f).height(96.dp)) { GameEngine.home() }
                }
            }
        }
    }
}
