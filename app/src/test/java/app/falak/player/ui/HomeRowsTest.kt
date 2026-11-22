package app.falak.player.ui

import app.falak.player.data.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 8.46: a shorter Home for whoever never changed its rows; whoever did keeps theirs. */
class HomeRowsTest {
    private val all = HomeRows.ALL.map { it.first }

    @Test
    fun untouchedHomeStartsWithYourOwnThings() {
        val shown = HomeRows.shown(Settings())
        assertEquals(listOf("continue", "favLive", "recentLive"), shown.take(3))
        assertFalse("popular" in shown)
        assertFalse("forThisHour" in shown)
        // Every row is still in the list (to turn on), each once.
        assertEquals(all.sorted(), HomeRows.order(Settings()).sorted())
    }

    @Test
    fun whoeverOnlyHidRowsKeepsTheOldOrder() {
        val s = Settings(homeHidden = listOf("sports"))
        assertEquals(all, HomeRows.order(s))
        assertEquals(all - "sports", HomeRows.shown(s))
    }

    @Test
    fun whoeverArrangedKeepsTheirOrder() {
        val s = Settings(homeRows = listOf("lists", "top"))
        assertEquals(listOf("lists", "top") + (all - "lists" - "top"), HomeRows.order(s))
        assertTrue("popular" in HomeRows.shown(s))
    }

    @Test
    fun changingOneRowChangesOnlyThatRow() {
        val hidTop = HomeRows.toggled(Settings(), "top")
        assertEquals(HomeRows.shown(Settings()) - "top", HomeRows.shown(hidTop))
        val shownPopular = HomeRows.toggled(Settings(), "popular")
        assertEquals(HomeRows.order(Settings()).filter { it != "forThisHour" }, HomeRows.shown(shownPopular))
        val moved = HomeRows.movedSettings(Settings(), "sports", -1)
        assertEquals(HomeRows.hidden(Settings()), HomeRows.hidden(moved))
        val before = HomeRows.shown(Settings())
        val at = before.indexOf("sports")
        assertEquals(before.toMutableList().apply { removeAt(at); add(at - 1, "sports") }, HomeRows.shown(moved))
    }
}
