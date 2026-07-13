package dev.allan.workoutapp

import dev.allan.workoutapp.ui.stats.DAY_MS
import dev.allan.workoutapp.ui.stats.Granularity
import dev.allan.workoutapp.ui.stats.SeriesAggregate
import dev.allan.workoutapp.ui.stats.StatsRange
import dev.allan.workoutapp.ui.stats.bucket
import dev.allan.workoutapp.ui.stats.gridStep
import dev.allan.workoutapp.ui.stats.xTicks
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class TimeSeriesChartTest {

    private fun ms(date: LocalDate) = date.toEpochDay() * DAY_MS

    // --- bucket ---

    @Test
    fun `day granularity keeps one point per day and averages`() {
        val day = LocalDate.of(2026, 7, 13)
        val points = listOf(
            ms(day) + 1_000L to 80.0,
            ms(day) + 2_000L to 82.0,
            ms(day.minusDays(1)) to 81.0,
        )
        val out = bucket(points, Granularity.DAY, SeriesAggregate.MEAN)
        assertEquals(2, out.size)
        assertEquals(81.0, out[0].second, 1e-9)
        assertEquals(81.0, out[1].second, 1e-9)
        // Bucket x = latest sample of the day.
        assertEquals(ms(day) + 2_000L, out[1].first)
    }

    @Test
    fun `week granularity starts weeks on monday and sums`() {
        val monday = LocalDate.of(2026, 7, 13) // a Monday
        val sunday = monday.minusDays(1)
        val points = listOf(
            ms(sunday) to 100.0,
            ms(monday) to 10.0,
            ms(monday.plusDays(6)) to 5.0,
        )
        val out = bucket(points, Granularity.WEEK, SeriesAggregate.SUM)
        assertEquals(2, out.size)
        assertEquals(100.0, out[0].second, 1e-9)
        assertEquals(15.0, out[1].second, 1e-9)
    }

    @Test
    fun `month granularity groups by calendar month`() {
        val points = listOf(
            ms(LocalDate.of(2026, 6, 30)) to 1.0,
            ms(LocalDate.of(2026, 7, 1)) to 2.0,
            ms(LocalDate.of(2026, 7, 31)) to 4.0,
        )
        val out = bucket(points, Granularity.MONTH, SeriesAggregate.SUM)
        assertEquals(2, out.size)
        assertEquals(1.0, out[0].second, 1e-9)
        assertEquals(6.0, out[1].second, 1e-9)
    }

    // --- gridStep ---

    @Test
    fun `grid step scales with the value spread`() {
        assertEquals(0.5, gridStep(0.0), 1e-9)
        assertEquals(0.5, gridStep(2.0), 1e-9)
        assertEquals(1.0, gridStep(4.0), 1e-9)
        assertEquals(2.0, gridStep(8.0), 1e-9)
        assertEquals(5.0, gridStep(20.0), 1e-9)
        assertEquals(10.0, gridStep(45.0), 1e-9)
        assertEquals(500.0, gridStep(2_400.0), 1e-9)
        assertEquals(50_000.0, gridStep(1_000_000.0), 1e-9)
    }

    // --- xTicks ---

    @Test
    fun `week ticks label every day with today rightmost`() {
        val today = LocalDate.of(2026, 7, 13)
        val ticks = xTicks(StatsRange.WEEK, ms(today.minusDays(6)), today)
        assertEquals(7, ticks.size)
        assertEquals(ms(today), ticks.first().first)
        assertEquals("13", ticks.first().second)
        assertEquals("7", ticks.last().second)
    }

    @Test
    fun `month ticks step back seven days from today`() {
        val today = LocalDate.of(2026, 7, 13)
        val ticks = xTicks(StatsRange.MONTH, ms(today.minusDays(29)), today)
        assertEquals(listOf("13/7", "6/7", "29/6", "22/6", "15/6"), ticks.map { it.second })
    }

    @Test
    fun `three month ticks are monthly without today`() {
        val today = LocalDate.of(2026, 7, 13)
        val ticks = xTicks(StatsRange.THREE_MONTHS, ms(today.minusDays(91)), today)
        assertEquals(listOf("13/6", "13/5", "13/4"), ticks.map { it.second })
    }

    @Test
    fun `six month ticks use two month steps`() {
        val today = LocalDate.of(2026, 7, 13)
        val ticks = xTicks(StatsRange.SIX_MONTHS, ms(today.minusDays(182)), today)
        assertEquals(listOf("13/5", "13/3", "13/1"), ticks.map { it.second })
    }

    @Test
    fun `year ticks use three month steps`() {
        val today = LocalDate.of(2026, 7, 13)
        val ticks = xTicks(StatsRange.YEAR, ms(today.minusDays(365)), today)
        assertEquals(listOf("13/4", "13/1", "13/10", "13/7"), ticks.map { it.second })
    }

    @Test
    fun `all range with long history labels calendar years`() {
        val today = LocalDate.of(2026, 7, 13)
        val ticks = xTicks(StatsRange.ALL, ms(LocalDate.of(2023, 5, 1)), today)
        assertEquals(listOf("2026", "2025", "2024"), ticks.map { it.second })
    }

    @Test
    fun `all range with short history falls back to quarterly ticks`() {
        val today = LocalDate.of(2026, 7, 13)
        val ticks = xTicks(StatsRange.ALL, ms(today.minusMonths(7)), today)
        assertEquals(listOf("13/4", "13/1"), ticks.map { it.second })
    }
}
