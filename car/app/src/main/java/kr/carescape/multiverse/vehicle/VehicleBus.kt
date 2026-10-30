package kr.carescape.multiverse.vehicle

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The four seats the game knows. The driver is always the parent ("1열 기관장"). */
enum class Seat(val label: String) {
    DRIVER("운전석"),
    FRONT_RIGHT("조수석"),
    REAR_LEFT("뒷좌석 왼쪽"),
    REAR_RIGHT("뒷좌석 오른쪽");

    val isRear: Boolean get() = this == REAR_LEFT || this == REAR_RIGHT
}

enum class Turn { OFF, LEFT, RIGHT }

/**
 * Everything the game reads from the car, as the latest known value of each signal.
 * The Pleos SDK reports changes only, so a field stays at its default until the car has said something.
 */
data class Signals(
    val gear: String = "?",
    val speedKmh: Float = 0f,
    val occupied: Map<Seat, Boolean> = emptyMap(),
    val belted: Map<Seat, Boolean> = emptyMap(),
    val doorOpen: Map<Seat, Boolean> = emptyMap(),
    val doorLocked: Map<Seat, Boolean> = emptyMap(),
    val windowLocked: Boolean? = null,
    val windowOpen: Map<Seat, Boolean> = emptyMap(),
    val gloveBoxLocked: Boolean? = null,
    val defroster: Boolean = false,
    val maxDefrost: Boolean = false,
    val mirrorHeat: Boolean = false,
    val recirculation: Boolean = false,
    val airCleaner: Boolean = false,
    val hvacTempC: Float? = null,
    val fanSpeed: Int? = null,
    val readingLight: Map<Seat, Boolean> = emptyMap(),
    val steeringAngle: Float = 0f,
    val brake: Boolean = false,
    val turn: Turn = Turn.OFF,
    val evSoc: Int? = null,
    val wiperOn: Boolean = false,
    val lat: Double? = null,
    val lng: Double? = null,
    /** Features the car has answered for at least once (e.g. "glovebox", "smartglass", "windowlock"). */
    val supported: Set<String> = emptySet()
) {
    val parked: Boolean get() = gear == "P" || gear == "?"
    val anyDoorOpen: Boolean get() = doorOpen.values.any { it }
    val openDoors: List<Seat> get() = doorOpen.filterValues { it }.keys.toList()
    val defrostOn: Boolean get() = defroster || maxDefrost || mirrorHeat
}

/** What the car looked like before the game, so every change can be undone on exit. */
data class Snapshot(
    val doorLocked: Map<Seat, Boolean> = emptyMap(),
    val windowLocked: Boolean? = null,
    val gloveBoxLocked: Boolean? = null,
    val readingLight: Map<Seat, Boolean> = emptyMap(),
    val cabinLightOn: Boolean? = null,
    val ambientColor: Map<Seat, Int> = emptyMap(),
    val ambientBrightness: Map<Seat, Int> = emptyMap(),
    val seatVentilation: Map<Seat, Int> = emptyMap(),
    val seatHeat: Map<Seat, Int> = emptyMap(),
    val hvacTempC: Float? = null,
    val fanSpeed: Int? = null,
    val recirculation: Boolean? = null,
    val airCleaner: Boolean? = null,
    val smartGlassOpaque: Boolean? = null,
    val etcsVolume: Int? = null,
    val takenAt: Long = System.currentTimeMillis()
) {
    /** The parts the clear screen lists as restored. */
    fun restoredItems(): List<String> = buildList {
        if (doorLocked.isNotEmpty()) add("문 잠금")
        if (windowLocked != null) add("창문 잠금")
        if (gloveBoxLocked != null) add("글로브박스")
        if (hvacTempC != null || fanSpeed != null || recirculation != null || airCleaner != null) add("공조")
        if (readingLight.isNotEmpty() || cabinLightOn != null || ambientColor.isNotEmpty()) add("조명")
        if (seatVentilation.isNotEmpty() || seatHeat.isNotEmpty()) add("시트 통풍과 열선")
        if (smartGlassOpaque != null) add("스마트 글래스")
        if (etcsVolume != null) add("하이패스 안내음")
    }
}

/**
 * The write side of the car. [PleosVehicle] talks to the real SDK, [FakeVehicle] is the demo car.
 * Every call is fire-and-forget and must never throw: a car without the feature just ignores it.
 */
interface Controls {
    suspend fun snapshot(): Snapshot
    fun lockDoors(locked: Boolean)
    fun lockWindows(locked: Boolean)
    fun closeWindows()
    fun lockGloveBox(locked: Boolean)
    fun readingLight(seat: Seat, on: Boolean)
    fun cabinLight(on: Boolean)
    /** [argb] is 0xAARRGGBB as the SDK takes it; brightness 0..100. */
    fun ambient(seat: Seat?, argb: Int, brightness: Int)
    fun seatVentilation(seat: Seat, level: Int)
    fun seatHeat(seat: Seat, level: Int)
    fun hvacTemp(celsius: Float)
    fun fan(level: Int)
    fun recirculation(on: Boolean)
    fun airCleaner(on: Boolean)
    /** Whole-car smart glass: true darkens every window. */
    fun smartGlass(opaque: Boolean)
    /** One window stays clear while the rest are dark (the "빛의 조각" gimmick). */
    fun smartGlassOnlyClear(window: Seat)
    fun blinds(close: Boolean)
    fun etcsVolume(volume: Int)
    fun restore(snapshot: Snapshot)
}

/**
 * The single place the app reads vehicle signals from. The service pushes SDK readings in, the demo tools can
 * push the same fields in on top, and the engine and the UI only ever read [signals].
 */
object VehicleBus {
    private const val TAG = "CarEscape"
    private val _signals = MutableStateFlow(Signals())
    val signals: StateFlow<Signals> = _signals

    private val _log = MutableStateFlow<List<String>>(emptyList())
    /** A short rolling log for the settings screen (what the car said and what the app sent). */
    val log: StateFlow<List<String>> = _log

    /** The effects currently applied to the car, for the "4D 연출" strip. */
    private val _effects = MutableStateFlow<Map<String, String>>(emptyMap())
    val effects: StateFlow<Map<String, String>> = _effects

    @Volatile
    var controls: Controls = FakeVehicle

    private val time = SimpleDateFormat("HH:mm:ss", Locale.KOREA)

    fun update(change: (Signals) -> Signals) = _signals.update(change)

    fun supported(feature: String) = _signals.update { it.copy(supported = it.supported + feature) }

    fun note(msg: String) {
        Log.i(TAG, msg)
        val stamp = synchronized(time) { time.format(Date()) }
        _log.update { (it + "$stamp $msg").takeLast(60) }
    }

    fun effect(key: String, value: String?) = _effects.update { m -> if (value == null) m - key else m + (key to value) }

    fun clearEffects() {
        _effects.value = emptyMap()
    }
}
