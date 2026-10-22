package kr.fairpool.car.trip

import android.util.Log
import kr.fairpool.car.data.Place
import kr.fairpool.car.data.Rpc
import kr.fairpool.car.data.args
import kr.fairpool.car.settlement.Allocation
import kr.fairpool.car.settlement.Rider
import kr.fairpool.car.settlement.Settlement
import kr.fairpool.car.settlement.Shapley
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
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
    val alightPlace: Place? = null
)

/** A stop in the order the car visits them. */
data class Stop(val kind: String, val passengerId: String?, val name: String, val place: Place, val done: Boolean = false)

/** A recorded boarding or alighting, in order, with the odometer reading. */
data class TripEvent(val passengerId: String, val boarding: Boolean, val km: Double, val place: Place, val time: Long)

/** The confirmation card on L5 (정차 중 하차 확인). */
data class PendingCard(val passengerId: String, val name: String, val boarding: Boolean, val at: Long)

data class TripState(
    val phase: Phase = Phase.IDLE,
    val code: String = "",
    val adminSecret: String = "",
    val title: String = "",
    val includeDriver: Boolean = true,
    val origin: Place? = null,
    val destination: Place? = null,
    val seats: Int = 3,
    val passengers: List<Passenger> = emptyList(),
    val plan: List<Stop> = emptyList(),
    val events: List<TripEvent> = emptyList(),
    val gear: String = "?",
    val speedKmh: Float = 0f,
    val odometerKm: Double? = null,
    val startOdometerKm: Double? = null,
    val efficiencyKmL: Double? = null,
    val toll: Long = 0,
    val seatOccupied: Map<SeatSlot, Boolean> = emptyMap(),
    val doorOpen: Map<SeatSlot, Boolean> = emptyMap(),
    val card: PendingCard? = null,
    val allocation: Allocation? = null,
    val settlementMethod: String = "",
    val settlementPosted: Boolean = false,
    val log: List<String> = emptyList(),
    val error: String? = null
) {
    val drivenKm: Double
        get() = if (odometerKm != null && startOdometerKm != null) (odometerKm - startOdometerKm).coerceAtLeast(0.0) else 0.0
    val aboard: List<Passenger> get() = passengers.filter { it.boarded && !it.alighted }
    val nextStop: Stop? get() = plan.firstOrNull { !it.done }
}

/**
 * The trip as the car sees it. Sensors (TripService) push readings in, the UI reads [state] and calls actions.
 * Boarding: a free seat becomes occupied (seat sensor or seat belt) while stopped → the next passenger due to board.
 * Alighting: a seat with a passenger becomes free while stopped → that passenger got off.
 */
object TripEngine {
    private const val TAG = "FairPoolTrip"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(TripState())
    val state: StateFlow<TripState> = _state

    var rpc: Rpc? = null
    var navi: NaviBridge? = null
    var fuelPrice: Int = 1700

    private val time = SimpleDateFormat("HH:mm:ss", Locale.KOREA)

    fun log(msg: String) {
        Log.i(TAG, msg)
        _state.update { it.copy(log = (it.log + "${time.format(Date())} $msg").takeLast(40)) }
    }

    fun reset() {
        _state.value = TripState()
    }

    // ---- trip lifecycle -------------------------------------------------------------------------------

    fun startInvite(code: String, adminSecret: String, title: String, origin: Place, destination: Place, seats: Int, includeDriver: Boolean) {
        _state.value = TripState(
            phase = Phase.INVITE, code = code, adminSecret = adminSecret, title = title,
            origin = origin, destination = destination, seats = seats, includeDriver = includeDriver,
            seatOccupied = _state.value.seatOccupied, doorOpen = _state.value.doorOpen,
            gear = _state.value.gear, odometerKm = _state.value.odometerKm, efficiencyKmL = _state.value.efficiencyKmL
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
                    boardKm = old?.boardKm, alightKm = old?.alightKm, boardPlace = old?.boardPlace, alightPlace = old?.alightPlace
                )
            }
            s.copy(passengers = merged, plan = if (s.phase == Phase.DRIVING) s.plan else planStops(s.origin, s.destination, merged))
        }
    }

    /**
     * Visiting order: passengers boarding at the start get in first, then pickups and drop-offs are
     * ordered by a greedy nearest-next walk that never drops someone before picking them up.
     */
    fun planStops(origin: Place?, destination: Place?, passengers: List<Passenger>): List<Stop> {
        val stops = mutableListOf<Stop>()
        var here = origin ?: return emptyList()
        val pending = passengers.flatMap { p ->
            listOfNotNull(
                p.pickup?.takeIf { origin.distanceKm(it) > 0.3 }?.let { Stop("pickup", p.id, p.name, it) },
                p.dropoff?.let { Stop("dropoff", p.id, p.name, it) }
            )
        }.toMutableList()
        val picked = passengers.filter { it.pickup == null || origin.distanceKm(it.pickup) <= 0.3 }.map { it.id }.toMutableSet()
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
        val atStart = s.passengers.filter { it.pickup == null || (s.origin?.distanceKm(it.pickup) ?: 99.0) <= 0.3 }
        val freeSeats = SeatSlot.entries.filter { slot -> s.seatOccupied[slot] == true && s.passengers.none { it.seat == slot } }
        atStart.filter { it.seat == null }.zip(freeSeats).forEach { (p, slot) -> assignSeat(p.id, slot) }
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
        val km = s0.odometerKm ?: 0.0
        val now = System.currentTimeMillis()
        val starters = s0.passengers.filter { it.pickup == null || origin.distanceKm(it.pickup) <= 0.3 }
        _state.update { s ->
            s.copy(
                phase = Phase.DRIVING, startOdometerKm = km,
                passengers = s.passengers.map { p ->
                    if (starters.any { it.id == p.id }) p.copy(boarded = true, boardKm = km, boardPlace = origin) else p
                },
                events = starters.map { TripEvent(it.id, true, km, origin, now) }
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
        log("출발: 동승 ${starters.size}명, 주행거리 기준 ${"%.1f".format(km)}km")
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

    // ---- sensor input ---------------------------------------------------------------------------------

    fun onGear(gear: String) {
        _state.update { it.copy(gear = gear) }
    }

    fun onSpeed(kmh: Float) = _state.update { it.copy(speedKmh = kmh) }

    fun onOdometer(km: Double) = _state.update { it.copy(odometerKm = km) }

    fun onEfficiency(kmL: Double) {
        if (kmL > 1) _state.update { it.copy(efficiencyKmL = kmL) }
    }

    fun onToll(won: Long) {
        if (won > 0) _state.update { it.copy(toll = won) }
    }

    fun onDoor(slot: SeatSlot, open: Boolean) {
        _state.update { it.copy(doorOpen = it.doorOpen + (slot to open)) }
        log("${slot.label} 문 ${if (open) "열림" else "닫힘"}")
    }

    fun onSeat(slot: SeatSlot, occupied: Boolean) {
        val before = _state.value.seatOccupied[slot] == true
        _state.update { it.copy(seatOccupied = it.seatOccupied + (slot to occupied)) }
        if (before == occupied) return
        log("${slot.label} ${if (occupied) "착석" else "비움"}")
        val s = _state.value
        if (s.phase != Phase.DRIVING || s.speedKmh > 3f) return
        if (occupied) {
            val due = s.plan.firstOrNull { !it.done && it.kind == "pickup" }
                ?.let { st -> s.passengers.firstOrNull { it.id == st.passengerId } }
                ?: s.passengers.firstOrNull { !it.boarded }
            if (due != null && s.passengers.none { it.seat == slot && it.boarded && !it.alighted }) board(due.id, slot)
        } else {
            s.passengers.firstOrNull { it.seat == slot && it.boarded && !it.alighted }?.let { alight(it.id) }
        }
    }

    // ---- boarding and alighting -----------------------------------------------------------------------

    fun board(passengerId: String, slot: SeatSlot?, unchecked: Boolean = false) {
        val s = _state.value
        val p = s.passengers.firstOrNull { it.id == passengerId } ?: return
        val km = s.odometerKm ?: 0.0
        val place = p.pickup ?: s.origin ?: return
        _state.update { st ->
            st.copy(
                passengers = st.passengers.map { if (it.id == passengerId) it.copy(boarded = true, alighted = false, seat = slot ?: it.seat, boardKm = km, boardPlace = place) else it },
                events = st.events + TripEvent(passengerId, true, km, place, System.currentTimeMillis()),
                plan = st.plan.map { if (it.kind == "pickup" && it.passengerId == passengerId) it.copy(done = true) else it },
                card = PendingCard(passengerId, p.name, true, System.currentTimeMillis())
            )
        }
        log("${p.name} 탑승 (${slot?.label ?: "좌석 미지정"})")
        scope.launch {
            call("mark_passenger", args("p_code" to s.code, "p_admin_secret" to s.adminSecret, "p_passenger_id" to passengerId,
                "p_seat" to (slot ?: p.seat)?.code, "p_boarded" to true, "p_unchecked" to unchecked))
            pushProgress()
        }
        if (s.plan.firstOrNull { !it.done }?.passengerId == passengerId) sendRoute()
    }

    fun alight(passengerId: String, unchecked: Boolean = false) {
        val s = _state.value
        val p = s.passengers.firstOrNull { it.id == passengerId } ?: return
        val km = s.odometerKm ?: 0.0
        val place = p.dropoff ?: s.destination ?: return
        _state.update { st ->
            st.copy(
                passengers = st.passengers.map { if (it.id == passengerId) it.copy(alighted = true, alightKm = km, alightPlace = place) else it },
                events = st.events + TripEvent(passengerId, false, km, place, System.currentTimeMillis()),
                plan = st.plan.map { if (it.passengerId == passengerId) it.copy(done = true) else it },
                card = PendingCard(passengerId, p.name, false, System.currentTimeMillis())
            )
        }
        log("${p.name} 하차 기록")
        scope.launch {
            call("mark_passenger", args("p_code" to s.code, "p_admin_secret" to s.adminSecret, "p_passenger_id" to passengerId,
                "p_alighted" to true, "p_unchecked" to unchecked))
            pushProgress()
        }
        sendRoute()
    }

    /** L5 "잘못 인식": undoes the last automatic boarding or alighting of that passenger. */
    fun undoCard() {
        val s = _state.value
        val card = s.card ?: return
        _state.update { st ->
            st.copy(
                card = null,
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
        sendRoute()
    }

    fun dismissCard() = _state.update { it.copy(card = null) }

    fun arrive() {
        val s = _state.value
        s.passengers.filter { it.boarded && !it.alighted }.forEach { alight(it.id, unchecked = true) }
        _state.update { it.copy(phase = Phase.ARRIVED, card = null) }
        log("도착: 주행 ${"%.1f".format(_state.value.drivenKm)}km")
    }

    // ---- progress for the passengers' pages -----------------------------------------------------------

    private suspend fun pushProgress() {
        val s = _state.value
        if (s.code.isEmpty()) return
        val stops = JSONArray()
        s.origin?.let { stops.put(JSONObject().put("kind", "start").put("label", it.label).put("state", "done")) }
        val next = s.nextStop
        for (st in s.plan) {
            val o = JSONObject().put("kind", st.kind).put("label", st.place.label).put("area", st.place.area)
                .put("state", if (st.done) "done" else if (st == next) "now" else "next")
            st.passengerId?.let { o.put("passenger_id", it) }
            stops.put(o)
        }
        call("update_progress", args("p_code" to s.code, "p_admin_secret" to s.adminSecret,
            "p_progress" to JSONObject().put("stops", stops)))
    }

    private suspend fun call(fn: String, a: JSONObject): JSONObject? = try {
        rpc?.call(fn, a)
    } catch (e: Exception) {
        log("서버 $fn 실패: ${e.message}")
        null
    }

    // ---- settlement -----------------------------------------------------------------------------------

    /**
     * Total cost = driven km × (fuel price / efficiency) + toll. Each group's distance comes from the
     * public OSRM router (shortest by distance is not offered, so the passing rule smooths detours);
     * if routing fails the fixed-route Shapley value (segment N분의 1) is used without any network call.
     */
    suspend fun computeSettlement(): Allocation? = withContext(Dispatchers.Default) {
        val s = _state.value
        val origin = s.origin ?: return@withContext null
        val dest = s.destination ?: origin
        val riders = listOf(Rider(null, "운전자", true)) + s.passengers.filter { it.boarded }.map { Rider(it.id, it.name, false) }
        val people = s.passengers.filter { it.boarded }
        val n = riders.size

        // visiting sequence: start, every recorded boarding/alighting in order, end
        data class Pt(val owner: Int, val place: Place)
        val seq = mutableListOf(Pt(0, origin))
        for (e in s.events.sortedBy { it.time }) {
            val idx = people.indexOfFirst { it.id == e.passengerId }
            if (idx >= 0) seq += Pt(idx + 1, e.place)
        }
        seq += Pt(0, dest)

        val efficiency = s.efficiencyKmL ?: 12.0
        val fuelPerKm = fuelPrice / efficiency
        val tollPayers = buildSet {
            add(0)
            people.forEachIndexed { i, p -> if (p.boardPlace != null && p.boardPlace.distanceKm(origin) <= 0.3) add(i + 1) }
        }

        var method = "shapley"
        val full = (1 shl n) - 1
        val raw = DoubleArray(1 shl n)
        try {
            for (mask in 1..full) {
                val pts = seq.filter { mask and (1 shl it.owner) != 0 }.map { it.place }
                raw[mask] = routeKm(pts)
            }
        } catch (e: Exception) {
            method = "fixed_route"
            log("길찾기 실패, 구간별 N분의 1로 계산: ${e.message}")
        }

        val shareKm: DoubleArray
        val aloneKm: DoubleArray
        val actualKm: Double
        if (method == "shapley") {
            val cost = Shapley.applyPassingRule(n, raw)
            shareKm = Shapley.values(n, cost)
            aloneKm = DoubleArray(n) { cost[1 shl it] }
            actualKm = s.drivenKm.takeIf { it > 0.5 } ?: cost[full]
        } else {
            val segments = mutableListOf<Pair<Double, Int>>()
            var aboard = 1
            var lastKm = s.startOdometerKm ?: 0.0
            for (e in s.events.sortedBy { it.time }) {
                val idx = people.indexOfFirst { it.id == e.passengerId } + 1
                segments += (e.km - lastKm).coerceAtLeast(0.0) to aboard
                lastKm = e.km
                aboard = if (e.boarding) aboard or (1 shl idx) else aboard and (1 shl idx).inv()
            }
            segments += ((s.odometerKm ?: lastKm) - lastKm).coerceAtLeast(0.0) to aboard
            shareKm = Shapley.fixedRoute(n, segments)
            actualKm = s.drivenKm
            aloneKm = DoubleArray(n) { i -> segments.filter { it.second and (1 shl i) != 0 }.sumOf { it.first } }
        }
        val allocation = Settlement.allocate(riders, shareKm, aloneKm, actualKm, fuelPerKm, s.toll, tollPayers, s.includeDriver, method)
        _state.update { it.copy(allocation = allocation, settlementMethod = method) }
        allocation
    }

    fun payload(a: Allocation): JSONObject {
        val s = _state.value
        val shares = JSONArray()
        for (sh in a.shares) {
            val p = s.passengers.firstOrNull { it.id == sh.rider.id }
            val o = JSONObject().put("passenger_id", sh.rider.id ?: JSONObject.NULL).put("name", sh.rider.name)
                .put("role", if (sh.rider.isDriver) "driver" else "passenger").put("amount", sh.amount)
                .put("share_km", round1(sh.shareKm)).put("alone", sh.alone)
            if (p != null) {
                o.put("km", round1(((p.alightKm ?: 0.0) - (p.boardKm ?: 0.0)).coerceAtLeast(0.0)))
                o.put("from", p.boardPlace?.label ?: "").put("to", p.alightPlace?.label ?: "")
            }
            shares.put(o)
        }
        return JSONObject().put("rule", if (a.method == "shapley") "shapley" else "segment_split")
            .put("include_driver", s.includeDriver).put("total", a.total).put("toll", a.toll)
            .put("fuel", JSONObject().put("distance_km", round1(s.drivenKm)).put("efficiency_km_l", round1(s.efficiencyKmL ?: 12.0))
                .put("amount", a.fuelAmount))
            .put("shares", shares)
    }

    suspend fun postSettlement(): Boolean {
        val s = _state.value
        val a = s.allocation ?: return false
        val ok = call("post_settlement", args("p_code" to s.code, "p_admin_secret" to s.adminSecret, "p_payload" to payload(a))) != null
        _state.update { it.copy(settlementPosted = ok, phase = if (ok) Phase.SETTLED else it.phase) }
        return ok
    }

    private fun round1(x: Double) = Math.round(x * 10) / 10.0

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
