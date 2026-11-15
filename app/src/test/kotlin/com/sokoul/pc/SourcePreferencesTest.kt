package com.sokoul.pc

import com.sokoul.desktop.data.PlaybackPrefs
import com.sokoul.desktop.player.StreamRequest
import com.sokoul.iptv.domain.AudioLanguage
import com.sokoul.pc.playback.session.*
import com.sokoul.pc.playback.sources.*
import com.sokoul.pc.playback.ui.*
import org.junit.Test
import org.junit.Assert.assertEquals

class SourcePreferencesTest {
    private fun version(source: String, host: String, language: AudioLanguage = AudioLanguage.VF, readiness: Readiness = Readiness.Ready) =
        PlayVersion("$source-$host-$language", VersionOrigin.Web, "$source · VF · $host", language = language, readiness = readiness,
            providerHost = host, open = { _, _ -> StreamRequest("https://fixture.test/a.m3u8", null, null, "Fixture") })

    @Test fun prioritiesReorderEligibleCandidatesWithoutRemovingAnyAndKeepLanguageReadiness() {
        val vf = version("Omega", "vidmoly.to")
        val other = version("FStream", "voe.sx")
        val sub = version("FStream", "sibnet.ru", AudioLanguage.VOSTFR)
        val waiting = version("FStream", "vidzy.org", readiness = Readiness.NotCached)
        val order = SourcePreferenceOrder(sources = listOf("FStream", "Omega"))
        val result = SourcePreferences.rank(listOf(vf, other, sub, waiting), { order }, PlaybackPrefs(), true, DeviceCaps.Pc, { it.language }, { false })
        assertEquals(listOf(other, vf, sub, waiting), result)
        assertEquals(4, result.size)
    }

    @Test fun rememberedHosterIsScopedToSourceAndCategory() {
        val first = version("FStream", "vidmoly.to")
        val chosen = version("FStream", "voe.sx")
        val order = SourcePreferenceOrder(remembered = mapOf("FStream" to "voe", "Omega" to "sibnet"))
        val result = SourcePreferences.rank(listOf(first, chosen), { order }, PlaybackPrefs(), true, DeviceCaps.Pc, { it.language }, { false })
        assertEquals(listOf(chosen, first), result)
        assertEquals(listOf("voe", "vidmoly", "sibnet"), SourcePreferences.normalize(listOf("voe", "voe", "unknown"), listOf("vidmoly", "sibnet", "voe")))
    }
}
