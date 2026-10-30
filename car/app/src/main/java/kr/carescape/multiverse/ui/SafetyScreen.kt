package kr.carescape.multiverse.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kr.carescape.multiverse.game.CheckState
import kr.carescape.multiverse.game.GameEngine
import kr.carescape.multiverse.game.GameState

/** E1: the safety sandbox checklist while the AI writes today's episode. */
@Composable
fun SafetyScreen(s: GameState, showTools: Boolean) {
    val theme = s.theme ?: return
    Column(Modifier.fillMaxSize().background(C.bg)) {
        TopBar(
            left = { GhostButton("돌아가기", onClick = { GameEngine.cancelSetup() }) },
            middle = { Text(theme.fullTitle, style = T.big.copy(color = theme.accent.asColor())) },
            right = { Text("안전 확인", style = T.big) }
        )
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(48.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Card(Modifier.weight(1.1f)) {
                    Text("출발 전 확인 (${s.checklist.size})", style = T.big)
                    for (item in s.checklist) {
                        Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
                            StateDot(item.state)
                            Spacer(Modifier.width(18.dp))
                            Column(Modifier.weight(1f)) {
                                Text(item.label, style = T.body)
                                if (item.detail.isNotEmpty()) Text(item.detail, style = T.sub)
                            }
                            Text(
                                when (item.state) {
                                    CheckState.PASS -> "통과"; CheckState.WAIT -> "진행 중"; CheckState.FAIL -> "확인 필요"; CheckState.SKIPPED -> "건너뜀"
                                },
                                style = T.body.copy(color = when (item.state) {
                                    CheckState.PASS -> C.success; CheckState.WAIT -> C.primary; CheckState.FAIL -> C.danger; CheckState.SKIPPED -> C.sub
                                })
                            )
                        }
                    }
                }
                Card(Modifier.weight(1f)) {
                    Text("AI 게임마스터", style = T.big)
                    val ep = s.episode
                    if (s.generating || ep == null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(color = theme.accent.asColor(), modifier = Modifier.width(40.dp).height(40.dp))
                            Spacer(Modifier.width(18.dp))
                            Text("오늘의 에피소드를 만드는 중...", style = T.body)
                        }
                    } else {
                        Text(ep.title, style = T.big.copy(color = theme.accent.asColor()))
                        Text(ep.intro, style = T.body)
                        Text("만든 곳: ${if (ep.source == "llm") "차량 LLM (Pleos)" else "내장 생성기"}", style = T.sub)
                    }
                    Text("장소: ${s.context.place}", style = T.sub)
                    Text("날씨: ${s.context.weather}", style = T.sub)
                    Text("배터리: ${s.context.soc?.let { "$it%" } ?: "정보 없음"}", style = T.sub)
                    Text("차량 설정 백업 완료 (공조, 조명, 창문, 문, 글로브박스)", style = T.sub.copy(color = C.success))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                MainButton("방탈출 시작", enabled = s.allChecksPassed && s.episode != null, color = theme.accent.asColor()) { GameEngine.begin() }
            }
        }
        if (showTools) DemoTools(s.childSeat)
    }
}
