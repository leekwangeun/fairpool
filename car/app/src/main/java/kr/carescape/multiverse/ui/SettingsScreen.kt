package kr.carescape.multiverse.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.carescape.multiverse.data.Prefs
import kr.carescape.multiverse.game.GameEngine
import kr.carescape.multiverse.game.PlaceKind
import kr.carescape.multiverse.vehicle.VehicleBus

/** E5 (for the parent). [onDemoChanged] restarts the car service on the demo car or the real SDK. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(prefs: Prefs, onBack: () -> Unit, onDemoChanged: (Boolean) -> Unit) {
    var tick by remember { mutableIntStateOf(0) } // re-reads the prefs after each change
    val log by VehicleBus.log.collectAsState()
    @Suppress("UNUSED_EXPRESSION") tick
    Column(Modifier.fillMaxSize().background(C.bg).padding(32.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Header(
            left = { GhostButton("돌아가기", height = 64.dp, onClick = onBack); Text("설정", style = T.title.copy(fontSize = 34.sp)); Text("보호자용", style = T.chip.copy(color = C.sub)) },
            right = { StatusPill("주차 중") }
        )
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Card(Modifier.fillMaxWidth()) {
                    Text("AI 게임마스터", style = T.title.copy(fontSize = 30.sp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Chip("차량 LLM (Pleos)", filled = if (prefs.aiMode == "llm") C.primary else null, big = true) { prefs.aiMode = "llm"; tick++ }
                        Chip("내장 생성기", filled = if (prefs.aiMode == "builtin") C.primary else null, big = true) { prefs.aiMode = "builtin"; tick++ }
                    }
                    Text("차량 LLM이 없거나 답이 늦으면 내장 생성기가 대신 이야기를 만들어요.", style = T.sub)
                }
                Card(Modifier.fillMaxWidth()) {
                    Text("장소 힌트", style = T.title.copy(fontSize = 30.sp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Chip("자동 (위치)", filled = if (prefs.place == "auto") C.primary else null, big = true) { prefs.place = "auto"; GameEngine.refreshContext(); tick++ }
                        for (k in PlaceKind.entries) Chip(k.label, filled = if (prefs.place == k.name) C.primary else null, big = true) { prefs.place = k.name; GameEngine.refreshContext(); tick++ }
                    }
                }
                Card(Modifier.fillMaxWidth(), spacing = 28.dp) {
                    Toggle("데모 모드 (가짜 차량)", "차량 SDK 대신 앱 안의 가짜 차가 응답해요.", prefs.demo) { prefs.demo = it; onDemoChanged(it); tick++ }
                    Toggle("안전 확인 수동 통과", "이 차가 확인할 수 없는 항목(글로브박스 등)을 건너뜀으로 표시해요.", prefs.manualPass) { prefs.manualPass = it; tick++ }
                    Toggle("시연 도구 보이기", "안전 확인과 플레이 화면 아래에 차량 신호 버튼 줄이 생겨요.", prefs.showTools) { prefs.showTools = it; tick++ }
                }
            }
            Card(Modifier.width(600.dp).fillMaxHeight(), spacing = 10.dp) {
                Text("차량 기록", style = T.title.copy(fontSize = 30.sp))
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (line in log.asReversed()) Text(line, style = T.sub.copy(fontSize = 20.sp))
                }
            }
        }
    }
}

@Composable
private fun Toggle(title: String, detail: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = T.big)
            Text(detail, style = T.sub)
        }
        Switch(on, onChange, Modifier.scale(1.4f), colors = SwitchDefaults.colors(checkedTrackColor = C.success, uncheckedTrackColor = C.line, uncheckedBorderColor = C.line, uncheckedThumbColor = C.surface))
    }
}
