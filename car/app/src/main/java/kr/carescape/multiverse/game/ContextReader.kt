package kr.carescape.multiverse.game

import kr.carescape.multiverse.vehicle.Seat
import kr.carescape.multiverse.vehicle.Signals
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Turns the raw vehicle signals into the story context: where the car is, the weather (wipers) and the battery.
 * The place is the nearest known landmark within 30 km; there is no map API in the car.
 */
object ContextReader {
    data class Landmark(val name: String, val kind: PlaceKind, val lat: Double, val lng: Double)

    val landmarks = listOf(
        Landmark("강릉 경포 바다 근처 휴게소", PlaceKind.SEA, 37.7916, 128.9145),
        Landmark("부산 해운대", PlaceKind.SEA, 35.1587, 129.1604),
        Landmark("속초 해변", PlaceKind.SEA, 38.1904, 128.6020),
        Landmark("설악산 주차장", PlaceKind.MOUNTAIN, 38.1730, 128.4895),
        Landmark("가평 산골 캠핑장", PlaceKind.MOUNTAIN, 37.8315, 127.5105),
        Landmark("서울 도심", PlaceKind.CITY, 37.5665, 126.9780),
        Landmark("판교 테크노밸리", PlaceKind.CITY, 37.4020, 127.1086),
        Landmark("덕평 휴게소", PlaceKind.REST_STOP, 37.2465, 127.3836),
        Landmark("안성 휴게소", PlaceKind.REST_STOP, 37.0007, 127.2240)
    )

    private val overrideNames = mapOf(
        PlaceKind.SEA to "바다 근처 휴게소", PlaceKind.MOUNTAIN to "산속 주차장", PlaceKind.CITY to "도심 주차장",
        PlaceKind.REST_STOP to "고속도로 휴게소", PlaceKind.CHARGER to "급속 충전소"
    )

    /** [override] is the settings choice ("auto" or a PlaceKind name). */
    fun read(s: Signals, child: Seat, hour: Int, override: String): CarContext {
        val forced = PlaceKind.entries.firstOrNull { it.name == override }
        val (place, kind) = when {
            forced != null -> overrideNames.getValue(forced) to forced
            s.lat != null && s.lng != null -> nearest(s.lat, s.lng)?.let { it.name to it.kind } ?: ("도심 주차장" to PlaceKind.CITY)
            else -> "도심 주차장" to PlaceKind.CITY
        }
        return CarContext(
            place = place, placeKind = kind, rain = s.wiperOn, night = hour >= 19 || hour < 6,
            soc = s.evSoc, hour = hour, childSeat = child
        )
    }

    fun nearest(lat: Double, lng: Double): Landmark? =
        landmarks.map { it to km(lat, lng, it.lat, it.lng) }.filter { it.second <= 30.0 }.minByOrNull { it.second }?.first

    /** Great-circle distance in km. */
    fun km(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
        return 2 * r * asin(sqrt(a))
    }

    /** The theme the home screen highlights for this situation. */
    fun recommended(ctx: CarContext): Theme = when {
        ctx.rain || ctx.night -> Theme.GHOST_TAXI
        ctx.soc != null && ctx.placeKind == PlaceKind.CHARGER -> Theme.CODE_RED
        ctx.placeKind == PlaceKind.SEA -> Theme.SUBMARINE
        ctx.soc != null -> Theme.CODE_RED
        else -> Theme.DETECTIVE
    }
}
