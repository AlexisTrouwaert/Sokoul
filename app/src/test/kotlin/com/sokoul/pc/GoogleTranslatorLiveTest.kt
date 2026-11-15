package com.sokoul.pc

import com.sokoul.pc.data.providers.GoogleTranslator
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** The real translation service, with subtitle lines as the player sends them. Skipped unless SOKOUL_NETWORK_TESTS is set. */
class GoogleTranslatorLiveTest {
    private val translator = GoogleTranslator(OkHttpClient())

    @Test
    fun translatesRealSubtitleLinesIntoFrench() = runBlocking {
        assumeTrue("SOKOUL_NETWORK_TESTS not set", !System.getenv("SOKOUL_NETWORK_TESTS").isNullOrBlank())
        val lines = listOf(
            "Not even my head of security knows\nthis apartment. How did you find it?",
            "<i>You mustn't be afraid to dream a little bigger, darling.</i>",
            "Hello",
        )
        val out = translator.translate(lines, "fr")
        println("LIVE: " + out.values.joinToString(" | ").replace("\n", " / "))
        // Every line comes back, keyed by the original text, and none is left in English.
        assertEquals(lines.toSet(), out.keys)
        assertTrue(out.getValue("Hello").contains("Bonjour", ignoreCase = true) || out.getValue("Hello").contains("Salut", ignoreCase = true))
        assertTrue(out.values.none { it.contains("head of security") })
    }
}
