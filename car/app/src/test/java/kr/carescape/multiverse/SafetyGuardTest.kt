package kr.carescape.multiverse

import kr.carescape.multiverse.game.CheckState
import kr.carescape.multiverse.game.PauseReason
import kr.carescape.multiverse.game.SafetyGuard
import kr.carescape.multiverse.vehicle.Seat
import kr.carescape.multiverse.vehicle.Signals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyGuardTest {
    private val child = Seat.REAR_RIGHT
    private val ready = Signals(
        gear = "P",
        occupied = mapOf(Seat.DRIVER to true, child to true),
        belted = mapOf(child to true),
        doorOpen = Seat.entries.associateWith { false },
        doorLocked = Seat.entries.associateWith { true },
        windowLocked = true,
        gloveBoxLocked = true,
        supported = setOf("windowlock", "glovebox")
    )

    @Test
    fun aReadyCarPassesEveryCheck() {
        val list = SafetyGuard.checklist(ready, child, manualPass = false)
        assertEquals(6, list.size)
        assertTrue(list.all { it.state == CheckState.PASS })
    }

    @Test
    fun missingFeaturesFailUnlessManualPassIsOn() {
        val noGlove = ready.copy(gloveBoxLocked = null, supported = setOf("windowlock"))
        assertEquals(CheckState.FAIL, SafetyGuard.checklist(noGlove, child, false).last().state)
        assertEquals(CheckState.SKIPPED, SafetyGuard.checklist(noGlove, child, true).last().state)
    }

    @Test
    fun openDoorAndUnbuckledBeltFail() {
        val open = SafetyGuard.checklist(ready.copy(doorOpen = mapOf(Seat.REAR_LEFT to true)), child, true)
        assertEquals(CheckState.FAIL, open.first { it.label.startsWith("문") }.state)
        val belt = SafetyGuard.checklist(ready.copy(belted = mapOf(child to false)), child, true)
        assertEquals(CheckState.FAIL, belt.first { it.label.startsWith("안전벨트") }.state)
        val noSensor = SafetyGuard.checklist(ready.copy(belted = emptyMap()), child, true)
        assertEquals(CheckState.SKIPPED, noSensor.first { it.label.startsWith("안전벨트") }.state)
    }

    @Test
    fun aBuckledBeltCountsAsSeatedForSensorlessRearSeats() {
        assertTrue(SafetyGuard.childSeated(ready.copy(occupied = mapOf(Seat.DRIVER to true)), child))
    }

    @Test
    fun pauseReasonsInOrderOfDanger() {
        assertNull(SafetyGuard.pauseReason(ready, child))
        assertEquals(PauseReason.DRIVING, SafetyGuard.pauseReason(ready.copy(gear = "D", doorOpen = mapOf(child to true)), child)?.first)
        assertEquals(PauseReason.DRIVING, SafetyGuard.pauseReason(ready.copy(speedKmh = 10f), child)?.first)
        assertEquals(PauseReason.DOOR_OPEN, SafetyGuard.pauseReason(ready.copy(doorOpen = mapOf(child to true), belted = mapOf(child to false)), child)?.first)
        assertEquals(PauseReason.BELT_OFF, SafetyGuard.pauseReason(ready.copy(belted = mapOf(child to false)), child)?.first)
        assertEquals(PauseReason.CHILD_LEFT_SEAT, SafetyGuard.pauseReason(ready.copy(belted = mapOf(child to false), occupied = mapOf(child to false)), child)?.first)
        assertNull("no belt sensor cannot pause the game", SafetyGuard.pauseReason(ready.copy(belted = emptyMap()), child))
    }
}
