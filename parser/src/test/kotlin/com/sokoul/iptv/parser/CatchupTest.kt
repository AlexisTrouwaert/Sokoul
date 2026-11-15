package com.sokoul.iptv.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone

class CatchupTest {
    @Test
    fun `builds Xtream replay URL from the channel stream`() {
        val start = 1_800_000_000_000L
        val end = start + 30 * 60_000L
        val replay = Catchup.build(
            "xc", null, "https://provider.test/live/user/pass/42.ts", start, end, end + 10_000,
        )!!

        assertTrue(replay.startsWith("https://provider.test/timeshift/user/pass/30/"))
        assertTrue(replay.endsWith("/42.ts"))
    }

    @Test
    fun `fills declared M3U catchup template and rejects unknown format`() {
        val start = 1_800_000_000_000L
        val end = start + 25 * 60_000L
        val replay = Catchup.build(
            "default", "https://provider.test/replay?from={utc}&to={utcend}", "https://provider.test/live.m3u8", start, end, end,
        )!!

        assertEquals("https://provider.test/replay?from=1800000000&to=1800001500", replay)
        assertNull(Catchup.build("custom-unsupported", "template", "https://provider.test/live.m3u8", start, end, end))
    }

    @Test
    fun `Xtream timeshift supports an explicit provider timezone`() {
        val start = 1_800_000_000_000L
        val replay = XtreamUrls.timeshift("https://provider.test", "user", "pass", 42, start, 30, TimeZone.getTimeZone("UTC"))

        assertTrue(replay.startsWith("https://provider.test/timeshift/user/pass/30/"))
        assertTrue(replay.endsWith("/42.ts"))
    }

    @Test
    fun `Xtream archive placeholders use the supplied server timezone`() {
        val start = 1_800_000_000_000L
        val end = start + 30 * 60_000L
        val timezone = TimeZone.getTimeZone("America/Los_Angeles")
        val expected = SimpleDateFormat("yyyy-MM-dd:HH-mm").apply { timeZone = timezone }.format(Date(start))

        val replay = Catchup.build(
            "xc", null, "https://provider.test/live/user/pass/42.ts", start, end, end, timezone,
        )!!

        assertTrue(replay.contains("/30/$expected/42.ts"))
    }
}
