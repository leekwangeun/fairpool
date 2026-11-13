package kr.fairpool.car.trip

import android.util.Log
import kr.fairpool.car.data.Place
import kr.fairpool.car.data.Rpc
import kr.fairpool.car.data.RpcException
import kr.fairpool.car.data.args
import kr.fairpool.car.settlement.Allocation
import kr.fairpool.car.settlement.Rider
import kr.fairpool.car.settlement.Settlement
import kr.fairpool.car.settlement.Shapley
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Phase { IDLE, INVITE, SEATS, DRIVING, ARRIVED, SETTLED }

/** Seats a passenger can take, with the door on the same side. */
enum class SeatSlot(val label: String, val code: String) {
    FRONT_RIGHT("조수석", "row1_right"),
    REAR_LEFT("뒷좌석 왼쪽", "row2_left"),
    REAR_RIGHT("뒷좌석 오른쪽", "row2_right")
}

data class Passenger(
    val id: String,
    val name: String,
    val pickup: Place?,
    val dropoff: Place?,
    val seat: SeatSlot? = null,
    val boarded: Boolean = false,
    val alighted: Boolean = false,
    val boardKm: Double? = null,
    val alightKm: Double? = null,
    val boardPlace: Place? = null,
    val alightPlace: Place? = null,
    /** Got in where the car started (recorded at departure). */
    val boardedAtStart: Boolean = false
)

/** A stop in the order the car visits them. */
data class Stop(val kind: String, val passengerId: String?, val name: String, val place: Place, val done: Boolean = false)

/** A recorded boarding or alighting, in order, with the odometer reading (null until the car reports one). */
data class TripEvent(val passengerId: String, val boarding: Boolean, val km: Double?, val place: Place, val time: Long,
                     val fix: Pair<Double, Double>? = null)

/**
 * A card on L5 (정차 중 확인). [confirm] false: the car recorded it and the driver can still undo it (잘못 인식).
 * [confirm] true: the car is not sure (rest area, seat swap, no GPS), so nothing is recorded until the driver answers.
 */
data class PendingCard(val passengerId: String, val name: String, val boarding: Boolean, val at: Long,
                       val confirm: Boolean = false, val slot: SeatSlot? = null)

/** What happened to the one settlement upload. */
sealed class PostResult {
    object Ok : PostResult()
    /** No answer from the server (network); worth trying again. */
    object Offline : PostResult()
    /** The server answered and said no; trying again gives the same answer. */
    data class Refused(val code: String) : PostResult()
}

data class TripState(
    val phase: Phase = Phase.IDLE,
    val code: String = "",
    val adminSecret: String = "",
    val title: String = "",
    val includeDriver: Boolean = true,
    /** "shapley" (공정 정산), "distance" (탄 거리 비례) or "equal" (N분의 1). */
    val rule: String = "shapley",
    val origin: Place? = null,
    val destination: Place? = null,
    val seats: Int = 3,
    val passengers: List<Passenger> = emptyList(),
    val plan: List<Stop> = emptyList(),
    val events: List<TripEvent> = emptyList(),
    val gear: String = "?",
    val speedKmh: Float = 0f,
    /** A speed reading has come in (CAR_SPEED granted and the car reports it). */
    val speedKnown: Boolean = false,
    /** Below 3 km/h for 2 seconds, or in P or N. Boardings and drop-offs are only read while stopped. */
    val stopped: Boolean = true,
    val odometerKm: Double? = null,
    val startOdometerKm: Double? = null,
    /** Odometer at arrival; the trip's distance stops here even if the car moves on (주차장 이동). */
    val endOdometerKm: Double? = null,
    /** Fuel economy the car reports (current), and the instantaneous one used for the running fuel sum. */
    val efficiencyKmL: Double? = null,
    val instantKmL: Double? = null,
    /** Fuel used so far on this trip (each odometer step divided by the economy at that moment), and the km it covers. */
    val fuelUsedL: Double = 0.0,
    val fuelKm: Double = 0.0,
    val toll: Long = 0,
    val fix: Pair<Double, Double>? = null,
    /** Road km and minutes from the car to every stop still ahead, in plan order (OSRM from the current fix). */
    val ahead: List<Pair<Double, Int>> = emptyList(),
    /** The car is within the arrival radius of the next stop. */
    val atStop: Boolean = false,
    /** Seats the car can read (seat sensor or belt) and whether someone sits there; seats without either are left out. */
    val seatOccupied: Map<SeatSlot, Boolean> = emptyMap(),
    /** Which sensor tells each readable seat: "좌석 센서" or "안전벨트". */
    val seatSensor: Map<SeatSlot, String> = emptyMap(),
    val doorOpen: Map<SeatSlot, Boolean> = emptyMap(),
    /** Seats that changed while the car was moving; looked at again at the next stop. */
    val deferredSeats: Set<SeatSlot> = emptySet(),
    val cards: List<PendingCard> = emptyList(),
    val allocation: Allocation? = null,
    val settlementMethod: String = "",
    val settlementKm: Double = 0.0,
    /** Km each person in [allocation] rode (the driver: the whole trip). */
    val riddenKm: List<Double> = emptyList(),
    /** Fuel economy the settlement used and where it came from: "trip" (measured while driving), "car", "default". */
    val settlementEfficiency: Double = 0.0,
    val efficiencySource: String = "",
    /** Road km of every group the settlement used (names, km after the passing rule), and where they came from. */
    val coalitions: List<Pair<List<String>, Double>> = emptyList(),
    val distanceSource: String = "",
    val settlementPosted: Boolean = false,
    val log: List<String> = emptyList(),
    val error: String? = null
) {
    val drivenKm: Double
        get() {
            val end = endOdometerKm ?: odometerKm
            return if (end != null && startOdometerKm != null) (end - startOdometerKm).coerceAtLeast(0.0) else 0.0
        }
    val aboard: List<Passenger> get() = passengers.filter { it.boarded && !it.alighted }
    val nextStop: Stop? get() = plan.firstOrNull { !it.done }
    val card: PendingCard? get() = cards.firstOrNull()
    /** Average economy measured on this trip so far, once it covers a meaningful distance. */
    val tripKmL: Double? get() = if (fuelKm >= 1.0 && fuelUsedL > 0) fuelKm / fuelUsedL else null
}

/**
 * The trip as the car sees it. Sensors (TripService) push readings in, the UI reads [state] and calls actions.
 * Boarding: while stopped, a free seat becomes occupied near the stop of the next passenger due to board.
 * Alighting: while stopped near that passenger's drop-off, their seat empties (and the same-side door opens).
 * Anything else (rest area, seat swap, belt off and on) raises a card the driver answers instead of being recorded.
 */
object TripEngine {
    private const val TAG = "FairPoolTrip"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(TripState())
    val state: StateFlow<TripState> = _state

    var rpc: Rpc? = null
    /** L1-10: the stock navi's bookmarks and recent destinations, filled by [NaviBridge]. */
    val naviBookmarks = MutableStateFlow<List<NaviPlace>>(emptyList())
    val naviRecents = MutableStateFlow<List<NaviPlace>>(emptyList())
    var navi: NaviBridge? = null
    var fuelPrice: Int = 1700
    var defaultEfficiency: Double = 11.0
    var driverName: String = "운전자"
    /** 시연 대비: settle with the table fetched at departure instead of asking the router again. */
    var preferSavedTable: Boolean = false
    /** Ask the public router for km and minutes to the stops ahead: only on the emulator, where the navi cannot follow the mock GPS. */
    var routerEta: Boolean = false
    /** The app is on screen. With no trip running, the service stops once it is not. */
    val uiVisible = MutableStateFlow(false)

    /** Group road km fetched when the trip left, keyed by the members' ids ("driver" for the driver). */
    private var savedTable: Map<Set<String>, Double> = emptyMap()

    private val time = SimpleDateFormat("HH:mm:ss", Locale.KOREA)

    fun log(msg: String) {
        Log.i(TAG, msg)
        val stamp = synchronized(time) { time.format(Date()) }
        _state.update { it.copy(log = (it.log + "$stamp $msg").takeLast(40)) }
    }

    /** Back to no trip; sensor readings stay because the SDK only reports changes. */
    fun reset() {
        routeJob?.cancel()
        clearWatches()
        _state.value = sensorsOnly(_state.value)
    }

    private fun sensorsOnly(s: TripState) = TripState(
        seatOccupied = s.seatOccupied, seatSensor = s.seatSensor, doorOpen = s.doorOpen, gear = s.gear,
        speedKmh = s.speedKmh, speedKnown = s.speedKnown, stopped = s.stopped, fix = s.fix,
        odometerKm = s.odometerKm, efficiencyKmL = s.efficiencyKmL, instantKmL = s.instantKmL, log = s.log
    )

    // ---- trip lifecycle -------------------------------------------------------------------------------

    fun startInvite(code: String, adminSecret: String, title: String, origin: Place, destination: Place, seats: Int,
                    includeDriver: Boolean, rule: String = "shapley") {
        clearWatches()
        _state.value = sensorsOnly(_state.value).copy(
            phase = Phase.INVITE, code = code, adminSecret = adminSecret, title = title,
            origin = origin, destination = destination, seats = seats, includeDriver = includeDriver, rule = rule
        )
        log("운행 $code 준비")
    }

    /** Merges the server's passenger list (get_trip_admin) without losing what the car recorded. */
    fun applyTripAdmin(trip: JSONObject) {
        val list = trip.optJSONArray("passengers") ?: JSONArray()
        _state.update { s ->
            val known = s.passengers.associateBy { it.id }
            val merged = (0 until list.length()).map { i ->
                val o = list.getJSONObject(i)
                val id = o.optString("id", o.optString("passenger_id"))
                val old = known[id]
                Passenger(
                    id = id, name = o.optString("name"),
                    pickup = Place.from(o.optJSONObject("pickup")), dropoff = Place.from(o.optJSONObject("dropoff")),
                    seat = old?.seat, boarded = old?.boarded ?: false, alighted = old?.alighted ?: false,
                    boardKm = old?.boardKm, alightKm = old?.alightKm, boardPlace = old?.boardPlace, alightPlace = old?.alightPlace,
                    boardedAtStart = old?.boardedAtStart ?: false
                )
            }
            val plan = when {
                s.phase == Phase.DRIVING -> s.plan
                else -> serverPlan(trip, s.destination) ?: planStops(s.origin, s.destination, merged)
            }
            s.copy(passengers = merged, plan = plan)
        }
    }

    /** The visiting order the server worked out (get_trip_admin.waypoints), when it sent one. */
    private fun serverPlan(trip: JSONObject, destination: Place?): List<Stop>? {
        val wps = trip.optJSONArray("waypoints") ?: return null
        if (wps.length() == 0) return null
        val stops = (0 until wps.length()).mapNotNull { i ->
            val w = wps.getJSONObject(i)
            val place = Place.from(w) ?: return@mapNotNull null
            when (val kind = w.optString("kind")) {
                "pickup", "dropoff" -> Stop(kind, w.optString("passenger_id"), w.optString("name"), place)
                else -> null
            }
        }
        return stops + listOfNotNull(destination?.let { Stop("end", null, "도착", it) })
    }

    /** L6: the driver corrects the toll (the navi only quotes the route still ahead). */
    fun setToll(won: Long) = _state.update { it.copy(toll = won.coerceAtLeast(0)) }

    /**
     * Visiting order: passengers boarding at the start get in first, then pickups and drop-offs are
     * ordered by a greedy nearest-next walk that never drops someone before picking them up.
     */
    fun planStops(origin: Place?, destination: Place?, passengers: List<Passenger>): List<Stop> {
        val stops = mutableListOf<Stop>()
        var here = origin ?: return emptyList()
        val pending = passengers.flatMap { p ->
            listOfNotNull(
                p.pickup?.takeIf { origin.distanceKm(it) > NEAR_KM }?.let { Stop("pickup", p.id, p.name, it) },
                p.dropoff?.let { Stop("dropoff", p.id, p.name, it) }
            )
        }.toMutableList()
        val picked = passengers.filter { it.pickup == null || origin.distanceKm(it.pickup) <= NEAR_KM }.map { it.id }.toMutableSet()
        while (pending.isNotEmpty()) {
            val next = pending.filter { it.kind == "pickup" || it.passengerId in picked }
                .minByOrNull { here.distanceKm(it.place) } ?: break
            stops += next
            pending.remove(next)
            if (next.kind == "pickup") picked += next.passengerId!!
            here = next.place
        }
        destination?.let { stops += Stop("end", null, "도착", it) }
        return stops
    }

    fun movePlan(from: Int, to: Int) {
        _state.update { s ->
            val list = s.plan.toMutableList()
            if (from !in list.indices || to !in list.indices || list[from].kind == "end" || list[to].kind == "end") return@update s
            val item = list.removeAt(from)
            list.add(to, item)
            // a drop-off may not come before the same person's pickup
            val ok = list.indices.all { i ->
                val st = list[i]
                val pickupAt = list.indexOfFirst { it.kind == "pickup" && it.passengerId == st.passengerId }
                st.kind != "dropoff" || pickupAt < i
            }
            if (ok) s.copy(plan = list) else s
        }
    }

    fun toSeats() = _state.update { it.copy(phase = Phase.SEATS) }

    /** Matches people already sitting in the car (at the start) to passengers who board here. */
    fun autoMatchSeatsAtStart() {
        val s = _state.value
        val atStart = s.passengers.filter { it.pickup == null || (s.origin?.distanceKm(it.pickup) ?: 99.0) <= NEAR_KM }
        val freeSeats = SeatSlot.entries.filter { slot -> s.seatOccupied[slot] == true && s.passengers.none { it.seat == slot } }
        atStart.filter { it.seat == null }.zip(freeSeats).forEach { (p, slot) -> assignSeat(p.id, slot) }
    }

    /** L3 seat tap: [passengerId] takes [slot]; whoever sat there moves to the seat they left. */
    fun swapSeat(passengerId: String, slot: SeatSlot) {
        _state.update { s ->
            val old = s.passengers.firstOrNull { it.id == passengerId }?.seat
            s.copy(passengers = s.passengers.map {
                when {
                    it.id == passengerId -> it.copy(seat = slot)
                    it.seat == slot && !it.alighted -> it.copy(seat = old)
                    else -> it
                }
            })
        }
    }

    fun assignSeat(passengerId: String, slot: SeatSlot?) {
        _state.update { s ->
            s.copy(passengers = s.passengers.map {
                when {
                    it.id == passengerId -> it.copy(seat = slot)
                    slot != null && it.seat == slot && !it.alighted -> it.copy(seat = null)
                    else -> it
                }
            })
        }
    }

    /** "경로 보내고 출발": the start passengers are aboard, route goes to the navi, recording begins. */
    fun depart() {
        val s0 = _state.value
        val origin = s0.origin ?: return
        // no odometer yet: the first reading fills these in (onOdometer)
        val km = s0.odometerKm
        val now = System.currentTimeMillis()
        val starters = s0.passengers.filter { it.pickup == null || origin.distanceKm(it.pickup) <= NEAR_KM }
        clearWatches()
        lastFuelOdometer = km
        _state.update { s ->
            s.copy(
                phase = Phase.DRIVING, startOdometerKm = km, endOdometerKm = null, fuelUsedL = 0.0, fuelKm = 0.0,
                deferredSeats = emptySet(), cards = emptyList(),
                passengers = s.passengers.map { p ->
                    if (starters.any { it.id == p.id }) p.copy(boarded = true, boardKm = km, boardPlace = origin, boardedAtStart = true) else p
                },
                events = starters.map { TripEvent(it.id, true, km, origin, now, s.fix) }
            )
        }
        scope.launch {
            call("set_trip_status", args("p_code" to s0.code, "p_admin_secret" to s0.adminSecret, "p_status" to "in_progress"))
            for (p in _state.value.passengers.filter { it.boarded }) {
                call("mark_passenger", args("p_code" to s0.code, "p_admin_secret" to s0.adminSecret,
                    "p_passenger_id" to p.id, "p_seat" to p.seat?.code, "p_boarded" to true))
            }
            pushProgress()
        }
        sendRoute()
        log("출발: 동승 ${starters.size}명, " + (km?.let { "주행거리 기준 %.1fkm".format(it) } ?: "주행거리는 첫 값이 오면 기록"))
        prefetchTable()
    }

    /**
     * At departure the planned course is known (start, every pickup and drop-off, end), so the road km of every
     * group is fetched once. If the network drops later, or the demo switch is on, the settlement uses this table.
     */
    private fun prefetchTable() {
        val s = _state.value
        val origin = s.origin ?: return
        val dest = s.destination ?: origin
        val owners = mutableListOf("driver" to origin)
        s.passengers.filter { p -> p.boarded }.forEach { owners += it.id to origin }
        for (st in s.plan) if (st.kind != "end" && st.passengerId != null) owners += st.passengerId to st.place
        owners += "driver" to dest
        val ids = owners.map { it.first }.distinct()
        if (ids.size > 5) return
        savedTable = emptyMap()
        scope.launch(Dispatchers.Default) {
            try {
                val table = mutableMapOf<Set<String>, Double>()
                for (mask in 1 until (1 shl ids.size)) {
                    val members = ids.filterIndexed { i, _ -> mask and (1 shl i) != 0 }.toSet()
                    table[members] = round1(routeKm(owners.filter { it.first in members }.map { it.second }))
                }
                savedTable = table
                log("조합 거리표 미리 받음: ${table.size}개")
            } catch (e: Exception) {
                log("조합 거리표를 미리 받지 못함: ${e.message}")
            }
        }
    }

    /** Sliding window: next two stops as waypoints, the third (or the end) as the destination. */
    fun sendRoute() {
        val remaining = _state.value.plan.filter { !it.done }
        if (remaining.isEmpty()) return
        val dest = if (remaining.size > 2) remaining[2] else remaining.last()
        val wps = remaining.take(2).filter { it != dest }
        navi?.route(wps.map { it.place }, dest.place)
        log("내비 경로: " + (wps.map { it.name } + "→ ${dest.name}").joinToString(" "))
    }

    private var routeJob: Job? = null

    /**
     * The stock navi comes to the front whenever it gets a new route, which would hide the L5 card.
     * So after a stop the next leg goes out once the card has had its time (and an undo can still change it).
     */
    private fun sendRouteSoon(delayMs: Long = 10_000) {
        routeJob?.cancel()
        routeJob = scope.launch {
            delay(delayMs)
            if (_state.value.phase == Phase.DRIVING) sendRoute()
        }
    }

    // ---- sensor input: driving state ------------------------------------------------------------------

    private var lowSpeedSince: Long? = null
    private var stopTimer: Job? = null

    fun onGear(gear: String) {
        val short = when {
            gear.contains("PARK") -> "P"
            gear.contains("REVERSE") -> "R"
            gear.contains("NEUTRAL") -> "N"
            gear.contains("DRIVE") -> "D"
            else -> gear
        }
        _state.update { it.copy(gear = short) }
        updateStopped()
    }

    /** Vehicle speed in km/h (AAOS PERF_VEHICLE_SPEED). Stopped = under 3 km/h for 2 seconds. */
    fun onSpeed(kmh: Float) {
        _state.update { it.copy(speedKmh = kmh, speedKnown = true) }
        if (kmh < STOP_KMH) {
            if (lowSpeedSince == null) {
                lowSpeedSince = System.currentTimeMillis()
                stopTimer?.cancel()
                stopTimer = scope.launch {
                    delay(STOP_HOLD_MS + 50)
                    updateStopped()
                }
            }
        } else {
            lowSpeedSince = null
            stopTimer?.cancel()
        }
        updateStopped()
    }

    private fun isStopped(s: TripState): Boolean {
        if (s.gear == "P" || s.gear == "N") return true
        // no speed reading (permission or car): only the gear can tell, and D or R counts as moving
        if (!s.speedKnown) return s.gear != "D" && s.gear != "R"
        val since = lowSpeedSince ?: return false
        return System.currentTimeMillis() - since >= STOP_HOLD_MS
    }

    private fun updateStopped() {
        val s = _state.value
        val now = isStopped(s)
        if (now == s.stopped) return
        _state.update { it.copy(stopped = now) }
        if (s.phase != Phase.DRIVING) return
        if (now) {
            log("정차")
            reconcileAtStop()
        } else {
            log("주행 중: 좌석 변화는 다음 정차 때 확인")
        }
    }

    fun onOdometer(km: Double) {
        val s = _state.value
        // the running fuel sum: each odometer step at the economy the car reports at that moment
        val last = lastFuelOdometer
        val eff = s.instantKmL ?: s.efficiencyKmL
        val step = if (s.phase == Phase.DRIVING && last != null) km - last else 0.0
        val burn = if (step > 0 && step < MAX_ODOMETER_STEP_KM && eff != null && eff > 1) step / eff else 0.0
        if (s.phase == Phase.DRIVING) lastFuelOdometer = km
        _state.update {
            var n = it.copy(odometerKm = km)
            if (it.phase == Phase.DRIVING && it.startOdometerKm == null) {
                // the first reading after departure: everything recorded so far happened at this point
                n = n.copy(
                    startOdometerKm = km,
                    passengers = n.passengers.map { p ->
                        p.copy(boardKm = if (p.boarded) p.boardKm ?: km else p.boardKm,
                            alightKm = if (p.alighted) p.alightKm ?: km else p.alightKm)
                    },
                    events = n.events.map { e -> if (e.km == null) e.copy(km = km) else e }
                )
            }
            if (burn > 0) n = n.copy(fuelUsedL = n.fuelUsedL + burn, fuelKm = n.fuelKm + step)
            n
        }
    }

    private var lastFuelOdometer: Double? = null

    /** The fuel economy the car shows (km/L). */
    fun onEfficiency(kmL: Double) {
        if (kmL > 1) _state.update { it.copy(efficiencyKmL = kmL) }
    }

    /** Instantaneous fuel economy (km/L); idling readings (0) are skipped, the odometer does not move then anyway. */
    fun onInstantEfficiency(kmL: Double) {
        if (kmL > 1) _state.update { it.copy(instantKmL = kmL) }
    }

    /** Economy for the settlement: measured over this trip if it covers enough of it, else the car's value, else the default. */
    fun efficiencyFor(s: TripState): Pair<Double, String> {
        val trip = s.tripKmL
        return when {
            trip != null && s.fuelKm >= 0.5 * s.drivenKm -> trip to "trip"
            s.efficiencyKmL != null -> s.efficiencyKmL to "car"
            else -> defaultEfficiency to "default"
        }
    }

    private var fixLogged = false

    private var aheadAt = 0L
    private var aheadFrom: Pair<Double, Double>? = null
    private var aheadJob: Job? = null

    fun onLocation(lat: Double, lng: Double) {
        val here = Place("", lat, lng)
        val wasAt = _state.value.atStop
        _state.update { s ->
            val near = s.phase == Phase.DRIVING && s.nextStop?.let { it.place.distanceKm(here) <= ARRIVE_KM } == true
            s.copy(fix = lat to lng, atStop = near)
        }
        if (!fixLogged) {
            fixLogged = true
            log("위치 수신: %.5f, %.5f".format(lat, lng))
        }
        val s = _state.value
        if (s.phase != Phase.DRIVING) return
        if (s.atStop && !wasAt) log("${s.nextStop?.place?.label} 도착 (위치 반경 ${(ARRIVE_KM * 1000).toInt()}m)")
        // remaining km and minutes to every stop: the navi cannot follow the emulator GPS, so ask the router there only
        if (!routerEta) return
        val now = System.currentTimeMillis()
        val moved = aheadFrom?.let { Place("", it.first, it.second).distanceKm(here) } ?: Double.MAX_VALUE
        if (now - aheadAt < 5_000 || moved < 0.15 || aheadJob?.isActive == true) return
        aheadAt = now
        aheadFrom = lat to lng
        val stops = s.plan.filter { !it.done }.map { it.place }
        if (stops.isEmpty()) return
        aheadJob = scope.launch(Dispatchers.IO) {
            val legs = runCatching { routeLegs(listOf(here) + stops) }.getOrNull() ?: return@launch
            var km = 0.0
            var min = 0.0
            val list = legs.map { (k, m) -> km += k; min += m; round1(km) to Math.round(min).toInt() }
            _state.update { it.copy(ahead = list) }
            pushProgress()
        }
    }

    fun onToll(won: Long) {
        // every re-route quotes the toll still ahead; the largest quote covers the tolled stretch
        if (won > 0) _state.update { it.copy(toll = maxOf(it.toll, won)) }
    }

    // ---- sensor input: doors and seats ----------------------------------------------------------------

    private val doorOpenedAt = mutableMapOf<SeatSlot, Long>()
    private val doorClosedAt = mutableMapOf<SeatSlot, Long>()

    /** A passenger whose seat emptied during a stop, until it is settled (recorded, answered, or they sit again). */
    private class AlightWatch(val passengerId: String, val slot: SeatSlot, val since: Long, var job: Job?)

    private val alightWatch = mutableMapOf<String, AlightWatch>()

    private fun clearWatches() {
        alightWatch.values.forEach { it.job?.cancel() }
        alightWatch.clear()
    }

    fun onDoor(slot: SeatSlot, open: Boolean) {
        val was = _state.value.doorOpen[slot]
        _state.update { it.copy(doorOpen = it.doorOpen + (slot to open)) }
        if (was == open) return
        if (open) doorOpenedAt[slot] = System.currentTimeMillis() else doorClosedAt[slot] = System.currentTimeMillis()
        if (was != null) log("${slot.label} 문 ${if (open) "열림" else "닫힘"}")
    }

    /**
     * One seat as the car reads it: true someone sits, false empty, null the car cannot tell
     * (no seat sensor and no belt there, so that seat is confirmed by hand). [sensor] names what told us.
     */
    fun onSeat(slot: SeatSlot, occupied: Boolean?, sensor: String = "") {
        val before = _state.value.seatOccupied[slot]
        _state.update {
            if (occupied == null) it.copy(seatOccupied = it.seatOccupied - slot, seatSensor = it.seatSensor - slot)
            else it.copy(seatOccupied = it.seatOccupied + (slot to occupied), seatSensor = it.seatSensor + (slot to sensor))
        }
        // the first reading of a seat is where things stand, not someone getting in or out
        if (occupied == null || before == null || before == occupied) return
        log("${slot.label} ${if (occupied) "착석" else "비움"} ($sensor)")
        val s = _state.value
        if (s.phase != Phase.DRIVING) return
        if (!s.stopped) {
            // a belt or seat change on the move is not someone getting in or out: look again at the next stop
            _state.update { it.copy(deferredSeats = it.deferredSeats + slot) }
            return
        }
        if (occupied) seatTaken(slot, sure = true) else seatEmptied(slot, sure = true)
    }

    /** At every stop: changes seen on the move are looked at again, and empty seats near a drop-off are watched. */
    private fun reconcileAtStop() {
        val s = _state.value
        val changed = s.deferredSeats
        _state.update { it.copy(deferredSeats = emptySet()) }
        if (changed.isNotEmpty()) {
            val vacated = changed.filter { slot -> s.seatOccupied[slot] == false && s.aboard.any { it.seat == slot } }.toMutableList()
            val taken = changed.filter { slot -> s.seatOccupied[slot] == true }.toMutableList()
            // someone moved seats on the move: one aboard seat emptied and one free seat filled
            for (to in taken.filter { slot -> s.aboard.none { it.seat == slot } }) {
                val from = vacated.firstOrNull() ?: break
                val p = s.aboard.first { it.seat == from }
                assignSeat(p.id, to)
                vacated.remove(from)
                taken.remove(to)
                log("${p.name} 자리 옮김: ${from.label} → ${to.label} (주행 중)")
            }
            for (slot in vacated) seatEmptied(slot, sure = true)
            for (slot in taken) seatTaken(slot, sure = false)
        }
        // a seat that was already empty (belt off before the stop, or a "아니에요" earlier) at this passenger's drop-off
        val now = _state.value
        for (p in now.aboard) {
            val slot = p.seat ?: continue
            if (now.seatOccupied[slot] == false && p.id !in alightWatch && nearDropoff(now, p) == true) seatEmptied(slot, sure = true)
        }
    }

    private fun seatTaken(slot: SeatSlot, sure: Boolean) {
        val s = _state.value
        s.aboard.firstOrNull { it.seat == slot }?.let { backInSeat(it); return }
        // someone recorded by hand without a seat just sat down: give them the seat, nobody new boarded
        s.aboard.firstOrNull { it.seat == null }?.let {
            assignSeat(it.id, slot)
            log("${it.name} 자리: ${slot.label}")
            return
        }
        val due = dueBoarders(s)
        val nearby = due.firstOrNull { nearPickup(s, it) }
        if (nearby != null) {
            if (sure) board(nearby.id, slot)
            else raiseCard(PendingCard(nearby.id, nearby.name, true, System.currentTimeMillis(), confirm = true, slot = slot))
            return
        }
        // someone who left another seat during this stop (not at their drop-off) sat down here: a seat swap
        val mover = alightWatch.values.filter { it.slot != slot && s.seatOccupied[it.slot] != true }
            .sortedByDescending { it.since }
            .firstNotNullOfOrNull { w -> s.aboard.firstOrNull { it.id == w.passengerId && nearDropoff(s, it) != true } }
        if (mover != null) {
            val from = mover.seat
            dropWatch(mover.id)
            assignSeat(mover.id, slot)
            log("${mover.name} 자리 옮김: ${from?.label ?: ""} → ${slot.label}")
            return
        }
        // not at anyone's pickup (or no GPS): ask instead of guessing who got in
        val first = due.firstOrNull()
        if (first != null) {
            raiseCard(PendingCard(first.id, first.name, true, System.currentTimeMillis(), confirm = true, slot = slot))
            log("${slot.label} 착석: 탈 곳이 아니어서 ${first.name}님인지 확인을 기다림")
        } else {
            log("${slot.label} 착석: 탈 사람이 없어 기록하지 않음")
        }
    }

    /** Back in their own seat during the stop: a temporary exit (휴게소) or a re-buckled belt, nothing is recorded. */
    private fun backInSeat(p: Passenger) {
        val watched = dropWatch(p.id)
        if (watched) log("${p.name} 다시 앉음: 잠깐 내린 것으로 보고 기록하지 않음")
    }

    /** Stops watching [passengerId] and takes down their 하차 question; true if there was something to drop. */
    private fun dropWatch(passengerId: String): Boolean {
        val w = alightWatch.remove(passengerId)
        w?.job?.cancel()
        val hadCard = _state.value.cards.any { it.passengerId == passengerId && !it.boarding && it.confirm }
        if (hadCard) _state.update { st -> st.copy(cards = st.cards.filterNot { it.passengerId == passengerId && !it.boarding && it.confirm }) }
        return w != null || hadCard
    }

    private fun seatEmptied(slot: SeatSlot, sure: Boolean) {
        val s = _state.value
        val p = s.aboard.firstOrNull { it.seat == slot } ?: return
        if (p.id in alightWatch) return
        val since = System.currentTimeMillis()
        val w = AlightWatch(p.id, slot, since, null)
        alightWatch[p.id] = w
        w.job = scope.launch {
            // wait until the seat has stayed empty 5 s after the same-side door closed (or after it emptied, without a door reading)
            while (true) {
                delay(1000)
                val st = _state.value
                if (st.seatOccupied[slot] == true) return@launch
                if (!st.stopped || st.phase != Phase.DRIVING) break
                if (st.doorOpen[slot] == true) continue
                val doorKnown = st.doorOpen.containsKey(slot)
                val opened = doorOpenedAt[slot]?.takeIf { it >= since - DOOR_WINDOW_MS }
                val from = if (opened != null) maxOf(since, doorClosedAt[slot] ?: since) else since
                val waited = System.currentTimeMillis() - from
                if (waited >= EMPTY_HOLD_MS && (opened != null || !doorKnown || waited >= DOOR_WAIT_MS)) break
            }
            decideAlight(p.id, slot, since, sure)
        }
    }

    private fun decideAlight(passengerId: String, slot: SeatSlot, since: Long, sure: Boolean) {
        val st = _state.value
        if (st.phase != Phase.DRIVING) return
        val p = st.aboard.firstOrNull { it.id == passengerId && it.seat == slot }
        if (p == null) {
            alightWatch.remove(passengerId)
            return
        }
        val door = doorOpenedAt[slot]?.let { it >= since - DOOR_WINDOW_MS } == true
        val doorKnown = st.doorOpen.containsKey(slot)
        val auto = sure && when (nearDropoff(st, p)) {
            true -> door || !doorKnown
            // no GPS fix: the same-side door opening is the only evidence left
            null -> door
            false -> false
        }
        if (auto) {
            alight(p.id)
        } else {
            raiseCard(PendingCard(p.id, p.name, false, System.currentTimeMillis(), confirm = true, slot = slot))
            log("${p.name} 자리 빔: 내릴 곳이 아니어서 확인을 기다림")
        }
    }

    /** Passengers still to board, in the order the plan picks them up. */
    private fun dueBoarders(s: TripState): List<Passenger> =
        s.passengers.filter { p -> !p.boarded && s.cards.none { it.passengerId == p.id && it.boarding } }
            .sortedBy { p -> s.plan.indexOfFirst { it.kind == "pickup" && it.passengerId == p.id }.let { if (it < 0) Int.MAX_VALUE else it } }

    private fun distanceFromCar(s: TripState, place: Place?): Double? {
        val fix = s.fix ?: return null
        return place?.distanceKm(Place("", fix.first, fix.second))
    }

    private fun nearPickup(s: TripState, p: Passenger): Boolean =
        distanceFromCar(s, p.pickup ?: s.origin)?.let { it <= NEAR_KM } == true

    /** true/false: the car is (not) at this passenger's drop-off; null: no GPS fix to tell. */
    private fun nearDropoff(s: TripState, p: Passenger): Boolean? =
        distanceFromCar(s, p.dropoff ?: s.destination)?.let { it <= NEAR_KM }

    /** Where the car is now, named after the planned place when it is that place; the plan when there is no fix. */
    private fun placeHere(s: TripState, planned: List<Place>, label: String): Place? {
        val fix = s.fix ?: return planned.firstOrNull()
        val here = Place(label, fix.first, fix.second)
        val match = planned.firstOrNull { it.distanceKm(here) <= NEAR_KM }
        return match?.copy(lat = fix.first, lng = fix.second) ?: here
    }

    private fun raiseCard(card: PendingCard) {
        _state.update { st ->
            st.copy(cards = st.cards.filterNot { it.passengerId == card.passengerId && it.boarding == card.boarding } + card)
        }
    }

    // ---- boarding and alighting -----------------------------------------------------------------------

    fun board(passengerId: String, slot: SeatSlot?, unchecked: Boolean = false, showCard: Boolean = true) {
        val s = _state.value
        val p = s.passengers.firstOrNull { it.id == passengerId } ?: return
        val km = s.odometerKm
        val place = placeHere(s, listOfNotNull(p.pickup, s.origin), "탄 곳") ?: return
        val now = System.currentTimeMillis()
        _state.update { st ->
            st.copy(
                passengers = st.passengers.map {
                    when {
                        it.id == passengerId -> it.copy(boarded = true, alighted = false, seat = slot ?: it.seat, boardKm = km,
                            boardPlace = place, boardedAtStart = false)
                        slot != null && it.seat == slot && !it.alighted -> it.copy(seat = null)
                        else -> it
                    }
                },
                events = st.events + TripEvent(passengerId, true, km, place, now, st.fix),
                plan = st.plan.map { if (it.kind == "pickup" && it.passengerId == passengerId) it.copy(done = true) else it },
                ahead = st.ahead.drop(1),
                cards = st.cards.filterNot { it.passengerId == passengerId && it.boarding } +
                    listOfNotNull(if (showCard) PendingCard(passengerId, p.name, true, now) else null)
            )
        }
        log("${p.name} 탑승 (${slot?.label ?: "좌석 미지정"})" + (s.fix?.let { " 위치 %.5f, %.5f".format(it.first, it.second) } ?: ""))
        scope.launch {
            call("mark_passenger", args("p_code" to s.code, "p_admin_secret" to s.adminSecret, "p_passenger_id" to passengerId,
                "p_seat" to (slot ?: p.seat)?.code, "p_boarded" to true, "p_unchecked" to unchecked))
            pushProgress()
        }
        if (s.plan.firstOrNull { !it.done }?.passengerId == passengerId) sendRouteSoon()
    }

    fun alight(passengerId: String, unchecked: Boolean = false, showCard: Boolean = true) {
        val s = _state.value
        val p = s.passengers.firstOrNull { it.id == passengerId } ?: return
        val km = s.endOdometerKm ?: s.odometerKm
        val place = placeHere(s, listOfNotNull(p.dropoff, s.destination), "내린 곳") ?: return
        val now = System.currentTimeMillis()
        alightWatch.remove(passengerId)?.job?.cancel()
        _state.update { st ->
            st.copy(
                passengers = st.passengers.map { if (it.id == passengerId) it.copy(alighted = true, alightKm = km, alightPlace = place) else it },
                events = st.events + TripEvent(passengerId, false, km, place, now, st.fix),
                plan = st.plan.map { if (it.passengerId == passengerId) it.copy(done = true) else it },
                ahead = st.ahead.drop(1),
                cards = st.cards.filterNot { it.passengerId == passengerId && !it.boarding } +
                    listOfNotNull(if (showCard) PendingCard(passengerId, p.name, false, now) else null)
            )
        }
        log("${p.name} 하차 기록" + (s.fix?.let { " 위치 %.5f, %.5f".format(it.first, it.second) } ?: ""))
        scope.launch {
            call("mark_passenger", args("p_code" to s.code, "p_admin_secret" to s.adminSecret, "p_passenger_id" to passengerId,
                "p_alighted" to true, "p_unchecked" to unchecked))
            pushProgress()
        }
        sendRouteSoon()
    }

    /** "탔어요" / "내렸어요" on a question card: records it where the car is now. */
    fun confirmCard() {
        val card = _state.value.card ?: return
        if (!card.confirm) {
            dismissCard()
            return
        }
        if (card.boarding) board(card.passengerId, card.slot, showCard = false) else alight(card.passengerId, showCard = false)
        _state.update { st -> st.copy(cards = st.cards.filterNot { it.passengerId == card.passengerId && it.boarding == card.boarding }) }
    }

    /** L5 "잘못 인식": undoes the last automatic boarding or alighting of that passenger (a question card is just closed). */
    fun undoCard() {
        val s = _state.value
        val card = s.card ?: return
        if (card.confirm) {
            dismissCard()
            return
        }
        _state.update { st ->
            st.copy(
                cards = st.cards.drop(1),
                events = st.events.dropLastWhile { it.passengerId == card.passengerId && it.boarding == card.boarding },
                passengers = st.passengers.map {
                    if (it.id != card.passengerId) it
                    else if (card.boarding) it.copy(boarded = false, boardKm = null, boardPlace = null)
                    else it.copy(alighted = false, alightKm = null, alightPlace = null)
                },
                plan = st.plan.map {
                    if (it.passengerId != card.passengerId) it
                    else if (card.boarding) it.copy(done = false)
                    else it.copy(done = it.kind == "pickup")
                }
            )
        }
        log("${card.name} ${if (card.boarding) "탑승" else "하차"} 취소 (잘못 인식)")
        scope.launch {
            val key = if (card.boarding) "p_boarded" else "p_alighted"
            call("mark_passenger", args("p_code" to s.code, "p_admin_secret" to s.adminSecret, "p_passenger_id" to card.passengerId, key to false))
            pushProgress()
        }
        sendRouteSoon()
    }

    /** Closes the front card. On a 하차 question this is "아니에요": they are still riding (잠깐 내렸음). */
    fun dismissCard() {
        val card = _state.value.card ?: return
        if (card.confirm && !card.boarding) {
            alightWatch.remove(card.passengerId)?.job?.cancel()
            log("${card.name} 계속 탑승 중으로 둠")
        }
        _state.update { it.copy(cards = it.cards.drop(1)) }
    }

    fun arrive() {
        val s = _state.value
        clearWatches()
        // the trip's distance ends here, even if the car moves on to park
        _state.update { it.copy(endOdometerKm = it.odometerKm) }
        s.aboard.forEach { alight(it.id, unchecked = true, showCard = false) }
        routeJob?.cancel()
        _state.update { it.copy(phase = Phase.ARRIVED, cards = emptyList(), deferredSeats = emptySet()) }
        log("도착: 주행 ${"%.1f".format(_state.value.drivenKm)}km")
    }

    // ---- progress for the passengers' pages -----------------------------------------------------------

    private suspend fun pushProgress() {
        val s = _state.value
        if (s.code.isEmpty() || s.phase == Phase.ARRIVED || s.phase == Phase.SETTLED) return
        val stops = JSONArray()
        val hhmm = SimpleDateFormat("HH:mm", Locale.KOREA)
        s.origin?.let { o ->
            val left = s.events.minOfOrNull { it.time }
            stops.put(JSONObject().put("kind", "start").put("label", o.label).put("state", "done").apply {
                if (left != null) put("time", hhmm.format(Date(left)))
            })
        }
        val next = s.nextStop
        var aheadIndex = 0
        for (st in s.plan) {
            val o = JSONObject().put("kind", st.kind).put("label", st.place.label).put("area", st.place.area)
                .put("state", if (st.done) "done" else if (st == next) "now" else "next")
            st.passengerId?.let { o.put("passenger_id", it) }
            if (st.done) {
                s.events.lastOrNull { it.passengerId == st.passengerId && it.boarding == (st.kind == "pickup") }
                    ?.let { o.put("time", hhmm.format(Date(it.time))) }
            } else {
                s.ahead.getOrNull(aheadIndex++)?.let { (km, min) -> o.put("eta_min", min).put("distance_km", km) }
            }
            stops.put(o)
        }
        val progress = JSONObject().put("stops", stops)
        s.fix?.let { (lat, lng) -> progress.put("car", JSONObject().put("lat", lat).put("lng", lng)) }
        call("update_progress", args("p_code" to s.code, "p_admin_secret" to s.adminSecret, "p_progress" to progress))
    }

    /** One call at a time, in the order the car recorded things (an undo must not overtake its boarding). */
    private val serverLine = Mutex()

    private suspend fun call(fn: String, a: JSONObject): JSONObject? = serverLine.withLock {
        try {
            rpc?.call(fn, a)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("서버 $fn 실패: ${e.message}")
            null
        }
    }

    // ---- settlement -----------------------------------------------------------------------------------

    /**
     * Total cost = driven km × (fuel price / economy) + toll, split by the trip's rule:
     * shapley (공정 정산, each group's road km from the public OSRM router with the passing rule),
     * distance (탄 거리 비례, km each person was aboard) or equal (N분의 1).
     * The 공정 정산 amounts are always worked out too, so another rule can show the difference.
     * If routing fails the fixed-route Shapley value (segment N분의 1) stands in, and the amounts are marked 임시.
     */
    suspend fun computeSettlement(forceRoute: Boolean = false): Allocation? {
        val c = settle(_state.value, forceRoute) ?: return null
        _state.update { c.applyTo(it) }
        return c.allocation
    }

    /** A worked-out settlement and what it was based on, before it goes into the state. */
    private class Computed(
        val allocation: Allocation, val km: Double, val groups: List<Pair<List<String>, Double>>, val source: String,
        val ridden: List<Double>, val efficiency: Double, val efficiencySource: String
    ) {
        fun applyTo(s: TripState) = s.copy(allocation = allocation, settlementMethod = allocation.method, settlementKm = km,
            coalitions = groups, distanceSource = source, riddenKm = ridden, settlementEfficiency = efficiency,
            efficiencySource = efficiencySource)
    }

    /** [forceRoute]: ask the router even when the demo switch prefers the table fetched at departure. */
    private suspend fun settle(s: TripState, forceRoute: Boolean): Computed? = withContext(Dispatchers.Default) {
        val origin = s.origin ?: return@withContext null
        val dest = s.destination ?: origin
        val people = s.passengers.filter { it.boarded }
        val riders = listOf(Rider(null, driverName, true)) + people.map { Rider(it.id, it.name, false) }
        val n = riders.size
        val events = s.events.sortedBy { it.time }

        // visiting sequence: start, every recorded boarding/alighting in order, end
        data class Pt(val owner: Int, val place: Place)
        val seq = mutableListOf(Pt(0, origin))
        for (e in events) {
            val idx = people.indexOfFirst { it.id == e.passengerId }
            if (idx >= 0) seq += Pt(idx + 1, e.place)
        }
        seq += Pt(0, dest)

        val (efficiency, efficiencySource) = efficiencyFor(s)
        val fuelPerKm = fuelPrice / efficiency
        // aboard on the tolled stretch: got in within 0.3 km of the start by the odometer (or at departure, without one)
        val tollPayers = buildSet {
            add(0)
            people.forEachIndexed { i, p ->
                val start = s.startOdometerKm
                val atStart = if (p.boardKm != null && start != null) p.boardKm - start < NEAR_KM else p.boardedAtStart
                if (atStart) add(i + 1)
            }
        }

        var method = "shapley"
        val full = (1 shl n) - 1
        val raw = DoubleArray(1 shl n)
        val keyOf = { mask: Int -> riders.indices.filter { mask and (1 shl it) != 0 }.map { riders[it].id ?: "driver" }.toSet() }
        val masks = (1..full).toList()
        val saved = savedTable
        val savedCovers = masks.all { saved.containsKey(keyOf(it)) }
        var source = "osrm"
        if (preferSavedTable && savedCovers && !forceRoute) {
            masks.forEach { raw[it] = saved.getValue(keyOf(it)) }
            source = "saved"
            log("시연 대비: 출발 때 받은 거리표로 계산")
        } else {
            try {
                for (mask in masks) {
                    val pts = seq.filter { mask and (1 shl it.owner) != 0 }.map { it.place }
                    raw[mask] = round1(routeKm(pts))
                }
            } catch (e: Exception) {
                if (savedCovers) {
                    masks.forEach { raw[it] = saved.getValue(keyOf(it)) }
                    source = "saved"
                    log("길찾기 실패, 출발 때 받은 거리표로 계산: ${e.message}")
                } else {
                    method = "fixed_route"
                    source = "segments"
                    log("길찾기 실패, 구간별 N분의 1로 계산: ${e.message}")
                }
            }
        }

        val segments = drivenSegments(s, people, events, origin, dest)
        val ridden = Shapley.riddenKm(n, segments)
        val fairKm: DoubleArray
        val aloneKm: DoubleArray
        val cost = if (method == "shapley") Shapley.applyPassingRule(n, raw) else null
        if (cost != null) {
            fairKm = Shapley.values(n, cost)
            aloneKm = DoubleArray(n) { cost[1 shl it] }
        } else {
            fairKm = Shapley.fixedRoute(n, segments)
            // no routing: going alone is a straight line with a road factor (shown as 약, never used for the cap)
            aloneKm = DoubleArray(n) { i ->
                if (i == 0) origin.distanceKm(dest) * ROAD_FACTOR
                else people[i - 1].let { p -> (p.boardPlace ?: origin).distanceKm(p.alightPlace ?: dest) * ROAD_FACTOR }
            }
        }
        val actualKm = s.drivenKm.takeIf { it > 0.5 } ?: cost?.get(full) ?: segments.sumOf { it.first }
        val capKnown = cost != null
        val fair = Settlement.allocate(riders, fairKm, aloneKm, actualKm, fuelPerKm, s.toll, tollPayers, s.includeDriver,
            method, capAtAlone = capKnown)
        val chosen = when (s.rule) {
            // N분의 1: the whole cost split evenly, toll included
            "equal" -> Settlement.allocate(riders, DoubleArray(n) { 1.0 }, aloneKm, actualKm, fuelPerKm, s.toll,
                riders.indices.toSet(), s.includeDriver, "equal", capAtAlone = false)
            "distance" -> Settlement.allocate(riders, ridden, aloneKm, actualKm, fuelPerKm, s.toll, tollPayers, s.includeDriver,
                "distance", capAtAlone = capKnown)
            else -> fair
        }
        val allocation = chosen.copy(
            fair = if (chosen === fair) null else fair.shares.map { it.amount },
            provisional = source != "osrm"
        )
        val groups = if (cost != null) {
            (1..full).map { m -> riders.indices.filter { m and (1 shl it) != 0 }.map { riders[it].name } to round1(cost[m]) }
        } else emptyList()
        Computed(allocation, actualKm, groups, source, ridden.toList(), efficiency, efficiencySource)
    }

    /**
     * The trip cut at every boarding and alighting, with who was aboard (bit 0 the driver, bit i+1 passenger i).
     * Lengths come from the odometer; without odometer movement (emulator, sensor fault) from straight lines
     * between the recorded places with a road factor.
     */
    private fun drivenSegments(s: TripState, people: List<Passenger>, events: List<TripEvent>, origin: Place, dest: Place):
        List<Pair<Double, Int>> {
        val segments = mutableListOf<Pair<Double, Int>>()
        var aboard = 1
        val start = s.startOdometerKm
        fun step(e: TripEvent) {
            val idx = people.indexOfFirst { it.id == e.passengerId }
            if (idx < 0) return
            val bit = 1 shl (idx + 1)
            aboard = if (e.boarding) aboard or bit else aboard and bit.inv()
        }
        if (s.drivenKm > 0.5 && start != null) {
            var lastKm: Double = start
            for (e in events) {
                val km = e.km ?: lastKm
                segments += (km - lastKm).coerceAtLeast(0.0) to aboard
                lastKm = maxOf(lastKm, km)
                step(e)
            }
            segments += ((s.endOdometerKm ?: s.odometerKm ?: lastKm) - lastKm).coerceAtLeast(0.0) to aboard
        } else {
            var last = origin
            for (e in events) {
                segments += last.distanceKm(e.place) * ROAD_FACTOR to aboard
                last = e.place
                step(e)
            }
            segments += last.distanceKm(dest) * ROAD_FACTOR to aboard
        }
        return segments
    }

    fun payload(a: Allocation): JSONObject = payload(_state.value, a)

    /**
     * What post_settlement stores. [rule] is the trip's rule (shapley, distance, equal) even when routing failed;
     * then provisional is true (and the server holds payment until a routed settlement replaces it).
     */
    private fun payload(s: TripState, a: Allocation): JSONObject {
        val shares = JSONArray()
        a.shares.forEachIndexed { i, sh ->
            val p = s.passengers.firstOrNull { it.id == sh.rider.id }
            val o = JSONObject().put("passenger_id", sh.rider.id ?: JSONObject.NULL).put("name", sh.rider.name)
                .put("role", if (sh.rider.isDriver) "driver" else "passenger").put("amount", sh.amount)
                .put("share_km", round1(sh.shareKm)).put("alone", sh.alone)
                .put("capped", i in a.capped).put("provisional", a.provisional)
            a.fair?.getOrNull(i)?.let { f -> o.put("fair_amount", f).put("diff_from_fair", sh.amount - f) }
            if (p != null) {
                o.put("km", round1(s.riddenKm.getOrNull(i) ?: 0.0))
                o.put("from", p.boardPlace?.label ?: "").put("to", p.alightPlace?.label ?: "")
            }
            shares.put(o)
        }
        val rule = when (s.rule) { "distance" -> "distance"; "equal" -> "equal"; else -> "shapley" }
        return JSONObject().put("rule", rule).put("method", a.method)
            .put("driver_included", s.includeDriver).put("include_driver", s.includeDriver)
            .put("total", a.total).put("toll", a.toll)
            .put("driver_absorbed", a.driverAbsorbed)
            .put("provisional", a.provisional)
            .put("fuel", JSONObject().put("distance_km", round1(s.settlementKm)).put("efficiency_km_l", round1(s.settlementEfficiency))
                .put("efficiency_source", s.efficiencySource).put("amount", a.fuelAmount))
            .put("shares", shares)
            .put("distance_source", s.distanceSource)
            .put("coalitions", JSONArray().apply {
                for ((names, km) in s.coalitions) put(JSONObject().put("members", JSONArray(names)).put("km", km))
            })
    }

    /** The one upload of the settlement. Only a missing network is worth trying again; a refusal is final. */
    suspend fun postSettlement(): PostResult {
        val s = _state.value
        val a = s.allocation ?: return PostResult.Refused("no_settlement")
        val result = send(s, a)
        when (result) {
            is PostResult.Refused -> log("정산 올리기 거절: ${result.code}")
            PostResult.Offline -> log("정산 올리기: 네트워크 없음")
            PostResult.Ok -> log(if (a.provisional) "임시 정산 올림: 길찾기가 되면 다시 올림" else "정산 올림")
        }
        _state.update { it.copy(settlementPosted = result == PostResult.Ok, phase = if (result == PostResult.Ok) Phase.SETTLED else it.phase) }
        if (result == PostResult.Ok && a.provisional) repostWhenRouted(_state.value)
        return result
    }

    private suspend fun send(s: TripState, a: Allocation): PostResult {
        val r = rpc ?: return PostResult.Refused("no_server")
        return serverLine.withLock {
            try {
                r.call("post_settlement", args("p_code" to s.code, "p_admin_secret" to s.adminSecret, "p_payload" to payload(s, a)))
                PostResult.Ok
            } catch (e: CancellationException) {
                throw e
            } catch (e: RpcException) {
                if (e.code in TRANSIENT_HTTP) PostResult.Offline else PostResult.Refused(e.code)
            } catch (e: IOException) {
                PostResult.Offline
            } catch (e: Exception) {
                PostResult.Refused(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private var repostJob: Job? = null

    /**
     * A provisional settlement was posted (no routing after arrival). Every minute for half an hour, the same trip
     * is routed again; once that works the routed settlement replaces the provisional one on the server.
     * Works from a copy of the trip, so it carries on after the screen went back to the start.
     */
    private fun repostWhenRouted(snapshot: TripState) {
        repostJob?.cancel()
        repostJob = scope.launch {
            repeat(REPOST_TRIES) {
                delay(REPOST_EVERY_MS)
                val c = settle(snapshot, forceRoute = true) ?: return@launch
                if (c.allocation.provisional) return@repeat
                when (val r = send(c.applyTo(snapshot), c.allocation)) {
                    PostResult.Ok -> {
                        log("길찾기 결과로 정산을 다시 올림 (임시 아님)")
                        if (_state.value.code == snapshot.code) _state.update { c.applyTo(it) }
                        return@launch
                    }
                    is PostResult.Refused -> {
                        log("길찾기 결과 정산을 서버가 받지 않음: ${r.code}")
                        return@launch
                    }
                    PostResult.Offline -> Unit
                }
            }
        }
    }

    /** What the driver sees when the server refuses a settlement call. */
    fun refusalMessage(code: String): String = when (code) {
        "settle_started" -> "동승자가 이미 정산을 시작해서 금액을 바꿀 수 없어요"
        "batch_locked" -> "카풀 폴더 정산을 이미 요청해서 이 운행은 바꿀 수 없어요"
        "trip_not_found" -> "서버에서 이 운행을 찾지 못했어요"
        "not_allowed" -> "이 차로는 바꿀 수 없는 운행이에요"
        else -> "서버가 정산을 받지 않았어요 ($code)"
    }

    private fun round1(x: Double) = Math.round(x * 10) / 10.0

    private const val ARRIVE_KM = 0.12
    /** A boarding or drop-off counts as "at that stop" within this distance of the car. */
    private const val NEAR_KM = 0.3
    private const val ROAD_FACTOR = 1.3
    private const val STOP_KMH = 3f
    private const val STOP_HOLD_MS = 2_000L
    /** A same-side door opening this long before the seat emptied still counts. */
    private const val DOOR_WINDOW_MS = 60_000L
    /** The seat must stay empty this long after the door closed. */
    private const val EMPTY_HOLD_MS = 5_000L
    /** With a door sensor but no door opening, decide (ask) after this long. */
    private const val DOOR_WAIT_MS = 60_000L
    /** Odometer jumps larger than this (a missed reading) are left out of the fuel sum. */
    private const val MAX_ODOMETER_STEP_KM = 5.0
    private val TRANSIENT_HTTP = setOf("http_502", "http_503", "http_504")
    private const val REPOST_TRIES = 30
    private const val REPOST_EVERY_MS = 60_000L

    /** Road km and minutes of each leg through the points in order, from the public OSRM demo router. */
    private fun routeLegs(points: List<Place>): List<Pair<Double, Double>> {
        val coords = points.joinToString(";") { "%.6f,%.6f".format(Locale.US, it.lng, it.lat) }
        val conn = URL("https://router.project-osrm.org/route/v1/driving/$coords?overview=false").openConnection() as HttpURLConnection
        conn.connectTimeout = 6000
        conn.readTimeout = 10000
        conn.setRequestProperty("User-Agent", "FairPool-car-demo")
        try {
            val legs = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                .getJSONArray("routes").getJSONObject(0).getJSONArray("legs")
            return (0 until legs.length()).map { i ->
                legs.getJSONObject(i).let { it.getDouble("distance") / 1000.0 to it.getDouble("duration") / 60.0 }
            }
        } finally {
            conn.disconnect()
        }
    }

    private val routeCache = mutableMapOf<String, Double>()

    /** Road distance through the points in order (km), from the public OSRM demo router. */
    private fun routeKm(points: List<Place>): Double {
        val pts = points.fold(mutableListOf<Place>()) { acc, p -> if (acc.isEmpty() || acc.last().distanceKm(p) > 0.05) acc += p; acc }
        if (pts.size < 2) return 0.0
        val coords = pts.joinToString(";") { "%.6f,%.6f".format(Locale.US, it.lng, it.lat) }
        routeCache[coords]?.let { return it }
        val conn = URL("https://router.project-osrm.org/route/v1/driving/$coords?overview=false").openConnection() as HttpURLConnection
        conn.connectTimeout = 6000
        conn.readTimeout = 10000
        conn.setRequestProperty("User-Agent", "FairPool-car-demo")
        try {
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val km = JSONObject(body).getJSONArray("routes").getJSONObject(0).getDouble("distance") / 1000.0
            routeCache[coords] = km
            Thread.sleep(250) // be polite to the shared demo server
            return km
        } finally {
            conn.disconnect()
        }
    }
}

fun Place.distanceKm(o: Place): Double {
    val r = 6371.0
    val dLat = Math.toRadians(o.lat - lat)
    val dLng = Math.toRadians(o.lng - lng)
    val a = Math.sin(dLat / 2).let { it * it } +
        Math.cos(Math.toRadians(lat)) * Math.cos(Math.toRadians(o.lat)) * Math.sin(dLng / 2).let { it * it }
    return 2 * r * Math.asin(Math.sqrt(a))
}
