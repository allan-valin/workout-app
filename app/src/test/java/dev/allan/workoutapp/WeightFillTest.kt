package dev.allan.workoutapp

import dev.allan.workoutapp.data.db.SetType
import dev.allan.workoutapp.data.db.ValueUnit
import dev.allan.workoutapp.ui.session.SessionSet
import dev.allan.workoutapp.ui.session.forwardFillWeight
import org.junit.Assert.assertEquals
import org.junit.Test

/** Forward-fill of a typed weight must not spread a warm-up weight over the working sets (Allan, 16/09). */
class WeightFillTest {

    private fun set(id: Long, kg: Double = 0.0, type: SetType = SetType.NORMAL, done: Boolean = false) =
        SessionSet(templateId = id, setIndex = id.toInt(), type = type, weightKg = kg, value = 10,
            valueUnit = ValueUnit.REPS, restSecs = 60, targetMin = 10, done = done)

    @Test
    fun `fills later empty sets of the same type only`() {
        val sets = listOf(set(0, type = SetType.WARMUP), set(1), set(2), set(3, type = SetType.DROP))
        val out = forwardFillWeight(sets, editedIndex = 0, weightKg = 20.0)
        assertEquals(listOf(20.0, 0.0, 0.0, 0.0), out.map { it.weightKg })
        val out2 = forwardFillWeight(out, editedIndex = 1, weightKg = 60.0)
        assertEquals(listOf(20.0, 60.0, 60.0, 0.0), out2.map { it.weightKg })
    }

    @Test
    fun `does not touch done or already-weighted sets or earlier ones`() {
        val sets = listOf(set(0, 50.0), set(1), set(2, 55.0), set(3, done = true))
        val out = forwardFillWeight(sets, editedIndex = 1, weightKg = 60.0)
        assertEquals(listOf(50.0, 60.0, 55.0, 0.0), out.map { it.weightKg })
    }

    @Test
    fun `clearing a weight does not propagate`() {
        val sets = listOf(set(0, 50.0), set(1))
        assertEquals(listOf(0.0, 0.0), forwardFillWeight(sets, 0, 0.0).map { it.weightKg })
    }
}
