package kr.carescape.multiverse.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.carescape.multiverse.game.CheckState
import kr.carescape.multiverse.game.GameEngine
import kr.carescape.multiverse.game.GameState

/** E1: the safety sandbox checklist (for the parent) while the AI writes today's episode (for the child). */
@Composable
fun SafetyScreen(s: GameState, showTools: Boolean) {
    val theme = s.theme ?: return
    val accent = theme.accent.asColor()
    val locked = s.allChecksPassed
    Column(Modifier.fillMaxSize().background(C.bg)) {
        Column(Modifier.weight(1f).fillMaxWidth().padding(32.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Header(
                left = {
                    GhostButton("돌아가기", height = 64.dp) { GameEngine.cancelSetup() }
                    Column {
                        Text("테마 ${theme.number}", style = T.chip.copy(color = accent, fontSize = 20.sp))
                        Text("${theme.title} 준비 중", style = T.title.copy(fontSize = 30.sp))
                    }
                },
                right = { StatusPill(if (locked) "안전 잠금 완료" else "잠그는 중", if (locked) C.success else C.primary) }
            )
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Card(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), spacing = 20.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Shield()
                        Spacer(Modifier.width(12.dp))
                        Text("출발 전 안전 확인", style = T.title.copy(fontSize = 34.sp))
                    }
                    Text("보호자가 확인해요. 모두 초록색이 되면 시작할 수 있어요.", style = T.sub)
                    for (item in s.checklist) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            StateDot(item.state)
                            Spacer(Modifier.width(18.dp))
                            Column(Modifier.weight(1f)) {
                                Text(item.label, style = T.big.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium))
                                if (item.detail.isNotEmpty()) Text(item.detail, style = T.sub)
                            }
                            Text(
                                when (item.state) {
                                    CheckState.PASS -> "통과"; CheckState.WAIT -> "진행 중"; CheckState.FAIL -> "확인 필요"; CheckState.SKIPPED -> "건너뜀"
                                },
                                style = T.big.copy(color = when (item.state) {
                                    CheckState.PASS -> C.success; CheckState.WAIT -> C.primary; CheckState.FAIL -> C.danger; CheckState.SKIPPED -> C.sub
                                })
                            )
                        }
                    }
                }
                Card(Modifier.weight(1f).fillMaxHeight(), color = theme.soft.asColor()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Mascot(accent, 120.dp)
                        Spacer(Modifier.width(20.dp))
                        Column {
                            Text("${theme.npcRole} ${theme.npcName}", style = T.title.copy(fontSize = 30.sp))
                            Text(if (s.generating) "오늘의 이야기를 만드는 중이에요" else "오늘의 이야기를 만들었어요", style = T.body.copy(color = accent))
                        }
                    }
                    val ep = s.episode
                    Card(Modifier.fillMaxWidth(), radius = 28.dp, pad = 28.dp, spacing = 10.dp) {
                        if (s.generating || ep == null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(color = accent, modifier = Modifier.size(40.dp))
                                Spacer(Modifier.width(18.dp))
                                Text("이야기를 쓰는 중...", style = T.big)
                            }
                        } else {
                            Text(ep.title, style = T.title.copy(color = accent))
                            Text(ep.intro, style = T.body.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium))
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { ContextChips(s.context) }
                    Text("만든 곳: ${if (ep?.source == "llm") "차량 LLM (Pleos)" else if (ep == null) "준비 중" else "내장 생성기"}  /  차량 설정 백업 완료", style = T.sub)
                    Spacer(Modifier.weight(1f))
                    MainButton("방탈출 시작!", Modifier.fillMaxWidth(), color = accent, enabled = locked && ep != null, height = 120.dp, fontSize = 38) { GameEngine.begin() }
                }
            }
        }
        if (showTools) DemoTools(s.childSeat)
    }
}

@Composable
private fun Shield() {
    Canvas(Modifier.size(48.dp)) {
        val k = size.width / 48f
        val p = Path().apply {
            moveTo(24 * k, 4 * k); lineTo(40 * k, 10 * k); lineTo(40 * k, 22 * k)
            cubicTo(40 * k, 33 * k, 33 * k, 41 * k, 24 * k, 44 * k); cubicTo(15 * k, 41 * k, 8 * k, 33 * k, 8 * k, 22 * k); lineTo(8 * k, 10 * k); close()
        }
        drawPath(p, C.success)
        val check = Path().apply { moveTo(16 * k, 24 * k); lineTo(22 * k, 30 * k); lineTo(33 * k, 18 * k) }
        drawPath(check, Color.White, style = Stroke(5 * k, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}
