package com.sokoul.pc

import com.sokoul.pc.data.HomeLayout
import com.sokoul.pc.data.MenuEntries
import com.sokoul.pc.data.PageKind
import com.sokoul.pc.data.PagePlans
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The home page as arranged by the user: order, hidden rows, new rows, and the menu that always keeps an entry. */
class HomeLayoutTest {
    private val plan = PagePlans.of(PageKind.Home)
    private val ids = plan.map { it.id }

    @Test
    fun theDefaultLayoutChangesNothing() {
        assertTrue(HomeLayout().isDefault)
        assertEquals(ids, HomeLayout().apply(plan).map { it.id })
        assertNull(HomeLayout().encode())
    }

    @Test
    fun hidingAndMovingRows() {
        val first = ids[0]
        val second = ids[1]
        val hidden = HomeLayout().toggle(first)
        assertEquals(ids.drop(1), hidden.apply(plan).map { it.id })
        // The editor still lists the hidden row, where it is.
        assertEquals(ids, hidden.listed(plan).map { it.id })
        assertEquals(ids, hidden.toggle(first).listed(plan).map { it.id })
        assertTrue(hidden.toggle(first).isDefault)

        val moved = HomeLayout().move(plan, second, -1)
        assertEquals(listOf(second, first) + ids.drop(2), moved.apply(plan).map { it.id })
        // Moving past either end stays in place.
        assertEquals(moved, moved.move(plan, second, -1))
        val last = ids.last()
        assertEquals(ids, HomeLayout().move(plan, last, 1).listed(plan).map { it.id })
    }

    @Test
    fun rowsThatAppearedOrWentAreHandled() {
        val saved = HomeLayout(order = listOf("gone", ids[2], ids[0]), hidden = setOf("also-gone"))
        val shown = saved.apply(plan).map { it.id }
        assertEquals(listOf(ids[2], ids[0]) + ids.filter { it != ids[2] && it != ids[0] }, shown)
        assertEquals(ids.size, shown.size)
    }

    @Test
    fun theLayoutSurvivesBeingStored() {
        val layout = HomeLayout().toggle(ids[3]).move(plan, ids[4], -2)
        assertEquals(layout, HomeLayout.decode(layout.encode()))
        assertEquals(HomeLayout(), HomeLayout.decode("not json"))
        assertEquals(HomeLayout(), HomeLayout.decode(null))
    }

    @Test
    fun theMenuKeepsAtLeastOneEntry() {
        val all = listOf("home", "library", "movies")
        var hidden = emptySet<String>()
        hidden = MenuEntries.toggle(hidden, all, "library")
        hidden = MenuEntries.toggle(hidden, all, "movies")
        assertEquals(setOf("library", "movies"), hidden)
        // The last one cannot be hidden.
        assertEquals(hidden, MenuEntries.toggle(hidden, all, "home"))
        assertEquals(listOf("home"), MenuEntries.visible(all, hidden))
        assertFalse("library" in MenuEntries.toggle(hidden, all, "library"))
    }
}
