package dev.allan.workoutapp

import dev.allan.workoutapp.data.db.SetType
import dev.allan.workoutapp.data.db.ValueUnit
import dev.allan.workoutapp.data.transfer.CsvExport
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Weight-evolution export, second cut (Allan, 30/09): one row per SET of each exercise, one
 * column per training day, cell = weight × reps — a single number per exercise said nothing
 * when every set has its own reps/weight, and bodyweight work (0 kg) progressed in reps only.
 */
class SetPivotTest {

    private fun p(name: String, date: String, set: Int, kg: Double, reps: Int, at: Long = 0,
                  type: SetType = SetType.NORMAL, unit: ValueUnit = ValueUnit.REPS,
                  start: String = "", end: String = "") =
        CsvExport.SetPoint(name, date, set, type, kg, reps, unit, at, start, end)

    /** Allan, 2026-10-06: start/completion clock times per set, to see where the idle time goes. */
    @Test
    fun `a cell carries the set's start and completion time when known`() {
        val csv = CsvExport.setPivot(
            listOf(
                p("Squat", "2026-09-01", 0, 80.0, 8, start = "21:06:12", end = "21:08:40"),
                p("Squat", "2026-09-01", 1, 80.0, 8, start = "", end = "21:12:00"),
            )
        )
        assertEquals(
            "exercise,set,2026-09-01\n" +
                "Squat,1,80 x 8 [21:06:12-21:08:40]\n" +
                "Squat,2,80 x 8 [?-21:12:00]\n",
            csv,
        )
    }

    @Test
    fun `row per set, column per day, weight x reps in the cell`() {
        val csv = CsvExport.setPivot(
            listOf(
                p("Squat", "2026-09-01", 0, 20.0, 12, type = SetType.WARMUP),
                p("Squat", "2026-09-01", 1, 80.0, 8),
                p("Squat", "2026-09-01", 2, 80.0, 7),
                p("Squat", "2026-09-08", 1, 82.5, 8),
                p("Squat", "2026-09-08", 2, 82.5, 6),
                p("Calf raise", "2026-09-01", 0, 0.0, 16),
                p("Calf raise", "2026-09-08", 0, 0.0, 46),
            )
        )
        assertEquals(
            "exercise,set,2026-09-01,2026-09-08\n" +
                "Calf raise,1,BW x 16,BW x 46\n" +
                "Squat,1 W,20 x 12,\n" +
                "Squat,2,80 x 8,82.5 x 8\n" +
                "Squat,3,80 x 7,82.5 x 6\n",
            csv,
        )
    }

    @Test
    fun `timed sets show seconds, a later log of the same day wins`() {
        val csv = CsvExport.setPivot(
            listOf(
                p("Plank", "2026-09-01", 0, 0.0, 60, at = 1, unit = ValueUnit.SECS),
                p("Plank", "2026-09-01", 0, 0.0, 75, at = 2, unit = ValueUnit.SECS),
                p("Farmer walk", "2026-09-01", 0, 24.0, 40, unit = ValueUnit.SECS),
            )
        )
        assertEquals(
            "exercise,set,2026-09-01\n" +
                "Farmer walk,1,24 x 40s\n" +
                "Plank,1,75s\n",
            csv,
        )
    }

    @Test
    fun `names with commas are quoted and empty input is just the header`() {
        assertEquals("exercise,set,2026-09-01\n\"Row, bent over\",1,40 x 10\n",
            CsvExport.setPivot(listOf(p("Row, bent over", "2026-09-01", 0, 40.0, 10))))
        assertEquals("exercise,set\n", CsvExport.setPivot(emptyList()))
    }
}
