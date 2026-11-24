package com.sokoul.pc

import com.sokoul.desktop.data.Channel
import com.sokoul.desktop.data.Program
import com.sokoul.pc.live.TvCatalog
import com.sokoul.pc.live.sport.MatchState
import com.sokoul.pc.live.sport.SportGuideMatch
import com.sokoul.pc.live.sport.SportLeague
import com.sokoul.pc.live.sport.SportMatch
import com.sokoul.pc.live.sport.SportTeam
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A match found in the owner's guide: both clubs in a programme title, during the match. */
class SportGuideMatchTest {
    private val kickoff = 1_000_000_000_000L
    private fun team(abbr: String, name: String) = SportTeam(abbr, name, null, null, null)
    private val om = SportMatch("1", SportLeague.Ligue1, kickoff, MatchState.Upcoming, null, null, team("OLM", "Marseille"), team("MNS", "Le Mans"), emptyList())
    private fun channel(id: String) = Channel(1, id, 1, "FR| $id", null, "c", null, id.lowercase(), null, null, null, 0)

    @Test
    fun bothClubsMustBeNamed() {
        assertTrue(SportGuideMatch.names("Marseille / Le Mans", om))
        assertTrue(SportGuideMatch.names("Football : MARSEILLE - Le Mans", om))
        // "Le" alone is no club: one club named is not the match.
        assertFalse(SportGuideMatch.names("Marseille / Lens", om))
        assertFalse(SportGuideMatch.names("Ligue 1 : le multiplex", om))
    }

    @Test
    fun theChannelCoveringTheMatchComesFirstAndOtherTimesAreLeftOut() {
        val hour = 3_600_000L
        val programmes = listOf(
            TvCatalog.Hit(channel("RECAP"), Program("r", kickoff - 2 * hour, kickoff - hour, "Marseille / Le Mans", null)),
            TvCatalog.Hit(channel("PRE"), Program("p", kickoff - 20 * 60_000L, kickoff + 10 * 60_000L, "Avant-match Marseille / Le Mans", null)),
            TvCatalog.Hit(channel("LIVE"), Program("l", kickoff - 5 * 60_000L, kickoff + 2 * hour, "Marseille / Le Mans", null)),
        )
        val found = SportGuideMatch.find(om) { _, _, _ -> programmes }
        assertEquals(listOf("LIVE", "PRE"), found.map { it.first.id })
    }
}
