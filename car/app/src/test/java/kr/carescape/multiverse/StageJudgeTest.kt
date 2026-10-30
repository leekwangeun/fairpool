package kr.carescape.multiverse

import kr.carescape.multiverse.game.GimmickKind
import kr.carescape.multiverse.game.Stage
import kr.carescape.multiverse.game.StageJudge
import kr.carescape.multiverse.game.StageProgress
import kr.carescape.multiverse.vehicle.Seat
import kr.carescape.multiverse.vehicle.Signals
import kr.carescape.multiverse.vehicle.Turn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StageJudgeTest {
    private val child = Seat.REAR_RIGHT
    private fun stage(kind: GimmickKind, f: (Stage) -> Stage = { it }) = f(Stage(kind, "", "", "", "", "", digit = 3))

    /** Feeds readings one after another, like the engine does. */
    private fun run(stage: Stage, vararg readings: Signals): StageProgress {
        var p = StageProgress()
        var before = Signals()
        for (now in readings) {
            p = StageJudge.step(stage, child, before, now, p)
            before = now
        }
        return p
    }

    @Test
    fun defrostPassesOnAnyDefrostControl() {
        val s = stage(GimmickKind.DEFROST)
        assertFalse(run(s, Signals()).done)
        assertTrue(run(s, Signals(defroster = true)).done)
        assertTrue(run(s, Signals(maxDefrost = true)).done)
        assertTrue(run(s, Signals(mirrorHeat = true)).done)
    }

    @Test
    fun purifyNeedsBothRecirculationAndAirCleaner() {
        val s = stage(GimmickKind.PURIFY)
        val half = run(s, Signals(recirculation = true))
        assertFalse(half.done)
        assertEquals(0.5f, half.ratio)
        assertTrue(run(s, Signals(recirculation = true), Signals(recirculation = true, airCleaner = true)).done)
    }

    @Test
    fun flashlightCountsOnlyTheChildsLightTurningOn() {
        val s = stage(GimmickKind.FLASHLIGHT) { it.copy(blinks = 3) }
        val on = Signals(readingLight = mapOf(child to true))
        val off = Signals(readingLight = mapOf(child to false))
        val other = Signals(readingLight = mapOf(Seat.REAR_LEFT to true))
        assertEquals(2, run(s, on, off, other, off, on, on, off).count)
        assertTrue(run(s, on, off, on, off, on).done)
    }

    @Test
    fun safeDialNeedsTheMarkWithBrakeThenTheTurnSignal() {
        val s = stage(GimmickKind.SAFE_DIAL) { it.copy(angle = -90, turn = Turn.LEFT, turnCount = 2) }
        // brake away from the mark does nothing
        assertFalse(run(s, Signals(steeringAngle = -40f, brake = true)).armed)
        // on the mark (within 12 degrees) with the brake: armed
        val armed = run(s, Signals(steeringAngle = -85f), Signals(steeringAngle = -85f, brake = true))
        assertTrue(armed.armed)
        // the wrong side does not count, the right side twice passes
        val readings = arrayOf(
            Signals(steeringAngle = -85f, brake = true),
            Signals(turn = Turn.RIGHT), Signals(), Signals(turn = Turn.LEFT), Signals(), Signals(turn = Turn.LEFT)
        )
        assertTrue(run(s, *readings).done)
        assertFalse(run(s, *readings.copyOfRange(0, 4)).done)
    }

    @Test
    fun temperatureWithinAQuarterDegree() {
        val s = stage(GimmickKind.TEMPERATURE) { it.copy(temperature = 18.5f) }
        assertFalse(run(s, Signals(hvacTempC = 19f)).done)
        assertTrue(run(s, Signals(hvacTempC = 18.5f)).done)
        assertFalse(run(s, Signals(hvacTempC = null)).done)
    }

    @Test
    fun lightShardAnswer() {
        val s = stage(GimmickKind.LIGHT_SHARD) { it.copy(window = Seat.REAR_LEFT) }
        val wrong = StageJudge.answerWindow(s, Seat.DRIVER, StageProgress())
        assertEquals(1, wrong.wrongAnswers)
        assertTrue(StageJudge.answerWindow(s, Seat.REAR_LEFT, wrong).done)
    }

    @Test
    fun finalCodeFromKeypadOrSpeech() {
        assertTrue(StageJudge.answerCode("47109", "47109", StageProgress()).done)
        assertTrue(StageJudge.answerCode("47109", "암호는 사칠일공구야", StageProgress()).done)
        assertTrue(StageJudge.answerCode("47109", "넷, 일곱, 하나, 영, 아홉!", StageProgress()).done)
        assertFalse(StageJudge.answerCode("47109", "4710", StageProgress()).done)
        assertFalse(StageJudge.answerCode("", "", StageProgress()).done)
    }

    @Test
    fun digitsAndWindowsFromSpeech() {
        assertEquals("8613", StageJudge.digitsIn("여덟 여섯 하나 셋"))
        assertEquals("0912", StageJudge.digitsIn("공구1둘"))
        assertEquals(Seat.REAR_LEFT, StageJudge.windowIn("뒷좌석 왼쪽이 밝아"))
        assertEquals(Seat.FRONT_RIGHT, StageJudge.windowIn("조수석!"))
        assertEquals(Seat.DRIVER, StageJudge.windowIn("운전석 창문"))
        assertNull(StageJudge.windowIn("모르겠어"))
    }
}
