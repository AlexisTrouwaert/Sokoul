package com.sokoul.pc

import com.sokoul.pc.party.PartyPlayback
import com.sokoul.pc.party.PartySyncEngine
import org.junit.Assert.*
import org.junit.Test

class PartySyncEngineTest {
    private fun calibrated(offset: Long = 5_000) = PartySyncEngine().apply {
        setMode("pro")
        repeat(3) { n -> assertTrue(probe(10_000L+n*100,15_020L+n*100+(offset-5_000),15_020L+n*100+(offset-5_000),10_040L+n*100)) }
    }

    @Test fun clockSkewUsesServerTimestampInsteadOfReceiptTime() {
        val sync = calibrated()
        assertEquals(5000.0,sync.offsetMs,0.001)
        sync.ingest(PartyPlayback(true,10.0,10_000,14_000))
        assertTrue(sync.tick(true,11.0,10_000).none)
    }

    @Test fun probesRejectInvalidTimesAndKeepOnlyFiveSamples() {
        val sync = PartySyncEngine()
        assertFalse(sync.probe(100,105,106,99))
        assertFalse(sync.probe(100,105,999,110))
        assertFalse(sync.probe(100,105,106,10_000))
        assertFalse(sync.calibrated)
        repeat(5) { assertTrue(sync.probe(100,120,120,140)) }
        assertTrue(sync.probe(100,220,220,140))
        assertEquals(20.0,sync.offsetMs,.001)
    }

    @Test fun futureCommandsWaitAndStaleSnapshotsCannotUndoThem() {
        val sync = calibrated()
        sync.ingest(PartyPlayback(false,5.0,10_000,15_000))
        assertTrue(sync.schedule("play",20.0,15_250,15_000,10_000))
        assertTrue(sync.tick(false,5.0,10_249).none)
        assertFalse(sync.ingest(PartyPlayback(false,5.0,10_100,15_100)))
        val due = sync.tick(false,5.0,10_250)
        assertEquals(true,due.setPlaying)
        assertEquals(20.0,due.seekToSec!!,.001)
    }

    @Test fun slightDriftAdjustsRateAndLargeDriftSeeksWithCooldown() {
        val sync = calibrated()
        sync.ingest(PartyPlayback(true,10.0,10_000,15_000))
        assertEquals(1.04,sync.tick(true,9.5,10_000).playbackRate!!,.001)
        assertEquals(1.0,sync.tick(true,10.25,10_250).playbackRate!!,.001)
        assertNotNull(sync.tick(true,0.0,10_500).seekToSec)
        assertNull(sync.tick(true,0.0,10_750).seekToSec)
        assertNotNull(sync.tick(true,0.0,11_750).seekToSec)
    }

    @Test fun unsupportedServerRetainsClassicThresholdAndInvalidNumbersAreIgnored() {
        val sync = PartySyncEngine().apply { setMode("pro") }
        assertFalse(sync.ingest(PartyPlayback(true,Double.NaN,0)))
        sync.ingest(PartyPlayback(true,100.0,0))
        assertTrue(sync.tick(true,101.5,0).none)
        assertNotNull(sync.tick(true,50.0,0).seekToSec)
        assertTrue(sync.tick(true,Double.POSITIVE_INFINITY,1000).none)
        assertFalse(sync.schedule("unknown",0.0,1000,1000,1000))
    }

    @Test fun resetDropsRoomAndCalibrationAndZeroSkewStillUsesServerTime() {
        val sync = calibrated(0)
        sync.ingest(PartyPlayback(true,10.0,10_500,10_000))
        assertTrue(sync.tick(true,10.5,10_500).none)
        sync.reset()
        assertFalse(sync.pro)
        assertFalse(sync.calibrated)
        assertTrue(sync.tick(true,0.0,0).none)
    }
}
