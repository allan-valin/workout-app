package dev.allan.workoutapp

import dev.allan.workoutapp.data.transfer.CsvExport
import org.junit.Assert.assertEquals
import org.junit.Test

/** "Export a table with the active cycle's set weight evolution" (Allan, 29/08). */
class WeightPivotTest {

    @Test
    fun `one row per exercise, one column per session date, top working weight in the cell`() {
        val points = listOf(
            CsvExport.WeightPoint("Squat", "2026-09-01", 80.0),
            CsvExport.WeightPoint("Squat", "2026-09-08", 82.5),
            CsvExport.WeightPoint("Bench", "2026-09-08", 60.0),
            CsvExport.WeightPoint("Bench", "2026-09-15", 62.5),
            // A second session on the same day keeps the heavier of the two.
            CsvExport.WeightPoint("Bench", "2026-09-15", 61.0),
        )
        val csv = CsvExport.weightPivot(points)
        assertEquals(
            "exercise,2026-09-01,2026-09-08,2026-09-15\n" +
                "Bench,,60.0,62.5\n" +
                "Squat,80.0,82.5,\n",
            csv,
        )
    }

    @Test
    fun `names with commas are quoted`() {
        val csv = CsvExport.weightPivot(listOf(CsvExport.WeightPoint("Row, bent over", "2026-09-01", 40.0)))
        assertEquals("exercise,2026-09-01\n\"Row, bent over\",40.0\n", csv)
    }

    @Test
    fun `empty input gives just the header`() {
        assertEquals("exercise\n", CsvExport.weightPivot(emptyList()))
    }
}
