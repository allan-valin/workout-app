package dev.allan.workoutapp

import dev.allan.workoutapp.data.transfer.CsvExport
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Allan, 2026-10-06: 30 min of idle per session even without stopping between exercises —
 * the sets export gets each set's start and the unexplained gap before it, so the
 * spreadsheet shows where the time goes (setting up the machine, most likely).
 */
class SetGapTest {

    @Test
    fun `start is completion minus active time, or completion when nothing was booked`() {
        assertEquals(1_000_000L - 40_000L, CsvExport.startedAt(completedAt = 1_000_000L, activeSecs = 40))
        assertEquals(1_000_000L, CsvExport.startedAt(completedAt = 1_000_000L, activeSecs = null))
    }

    @Test
    fun `gap is the time between the previous rest ending and this set starting`() {
        // previous set done at t=0, rested 60 s, this one started at 150 s → 90 s unexplained.
        assertEquals(90, CsvExport.gapBeforeSecs(startedAt = 150_000L, prevCompletedAt = 0L, prevRestSecs = 60))
        // no rest booked → the whole span is a gap
        assertEquals(150, CsvExport.gapBeforeSecs(startedAt = 150_000L, prevCompletedAt = 0L, prevRestSecs = null))
        // overlap (rest longer than the span) never goes negative
        assertEquals(0, CsvExport.gapBeforeSecs(startedAt = 30_000L, prevCompletedAt = 0L, prevRestSecs = 60))
    }
}
