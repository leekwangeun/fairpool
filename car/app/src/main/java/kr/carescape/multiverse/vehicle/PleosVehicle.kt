package kr.carescape.multiverse.vehicle

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.MirrorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.MirrorValue
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.LightState
import ai.pleos.playground.vehicle.constant.control.LightSwitch
import ai.pleos.playground.vehicle.constant.control.MoveControl
import ai.pleos.playground.vehicle.constant.control.SmartGlassZoneCommand
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.constant.control.WiperState
import ai.pleos.playground.vehicle.constant.unit.TemperatureUnit
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.DoorLockStateListener
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import ai.pleos.playground.vehicle.listener.EvBatteryStateOfChargeListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.GloveBoxLockStateListener
import ai.pleos.playground.vehicle.listener.HvacAirCleanerStateListener
import ai.pleos.playground.vehicle.listener.HvacDefrosterStateListener
import ai.pleos.playground.vehicle.listener.HvacFanSpeedListener
import ai.pleos.playground.vehicle.listener.HvacMaxDefrostStateListener
import ai.pleos.playground.vehicle.listener.HvacRecirculationStateListener
import ai.pleos.playground.vehicle.listener.HvacSideMirrorHeatStateListener
import ai.pleos.playground.vehicle.listener.HvacTemperatureListener
import ai.pleos.playground.vehicle.listener.LightStateListener
import ai.pleos.playground.vehicle.listener.SeatBeltBuckledStateListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import ai.pleos.playground.vehicle.listener.WindowLockStateListener
import ai.pleos.playground.vehicle.listener.WindowPositionListener
import ai.pleos.playground.vehicle.listener.WiperStateListener
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * The real car through the Pleos Vehicle SDK 2.3.2 and FusedLocation 1.1.3.
 *
 * Reads are registered once in [start] and pushed into [VehicleBus]. Writes go through [Controls].
 * Every SDK call is wrapped so a car (or the emulator) that lacks a feature only logs it.
 */
class PleosVehicle(private val context: Context) : Controls {
    private val main = Handler(Looper.getMainLooper())
    private var vehicle: Vehicle? = null
    private var fused: FusedLocation? = null
    private var car: Car? = null

    private val seatValues = mapOf(
        Seat.DRIVER to SeatValue.ROW_1_LEFT,
        Seat.FRONT_RIGHT to SeatValue.ROW_1_RIGHT,
        Seat.REAR_LEFT to SeatValue.ROW_2_LEFT,
        Seat.REAR_RIGHT to SeatValue.ROW_2_RIGHT
    )
    private val doorValues = mapOf(
        Seat.DRIVER to DoorValue.ROW_1_LEFT,
        Seat.FRONT_RIGHT to DoorValue.ROW_1_RIGHT,
        Seat.REAR_LEFT to DoorValue.ROW_2_LEFT,
        Seat.REAR_RIGHT to DoorValue.ROW_2_RIGHT
    )
    private val windowValues = mapOf(
        Seat.DRIVER to WindowValue.ROW_1_LEFT,
        Seat.FRONT_RIGHT to WindowValue.ROW_1_RIGHT,
        Seat.REAR_LEFT to WindowValue.ROW_2_LEFT,
        Seat.REAR_RIGHT to WindowValue.ROW_2_RIGHT
    )

    private fun seat(s: Seat) = SeatArea(seatValues.getValue(s))
    private fun door(s: Seat) = DoorArea(doorValues.getValue(s))
    private fun window(s: Seat) = WindowArea(windowValues.getValue(s))
    private val allSeats get() = SeatArea(*seatValues.values.toTypedArray())
    private val allDoors get() = DoorArea(*doorValues.values.toTypedArray())
    private val allWindows get() = WindowArea(*windowValues.values.toTypedArray())
    private val driverZone get() = SeatArea(SeatValue.ROW_1_LEFT)
    private val frontWindshield get() = WindowArea(WindowValue.FRONT_WINDSHIELD)

    fun start() {
        vehicle = runCatching { Vehicle(context.applicationContext).also { it.initialize() } }
            .onFailure { VehicleBus.note("차량 SDK 연결 실패: ${it.message}") }.getOrNull()
        fused = runCatching { FusedLocation(context.applicationContext).also { it.initialize() } }
            .onFailure { VehicleBus.note("위치 SDK 연결 실패: ${it.message}") }.getOrNull()
        // the SDK needs a moment after initialize() before listeners are accepted
        main.postDelayed({ register() }, 1500)
    }

    fun stop() {
        main.removeCallbacksAndMessages(null)
        runCatching { vehicle?.release() }
        runCatching { fused?.release() }
        runCatching { car?.disconnect() }
        vehicle = null
        fused = null
        car = null
    }

    // ---- reads -------------------------------------------------------------------------------------------

    private fun guarded(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            VehicleBus.note("$what 수신 실패: ${e.message}")
        }
    }

    private fun post(change: (Signals) -> Signals) = main.post { VehicleBus.update(change) }

    private fun register() {
        val v = vehicle ?: return
        guarded("기어") { registerGear(v) }
        guarded("속도") { registerSpeed() }
        guarded("좌석") { registerSeats(v) }
        guarded("문") { registerDoors(v) }
        guarded("창문") { registerWindows(v) }
        guarded("글로브박스") { registerGloveBox(v) }
        guarded("공조") { registerHvac(v) }
        guarded("사이드미러 열선") { registerMirror(v) }
        guarded("독서등") { registerReadingLights(v) }
        guarded("핸들") { registerSteering(v) }
        guarded("브레이크") { registerBrake(v) }
        guarded("방향지시등") { registerTurn(v) }
        guarded("배터리") { registerBattery(v) }
        guarded("와이퍼") { registerWiper(v) }
        guarded("위치") { registerLocation() }
        VehicleBus.note("차량 신호 연결: 기어, 좌석 4, 문 4, 창문, 글로브박스, 공조, 독서등, 핸들, 브레이크, 깜빡이, 배터리, 와이퍼, 위치")
    }

    private fun registerGear(v: Vehicle) {
        fun name(g: VehicleGear?) = when (g) {
            VehicleGear.GEAR_PARK -> "P"; VehicleGear.GEAR_DRIVE -> "D"; VehicleGear.GEAR_REVERSE -> "R"
            VehicleGear.GEAR_NEUTRAL -> "N"; null, VehicleGear.GEAR_UNKNOWN -> "?"; else -> "D"
        }
        v.getGear().registerGearSelection(object : GearSelectionListener {
            override fun onSelected(gear: VehicleGear?) = post { it.copy(gear = name(gear)) }.let { }
            override fun onFailed(e: Exception) = VehicleBus.note("기어 수신 실패: ${e.message}")
        })
        v.getGear().getCurrentGearSelectionState({ g -> post { it.copy(gear = name(g)) } }, { })
    }

    /** Speed comes from AAOS itself; the Pleos SDK has no speed signal. */
    private fun registerSpeed() {
        val c = Car.createCar(context) ?: return
        car = c
        val pm = c.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager ?: return
        pm.registerCallback(object : CarPropertyManager.CarPropertyEventCallback {
            override fun onChangeEvent(value: CarPropertyValue<*>) {
                val mps = (value.value as? Float) ?: return
                post { it.copy(speedKmh = mps * 3.6f) }
            }
            override fun onErrorEvent(propId: Int, zone: Int) {}
        }, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_NORMAL)
    }

    /**
     * Vehicle SDK 2.3.2 turns SEAT_OCCUPANCY into `value == 1`, and in VHAL 1 is VACANT (2 is OCCUPIED).
     * Measured on the Pleos emulator: Virtual Console "Occupied" arrives as false, "Empty" as true.
     */
    private fun isOccupied(sdkValue: Boolean?) = sdkValue == false

    private fun registerSeats(v: Vehicle) {
        val seatApi = v.getSeat()
        for ((slot, _) in seatValues) {
            val area = seat(slot)
            seatApi.registerSeatOccupancy(area, object : SeatOccupancyListener {
                override fun onSeatOccupancyUpdated(value: Boolean?) = post { it.copy(occupied = it.occupied + (slot to isOccupied(value))) }.let { }
                override fun onFailed(e: Exception) = VehicleBus.note("좌석 ${slot.label} 수신 실패: ${e.message}")
            })
            seatApi.registerSeatBeltBuckledState(area, object : SeatBeltBuckledStateListener {
                override fun onSeatBeltBuckledStateUpdated(buckled: Boolean?) = post { it.copy(belted = it.belted + (slot to (buckled == true))) }.let { }
                override fun onFailed(e: Exception) = VehicleBus.note("벨트 ${slot.label} 수신 실패: ${e.message}")
            })
            seatApi.getCurrentSeatOccupancy(area, { occ -> post { it.copy(occupied = it.occupied + (slot to isOccupied(occ))) } }, { })
            seatApi.getCurrentSeatBeltBuckledState(area, { b -> post { it.copy(belted = it.belted + (slot to (b == true))) } }, { })
        }
    }

    private fun registerDoors(v: Vehicle) {
        val doorApi = v.getDoor()
        for ((slot, _) in doorValues) {
            val area = door(slot)
            doorApi.registerDoorPosition(area, object : DoorPositionListener {
                override fun onPositionUpdated(pos: Int?) = post { it.copy(doorOpen = it.doorOpen + (slot to ((pos ?: 0) > 0))) }.let { }
                override fun onFailed(e: Exception) = VehicleBus.note("문 ${slot.label} 수신 실패: ${e.message}")
            })
            doorApi.registerDoorLockState(area, object : DoorLockStateListener {
                override fun onDoorLockStateUpdated(locked: Boolean?) = post { it.copy(doorLocked = it.doorLocked + (slot to (locked == true))) }.let { }
                override fun onFailed(e: Exception) = VehicleBus.note("문 잠금 ${slot.label} 수신 실패: ${e.message}")
            })
            doorApi.getCurrentDoorPosition(area, { pos -> post { it.copy(doorOpen = it.doorOpen + (slot to ((pos ?: 0) > 0))) } }, { })
            doorApi.getDoorLockState(area, { l -> post { it.copy(doorLocked = it.doorLocked + (slot to (l == true))) } }, { })
        }
    }

    private fun registerWindows(v: Vehicle) {
        val w = v.getWindow()
        w.registerWindowLockState(allWindows, object : WindowLockStateListener {
            override fun onWindowLockStateUpdated(locked: Boolean?) = post { it.copy(windowLocked = locked == true, supported = it.supported + "windowlock") }.let { }
            override fun onFailed(e: Exception) = VehicleBus.note("창문 잠금 수신 실패: ${e.message}")
        })
        w.getCurrentWindowLockState(allWindows, { l -> post { it.copy(windowLocked = l == true, supported = it.supported + "windowlock") } }, { })
        for ((slot, _) in windowValues) {
            val area = window(slot)
            w.registerWindowPosition(area, object : WindowPositionListener {
                override fun onWindowPositionUpdated(pos: Int?) = post { it.copy(windowOpen = it.windowOpen + (slot to ((pos ?: 0) > 0))) }.let { }
                override fun onFailed(e: Exception) = VehicleBus.note("창문 ${slot.label} 수신 실패: ${e.message}")
            })
            w.getCurrentWindowPosition(area, { pos -> post { it.copy(windowOpen = it.windowOpen + (slot to ((pos ?: 0) > 0))) } }, { })
        }
        w.getSmartGlassConfig(window(Seat.REAR_RIGHT), { cfg ->
            VehicleBus.note("스마트 글래스: 존 ${cfg?.zoneCount}, 단계 ${cfg?.levelCount}")
            main.post { VehicleBus.supported("smartglass") }
        }, { })
    }

    private fun registerGloveBox(v: Vehicle) {
        val d = v.getDoor()
        d.registerGloveBoxLockState(object : GloveBoxLockStateListener {
            override fun onGloveBoxLockStateUpdated(locked: Boolean?) = post { it.copy(gloveBoxLocked = locked == true, supported = it.supported + "glovebox") }.let { }
            override fun onFailed(e: Exception) = VehicleBus.note("글로브박스 수신 실패: ${e.message}")
        })
        d.getCurrentGloveBoxLockState({ l -> post { it.copy(gloveBoxLocked = l == true, supported = it.supported + "glovebox") } }, { })
    }

    private fun registerHvac(v: Vehicle) {
        val h = v.getHVAC()
        h.registerHvacDefrosterState(frontWindshield, object : HvacDefrosterStateListener {
            override fun onHvacDefrosterStateUpdated(on: Boolean?) = post { it.copy(defroster = on == true) }.let { }
            override fun onFailed(e: Exception) = VehicleBus.note("성애 제거 수신 실패: ${e.message}")
        })
        h.getHvacDefrosterState(frontWindshield, { on -> post { it.copy(defroster = on == true) } }, { })
        h.registerHvacMaxDefrostState(driverZone, object : HvacMaxDefrostStateListener {
            override fun onHvacMaxDefrostStateUpdated(on: Boolean?) = post { it.copy(maxDefrost = on == true) }.let { }
            override fun onFailed(e: Exception) = VehicleBus.note("최대 성애 제거 수신 실패: ${e.message}")
        })
        h.registerHvacRecirculationState(driverZone, object : HvacRecirculationStateListener {
            override fun onHvacAutoRecirculationStateUpdated(on: Boolean?) = post { it.copy(recirculation = on == true) }.let { }
            override fun onFailed(e: Exception) = VehicleBus.note("내기 순환 수신 실패: ${e.message}")
        })
        h.getHvacRecirculationState(driverZone, { on -> post { it.copy(recirculation = on == true) } }, { })
        h.registerHvacAirCleanerState(driverZone, object : HvacAirCleanerStateListener {
            override fun onHvacAirCleanerStateUpdated(on: Boolean?) = post { it.copy(airCleaner = on == true) }.let { }
            override fun onFailed(e: Exception) = VehicleBus.note("공기청정 수신 실패: ${e.message}")
        })
        h.getHvacAirCleanerState(driverZone, { on -> post { it.copy(airCleaner = on == true) } }, { })
        h.registerHvacTemperature(driverZone, object : HvacTemperatureListener {
            override fun onHvacTemperatureUpdated(t: Float?) = post { it.copy(hvacTempC = t) }.let { }
            override fun onFailed(e: Exception) = VehicleBus.note("공조 온도 수신 실패: ${e.message}")
        })
        h.getHvacTemperature(driverZone, TemperatureUnit.CELSIUS, { t -> post { it.copy(hvacTempC = t) } }, { })
        h.registerHvacFanSpeed(driverZone, object : HvacFanSpeedListener {
            override fun onHvacFanSpeedUpdated(s: Int?) = post { it.copy(fanSpeed = s) }.let { }
            override fun onFailed(e: Exception) = VehicleBus.note("풍량 수신 실패: ${e.message}")
        })
        h.getHvacFanSpeed(driverZone, { s -> post { it.copy(fanSpeed = s) } }, { })
    }

    private fun registerMirror(v: Vehicle) {
        v.getSideMirror().registerHvacSideMirrorHeatState(MirrorArea(MirrorValue.DRIVER_LEFT, MirrorValue.DRIVER_RIGHT), object : HvacSideMirrorHeatStateListener {
            override fun onHvacSideMirrorHeatStateUpdated(level: Int?) = post { it.copy(mirrorHeat = (level ?: 0) > 0) }.let { }
            override fun onFailed(e: Exception) = VehicleBus.note("사이드미러 열선 수신 실패: ${e.message}")
        })
    }

    private fun registerReadingLights(v: Vehicle) {
        val l = v.getLight()
        for ((slot, _) in seatValues) {
            l.registerReadingLightState(seat(slot), object : LightStateListener {
                override fun onLightStateUpdated(state: LightState?) = post { it.copy(readingLight = it.readingLight + (slot to (state == LightState.ON))) }.let { }
                override fun onFailed(e: Exception) = VehicleBus.note("독서등 ${slot.label} 수신 실패: ${e.message}")
            })
            l.getCurrentReadingLightState(seat(slot), { s -> post { it.copy(readingLight = it.readingLight + (slot to (s == LightState.ON))) } }, { })
        }
    }

    private fun registerSteering(v: Vehicle) {
        v.getSteeringWheel().registerSteeringWheelAngle(object : SteeringWheelAngleListener {
            override fun onSteeringWheelAngleUpdated(angle: Float?) = post { it.copy(steeringAngle = angle ?: 0f) }.let { }
            override fun onFailed(e: Exception) = VehicleBus.note("핸들 각도 수신 실패: ${e.message}")
        })
        v.getSteeringWheel().getCurrentSteeringWheelAngle({ a -> post { it.copy(steeringAngle = a ?: 0f) } }, { })
    }

    private fun registerBrake(v: Vehicle) {
        v.getBrake().registerBrakePedalStatus(object : BrakePedalStatusListener {
            override fun onBrakePedalStatusUpdated(pressed: Boolean?) = post { it.copy(brake = pressed == true) }.let { }
            override fun onFailed(e: Exception) = VehicleBus.note("브레이크 수신 실패: ${e.message}")
        })
    }

    private fun registerTurn(v: Vehicle) {
        fun turn(t: TurnSignalValue?) = when (t) { TurnSignalValue.LEFT -> Turn.LEFT; TurnSignalValue.RIGHT -> Turn.RIGHT; else -> Turn.OFF }
        v.getTurnSignal().registerTurnSignalState(object : TurnSignalStateListener {
            override fun onTurnSignalStateUpdated(t: TurnSignalValue?) = post { it.copy(turn = turn(t)) }.let { }
            override fun onFailed(e: Exception) = VehicleBus.note("방향지시등 수신 실패: ${e.message}")
        })
    }

    private fun registerBattery(v: Vehicle) {
        v.getEvBattery().registerEvBatteryStateOfCharge(object : EvBatteryStateOfChargeListener {
            override fun onEvBatteryStateOfChargeUpdated(soc: Int?) = post { it.copy(evSoc = soc) }.let { }
            override fun onFailed(e: Exception) = VehicleBus.note("배터리 수신 실패: ${e.message}")
        })
        v.getEvBattery().getEvBatteryStateOfCharge({ soc -> post { it.copy(evSoc = soc) } }, { })
    }

    private fun registerWiper(v: Vehicle) {
        v.getWiper().registerWiperState(frontWindshield, object : WiperStateListener {
            override fun onWiperStateUpdated(s: WiperState?) = post { it.copy(wiperOn = s == WiperState.ON) }.let { }
            override fun onFailed(e: Exception) = VehicleBus.note("와이퍼 수신 실패: ${e.message}")
        })
        v.getWiper().getCurrentWiperState(frontWindshield, { s -> post { it.copy(wiperOn = s == WiperState.ON) } }, { })
    }

    private fun registerLocation() {
        val f = fused ?: return
        if (!f.isSupported()) {
            VehicleBus.note("위치 SDK: 이 차에서는 지원하지 않음")
            return
        }
        val listener = object : FusedLocationListener {
            override fun onChanged(data: LocationData?) {
                if (data != null) post { it.copy(lat = data.latitude, lng = data.longitude) }
            }
            override fun onError(e: Exception) = VehicleBus.note("위치 수신 실패: ${e.message}")
        }
        f.registerFusedLocationCallback(listener)
        f.getLastKnownLocation(listener)
    }

    // ---- writes ------------------------------------------------------------------------------------------

    private inline fun send(what: String, block: () -> Unit) {
        try {
            block()
            VehicleBus.note("보냄: $what")
        } catch (e: Exception) {
            VehicleBus.note("$what 실패: ${e.message}")
        }
    }

    private val failed: (Exception) -> Unit = { e -> VehicleBus.note("차량 응답 오류: ${e.message}") }

    override fun lockDoors(locked: Boolean) = send("문 잠금 $locked") {
        vehicle?.getDoor()?.setDoorLock(allDoors, locked, { _, _ -> }, failed)
    }

    override fun lockWindows(locked: Boolean) = send("창문 잠금 $locked") {
        vehicle?.getWindow()?.setWindowLock(allWindows, locked, { _, _ -> }, failed)
    }

    override fun closeWindows() = send("창문 닫기") {
        vehicle?.getWindow()?.setWindowPosition(allWindows, 0, { _, _ -> }, failed)
    }

    override fun lockGloveBox(locked: Boolean) = send("글로브박스 잠금 $locked") {
        vehicle?.getDoor()?.setGloveBoxLock(locked, { }, failed)
    }

    override fun readingLight(seat: Seat, on: Boolean) = send("독서등 ${seat.label} $on") {
        vehicle?.getLight()?.setReadingLightSwitch(seat(seat), if (on) LightSwitch.ON else LightSwitch.OFF, { }, failed)
        VehicleBus.effect("독서등 ${seat.label}", if (on) "켜짐" else null)
    }

    override fun cabinLight(on: Boolean) = send("실내등 $on") {
        vehicle?.getLight()?.setCabinLightSwitch(if (on) LightSwitch.ON else LightSwitch.OFF, { }, failed)
        VehicleBus.effect("실내등", if (on) "켜짐" else "꺼짐")
    }

    override fun ambient(seat: Seat?, argb: Int, brightness: Int) = send("앰비언트 ${seat?.label ?: "전체"} ${Integer.toHexString(argb)} $brightness") {
        val area = if (seat == null) allSeats else seat(seat)
        val l = vehicle?.getLight() ?: return@send
        l.setAmbientLightSwitch(area, LightSwitch.ON, { _, _ -> }, failed)
        l.setAmbientLightBrightnessColor(area, argb, { _, _ -> }, failed)
        l.setAmbientLightBrightness(area, brightness, { _, _ -> }, failed)
        VehicleBus.effect("앰비언트", "#" + Integer.toHexString(argb and 0xFFFFFF).padStart(6, '0').uppercase() + " $brightness%")
    }

    override fun seatVentilation(seat: Seat, level: Int) = send("시트 통풍 ${seat.label} $level") {
        vehicle?.getSeat()?.setHvacSeatVentilation(seat(seat), level, { _, _ -> }, failed)
        VehicleBus.effect("통풍 ${seat.label}", if (level > 0) "$level 단" else null)
    }

    override fun seatHeat(seat: Seat, level: Int) = send("시트 열선 ${seat.label} $level") {
        vehicle?.getSeat()?.setHvacSeatTemperature(seat(seat), level, { _, _ -> }, failed)
    }

    override fun hvacTemp(celsius: Float) = send("공조 온도 $celsius") {
        vehicle?.getHVAC()?.setHvacTemperature(driverZone, celsius, TemperatureUnit.CELSIUS, { _, _ -> }, failed)
    }

    override fun fan(level: Int) = send("풍량 $level") {
        vehicle?.getHVAC()?.setHvacFanSpeed(driverZone, level, { _, _ -> }, failed)
    }

    override fun recirculation(on: Boolean) = send("내기 순환 $on") {
        vehicle?.getHVAC()?.setHvacRecirculation(driverZone, on, { _, _ -> }, failed)
    }

    override fun airCleaner(on: Boolean) = send("공기청정 $on") {
        vehicle?.getHVAC()?.setHvacAirCleaner(driverZone, on, { _, _ -> }, failed)
    }

    override fun smartGlass(opaque: Boolean) = send("스마트 글래스 전체 ${if (opaque) "암막" else "투명"}") {
        vehicle?.getWindow()?.setSmartGlassState(opaque, { }, failed)
        VehicleBus.effect("스마트 글래스", if (opaque) "암막" else null)
    }

    override fun smartGlassOnlyClear(window: Seat) = send("스마트 글래스 ${window.label} 만 투명") {
        val w = vehicle?.getWindow() ?: return@send
        for ((slot, _) in windowValues) {
            val cmd = if (slot == window) SmartGlassZoneCommand.OFF else SmartGlassZoneCommand.ON
            w.setSmartGlassZoneState(window(slot), listOf(cmd, cmd, cmd), { _, _ -> }, failed)
        }
        VehicleBus.effect("스마트 글래스", "${window.label} 만 투명")
    }

    override fun blinds(close: Boolean) = send("블라인드 ${if (close) "닫기" else "열기"}") {
        vehicle?.getWindow()?.moveWindowBlind(allWindows, if (close) MoveControl.FORWARD else MoveControl.BACKWARD, { _, _ -> }, failed)
        VehicleBus.effect("블라인드", if (close) "닫힘" else null)
    }

    override fun etcsVolume(volume: Int) = send("하이패스 안내음 $volume") {
        vehicle?.getToll()?.setEtcsVoiceGuidanceVolume(volume, { }, failed)
        VehicleBus.effect("하이패스 안내음", if (volume == 0) "음소거" else null)
    }

    // ---- snapshot and restore ----------------------------------------------------------------------------

    /** Turns one SDK get(ok, fail) call into a value, or null when the car does not answer within a second. */
    private suspend fun <T> read(block: ((T?) -> Unit, (Exception) -> Unit) -> Unit): T? =
        withTimeoutOrNull(1200) {
            suspendCancellableCoroutine { cont ->
                try {
                    block({ v -> if (cont.isActive) cont.resume(v) }, { if (cont.isActive) cont.resume(null) })
                } catch (e: Exception) {
                    if (cont.isActive) cont.resume(null)
                }
            }
        }

    override suspend fun snapshot(): Snapshot {
        val v = vehicle ?: return Snapshot()
        val doorLocked = buildMap { for (s in Seat.entries) read<Boolean> { ok, f -> v.getDoor().getDoorLockState(door(s), ok, f) }?.let { put(s, it) } }
        val windowLocked = read<Boolean> { ok, f -> v.getWindow().getCurrentWindowLockState(allWindows, ok, f) }
        val glove = read<Boolean> { ok, f -> v.getDoor().getCurrentGloveBoxLockState(ok, f) }
        val reading = buildMap { for (s in Seat.entries) read<LightState> { ok, f -> v.getLight().getCurrentReadingLightState(seat(s), ok, f) }?.let { put(s, it == LightState.ON) } }
        val cabin = read<LightSwitch> { ok, f -> v.getLight().getCabinLightSwitch(ok, f) }?.let { it == LightSwitch.ON }
        val ambColor = buildMap { for (s in Seat.entries) read<Int> { ok, f -> v.getLight().getAmbientLightBrightnessColor(seat(s), ok, f) }?.let { put(s, it) } }
        val ambBright = buildMap { for (s in Seat.entries) read<Int> { ok, f -> v.getLight().getAmbientLightBrightness(seat(s), ok, f) }?.let { put(s, it) } }
        val vent = buildMap { for (s in Seat.entries) read<Int> { ok, f -> v.getSeat().getHvacSeatVentilationState(seat(s), ok, f) }?.let { put(s, it) } }
        val heat = buildMap { for (s in Seat.entries) read<Int> { ok, f -> v.getSeat().getHvacSeatTemperature(seat(s), ok, f) }?.let { put(s, it) } }
        val temp = read<Float> { ok, f -> v.getHVAC().getHvacTemperature(driverZone, TemperatureUnit.CELSIUS, ok, f) }
        val fan = read<Int> { ok, f -> v.getHVAC().getHvacFanSpeed(driverZone, ok, f) }
        val recirc = read<Boolean> { ok, f -> v.getHVAC().getHvacRecirculationState(driverZone, ok, f) }
        val cleaner = read<Boolean> { ok, f -> v.getHVAC().getHvacAirCleanerState(driverZone, ok, f) }
        val glass = read<Boolean> { ok, f -> v.getWindow().getCurrentSmartGlassState(ok, f) }
        val etcs = read<Int> { ok, f -> v.getToll().getCurrentEtcsVoiceGuidanceVolume(ok, f) }
        val snap = Snapshot(doorLocked, windowLocked, glove, reading, cabin, ambColor, ambBright, vent, heat, temp, fan, recirc, cleaner, glass, etcs)
        VehicleBus.note("차량 설정 백업: ${snap.restoredItems().joinToString()}")
        return snap
    }

    override fun restore(snapshot: Snapshot) {
        val v = vehicle ?: return
        VehicleBus.note("차량 설정 원상 복구 시작")
        for ((s, locked) in snapshot.doorLocked) runCatching { v.getDoor().setDoorLock(door(s), locked, { _, _ -> }, failed) }
        snapshot.windowLocked?.let { lockWindows(it) }
        snapshot.gloveBoxLocked?.let { lockGloveBox(it) }
        for ((s, on) in snapshot.readingLight) readingLight(s, on)
        snapshot.cabinLightOn?.let { cabinLight(it) }
        for ((s, c) in snapshot.ambientColor) runCatching { v.getLight().setAmbientLightBrightnessColor(seat(s), c, { _, _ -> }, failed) }
        for ((s, b) in snapshot.ambientBrightness) runCatching { v.getLight().setAmbientLightBrightness(seat(s), b, { _, _ -> }, failed) }
        for ((s, l) in snapshot.seatVentilation) seatVentilation(s, l)
        for ((s, l) in snapshot.seatHeat) seatHeat(s, l)
        snapshot.hvacTempC?.let { hvacTemp(it) }
        snapshot.fanSpeed?.let { fan(it) }
        snapshot.recirculation?.let { recirculation(it) }
        snapshot.airCleaner?.let { airCleaner(it) }
        snapshot.smartGlassOpaque?.let { smartGlass(it) }
        snapshot.etcsVolume?.let { etcsVolume(it) }
        VehicleBus.clearEffects()
    }
}
