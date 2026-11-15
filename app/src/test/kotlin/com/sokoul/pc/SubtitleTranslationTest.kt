package com.sokoul.pc

import com.sokoul.pc.data.providers.Cue
import com.sokoul.pc.data.providers.SubtitleResult
import com.sokoul.pc.data.providers.Translator
import com.sokoul.pc.playback.sources.OnlineSubtitles
import com.sokoul.pc.playback.session.PlayRequest
import com.sokoul.pc.playback.sources.SubtitleSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** Translated subtitles replace the lines on screen, keep what failed, and go back to the original on demand. */
class SubtitleTranslationTest {
    private val cues = listOf(Cue(0, 1_000, "Hello"), Cue(2_000, 3_000, "<i>Goodbye</i>"), Cue(4_000, 5_000, "Untranslatable"))

    private val source = object : SubtitleSource {
        override fun available() = true
        override suspend fun search(request: PlayRequest) = emptyList<SubtitleResult>()
        override suspend fun cues(result: SubtitleResult) = cues
    }

    private val french = Translator { texts, target ->
        assertEquals("fr", target)
        mapOf("Hello" to "Bonjour", "<i>Goodbye</i>" to "Au revoir").filterKeys { it in texts }
    }

    private fun chosen(translator: Translator?): OnlineSubtitles {
        val online = OnlineSubtitles(source, CoroutineScope(Dispatchers.Unconfined), translator)
        online.choose(sampleResult())
        return online
    }

    @Test
    fun translatesAndRestores() {
        val online = chosen(french)
        online.translate("fr")
        assertEquals("fr", online.translatedTo)
        assertFalse(online.translating)
        assertEquals("Bonjour", online.lineAt(500))
        assertEquals("Au revoir", online.lineAt(2_500))
        // A line the service did not translate stays as it was.
        assertEquals("Untranslatable", online.lineAt(4_500))

        online.translate(null)
        assertNull(online.translatedTo)
        assertEquals("Hello", online.lineAt(500))
    }

    @Test
    fun aFailedServiceKeepsTheOriginal() {
        val online = chosen(Translator { _, _ -> throw java.io.IOException("offline") })
        online.translate("fr")
        assertNull(online.translatedTo)
        assertEquals("Hello", online.lineAt(500))
    }

    @Test
    fun choosingAnotherFileDropsTheTranslation() {
        val online = chosen(french)
        online.translate("fr")
        online.choose(sampleResult())
        assertNull(online.translatedTo)
        assertEquals("Hello", online.lineAt(500))
    }
}

private fun sampleResult() = SubtitleResult("opensubtitles", "1", "en", "Sample", false)
