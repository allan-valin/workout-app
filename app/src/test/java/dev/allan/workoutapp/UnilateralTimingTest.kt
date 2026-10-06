package dev.allan.workoutapp

import dev.allan.workoutapp.data.SetTiming
import dev.allan.workoutapp.data.transfer.PlanTransfer
import dev.allan.workoutapp.ui.session.SessionExercise
import dev.allan.workoutapp.ui.session.SessionSet
import dev.allan.workoutapp.ui.session.estimateWorkoutSecs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Allan, 2026-10-06: "a toggle to suggest when an exercise is unilateral, meaning the
 * execution time is expected to be double, since you are doing it twice with same tempo."
 * Every estimate that scales with reps doubles; a measured stopwatch reading does not.
 */
class UnilateralTimingTest {

    @Test
    fun `estimates double for a unilateral exercise`() {
        assertEquals(70, SetTiming.defaultActiveSecs(10, "3-1-2-1"))
        assertEquals(140, SetTiming.defaultActiveSecs(10, "3-1-2-1", unilateral = true))
        assertEquals(80, SetTiming.defaultActiveSecs(10, "", unilateral = true))
        assertEquals(140, SetTiming.expectedSecs(10, "3-1-2-1", unilateral = true))
        // The forgot-to-log cap grows with the estimate too.
        assertEquals(300, SetTiming.capSecs(10, "3-1-2-1"))
        assertEquals(300, SetTiming.capSecs(10, "3-1-2-1", unilateral = true))
        assertEquals(560, SetTiming.capSecs(20, "3-1-2-1", unilateral = true)) // 2 × (7 s × 20 × 2 sides)
    }

    @Test
    fun `a measured reading is not doubled`() {
        assertEquals(55, SetTiming.measuredActiveSecs(60))
    }

    @Test
    fun `the workout estimate doubles the work of a unilateral exercise`() {
        fun ex(unilateral: Boolean) = SessionExercise(
            workoutExerciseId = 1, exerciseId = "wger:1", name = "Lunge",
            weightMode = dev.allan.workoutapp.data.db.WeightMode.TOTAL, barWeightKg = 20.0,
            imagePath = null, unilateral = unilateral,
            sets = listOf(
                SessionSet(
                    templateId = 1, setIndex = 0, type = dev.allan.workoutapp.data.db.SetType.NORMAL,
                    weightKg = 0.0, value = 10, valueUnit = dev.allan.workoutapp.data.db.ValueUnit.REPS,
                    restSecs = 60, targetMin = 10, tempo = "3-1-2-1",
                )
            ),
        )
        assertEquals(190, estimateWorkoutSecs(listOf(ex(false))))
        assertEquals(260, estimateWorkoutSecs(listOf(ex(true))))
    }

    @Test
    fun `plan files carry the flag and default to false`() {
        val file = """
            {"schema_version": 1, "plan": {"name": "T", "workouts": [{"name": "A", "exercises": [
              {"match": {"names": ["Lunge"]}, "unilateral": true, "sets": [{"type": "NORMAL", "value": 8}]},
              {"match": {"names": ["Squat"]}, "sets": [{"type": "NORMAL", "value": 8}]}
            ]}]}}
        """.trimIndent()
        val ex = (PlanTransfer.parse(file) as PlanTransfer.Parsed.PlanFile).plan.workouts[0].exercises
        assertTrue(ex[0].unilateral)
        assertFalse(ex[1].unilateral)
    }
}
