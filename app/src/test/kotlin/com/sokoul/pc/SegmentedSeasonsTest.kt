package com.sokoul.pc

import com.sokoul.pc.data.SegmentedSeasons
import com.sokoul.iptv.data.net.TmdbEpisode
import org.junit.Assert.*
import org.junit.Test

class SegmentedSeasonsTest {
    private fun ep(n: Int, code: String? = null, runtime: Int? = 11, name: String = "Part $n") =
        TmdbEpisode(1, n, name, "Story $n", null, runtime, productionCode = code)

    @Test fun separatedProductionHalvesUseBroadcastNumbering() {
        val result = SegmentedSeasons.season(68073, 3, listOf(ep(1,"301A"), ep(2,"302A"), ep(3,"302B"), ep(4,"301B")))
        assertEquals(listOf("Part 1 / Part 4", "Part 2 / Part 3"), result.map { it.name })
        assertEquals(listOf(1,2), result.map { it.episode })
        assertEquals(listOf(1,4), result.first().sourceEpisodeNumbers)
        assertEquals(22, result.first().runtimeMin)
    }

    @Test fun lettersOverrideWrongDurationAndDoubleEpisodesSplit() {
        val result = SegmentedSeasons.merge(listOf(ep(1,"414A",22),ep(2,"414B"),ep(3,"501 502",44),ep(4,"811",44)))
        assertEquals(5, result.size)
        assertEquals(listOf(1,2), result.first().sourceEpisodeNumbers)
        assertEquals("Part 3 (1/2)", result[1].name)
        assertEquals(22, result[2].runtimeMin)
        assertEquals(2, result[4].partIndex)
    }

    @Test fun repeatedCodesStartNewPairsAndOrphanIsKept() {
        val result = SegmentedSeasons.merge(listOf(ep(1,"101A"),ep(2,"101B"),ep(3,"101A"),ep(4,"101B"),ep(5,"102A")))
        assertEquals(listOf(listOf(1,2),listOf(3,4),listOf(5)), result.map { it.sourceEpisodeNumbers })
    }

    @Test fun uncodedEntriesPairOnlyWithAnUncodedAdjacentShortEntry() {
        val result = SegmentedSeasons.merge(listOf(ep(1),ep(2),ep(3,runtime=22),ep(4),ep(5,"303A"),ep(6)))
        assertEquals(listOf(listOf(1,2),listOf(3),listOf(4),listOf(5),listOf(6)),result.map { it.sourceEpisodeNumbers })
    }

    @Test fun configuredProductionFixesAndEnglishPlaceholdersAreApplied() {
        val input = listOf(ep(27,name="Episode 27"),ep(30,"215A"),ep(31,"215B"),ep(32,name=""))
        val result = SegmentedSeasons.season(68073,2,input,listOf(ep(27,name="First Half"),ep(32,name="Second Half")))
        assertEquals(listOf(27,32),result.first().sourceEpisodeNumbers)
        assertEquals("First Half / Second Half",result.first().name)
    }

    @Test fun otherShowsSpecialsAndAnAlreadyMappedSeasonStayUnchanged() {
        val input = listOf(ep(1),ep(2))
        assertSame(input,SegmentedSeasons.season(10,1,input))
        assertSame(input,SegmentedSeasons.season(68073,0,input))
        val mapped = SegmentedSeasons.season(68073,1,input)
        assertSame(mapped,SegmentedSeasons.season(68073,1,mapped))
        // The reference's partial-dubbing limits must not hide later VOSTFR episodes.
        assertEquals(15,SegmentedSeasons.season(68073,10,(1..30).map { ep(it) }).size)
    }
}
