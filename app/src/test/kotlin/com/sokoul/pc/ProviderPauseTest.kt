package com.sokoul.pc

import com.sokoul.pc.data.providers.ProviderHttp
import org.junit.Assert.assertEquals
import org.junit.Test

/** After "too many requests", the anime services come back when the service says, not an hour later. */
class ProviderPauseTest {
    @Test
    fun the_wait_follows_retry_after_within_bounds() {
        assertEquals(60_000L, ProviderHttp.busyPause("60"))
        assertEquals(60_000L, ProviderHttp.busyPause(null))
        assertEquals(60_000L, ProviderHttp.busyPause("soon"))
        assertEquals(1_000L, ProviderHttp.busyPause("0"))
        assertEquals(3_600_000L, ProviderHttp.busyPause("99999"))
    }
}
