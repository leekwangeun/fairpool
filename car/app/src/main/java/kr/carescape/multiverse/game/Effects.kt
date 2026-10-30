package kr.carescape.multiverse.game

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kr.carescape.multiverse.vehicle.Controls
import kr.carescape.multiverse.vehicle.Seat
import kr.carescape.multiverse.vehicle.VehicleBus

/**
 * The 4D side of the game: ambient colour, smart glass, reading lights and seat ventilation.
 * Only outputs the players feel; nothing here moves a motor that could pinch (no seat or window travel).
 */
class Effects(private val scope: CoroutineScope, private val controls: () -> Controls) {
    private var loop: Job? = null

    /** When play starts: theme lighting, dark glass, blinds down, toll voice muted. */
    fun enterTheme(theme: Theme) {
        val c = controls()
        c.cabinLight(false)
        c.ambient(null, theme.ambient, 70)
        c.smartGlass(true)
        c.blinds(true)
        c.etcsVolume(0)
        VehicleBus.effect("테마 조명", theme.title)
    }

    /** Effects that belong to one stage; the previous stage's are cleared first. */
    fun enterStage(theme: Theme, stage: Stage, child: Seat) {
        stopLoop()
        val c = controls()
        when (stage.kind) {
            GimmickKind.LIGHT_SHARD -> stage.window?.let { c.smartGlassOnlyClear(it) }
            GimmickKind.PURIFY -> {
                c.ambient(null, 0xFF7A8A2E.toInt(), 80) // murky gas colour until the parent fixes the air
                c.fan(4)
            }
            GimmickKind.TEMPERATURE -> if (theme == Theme.GHOST_TAXI) chill(child)
            GimmickKind.FINAL_CODE -> c.readingLight(Seat.FRONT_RIGHT, false)
            else -> {}
        }
        if (theme == Theme.GHOST_TAXI) haunt(child)
    }

    /** Short feedback when a stage is passed. */
    fun passed(theme: Theme, stage: Stage) {
        stopLoop()
        val c = controls()
        when (stage.kind) {
            GimmickKind.PURIFY -> {
                c.ambient(null, 0xFF2ED47A.toInt(), 80) // clean green
                c.fan(2)
            }
            GimmickKind.LIGHT_SHARD -> c.smartGlass(true)
            else -> c.ambient(null, theme.ambient, 70)
        }
        for (s in Seat.entries) c.seatVentilation(s, 0)
    }

    /**
     * The ghost taxi: the rear reading light flickers by itself and a cold draft comes from the seat behind
     * the players, as in scenario B. Runs until the stage ends.
     */
    private fun haunt(child: Seat) {
        val ghostSeat = if (child == Seat.REAR_LEFT) Seat.REAR_RIGHT else Seat.REAR_LEFT
        loop = scope.launch {
            delay(4000)
            while (isActive) {
                val c = controls()
                repeat(3) {
                    c.readingLight(ghostSeat, true); delay(220)
                    c.readingLight(ghostSeat, false); delay(260)
                }
                c.ambient(null, 0xFFB3122E.toInt(), 40) // blood red flash
                delay(700)
                c.ambient(null, Theme.GHOST_TAXI.ambient, 60)
                delay(9000)
            }
        }
    }

    /** "차가운 한기": the child's seat ventilation at full for a few seconds. */
    private fun chill(child: Seat) {
        val c = controls()
        c.seatVentilation(child, 3)
        c.fan(5)
        scope.launch {
            delay(6000)
            controls().seatVentilation(child, 0)
            controls().fan(2)
        }
    }

    /** The finale: passenger spotlight on the glove box, glass clear again. */
    fun finale(theme: Theme) {
        stopLoop()
        val c = controls()
        c.smartGlass(false)
        c.blinds(false)
        c.readingLight(Seat.FRONT_RIGHT, true)
        c.ambient(null, 0xFFFFD54F.toInt(), 100)
        VehicleBus.effect("피날레", "${theme.title} 글로브박스 스포트라이트")
    }

    /** Everything off at once (pause, abort). The snapshot restore then puts the car back. */
    fun stopAll() {
        stopLoop()
        val c = controls()
        for (s in Seat.entries) c.seatVentilation(s, 0)
    }

    private fun stopLoop() {
        loop?.cancel()
        loop = null
    }
}
