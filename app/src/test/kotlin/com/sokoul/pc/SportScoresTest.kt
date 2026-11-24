package com.sokoul.pc

import com.sokoul.pc.live.sport.MatchState
import com.sokoul.pc.live.sport.SportLeague
import com.sokoul.pc.live.sport.SportScoresParse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ESPN's scoreboard read as the Sport page's matches (a real Ligue 1 answer of 22 November 2026, kept in the tests). */
class SportScoresTest {
    private val body = javaClass.getResource("/espn-ligue1.json")!!.readText()

    @Test
    fun aScoreboardBecomesMatchesWithTeamsScoresAndBroadcasters() {
        val matches = SportScoresParse.matches(body, SportLeague.Ligue1)
        assertEquals(3, matches.size)
        val marseille = matches.first { it.home.abbr == "OLM" }
        assertEquals(MatchState.Finished, marseille.state)
        assertEquals("2", marseille.home.score)
        assertEquals("3", marseille.away.score)
        assertEquals("MNS", marseille.away.abbr)
        assertEquals(listOf("beIn Sports"), marseille.broadcasts)
        assertNotNull(marseille.home.logo)
        // "ffffff" becomes an opaque colour.
        assertEquals(0xFFFFFFFF, marseille.home.color)
        assertTrue(marseille.venue!!.startsWith("Stade V"))
        assertTrue(marseille.scored)
    }

    @Test
    fun espnDatesWithoutSecondsAreRead() {
        assertEquals(java.time.Instant.parse("2026-11-22T19:45:00Z").toEpochMilli(), SportScoresParse.parseDate("2026-11-22T19:45Z"))
    }

    @Test
    fun aBrokenAnswerGivesNoMatchRatherThanAnError() {
        assertEquals(0, SportScoresParse.matches("{not json", SportLeague.Nba).size)
    }

    private fun team(abbr: String, name: String) = com.sokoul.pc.live.sport.SportTeam(abbr, name, null, null, null)
    private fun match(home: com.sokoul.pc.live.sport.SportTeam, away: com.sokoul.pc.live.sport.SportTeam) =
        com.sokoul.pc.live.sport.SportMatch("1", SportLeague.Ligue1, 0, MatchState.Upcoming, null, null, home, away, listOf("beIn Sports"))

    @Test
    fun aMatchFindsItsSokoulServerEventByBothClubs() {
        val events = listOf(
            com.sokoul.pc.live.SportsEvent("streamed_a", "Olympique Lyonnais vs Paris FC", "football", false, null),
            com.sokoul.pc.live.SportsEvent("streamed_b", "Marseille vs Le Mans", "football", true, null),
            com.sokoul.pc.live.SportsEvent("streamed_c", "Monaco vs Saint-Étienne", "football", false, null),
        )
        assertEquals("streamed_b", com.sokoul.pc.live.sport.sokoulEvent(match(team("OLM", "Marseille"), team("MNS", "Le Mans")), events)?.id)
        // Accents do not matter; one club alone is not enough.
        assertEquals("streamed_c", com.sokoul.pc.live.sport.sokoulEvent(match(team("ASM", "Monaco"), team("ASSE", "Saint-Etienne")), events)?.id)
        assertEquals(null, com.sokoul.pc.live.sport.sokoulEvent(match(team("ASM", "Monaco"), team("LIL", "Lille")), events))
    }

    @Test
    fun broadcasterNamesAreComparedWithoutCaseOrAccents() {
        assertEquals("bein sports 1 hd", com.sokoul.pc.live.sport.sportKey("beIN SPORTS 1 HD"))
        assertTrue(com.sokoul.pc.live.sport.sportKey("beIN SPORTS 1 HD").contains(com.sokoul.pc.live.sport.sportKey("beIn Sports")))
        assertEquals("canal+ sport", com.sokoul.pc.live.sport.sportKey("Canal+ Sport"))
    }
}
