package kr.carescape.multiverse.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.dp
import kr.carescape.multiverse.data.Prefs
import kr.carescape.multiverse.game.GameEngine
import kr.carescape.multiverse.game.PlaceKind
import kr.carescape.multiverse.vehicle.VehicleBus

/** E5. [onDemoChanged] restarts the car service on the demo car or the real SDK. */
@Composable
fun SettingsScreen(prefs: Prefs, onBack: () -> Unit, onDemoChanged: (Boolean) -> Unit) {
    var tick by remember { mutableIntStateOf(0) } // re-reads the prefs after each change
    val log by VehicleBus.log.collectAsState()
    tick.let { }
    Column(Modifier.fillMaxSize().background(C.bg)) {
        TopBar(left = { GhostButton("돌아가기", onClick = onBack) }, right = { Text("설정", style = T.big) })
        Row(Modifier.fillMaxSize().padding(48.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Card(Modifier.fillMaxWidth()) {
                    Text("AI 게임마스터", style = T.big)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Chip("차량 LLM (Pleos LLM SDK)", filled = if (prefs.aiMode == "llm") C.success else null) { prefs.aiMode = "llm"; tick++ }
                        Chip("내장 생성기", filled = if (prefs.aiMode == "builtin") C.success else null) { prefs.aiMode = "builtin"; tick++ }
                    }
                    Text("차량 LLM이 없거나 답이 늦으면 내장 생성기가 대신합니다.", style = T.sub)
                }
                Card(Modifier.fillMaxWidth()) {
                    Text("장소 힌트", style = T.big)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Chip("자동 (위치)", filled = if (prefs.place == "auto") C.success else null) { prefs.place = "auto"; GameEngine.refreshContext(); tick++ }
                        for (k in PlaceKind.entries) Chip(k.label, filled = if (prefs.place == k.name) C.success else null) { prefs.place = k.name; GameEngine.refreshContext(); tick++ }
                    }
                }
                Card(Modifier.fillMaxWidth()) {
                    Toggle("데모 모드 (가짜 차량)", "켜면 차량 SDK 대신 앱 안의 가짜 차가 응답합니다.", prefs.demo) { prefs.demo = it; onDemoChanged(it); tick++ }
                    Toggle("안전 확인 수동 통과", "에뮬레이터에서 확인할 수 없는 항목(글로브박스, 창문 잠금 등)을 건너뜀으로 칩니다.", prefs.manualPass) { prefs.manualPass = it; tick++ }
                    Toggle("시연 도구 보이기", "플레이 화면 아래에 차량 신호 버튼 줄이 생깁니다.", prefs.showTools) { prefs.showTools = it; tick++ }
                }
            }
            Card(Modifier.weight(0.8f).fillMaxSize()) {
                Text("차량 기록", style = T.big)
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    for (line in log.asReversed()) Text(line, style = T.sub)
                }
            }
        }
    }
}

@Composable
private fun Toggle(title: String, detail: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = T.body)
            Text(detail, style = T.sub)
        }
        Switch(on, onChange, colors = SwitchDefaults.colors(checkedTrackColor = C.success))
    }
}
