package kr.carescape.multiverse

import kr.carescape.multiverse.ai.LlmPrompt
import kr.carescape.multiverse.data.SnapshotJson
import kr.carescape.multiverse.game.CarContext
import kr.carescape.multiverse.game.EpisodeGenerator
import kr.carescape.multiverse.game.GimmickKind
import kr.carescape.multiverse.game.Theme
import kr.carescape.multiverse.vehicle.Seat
import kr.carescape.multiverse.vehicle.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmPromptTest {
    private val ctx = CarContext(place = "서울 도심", soc = 45)
    private val base = EpisodeGenerator.generate(Theme.CODE_RED, ctx, 5)

    @Test
    fun promptCarriesTheCarAndTheLimits() {
        val p = LlmPrompt.episodePrompt(base, ctx)
        assertTrue(p.contains("서울 도심"))
        assertTrue(p.contains("45%"))
        assertTrue(p.contains("17.0~27.0"))
        assertTrue(p.contains("5개"))
    }

    @Test
    fun mergeKeepsGoodValuesAndRejectsImpossibleOnes() {
        // CODE_RED: PURIFY, DEFROST, TEMPERATURE, SAFE_DIAL, FLASHLIGHT
        val answer = """
            물론이죠! ```json
            {"title":"별빛 엔진","intro":"우주선이 흔들려요!","outro":"성공!","final_line":"암호를 외쳐!",
             "stages":[{"title":"먼지 폭풍","line":"막아!","hint":"화살표","digit":7},
                       {"line":"얼음!","digit":12},
                       {"line":"냉각!","digit":2,"temperature":40},
                       {"line":"레버!","digit":4,"angle":45},
                       {"line":"신호!","digit":9,"blinks":9}]}
            ```
        """.trimIndent()
        val e = LlmPrompt.merge(base, answer)!!
        assertEquals("llm", e.source)
        assertEquals("별빛 엔진", e.title)
        assertEquals("먼지 폭풍", e.stages[0].title)
        assertEquals(7, e.stages[0].digit)
        assertEquals("digit 12 is not a digit", base.stages[1].digit, e.stages[1].digit)
        assertEquals("40 degrees is out of range", base.stages[2].temperature, e.stages[2].temperature)
        assertEquals(45, e.stages[3].angle)
        assertTrue(e.stages[3].childTask.contains("오른쪽으로 45도"))
        assertEquals("9 blinks is out of range", base.stages[4].blinks, e.stages[4].blinks)
        assertEquals("암호를 외쳐!", e.stages.last().line)
        assertEquals(GimmickKind.FINAL_CODE, e.stages.last().kind)
    }

    @Test
    fun noJsonMeansNoMerge() {
        assertNull(LlmPrompt.merge(base, "죄송해요, 지금은 답할 수 없어요."))
        assertNull(LlmPrompt.merge(base, "{깨진 json"))
    }

    @Test
    fun repliesAreShortAndNeverGiveTheCodeAway() {
        assertEquals("안녕! 나는 코어야.", LlmPrompt.cleanReply("안녕! 나는 코어야. 오늘은 날씨가 좋네. 플레이어: 뭐?", "12345"))
        assertEquals("그건 너희가 직접 찾아야 해!", LlmPrompt.cleanReply("암호는 1 2 3 4 5 야!", "12345"))
    }

    @Test
    fun snapshotSurvivesTheRoundTrip() {
        val s = Snapshot(
            doorLocked = mapOf(Seat.DRIVER to false, Seat.REAR_RIGHT to true), windowLocked = false, gloveBoxLocked = null,
            ambientColor = mapOf(Seat.FRONT_RIGHT to 0xFF27C7D9.toInt()), hvacTempC = 22.5f, fanSpeed = 3, etcsVolume = 5, takenAt = 1234
        )
        assertEquals(s, SnapshotJson.decode(SnapshotJson.encode(s)))
    }
}
