package dev.allan.workoutapp

import dev.allan.workoutapp.data.StatsCalc
import dev.allan.workoutapp.data.db.SetLog
import dev.allan.workoutapp.data.db.SetType
import dev.allan.workoutapp.data.db.ValueUnit
import dev.allan.workoutapp.data.db.WeightMode
import org.junit.Assert.assertEquals
import org.junit.Test

class StatsCalcTest {

    private fun log(
        weight: Double,
        mode: WeightMode = WeightMode.TOTAL,
        bar: Double = 20.0,
        value: Int = 10,
        unit: ValueUnit = ValueUnit.REPS,
        exercise: String = "wger:1",
    ) = SetLog(
        sessionId = 1, workoutExerciseId = 1, exerciseId = exercise, setIndex = 0,
        type = SetType.NORMAL, weightKg = weight, weightMode = mode, barWeightKg = bar,
        value = value, valueUnit = unit, completedAt = 0,
    )

    @Test
    fun `total mode volume is weight times reps`() {
        assertEquals(200.0, StatsCalc.volumeKg(log(20.0)), 1e-9)
    }

    @Test
    fun `per dumbbell doubles the entered weight`() {
        assertEquals(400.0, StatsCalc.volumeKg(log(20.0, WeightMode.PER_DUMBBELL)), 1e-9)
    }

    @Test
    fun `per side adds bar plus twice the side weight`() {
        // bar 20 + 2×20 = 60 effective, ×10 reps = 600
        assertEquals(600.0, StatsCalc.volumeKg(log(20.0, WeightMode.PER_SIDE, bar = 20.0)), 1e-9)
    }

    @Test
    fun `timed sets carry no volume`() {
        assertEquals(0.0, StatsCalc.volumeKg(log(20.0, unit = ValueUnit.SECS)), 1e-9)
    }

    @Test
    fun `volume attributes to the primary muscle`() {
        // Allan's spec example: 2 chest sets + 1 quad set, all 10 reps × 1 kg
        val logs = listOf(
            log(1.0, exercise = "chest"), log(1.0, exercise = "chest"), log(1.0, exercise = "quads"),
        )
        val muscles = mapOf("chest" to listOf(4), "quads" to listOf(10))
        val perMuscle = StatsCalc.volumePerMuscle(logs) { muscles[it] ?: emptyList() }
        assertEquals(20.0, perMuscle[4]!!, 1e-9)
        assertEquals(10.0, perMuscle[10]!!, 1e-9)
        assertEquals(30.0, logs.sumOf(StatsCalc::volumeKg), 1e-9)
    }
}
