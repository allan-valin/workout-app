package dev.allan.workoutapp

import dev.allan.workoutapp.data.ProgressionEngine
import dev.allan.workoutapp.data.db.SetLog
import dev.allan.workoutapp.data.db.SetTemplate
import dev.allan.workoutapp.data.db.SetType
import dev.allan.workoutapp.data.db.ValueUnit
import dev.allan.workoutapp.data.db.WeightMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProgressionEngineTest {

    private val chest = listOf(4)
    private val quads = listOf(10)

    private fun template(index: Int, min: Int = 10, max: Int? = 12) = SetTemplate(
        id = index.toLong() + 1,
        workoutExerciseId = 1,
        setIndex = index,
        type = SetType.NORMAL,
        targetWeightKg = 40.0,
        targetValue = min,
        targetValueMax = max,
        valueUnit = ValueUnit.REPS,
    )

    private fun log(session: Long, index: Int, reps: Int, weight: Double = 40.0, at: Long = session * 1000) =
        SetLog(
            sessionId = session,
            workoutExerciseId = 1,
            exerciseId = "wger:1",
            setIndex = index,
            type = SetType.NORMAL,
            weightKg = weight,
            weightMode = WeightMode.TOTAL,
            barWeightKg = 20.0,
            value = reps,
            valueUnit = ValueUnit.REPS,
            completedAt = at + index,
        )

    private val templates = listOf(template(0), template(1), template(2))

    @Test
    fun `two sessions at top of range with same weight suggest weight increase`() {
        val history = (0..2).map { log(2, it, 12) } + (0..2).map { log(1, it, 12) }
        val s = ProgressionEngine.suggest(templates, history, chest, WeightMode.TOTAL)
        assertEquals(ProgressionEngine.Kind.ADD_WEIGHT, s?.kind)
        // 40 kg upper body: 2.5% = 1.0 → rounded to the 1.25 plate step.
        assertEquals(1.25, s!!.weightIncrementKg, 1e-9)
    }

    @Test
    fun `lower body gets a bigger increment`() {
        val history = (0..2).map { log(2, it, 12, weight = 100.0) } +
            (0..2).map { log(1, it, 12, weight = 100.0) }
        val s = ProgressionEngine.suggest(templates, history, quads, WeightMode.TOTAL)
        assertEquals(ProgressionEngine.Kind.ADD_WEIGHT, s?.kind)
        assertEquals(5.0, s!!.weightIncrementKg, 1e-9) // 5% of 100
    }

    @Test
    fun `single session at ceiling is not enough`() {
        val history = (0..2).map { log(1, it, 12) }
        assertNull(ProgressionEngine.suggest(templates, history, chest, WeightMode.TOTAL))
    }

    @Test
    fun `weight changed between sessions blocks weight suggestion`() {
        val history = (0..2).map { log(2, it, 12, weight = 42.5) } + (0..2).map { log(1, it, 12) }
        // Ceiling hit at a new weight → no ADD_WEIGHT (and no ADD_REP: already at top).
        assertNull(ProgressionEngine.suggest(templates, history, chest, WeightMode.TOTAL))
    }

    @Test
    fun `inside the range suggests one more rep`() {
        val history = (0..2).map { log(1, it, 10) }
        val s = ProgressionEngine.suggest(templates, history, chest, WeightMode.TOTAL)
        assertEquals(ProgressionEngine.Kind.ADD_REP, s?.kind)
    }

    @Test
    fun `below the range suggests nothing`() {
        val history = listOf(log(1, 0, 10), log(1, 1, 9), log(1, 2, 8))
        assertNull(ProgressionEngine.suggest(templates, history, chest, WeightMode.TOTAL))
    }

    @Test
    fun `incomplete last session suggests nothing`() {
        val history = listOf(log(1, 0, 12), log(1, 1, 12))
        assertNull(ProgressionEngine.suggest(templates, history, chest, WeightMode.TOTAL))
    }

    @Test
    fun `no explicit range falls back to target plus two`() {
        val fixed = listOf(template(0, min = 10, max = null))
        val atCeiling = listOf(log(2, 0, 12), log(1, 0, 12))
        val s = ProgressionEngine.suggest(fixed, atCeiling, chest, WeightMode.TOTAL)
        assertEquals(ProgressionEngine.Kind.ADD_WEIGHT, s?.kind)

        val inside = listOf(log(1, 0, 11))
        assertEquals(
            ProgressionEngine.Kind.ADD_REP,
            ProgressionEngine.suggest(fixed, inside, chest, WeightMode.TOTAL)?.kind,
        )
    }

    @Test
    fun `timed and warmup sets never trigger suggestions`() {
        val timed = listOf(
            template(0).copy(valueUnit = ValueUnit.SECS),
            template(1).copy(type = SetType.WARMUP),
        )
        val history = listOf(log(1, 0, 60), log(1, 1, 12))
        assertNull(ProgressionEngine.suggest(timed, history, chest, WeightMode.TOTAL))
    }
}
