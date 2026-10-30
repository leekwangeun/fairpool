package kr.carescape.multiverse

import kr.carescape.multiverse.game.CarContext
import kr.carescape.multiverse.game.EpisodeGenerator
import kr.carescape.multiverse.game.GimmickKind
import kr.carescape.multiverse.game.PlaceKind
import kr.carescape.multiverse.game.Theme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EpisodeGeneratorTest {
    private val ctx = CarContext(place = "강릉 경포 바다 근처 휴게소", placeKind = PlaceKind.SEA, rain = true, night = true, soc = 45)

    @Test
    fun sameSeedGivesSameEpisode() {
        val a = EpisodeGenerator.generate(Theme.SUBMARINE, ctx, 42)
        val b = EpisodeGenerator.generate(Theme.SUBMARINE, ctx, 42)
        assertEquals(a, b)
        assertNotEquals(a.code + a.stages, EpisodeGenerator.generate(Theme.SUBMARINE, ctx, 43).let { it.code + it.stages })
    }

    @Test
    fun everyThemeHasFiveGimmicksThenTheFinalCode() {
        for (theme in Theme.entries) {
            val e = EpisodeGenerator.generate(theme, ctx, 7)
            assertEquals(6, e.stages.size)
            assertEquals(GimmickKind.FINAL_CODE, e.stages.last().kind)
            assertEquals(5, e.code.length)
            assertEquals(theme.kinds, e.stages.dropLast(1).map { it.kind })
        }
    }

    @Test
    fun puzzleValuesStayInsideWhatTheCarCanDo() {
        for (seed in 0L until 300L) for (theme in Theme.entries) {
            for (s in EpisodeGenerator.generate(theme, ctx, seed).stages) when (s.kind) {
                GimmickKind.FLASHLIGHT -> assertTrue(s.blinks in EpisodeGenerator.Limits.blinks)
                GimmickKind.SAFE_DIAL -> {
                    assertTrue(EpisodeGenerator.Limits.angleOk(s.angle))
                    assertTrue(s.turnCount in EpisodeGenerator.Limits.turnCount)
                }
                GimmickKind.TEMPERATURE -> {
                    assertTrue(EpisodeGenerator.Limits.temperatureOk(s.temperature))
                    if (theme == Theme.GHOST_TAXI) assertTrue("the ghost wants it cold", s.temperature <= 19.5f)
                }
                GimmickKind.LIGHT_SHARD -> assertTrue(s.window != null)
                else -> {}
            }
        }
    }

    @Test
    fun contextShapesTheStory() {
        assertEquals("빗속의 히치하이커", EpisodeGenerator.generate(Theme.GHOST_TAXI, ctx, 1).title)
        assertEquals("자정의 히치하이커", EpisodeGenerator.generate(Theme.GHOST_TAXI, ctx.copy(rain = false), 1).title)
        assertTrue(EpisodeGenerator.generate(Theme.CODE_RED, ctx, 1).intro.contains("45%"))
        assertTrue(EpisodeGenerator.generate(Theme.CODE_RED, ctx, 1).intro.contains("80%"))
    }

    @Test
    fun temperatureRiddleWords() {
        assertEquals("열여덟 도 반", EpisodeGenerator.temperatureWords(18.5f))
        assertEquals("열일곱 도", EpisodeGenerator.temperatureWords(17f))
        assertEquals("스물 도", EpisodeGenerator.temperatureWords(20f))
        assertEquals("스물넷 도 반", EpisodeGenerator.temperatureWords(24.5f))
    }
}
