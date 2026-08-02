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

    /**
     * Changed 02/08: waiting for two sessions was too slow (Allan), so one uniform session
     * at the ceiling already suggests the weight step. The old test asserted null here.
     */
    @Test
    fun `single uniform session at ceiling is enough`() {
        val history = (0..2).map { log(1, it, 12) }
        val s = ProgressionEngine.suggest(templates, history, chest, WeightMode.TOTAL)
        assertEquals(ProgressionEngine.Kind.ADD_WEIGHT, s?.kind)
    }

    /**
     * Changed 02/08: only the last session is read now, so a weight change since the session
     * before it is irrelevant — the increment simply follows the weight actually used last.
     * Mixed weights *within* one session are still refused (see the test below).
     */
    @Test
    fun `weight changed between sessions follows the latest weight`() {
        val history = (0..2).map { log(2, it, 12, weight = 42.5) } + (0..2).map { log(1, it, 12) }
        val s = ProgressionEngine.suggest(templates, history, chest, WeightMode.TOTAL)
        assertEquals(ProgressionEngine.Kind.ADD_WEIGHT, s?.kind)
        assertEquals(
            ProgressionEngine.incrementFor(42.5, chest, WeightMode.TOTAL),
            s!!.weightIncrementKg,
            1e-9,
        )
    }

    @Test
    fun `inside the range suggests one more rep`() {
        val history = (0..2).map { log(1, it, 10) }
        val s = ProgressionEngine.suggest(templates, history, chest, WeightMode.TOTAL)
        assertEquals(ProgressionEngine.Kind.ADD_REP, s?.kind)
    }

    @Test
    fun `a spread of rep counts suggests nothing`() {
        val history = listOf(log(1, 0, 10), log(1, 1, 9), log(1, 2, 8))
        assertNull(ProgressionEngine.suggest(templates, history, chest, WeightMode.TOTAL))
    }

    @Test
    fun `incomplete last session suggests nothing`() {
        val history = listOf(log(1, 0, 12), log(1, 1, 12))
        assertNull(ProgressionEngine.suggest(templates, history, chest, WeightMode.TOTAL))
    }

    /**
     * Changed 02/08: without an explicit range max the rep suggestion runs up to target + 4
     * (Allan: "if I manage 16 reps in a 12 set, suggest +4"); only past that does it become
     * a weight step. The old ceiling was target + 2.
     */
    @Test
    fun `no explicit range keeps adding reps up to target plus four`() {
        val fixed = listOf(template(0, min = 10, max = null))

        val atCeiling = listOf(log(1, 0, 14))
        val ceilingSuggestion = ProgressionEngine.suggest(fixed, atCeiling, chest, WeightMode.TOTAL)
        assertEquals(ProgressionEngine.Kind.ADD_REP, ceilingSuggestion?.kind)
        assertEquals(4, ceilingSuggestion!!.repIncrement)

        val beyond = listOf(log(1, 0, 15))
        assertEquals(
            ProgressionEngine.Kind.ADD_WEIGHT,
            ProgressionEngine.suggest(fixed, beyond, chest, WeightMode.TOTAL)?.kind,
        )

        val inside = listOf(log(1, 0, 11))
        val insideSuggestion = ProgressionEngine.suggest(fixed, inside, chest, WeightMode.TOTAL)
        assertEquals(ProgressionEngine.Kind.ADD_REP, insideSuggestion?.kind)
        assertEquals(1, insideSuggestion!!.repIncrement)
    }

    // ── 02/08 rules: one uniform session is enough, and the rep suggestion carries the
    // real surplus. A session with a spread of rep counts says nothing yet.

    private fun uniform(session: Long, reps: Int, weight: Double = 40.0) =
        listOf(log(session, 0, reps, weight), log(session, 1, reps, weight), log(session, 2, reps, weight))

    private val range14to16 = listOf(template(0, 14, 16), template(1, 14, 16), template(2, 14, 16))

    @Test
    fun `range max reached suggests weight`() {
        val s = ProgressionEngine.suggest(range14to16, uniform(1, 16), chest, WeightMode.TOTAL)
        assertEquals(ProgressionEngine.Kind.ADD_WEIGHT, s!!.kind)
    }

    @Test
    fun `inside range suggests one rep`() {
        val s = ProgressionEngine.suggest(range14to16, uniform(1, 15), chest, WeightMode.TOTAL)
        assertEquals(ProgressionEngine.Kind.ADD_REP, s!!.kind)
        assertEquals(1, s.repIncrement)
    }

    @Test
    fun `below floor suggests dropping weight`() {
        val s = ProgressionEngine.suggest(range14to16, uniform(1, 12), chest, WeightMode.TOTAL)
        assertEquals(ProgressionEngine.Kind.DROP_WEIGHT, s!!.kind)
        assertEquals(
            ProgressionEngine.incrementFor(40.0, chest, WeightMode.TOTAL),
            s.weightIncrementKg,
            1e-9,
        )
    }

    @Test
    fun `fixed target overshoot suggests the real surplus`() {
        val fixed = listOf(template(0, 12, null), template(1, 12, null), template(2, 12, null))
        val s = ProgressionEngine.suggest(fixed, uniform(1, 16), chest, WeightMode.TOTAL)
        assertEquals(ProgressionEngine.Kind.ADD_REP, s!!.kind)
        assertEquals(4, s.repIncrement)
    }

    @Test
    fun `fixed target beyond plus four suggests weight`() {
        val fixed = listOf(template(0, 12, null), template(1, 12, null), template(2, 12, null))
        val s = ProgressionEngine.suggest(fixed, uniform(1, 17), chest, WeightMode.TOTAL)
        assertEquals(ProgressionEngine.Kind.ADD_WEIGHT, s!!.kind)
    }

    @Test
    fun `mixed rep counts suggest nothing`() {
        val history = listOf(log(1, 0, 12), log(1, 1, 14), log(1, 2, 14))
        assertNull(ProgressionEngine.suggest(range14to16, history, chest, WeightMode.TOTAL))
    }

    @Test
    fun `mixed weights inside one session suggest nothing`() {
        val history = listOf(log(1, 0, 16, 40.0), log(1, 1, 16, 42.5), log(1, 2, 16, 40.0))
        assertNull(ProgressionEngine.suggest(range14to16, history, chest, WeightMode.TOTAL))
    }

    @Test
    fun `only the newest session counts`() {
        // Older session below the floor, newest one at the top: the newest wins.
        val history = uniform(2, 16) + uniform(1, 10)
        val s = ProgressionEngine.suggest(range14to16, history, chest, WeightMode.TOTAL)
        assertEquals(ProgressionEngine.Kind.ADD_WEIGHT, s!!.kind)
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
