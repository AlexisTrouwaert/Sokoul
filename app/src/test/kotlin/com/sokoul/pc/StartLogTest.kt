package com.sokoul.pc

import com.sokoul.pc.playback.session.StartLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartLogTest {
    @Test fun aStartIsKeptAcrossRestartsWithItsOutcome() {
        val file = java.io.File.createTempFile("start-log", ".txt").apply { delete() }
        StartLog.file = { file }
        StartLog.begin("Dune : Deuxième partie", "+0 ms play movie 693134")
        StartLog.add("+300 ms try 4K · HDR · MULTI · Blu-ray · 13,7 GB (wait)")
        StartLog.add("+3507 ms first image")
        val last = StartLog.recent().first()
        assertTrue(last.played)
        assertEquals("3.5 s · 4K · HDR · MULTI · Blu-ray · 13,7 GB", last.outcome().replace(',', '.').replace("13.7", "13,7"))
        StartLog.begin("Pachinko · S1E1", "+0 ms play tv 110382 S1E1")
        StartLog.add("+15770 ms failed DebridLimit")
        assertFalse(StartLog.recent().first().played)
        assertEquals("failed DebridLimit", StartLog.recent().first().outcome())
    }
}
