package dev.allan.workoutapp

import dev.allan.workoutapp.data.db.SetType
import dev.allan.workoutapp.data.db.ValueUnit
import dev.allan.workoutapp.data.db.WeightMode
import dev.allan.workoutapp.ui.session.SessionExercise
import dev.allan.workoutapp.ui.session.SessionSet
import dev.allan.workoutapp.ui.session.SupersetOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SupersetOrderTest {

    private var nextTemplateId = 1L

    private fun sets(count: Int, done: Int = 0): List<SessionSet> = (0 until count).map { i ->
        SessionSet(
            templateId = nextTemplateId++,
            setIndex = i,
            type = SetType.NORMAL,
            weightKg = 40.0,
            value = 10,
            valueUnit = ValueUnit.REPS,
            restSecs = 90,
            targetMin = 10,
            done = i < done,
        )
    }

    private fun exercise(name: String, sets: List<SessionSet>, superset: Boolean = false) =
        SessionExercise(
            workoutExerciseId = name.hashCode().toLong(),
            exerciseId = name,
            name = name,
            weightMode = WeightMode.TOTAL,
            barWeightKg = 20.0,
            imagePath = null,
            sets = sets,
            supersetWithPrev = superset,
        )

    @Test
    fun `unpaired exercises are singleton chains`() {
        val exs = listOf(exercise("A", sets(3)), exercise("B", sets(3)))
        assertEquals(listOf(0), SupersetOrder.chain(exs, 0))
        assertEquals(listOf(1), SupersetOrder.chain(exs, 1))
    }

    @Test
    fun `paired exercises share one chain from either end`() {
        val exs = listOf(exercise("A", sets(3)), exercise("B", sets(3), superset = true))
        assertEquals(listOf(0, 1), SupersetOrder.chain(exs, 0))
        assertEquals(listOf(0, 1), SupersetOrder.chain(exs, 1))
    }

    @Test
    fun `interleave alternates rounds A1 B1 A2 B2`() {
        val a = exercise("A", sets(2))
        val b = exercise("B", sets(2), superset = true)
        val order = SupersetOrder.interleaved(listOf(a, b), listOf(0, 1))
        assertEquals(
            listOf(0 to 0, 1 to 0, 0 to 1, 1 to 1),
            order.map { (i, s) -> i to s.setIndex },
        )
    }

    @Test
    fun `next step follows the interleaved order`() {
        // A1 done -> expected next is B1, not A2.
        val a = exercise("A", sets(2, done = 1))
        val b = exercise("B", sets(2), superset = true)
        val exs = listOf(a, b)
        val next = SupersetOrder.nextStep(exs)
        assertEquals(1, next!!.first)
        assertEquals(b.sets[0].templateId, next.second)
    }

    @Test
    fun `next step is null when everything is done`() {
        val exs = listOf(exercise("A", sets(2, done = 2)))
        assertNull(SupersetOrder.nextStep(exs))
    }

    @Test
    fun `nextStepFrom stays on the current exercise while it has open sets`() {
        // A skipped entirely, B has 1 of 3 done -> next is B's set 2, not A.
        val a = exercise("A", sets(3))
        val b = exercise("B", sets(3, done = 1))
        val exs = listOf(a, b)
        val next = SupersetOrder.nextStepFrom(exs, 1)
        assertEquals(1, next!!.first)
        assertEquals(b.sets[1].templateId, next.second)
    }

    @Test
    fun `nextStepFrom moves forward before wrapping to skipped exercises`() {
        // A skipped, B done, C untouched -> from B the next is C, not A.
        val a = exercise("A", sets(2))
        val b = exercise("B", sets(2, done = 2))
        val c = exercise("C", sets(2))
        val next = SupersetOrder.nextStepFrom(listOf(a, b, c), 1)
        assertEquals(2, next!!.first)
        assertEquals(c.sets[0].templateId, next.second)
    }

    @Test
    fun `nextStepFrom wraps to a skipped exercise when nothing is left ahead`() {
        // A skipped, B and C done -> from C wrap back to A.
        val a = exercise("A", sets(2))
        val b = exercise("B", sets(2, done = 2))
        val c = exercise("C", sets(2, done = 2))
        val next = SupersetOrder.nextStepFrom(listOf(a, b, c), 2)
        assertEquals(0, next!!.first)
        assertEquals(a.sets[0].templateId, next.second)
    }

    @Test
    fun `nextStepFrom respects superset interleaving within the current chain`() {
        // A1 done, superset partner B1 undone -> from A the next is B1.
        val a = exercise("A", sets(2, done = 1))
        val b = exercise("B", sets(2), superset = true)
        val next = SupersetOrder.nextStepFrom(listOf(a, b), 0)
        assertEquals(1, next!!.first)
        assertEquals(b.sets[0].templateId, next.second)
    }

    @Test
    fun `nextStepFrom is null when everything is done`() {
        val exs = listOf(exercise("A", sets(2, done = 2)), exercise("B", sets(1, done = 1)))
        assertNull(SupersetOrder.nextStepFrom(exs, 1))
    }

    @Test
    fun `rest is skipped after the first half of a pair and taken after the second`() {
        val a = exercise("A", sets(2))
        val b = exercise("B", sets(2), superset = true)
        val exs = listOf(a, b)
        // After logging A set 1 (B set 1 still undone) -> no rest.
        assertTrue(SupersetOrder.restSkipped(exs, 0, a.sets[0]))
        // After logging B set 1 -> rest (round complete).
        assertFalse(SupersetOrder.restSkipped(exs, 1, b.sets[0]))
    }

    @Test
    fun `unpaired exercise always rests`() {
        val a = exercise("A", sets(2))
        assertFalse(SupersetOrder.restSkipped(listOf(a), 0, a.sets[0]))
    }
}
