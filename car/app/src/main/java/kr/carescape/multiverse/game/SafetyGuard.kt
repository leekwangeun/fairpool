package kr.carescape.multiverse.game

import kr.carescape.multiverse.vehicle.Seat
import kr.carescape.multiverse.vehicle.Signals

/**
 * The safety sandbox rules: what must hold before the game starts (E1) and what pauses it (E3).
 * Pure functions, tested in SafetyGuardTest.
 */
object SafetyGuard {
    /** Above this the car counts as moving even if the gear still reads P (Virtual Console can do that). */
    const val MOVING_KMH = 3f

    /**
     * The E1 checklist. A feature the car never answered for is SKIPPED when [manualPass] is on and FAIL otherwise,
     * so the emulator can still be demonstrated without pretending the check happened.
     */
    fun checklist(s: Signals, child: Seat, manualPass: Boolean): List<CheckItem> {
        fun unknown(label: String) =
            if (manualPass) CheckItem(label, CheckState.SKIPPED, "확인 불가, 건너뜀")
            else CheckItem(label, CheckState.FAIL, "이 차에서 확인할 수 없어요. 설정에서 수동 통과를 켜세요")

        val gear = when {
            s.gear == "P" && s.speedKmh <= MOVING_KMH -> CheckItem("기어 P", CheckState.PASS)
            s.gear == "?" -> if (manualPass) CheckItem("기어 P", CheckState.SKIPPED, "기어 신호 없음") else CheckItem("기어 P", CheckState.WAIT, "기어 신호를 기다리는 중")
            else -> CheckItem("기어 P", CheckState.FAIL, "주차(P)로 바꿔 주세요")
        }

        val seated = when {
            s.occupied[child] == null && s.belted[child] == null -> unknown("운전석과 아이 좌석 착석")
            s.occupied[Seat.DRIVER] == false -> CheckItem("운전석과 아이 좌석 착석", CheckState.FAIL, "운전석에 보호자가 앉아 있어야 해요")
            !childSeated(s, child) -> CheckItem("운전석과 아이 좌석 착석", CheckState.FAIL, "${child.label}에 아이가 앉아 있어야 해요")
            else -> CheckItem("운전석과 아이 좌석 착석", CheckState.PASS)
        }

        val belt = when (s.belted[child]) {
            true -> CheckItem("안전벨트 (아이)", CheckState.PASS)
            null -> unknown("안전벨트 (아이)")
            false -> CheckItem("안전벨트 (아이)", CheckState.FAIL, "${child.label} 안전벨트를 매 주세요")
        }

        val doors = when {
            s.anyDoorOpen -> CheckItem("문 닫힘과 잠금", CheckState.FAIL, "${s.openDoors.joinToString { it.label }} 문이 열려 있어요")
            Seat.entries.all { s.doorLocked[it] == true } -> CheckItem("문 닫힘과 잠금", CheckState.PASS)
            s.doorLocked.isEmpty() -> unknown("문 닫힘과 잠금")
            else -> CheckItem("문 닫힘과 잠금", CheckState.WAIT, "잠그는 중")
        }

        val windows = when {
            s.windowOpen.values.any { it } -> CheckItem("창문 닫힘과 잠금", CheckState.WAIT, "창문을 닫는 중")
            s.windowLocked == true -> CheckItem("창문 닫힘과 잠금", CheckState.PASS)
            "windowlock" !in s.supported -> unknown("창문 닫힘과 잠금")
            else -> CheckItem("창문 닫힘과 잠금", CheckState.WAIT, "잠그는 중")
        }

        val glove = when {
            s.gloveBoxLocked == true -> CheckItem("글로브박스 잠금 (보물 넣음)", CheckState.PASS)
            "glovebox" !in s.supported -> unknown("글로브박스 잠금 (보물 넣음)")
            else -> CheckItem("글로브박스 잠금 (보물 넣음)", CheckState.WAIT, "잠그는 중")
        }
        return listOf(gear, seated, belt, doors, windows, glove)
    }

    /** Rear seats may lack an occupancy sensor; a buckled belt also counts as someone sitting there. */
    fun childSeated(s: Signals, child: Seat) = s.occupied[child] == true || s.belted[child] == true

    /**
     * Why the game must stop right now, or null. DRIVING ends the game, the others only pause it.
     * A seat or belt the car never reported (no sensor) cannot pause the game; only a reported change can.
     */
    fun pauseReason(s: Signals, child: Seat): Pair<PauseReason, String>? = when {
        !s.parked || s.speedKmh > MOVING_KMH -> PauseReason.DRIVING to "게임을 끝내고 차량 설정을 원래대로 돌려놓았어요."
        s.anyDoorOpen -> PauseReason.DOOR_OPEN to "${s.openDoors.joinToString { it.label }} 문이 열렸어요. 문을 닫으면 이어집니다."
        s.occupied[child] == false && s.belted[child] != true -> PauseReason.CHILD_LEFT_SEAT to "${child.label}에 다시 앉으면 이어집니다."
        s.belted[child] == false -> PauseReason.BELT_OFF to "${child.label} 안전벨트를 매면 이어집니다."
        else -> null
    }
}
