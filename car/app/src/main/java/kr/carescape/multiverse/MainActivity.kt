package kr.carescape.multiverse

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import kr.carescape.multiverse.game.GameEngine
import kr.carescape.multiverse.game.Phase
import kr.carescape.multiverse.ui.C
import kr.carescape.multiverse.ui.ClearScreen
import kr.carescape.multiverse.ui.HomeScreen
import kr.carescape.multiverse.ui.PlayScreen
import kr.carescape.multiverse.ui.SafetyScreen
import kr.carescape.multiverse.ui.SettingsScreen
import kr.carescape.multiverse.vehicle.CarService
import kr.carescape.multiverse.vehicle.VehicleBus

/** Design width of docs/화면_설계.md; every screen is laid out in these units and scaled to the display. */
private const val DESIGN_WIDTH = 1682f

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GameEngine.attach(this)
        val prefs = GameEngine.prefs
        CarService.start(this, prefs.demo)
        val wanted = arrayOf(android.Manifest.permission.RECORD_AUDIO, "pleos.car.permission.FUSED_LOCATION")
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (wanted.isNotEmpty()) requestPermissions(wanted.toTypedArray(), 1)

        setContent {
            val widthPx = resources.displayMetrics.widthPixels.toFloat()
            CompositionLocalProvider(LocalDensity provides Density(widthPx / DESIGN_WIDTH, 1f)) {
                MaterialTheme(colorScheme = lightColorScheme(primary = C.primary, background = C.bg, surface = C.surface)) {
                    val s by GameEngine.state.collectAsState()
                    val signals by VehicleBus.signals.collectAsState()
                    var settings by remember { mutableStateOf(false) }
                    when {
                        settings && s.phase == Phase.HOME -> SettingsScreen(prefs, onBack = { settings = false }) { demo -> CarService.start(this, demo) }
                        s.phase == Phase.HOME -> HomeScreen(s, signals) { settings = true }
                        s.phase == Phase.SAFETY -> SafetyScreen(s, prefs.showTools)
                        s.phase == Phase.PLAYING || s.phase == Phase.PAUSED -> PlayScreen(s, signals, prefs.showTools)
                        s.phase == Phase.CLEARED -> ClearScreen(s)
                    }
                }
            }
        }
    }
}
