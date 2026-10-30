package kr.carescape.multiverse.vehicle

/**
 * The demo car: no SDK, every write just updates [VehicleBus] so the screens react the same way.
 * Also used as the stand-in before the real SDK has connected.
 */
object FakeVehicle : Controls {
    private var baseline = Snapshot()

    /** A parked car at a rest stop by the sea on a rainy night, parent in front and child at the rear right. */
    fun reset(rain: Boolean = false, soc: Int = 45) {
        VehicleBus.update {
            Signals(
                gear = "P",
                occupied = mapOf(Seat.DRIVER to true, Seat.FRONT_RIGHT to false, Seat.REAR_LEFT to false, Seat.REAR_RIGHT to true),
                belted = mapOf(Seat.DRIVER to true, Seat.REAR_RIGHT to true),
                doorOpen = Seat.entries.associateWith { false },
                doorLocked = Seat.entries.associateWith { false },
                windowLocked = false,
                windowOpen = Seat.entries.associateWith { false },
                gloveBoxLocked = false,
                hvacTempC = 22f,
                fanSpeed = 2,
                readingLight = Seat.entries.associateWith { false },
                evSoc = soc,
                wiperOn = rain,
                lat = 37.7916, lng = 128.9145,
                supported = setOf("glovebox", "smartglass", "windowlock", "demo")
            )
        }
        VehicleBus.clearEffects()
        VehicleBus.note("데모 차량 준비: 주차, 운전석과 뒷좌석 오른쪽 착석, 배터리 $soc%")
    }

    override suspend fun snapshot(): Snapshot {
        val s = VehicleBus.signals.value
        baseline = Snapshot(
            doorLocked = s.doorLocked, windowLocked = s.windowLocked, gloveBoxLocked = s.gloveBoxLocked,
            readingLight = s.readingLight, cabinLightOn = false,
            ambientColor = Seat.entries.associateWith { 0xFFFFFFFF.toInt() }, ambientBrightness = Seat.entries.associateWith { 30 },
            seatVentilation = Seat.entries.associateWith { 0 }, seatHeat = Seat.entries.associateWith { 0 },
            hvacTempC = s.hvacTempC, fanSpeed = s.fanSpeed, recirculation = s.recirculation, airCleaner = s.airCleaner,
            smartGlassOpaque = false, etcsVolume = 5
        )
        VehicleBus.note("데모 차량 설정 백업")
        return baseline
    }

    override fun lockDoors(locked: Boolean) {
        VehicleBus.update { it.copy(doorLocked = Seat.entries.associateWith { locked }) }
        VehicleBus.note("데모: 문 잠금 $locked")
    }

    override fun lockWindows(locked: Boolean) {
        VehicleBus.update { it.copy(windowLocked = locked) }
        VehicleBus.note("데모: 창문 잠금 $locked")
    }

    override fun closeWindows() {
        VehicleBus.update { it.copy(windowOpen = Seat.entries.associateWith { false }) }
    }

    override fun lockGloveBox(locked: Boolean) {
        VehicleBus.update { it.copy(gloveBoxLocked = locked) }
        VehicleBus.note("데모: 글로브박스 잠금 $locked")
    }

    override fun readingLight(seat: Seat, on: Boolean) {
        VehicleBus.update { it.copy(readingLight = it.readingLight + (seat to on)) }
        VehicleBus.effect("독서등 ${seat.label}", if (on) "켜짐" else null)
    }

    override fun cabinLight(on: Boolean) = VehicleBus.effect("실내등", if (on) "켜짐" else null)

    override fun ambient(seat: Seat?, argb: Int, brightness: Int) =
        VehicleBus.effect("앰비언트", "#" + Integer.toHexString(argb and 0xFFFFFF).padStart(6, '0').uppercase() + " $brightness%")

    override fun seatVentilation(seat: Seat, level: Int) = VehicleBus.effect("통풍 ${seat.label}", if (level > 0) "$level 단" else null)

    override fun seatHeat(seat: Seat, level: Int) = VehicleBus.effect("열선 ${seat.label}", if (level > 0) "$level 단" else null)

    override fun hvacTemp(celsius: Float) = VehicleBus.update { it.copy(hvacTempC = celsius) }

    override fun fan(level: Int) = VehicleBus.update { it.copy(fanSpeed = level) }

    override fun recirculation(on: Boolean) = VehicleBus.update { it.copy(recirculation = on) }

    override fun airCleaner(on: Boolean) = VehicleBus.update { it.copy(airCleaner = on) }

    override fun smartGlass(opaque: Boolean) = VehicleBus.effect("스마트 글래스", if (opaque) "암막" else null)

    override fun smartGlassOnlyClear(window: Seat) = VehicleBus.effect("스마트 글래스", "${window.label} 만 투명")

    override fun blinds(close: Boolean) = VehicleBus.effect("블라인드", if (close) "닫힘" else null)

    override fun etcsVolume(volume: Int) = VehicleBus.effect("하이패스 안내음", if (volume == 0) "음소거" else null)

    override fun restore(snapshot: Snapshot) {
        VehicleBus.update {
            it.copy(
                doorLocked = snapshot.doorLocked.ifEmpty { it.doorLocked },
                windowLocked = snapshot.windowLocked ?: it.windowLocked,
                gloveBoxLocked = snapshot.gloveBoxLocked ?: it.gloveBoxLocked,
                readingLight = snapshot.readingLight.ifEmpty { it.readingLight },
                hvacTempC = snapshot.hvacTempC ?: it.hvacTempC,
                fanSpeed = snapshot.fanSpeed ?: it.fanSpeed,
                recirculation = snapshot.recirculation ?: it.recirculation,
                airCleaner = snapshot.airCleaner ?: it.airCleaner
            )
        }
        VehicleBus.clearEffects()
        VehicleBus.note("데모 차량 설정 원상 복구")
    }
}
