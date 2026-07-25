package dev.allan.workoutapp

import dev.allan.workoutapp.ui.session.fmt
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The session clock lives in the TopAppBar title slot, which only gets the width the
 * navigation icon and actions leave behind. Minutes-only formatting grew without bound and
 * clipped the whole value out of view past 100 minutes (Allan, 26/07), so the format rolls
 * over to h:mm:ss at one hour and stays narrow.
 */
class SessionClockFormatTest {

    @Test
    fun `below an hour stays minutes and seconds`() {
        assertEquals("0:00", fmt(0))
        assertEquals("0:59", fmt(59))
        assertEquals("1:00", fmt(60))
        assertEquals("45:12", fmt(45 * 60 + 12))
    }

    @Test
    fun `the last second below an hour is still minutes and seconds`() {
        assertEquals("59:59", fmt(3599))
    }

    @Test
    fun `one hour rolls over to hours minutes seconds`() {
        assertEquals("1:00:00", fmt(3600))
        assertEquals("1:01:01", fmt(3661))
    }

    @Test
    fun `the reported case is narrow again`() {
        // 100 minutes 23 seconds used to render as "100:23" and clipped out of the bar.
        assertEquals("1:40:23", fmt(100 * 60 + 23))
    }

    @Test
    fun `minutes and seconds are zero padded past the hour`() {
        assertEquals("2:05:07", fmt(2 * 3600 + 5 * 60 + 7))
    }
}
