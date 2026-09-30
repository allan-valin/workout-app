package dev.allan.workoutapp

import dev.allan.workoutapp.data.db.SetType
import dev.allan.workoutapp.data.db.ValueUnit
import dev.allan.workoutapp.data.db.WeightMode
import dev.allan.workoutapp.ui.session.SessionExercise
import dev.allan.workoutapp.ui.session.SessionSet
import dev.allan.workoutapp.ui.session.cardioChainNext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** B2 (Allan, 29/08): on a cardio exercise, one timed set finishing starts the next one. */
class CardioChainTest {

    private fun set(id: Long, unit: ValueUnit = ValueUnit.SECS, done: Boolean = false) =
        SessionSet(templateId = id, setIndex = id.toInt(), type = SetType.NORMAL, weightKg = 0.0, value = 60,
            valueUnit = unit, restSecs = 60, targetMin = 60, done = done)

    private fun ex(sets: List<SessionSet>, cardio: Boolean) = SessionExercise(
        workoutExerciseId = 1, exerciseId = "e", name = "Elliptical", weightMode = WeightMode.TOTAL,
        barWeightKg = 0.0, imagePath = null, sets = sets, isCardio = cardio,
    )

    @Test
    fun `next undone timed set of the same cardio exercise`() {
        val e = ex(listOf(set(1, done = true), set(2), set(3)), cardio = true)
        assertEquals(0 to e.sets[1], cardioChainNext(listOf(e), 1))
    }

    @Test
    fun `rep sets are skipped, timed ones chain`() {
        val e = ex(listOf(set(1, done = true), set(2, unit = ValueUnit.REPS), set(3)), cardio = true)
        assertEquals(0 to e.sets[2], cardioChainNext(listOf(e), 1))
    }

    @Test
    fun `nothing after the last set`() {
        val e = ex(listOf(set(1, done = true), set(2, done = true)), cardio = true)
        assertNull(cardioChainNext(listOf(e), 2))
    }

    @Test
    fun `strength exercises never chain`() {
        val e = ex(listOf(set(1, done = true), set(2)), cardio = false)
        assertNull(cardioChainNext(listOf(e), 1))
    }

    @Test
    fun `unknown template gives nothing`() {
        val e = ex(listOf(set(1)), cardio = true)
        assertNull(cardioChainNext(listOf(e), 99))
    }
}
