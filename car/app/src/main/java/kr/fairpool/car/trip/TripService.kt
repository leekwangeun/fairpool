package kr.fairpool.car.trip

import ai.pleos.playground.navi.constants.BookmarkType
import ai.pleos.playground.navi.constants.RouteOption
import ai.pleos.playground.navi.data.BookmarkInfo
import ai.pleos.playground.navi.data.RecentDestinationInfo
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.NavigationScreenRequest
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteOverviewShowInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.RouteWithWaypointsRequest
import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.DistanceDrivenListener
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import ai.pleos.playground.vehicle.listener.FuelEconomyListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.SeatBeltBuckledStateListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kr.fairpool.car.data.Place
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/** Thin wrapper so the engine can ask the stock navi for a route without knowing NaviHelper types. */
/** A place from the stock navi: its bookmarks (집, 회사, 즐겨찾기) and recent destinations. */
data class NaviPlace(val place: Place, val kind: String)

class NaviBridge(context: Context) {
    private val navi = NaviHelper(context)
    private val bookmarks = TripEngine.naviBookmarks
    private val recents = TripEngine.naviRecents
    // NaviHelper calls back on binder threads; everything here runs on the main thread
    private val main = Handler(Looper.getMainLooper())
    private var ready = false
    private var pending: (() -> Unit)? = null

    init {
        navi.addListener(object : NaviHelperEventListener {
            override fun onBookmarkInfo(list: List<BookmarkInfo>) {
                main.post { TripEngine.log("내비 즐겨찾기 ${list.size}곳") }
                // 집, 회사 slots the driver never set come back without a place
                bookmarks.value = list.filter { it.latitude != 0.0 || it.longitude != 0.0 }.map { b ->
                    val kind = when (b.category) { BookmarkType.HOME -> "집"; BookmarkType.OFFICE -> "회사"; else -> "즐겨찾기" }
                    NaviPlace(Place(b.name.ifBlank { kind }, b.latitude, b.longitude, b.address ?: "", b.address ?: ""), kind)
                }
            }

            override fun onRecentDestinationInfo(list: List<RecentDestinationInfo>) {
                main.post { TripEngine.log("내비 최근 목적지 ${list.size}곳") }
                recents.value = list.filter { it.latitude != 0.0 || it.longitude != 0.0 }.map { r ->
                    NaviPlace(Place(r.name, r.latitude, r.longitude, r.address ?: "", r.address ?: ""), "최근")
                }
            }

            override fun onBookMarkChanged() {
                main.post { runCatching { navi.getBookmarkInfo() } }
            }

            override fun onRouteStarted(info: RouteStartInfo) {
                main.post {
                    TripEngine.onToll(info.tollFare.toLong())
                    TripEngine.log("내비 안내 시작: ${info.distance / 1000.0}km, 통행료 ${info.tollFare}원")
                }
            }
        })
        navi.initialize(
            onConnected = {
                main.post {
                    ready = true
                    TripEngine.log("내비 연결됨")
                    pending?.invoke()
                    pending = null
                }
            },
            onFailed = { t -> main.post { TripEngine.log("내비 연결 실패: ${t.message}") } }
        )
    }

    fun route(waypoints: List<Place>, destination: Place) {
        val send = {
            try {
                val wps = waypoints.take(2).mapIndexed { i, p ->
                    RequestWaypointInfo(p.lat, p.lng, p.label, if (i == 0) WaypointIndex.FIRST else WaypointIndex.SECOND,
                        "", p.address.ifEmpty { p.label }, "0", "")
                }
                val dest = RouteInfo(destination.lat, destination.lng, null, null, destination.label, "",
                    destination.address.ifEmpty { destination.label }, "0", RouteOption.RECOMMENDED, "", false)
                navi.requestRouteWithWaypoints(RouteWithWaypointsRequest(dest, wps))
            } catch (e: Exception) {
                TripEngine.log("내비 경로 요청 실패: ${e.message}")
            }
        }
        main.post { if (ready) send() else pending = send }
    }

    /** L1-10: asks the navi for its bookmarks and recent destinations (answers arrive in [bookmarks], [recents]). */
    fun requestPlaces() {
        main.post {
            val ask = {
                runCatching { navi.getBookmarkInfo() }.onFailure { TripEngine.log("내비 즐겨찾기 요청 실패: ${it.message}") }
                runCatching { navi.getRecentDestinationInfo() }.onFailure { TripEngine.log("내비 최근 목적지 요청 실패: ${it.message}") }
                Unit
            }
            if (ready) ask() else pending = ask
        }
    }

    /** "내비에서 보기": brings the stock navi forward on its route overview. */
    fun showInNavi() {
        try {
            navi.requestNavigationScreen(NavigationScreenRequest.OverviewRoute(RouteOverviewShowInfo(10000)))
        } catch (e: Exception) {
            TripEngine.log("내비 화면 요청 실패: ${e.message}")
        }
    }

    fun release() = runCatching { navi.release() }
}

/**
 * Keeps the Vehicle SDK listeners alive while a trip is being prepared or driven, even when another
 * app (the navi) is in front. Verified on the Pleos emulator: callbacks keep arriving in gear D.
 * With no trip running (IDLE or SETTLED) it stops itself once the app is off screen.
 */
class TripService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val scope = MainScope()
    private var vehicle: Vehicle? = null
    private var fused: FusedLocation? = null
    private var car: Car? = null
    private var speedCallback: CarPropertyManager.CarPropertyEventCallback? = null
    private var registered = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "카풀 기록", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_myplaces)
            .setContentTitle("페어풀(FairPool)")
            .setContentText("카풀 기록 중")
            .setOngoing(true)
            .build()
        startForeground(1, n)

        vehicle = runCatching { Vehicle(applicationContext).also { it.initialize() } }
            .onFailure { TripEngine.log("차량 SDK 연결 실패: ${it.message}") }.getOrNull()
        if (TripEngine.navi == null) {
            TripEngine.navi = runCatching { NaviBridge(applicationContext) }
                .onFailure { TripEngine.log("내비 연결 실패: ${it.message}") }.getOrNull()
            TripEngine.navi?.requestPlaces()
        }
        fused = runCatching { FusedLocation(applicationContext).also { it.initialize() } }
            .onFailure { TripEngine.log("위치 SDK 연결 실패: ${it.message}") }.getOrNull()
        Voice.init(applicationContext)
        main.postDelayed({ register() }, 1500)
        stopWhenIdle()
    }

    /** Started again (the app came to the front, or CAR_SPEED was just granted): pick up the speed if it was missing. */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        main.post { if (registered && speedCallback == null) guarded("속도") { registerSpeed() } }
        // the trip lives in memory only, so a restart after the process died would have nothing to record
        return START_NOT_STICKY
    }

    /** No trip (IDLE or SETTLED) and the app off screen for 10 s: nothing to record, so the service ends. */
    private fun stopWhenIdle() {
        scope.launch {
            combine(TripEngine.state.map { it.phase }.distinctUntilChanged(), TripEngine.uiVisible) { phase, visible ->
                (phase == Phase.IDLE || phase == Phase.SETTLED) && !visible
            }.distinctUntilChanged().collectLatest { idle ->
                if (!idle) return@collectLatest
                delay(10_000)
                TripEngine.log("운행이 없어 기록 서비스를 멈춤")
                stopSelf()
            }
        }
    }

    private val seatValues = mapOf(
        SeatSlot.FRONT_RIGHT to SeatValue.ROW_1_RIGHT,
        SeatSlot.REAR_LEFT to SeatValue.ROW_2_LEFT,
        SeatSlot.REAR_RIGHT to SeatValue.ROW_2_RIGHT
    )
    private val doorValues = mapOf(
        SeatSlot.FRONT_RIGHT to DoorValue.ROW_1_RIGHT,
        SeatSlot.REAR_LEFT to DoorValue.ROW_2_LEFT,
        SeatSlot.REAR_RIGHT to DoorValue.ROW_2_RIGHT
    )

    /** Raw SDK values per seat: occupancy is true only for VACANT, the belt true only when buckled. */
    private val occupancy = ConcurrentHashMap<SeatSlot, Boolean>()
    private val belt = ConcurrentHashMap<SeatSlot, Boolean>()
    /** Seats whose occupancy sensor or belt switch exists (capability check, or a reading only a real sensor can give). */
    private val occupancySupported: MutableSet<SeatSlot> = ConcurrentHashMap.newKeySet()
    private val beltSupported: MutableSet<SeatSlot> = ConcurrentHashMap.newKeySet()

    private fun slotOf(area: SeatArea): SeatSlot? = seatValues.entries.firstOrNull { area.values.contains(it.value) }?.key

    /**
     * Vehicle SDK 2.3.2 turns SEAT_OCCUPANCY into `value == 1`, and in VHAL 0 is UNKNOWN, 1 VACANT, 2 OCCUPIED.
     * So true means empty, and false means occupied only on a seat that really has the sensor (else it is UNKNOWN).
     * Measured on the Pleos emulator: Virtual Console "Occupied" (2) arrives as false, "Empty" (1) as true.
     */
    private fun occupiedBySensor(slot: SeatSlot): Boolean? =
        if (slot in occupancySupported) occupancy[slot]?.let { vacant -> !vacant } else null

    private fun buckled(slot: SeatSlot): Boolean? = if (slot in beltSupported) belt[slot] else null

    /**
     * Which reading decides a seat: the passenger seat trusts its occupancy sensor first, the rear seats their belt
     * first (rear occupancy sensors are rare). A seat with neither is not detected at all: confirmed by hand.
     */
    private fun seatChanged(slot: SeatSlot) {
        val sensor = occupiedBySensor(slot)
        val buckle = buckled(slot)
        val (value, by) = if (slot == SeatSlot.FRONT_RIGHT) {
            if (sensor != null) sensor to "좌석 센서" else buckle to "안전벨트"
        } else {
            if (buckle != null) buckle to "안전벨트" else sensor to "좌석 센서"
        }
        main.post { TripEngine.onSeat(slot, value, if (value == null) "" else by) }
    }

    /** Each sensor on its own: one the car does not offer must not take the others (or the app) down. */
    private fun guarded(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            fail(what, e)
        }
    }

    private fun register() {
        val v = vehicle
        if (v != null) {
            guarded("좌석") { registerSeats(v) }
            guarded("문") { registerDoors(v) }
            guarded("주행거리") { registerOdometer(v) }
            guarded("기어") { registerGear(v) }
            guarded("연비") { registerFuel(v) }
        }
        guarded("위치") { registerLocation() }
        guarded("속도") { registerSpeed() }
        registered = true
        TripEngine.log("차량 센서 연결: 좌석 3, 문 3, 주행거리, 기어, 연비, 속도")
    }

    private fun registerSeats(v: Vehicle) {
        val seat = v.getSeat()
        seat.checkSeatOccupancyCapabilities({ _: CapabilityConfig, areas: List<SeatArea> ->
            areas.mapNotNull { slotOf(it) }.forEach { occupancySupported += it }
            seatValues.keys.forEach { seatChanged(it) }
            main.post { logDetection() }
        }, { e: Exception -> fail("좌석 센서 확인", e) })
        seat.checkSeatBeltBuckledCapabilities({ _: CapabilityConfig, areas: List<SeatArea> ->
            areas.mapNotNull { slotOf(it) }.forEach { beltSupported += it }
            seatValues.keys.forEach { seatChanged(it) }
            main.post { logDetection() }
        }, { e: Exception -> fail("안전벨트 확인", e) })
        for ((slot, value) in seatValues) {
            val area = SeatArea(value)
            val onOccupancy = { v: Boolean? ->
                // only a working sensor can report VACANT
                if (v == true) occupancySupported += slot
                if (v == null) occupancy.remove(slot) else occupancy[slot] = v
                seatChanged(slot)
            }
            val onBelt = { b: Boolean? ->
                // only a working belt switch can report buckled
                if (b == true) beltSupported += slot
                if (b == null) belt.remove(slot) else belt[slot] = b
                seatChanged(slot)
            }
            seat.registerSeatOccupancy(area, object : SeatOccupancyListener {
                override fun onSeatOccupancyUpdated(value: Boolean?) = onOccupancy(value)
                override fun onFailed(e: Exception) = fail("좌석 ${slot.label}", e)
            })
            seat.registerSeatBeltBuckledState(area, object : SeatBeltBuckledStateListener {
                override fun onSeatBeltBuckledStateUpdated(buckled: Boolean?) = onBelt(buckled)
                override fun onFailed(e: Exception) = fail("벨트 ${slot.label}", e)
            })
            seat.getCurrentSeatOccupancy(area, { occ -> onOccupancy(occ) }, { })
            seat.getCurrentSeatBeltBuckledState(area, { b -> onBelt(b) }, { })
        }
    }

    private fun logDetection() {
        val text = seatValues.keys.joinToString(", ") { slot ->
            val how = when {
                slot == SeatSlot.FRONT_RIGHT && slot in occupancySupported -> "좌석 센서"
                slot in beltSupported -> "안전벨트"
                slot in occupancySupported -> "좌석 센서"
                else -> "수동 확인"
            }
            "${slot.label} $how"
        }
        TripEngine.log("좌석 감지: $text")
    }

    private fun registerDoors(v: Vehicle) {
        val door = v.getDoor()
        for ((slot, value) in doorValues) {
            door.registerDoorPosition(DoorArea(value), object : DoorPositionListener {
                override fun onPositionUpdated(pos: Int?) {
                    main.post { TripEngine.onDoor(slot, (pos ?: 0) > 0) }
                }
                override fun onFailed(e: Exception) = fail("문 ${slot.label}", e)
            })
        }
    }

    private fun registerOdometer(v: Vehicle) {
        v.getOdometer().registerDistanceDriven(object : DistanceDrivenListener {
            override fun onDistanceDrivenUpdated(km: Float?) {
                if (km != null) main.post { TripEngine.onOdometer(km.toDouble()) }
            }
            override fun onFailed(e: Exception) = fail("주행거리", e)
        })
        v.getOdometer().getCurrentDistanceDriven({ km -> if (km != null) main.post { TripEngine.onOdometer(km.toDouble()) } }, { })
    }

    private fun registerGear(v: Vehicle) {
        v.getGear().registerGearSelection(object : GearSelectionListener {
            override fun onSelected(gear: VehicleGear?) {
                main.post { TripEngine.onGear(gear?.name ?: "?") }
            }
            override fun onFailed(e: Exception) = fail("기어", e)
        })
        v.getGear().getCurrentGearSelectionState({ g -> main.post { TripEngine.onGear(g?.name ?: "?") } }, { })
    }

    /**
     * Vehicle speed from AAOS (PERF_VEHICLE_SPEED, m/s). The Vehicle SDK does not offer it.
     * Needs the CAR_SPEED runtime permission; without it, only the gear tells whether the car is stopped.
     */
    private fun registerSpeed() {
        if (checkSelfPermission(Car.PERMISSION_SPEED) != PackageManager.PERMISSION_GRANTED) {
            TripEngine.log("속도 권한 없음: 기어로만 정차를 판단")
            return
        }
        val c = car ?: Car.createCar(applicationContext).also { car = it }
        val properties = c.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
        val callback = object : CarPropertyManager.CarPropertyEventCallback {
            override fun onChangeEvent(value: CarPropertyValue<*>) {
                val mps = (value.value as? Float) ?: return
                main.post { TripEngine.onSpeed(abs(mps) * 3.6f) }
            }
            override fun onErrorEvent(propertyId: Int, zone: Int) {
                main.post { TripEngine.log("속도 수신 오류") }
            }
        }
        if (properties.registerCallback(callback, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_NORMAL)) {
            speedCallback = callback
        } else {
            TripEngine.log("속도: 이 차에서는 받을 수 없음")
        }
    }

    /** Pleos FusedLocation: where boardings and drop-offs really happened, if the navi is slow to say. */
    private fun registerLocation() {
        val f = fused ?: return
        if (!f.isSupported()) {
            TripEngine.log("위치 SDK: 이 차에서는 지원하지 않음")
            return
        }
        val listener = object : FusedLocationListener {
            override fun onChanged(data: LocationData?) {
                if (data != null) main.post { TripEngine.onLocation(data.latitude, data.longitude) }
            }
            override fun onError(e: Exception) = fail("위치", e)
        }
        f.registerFusedLocationCallback(listener)
        f.getLastKnownLocation(listener)
    }

    /**
     * Fuel economy, live: the instantaneous value feeds the running fuel sum (each odometer step at the economy of
     * that moment), the current value is what the car shows and the fallback.
     */
    private fun registerFuel(v: Vehicle) {
        val fuel = v.getFuel()
        guarded("순간 연비") {
            fuel.registerInstantaneousFuelEconomy(object : FuelEconomyListener {
                override fun onFuelEconomyUpdated(value: Float?) {
                    if (value != null) main.post { TripEngine.onInstantEfficiency(value.toDouble()) }
                }
                override fun onFailed(e: Exception) = fail("순간 연비", e)
            })
        }
        guarded("연비") {
            fuel.registerCurrentFuelEconomy(object : FuelEconomyListener {
                override fun onFuelEconomyUpdated(value: Float?) {
                    if (value != null) main.post { TripEngine.onEfficiency(value.toDouble()) }
                }
                override fun onFailed(e: Exception) = fail("연비", e)
            })
        }
        fuel.getCurrentFuelEconomy({ e -> if (e != null) main.post { TripEngine.onEfficiency(e.toDouble()) } }, { })
    }

    private fun fail(what: String, e: Exception) {
        main.post { TripEngine.log("$what 수신 실패: ${e.message}") }
    }

    override fun onDestroy() {
        scope.cancel()
        main.removeCallbacksAndMessages(null)
        speedCallback?.let { cb ->
            runCatching { (car?.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager)?.unregisterCallback(cb) }
        }
        speedCallback = null
        runCatching { car?.disconnect() }
        car = null
        runCatching { vehicle?.release() }
        vehicle = null
        runCatching { fused?.release() }
        fused = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "fairpool_trip"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, TripService::class.java))
        }
    }
}
