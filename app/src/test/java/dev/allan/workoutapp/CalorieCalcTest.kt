package dev.allan.workoutapp

import dev.allan.workoutapp.data.CalorieCalc
import dev.allan.workoutapp.data.db.Exercise
import dev.allan.workoutapp.data.db.SetLog
import dev.allan.workoutapp.data.db.SetType
import dev.allan.workoutapp.data.db.ValueUnit
import dev.allan.workoutapp.data.db.WeightMode
import org.junit.Assert.assertEquals
import org.junit.Test

class CalorieCalcTest {

    private fun log(
        exerciseId: String = "e1",
        weightKg: Double = 0.0,
        value: Int = 10,
        unit: ValueUnit = ValueUnit.REPS,
        activeSecs: Int? = 60,
    ) = SetLog(
        sessionId = 1,
        workoutExerciseId = 1,
        exerciseId = exerciseId,
        setIndex = 0,
        type = SetType.NORMAL,
        weightKg = weightKg,
        weightMode = WeightMode.TOTAL,
        barWeightKg = 0.0,
        value = value,
        valueUnit = unit,
        activeSecs = activeSecs,
        completedAt = 0L,
    )

    private fun exercise(id: String = "e1", cardio: Boolean = false) = Exercise(
        id = id,
        category = null,
        primaryMuscles = emptyList(),
        secondaryMuscles = emptyList(),
        equipment = emptyList(),
        imageUrl = null,
        isCardio = cardio,
    )

    @Test
    fun `loaded set counts as vigorous resistance training`() {
        // 6 MET x 3.5 x 80 kg / 200 = 8.4 kcal/min
        val kcal = CalorieCalc.kcal(
            CalorieCalc.met(exercise(), log(weightKg = 40.0)),
            80.0,
            60,
        )
        assertEquals(8.4, kcal, 0.01)
    }

    @Test
    fun `unloaded rep set counts as light resistance training`() {
        val met = CalorieCalc.met(exercise(), log(weightKg = 0.0))
        assertEquals(3.5, met, 0.001)
    }

    @Test
    fun `unloaded timed set counts as mobility work`() {
        val met = CalorieCalc.met(exercise(), log(weightKg = 0.0, unit = ValueUnit.SECS, value = 40))
        assertEquals(2.3, met, 0.001)
    }

    @Test
    fun `cardio exercise wins over the load heuristic`() {
        val met = CalorieCalc.met(exercise(cardio = true), log(weightKg = 0.0))
        assertEquals(5.0, met, 0.001)
    }

    @Test
    fun `session total adds rest at the recovery rate`() {
        // One 60 s loaded set (8.4 kcal) + 120 s rest at 1.5 MET
        // (1.5 x 3.5 x 80 / 200 = 2.1 kcal/min x 2 min = 4.2).
        val total = CalorieCalc.sessionKcal(
            logs = listOf(log(weightKg = 40.0)),
            exercises = mapOf("e1" to exercise()),
            bodyweightKg = 80.0,
            restSecs = 120,
        )
        assertEquals(12.6, total, 0.01)
    }

    @Test
    fun `logs without per-set seconds split the session fallback`() {
        // Two logs, no per-set timing, 120 s booked active -> 60 s each at 3.5 MET.
        val total = CalorieCalc.sessionKcal(
            logs = listOf(log(activeSecs = null), log(activeSecs = null)),
            exercises = mapOf("e1" to exercise()),
            bodyweightKg = 80.0,
            restSecs = 0,
            activeFallbackSecs = 120,
        )
        assertEquals(3.5 * 3.5 * 80.0 / 200.0 * 2, total, 0.01)
    }

    @Test
    fun `no bodyweight means no energy at all`() {
        assertEquals(0.0, CalorieCalc.kcal(6.0, 0.0, 600), 0.0001)
    }
}
