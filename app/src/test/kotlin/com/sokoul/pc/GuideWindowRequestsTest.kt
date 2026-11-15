package com.sokoul.pc

import com.sokoul.pc.live.GuideWindowKey
import com.sokoul.pc.live.GuideWindowRequests
import org.junit.Assert.*
import org.junit.Test

class GuideWindowRequestsTest {
    private val first = GuideWindowKey(listOf("channel-a"), 0, 100)

    @Test fun late_results_cannot_replace_a_new_group() {
        val owner = GuideWindowRequests()
        val old = owner.begin(first)!!
        val next = first.copy(channelIds = listOf("channel-b"))
        val current = owner.begin(next)!!
        var visible = ""
        assertTrue(owner.deliver(current, next) { visible = "new group" })
        assertFalse(owner.deliver(old, first) { visible = "obsolete group" })
        assertEquals("new group", visible)
    }

    @Test fun closing_a_menu_reuses_the_delivered_window() {
        val owner = GuideWindowRequests()
        val ticket = owner.begin(first)!!
        assertTrue(owner.deliver(ticket, first) {})
        owner.invalidate()
        assertNull(owner.begin(first))
        assertNotNull(owner.begin(first.copy(window = 1)))
    }

    @Test fun opening_a_menu_invalidates_pending_work_but_allows_a_retry() {
        val owner = GuideWindowRequests()
        val old = owner.begin(first)!!
        owner.invalidate()
        assertFalse(owner.deliver(old, first) { fail("covered guide changed") })
        val retry = owner.begin(first)!!
        assertTrue(owner.deliver(retry, first) {})
    }
}
