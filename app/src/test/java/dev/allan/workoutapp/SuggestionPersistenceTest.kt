package dev.allan.workoutapp

import dev.allan.workoutapp.data.ProgressionEngine
import dev.allan.workoutapp.data.db.SetType
import dev.allan.workoutapp.data.db.ValueUnit
import dev.allan.workoutapp.ui.session.SessionSet
import dev.allan.workoutapp.ui.session.applySuggestedSets
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Allan, 02/08: applying "+1" then swapping exercise (or backgrounding the app) lost the
 * change, because only the in-memory state was touched. The applied values now come from a
 * pure function so the numbers themselves are pinned down by a test; persistence is the
 * caller's job (saveDraft).
 */
class SuggestionPersistenceTest {

    private fun set(id: Long, index: Int, reps: Int = 12, weight: Double = 40.0, done: Boolean = false) =
        SessionSet(
            templateId = id,
            setIndex = index,
            type = SetType.NORMAL,
            weightKg = weight,
            value = reps,
            valueUnit = ValueUnit.REPS,
            restSecs = 60,
            targetMin = 12,
            targetMax = 14,
            done = done,
        )

    private val sets = listOf(set(1, 0), set(2, 1), set(3, 2))

    @Test
    fun `add rep applies the full surplus to every undone working set`() {
        val out = applySuggestedSets(
            sets,
            ProgressionEngine.Suggestion(ProgressionEngine.Kind.ADD_REP, repIncrement = 4),
        )
        assertEquals(listOf(16, 16, 16), out.map { it.value })
    }

    @Test
    fun `add weight raises the weight and resets reps to the target minimum`() {
        val out = applySuggestedSets(
            listOf(set(1, 0, reps = 14), set(2, 1, reps = 14)),
            ProgressionEngine.Suggestion(ProgressionEngine.Kind.ADD_WEIGHT, weightIncrementKg = 2.5),
        )
        assertEquals(listOf(42.5, 42.5), out.map { it.weightKg })
        assertEquals(listOf(12, 12), out.map { it.value })
    }

    @Test
    fun `drop weight lowers the weight, floors at zero and resets reps`() {
        val out = applySuggestedSets(
            listOf(set(1, 0, reps = 10, weight = 2.0)),
            ProgressionEngine.Suggestion(ProgressionEngine.Kind.DROP_WEIGHT, weightIncrementKg = 2.5),
        )
        assertEquals(0.0, out.single().weightKg, 0.001)
        assertEquals(12, out.single().value)
    }

    @Test
    fun `done sets are never touched`() {
        val out = applySuggestedSets(
            listOf(set(1, 0, reps = 12, done = true), set(2, 1, reps = 12)),
            ProgressionEngine.Suggestion(ProgressionEngine.Kind.ADD_REP, repIncrement = 2),
        )
        assertEquals(listOf(12, 14), out.map { it.value })
    }

    @Test
    fun `timed sets and warmups are never touched`() {
        val timed = set(1, 0).copy(valueUnit = ValueUnit.SECS, value = 45)
        val warmup = set(2, 1).copy(type = SetType.WARMUP)
        val out = applySuggestedSets(
            listOf(timed, warmup, set(3, 2)),
            ProgressionEngine.Suggestion(ProgressionEngine.Kind.ADD_REP, repIncrement = 3),
        )
        assertEquals(listOf(45, 12, 15), out.map { it.value })
    }
}
