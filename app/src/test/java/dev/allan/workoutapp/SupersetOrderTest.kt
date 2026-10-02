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

    // ---- nextStepAfter: the marker follows the LAST LOGGED set (Allan, 29/08: A4) ----

    private fun done(sets: List<SessionSet>, vararg idx: Int) =
        sets.mapIndexed { i, s -> if (i in idx) s.copy(done = true) else s }

    @Test
    fun `after a skipped set the marker sits after the last logged one, not on the skipped`() {
        // A1 logged, A2/A3 skipped, B1 logged -> next is B2, not A2.
        val a = exercise("A", done(sets(3), 0))
        val b = exercise("B", done(sets(3), 0))
        val exs = listOf(a, b)
        assertEquals(1 to b.sets[1].templateId, SupersetOrder.nextStepAfter(exs, 1 to b.sets[0].templateId))
    }

    @Test
    fun `superset with one member skipped keeps the marker on the trained member`() {
        // A+B superset, A never logged, B1 logged -> next is B2 (A is being skipped).
        val a = exercise("A", sets(3))
        val b = exercise("B", done(sets(3), 0), superset = true)
        val exs = listOf(a, b)
        assertEquals(1 to b.sets[1].templateId, SupersetOrder.nextStepAfter(exs, 1 to b.sets[0].templateId))
    }

    @Test
    fun `superset with both members trained alternates as before`() {
        val a = exercise("A", done(sets(3), 0))
        val b = exercise("B", done(sets(3), 0), superset = true)
        val exs = listOf(a, b)
        assertEquals(0 to a.sets[1].templateId, SupersetOrder.nextStepAfter(exs, 1 to b.sets[0].templateId))
        // After A2 the partner's B2 is next.
        val exs2 = listOf(exercise("A", done(sets(3), 0, 1)).let { it }, b)
        val a2 = exs2[0]
        assertEquals(1 to exs2[1].sets[1].templateId, SupersetOrder.nextStepAfter(exs2, 0 to a2.sets[1].templateId))
    }

    @Test
    fun `skipped exercises come last, after the chains ahead`() {
        // A untouched, B all done (last logged B3), C untouched -> C1, then wrap to A1.
        val a = exercise("A", sets(2))
        val b = exercise("B", done(sets(3), 0, 1, 2))
        val c = exercise("C", sets(2))
        assertEquals(2 to c.sets[0].templateId, SupersetOrder.nextStepAfter(listOf(a, b, c), 1 to b.sets[2].templateId))
        val cDone = exercise("C", done(sets(2), 0, 1))
        assertEquals(0 to a.sets[0].templateId, SupersetOrder.nextStepAfter(listOf(a, b, cDone), 2 to cDone.sets[1].templateId))
    }

    @Test
    fun `skipped superset member is reached only when everything else is done`() {
        val a = exercise("A", sets(2))
        val b = exercise("B", done(sets(2), 0, 1), superset = true)
        assertEquals(0 to a.sets[0].templateId, SupersetOrder.nextStepAfter(listOf(a, b), 1 to b.sets[1].templateId))
    }

    @Test
    fun `nothing logged yet falls back to the first undone set`() {
        val a = exercise("A", sets(2))
        val b = exercise("B", sets(2))
        assertEquals(0 to a.sets[0].templateId, SupersetOrder.nextStepAfter(listOf(a, b), null))
    }

    @Test
    fun `everything done gives null`() {
        val a = exercise("A", done(sets(2), 0, 1))
        assertNull(SupersetOrder.nextStepAfter(listOf(a), 0 to a.sets[1].templateId))
    }

    // ---- 30/09 regression: the FIRST set of a superset must hand over to the partner ----

    @Test
    fun `after the first set of a pair the partner's first set is next, not the own second`() {
        // A1 logged, B untouched -> B1 (the partner has simply not had its turn yet).
        val a = exercise("A", done(sets(3), 0))
        val b = exercise("B", sets(3), superset = true)
        val exs = listOf(a, b)
        assertEquals(1 to b.sets[0].templateId, SupersetOrder.nextStepAfter(exs, 0 to a.sets[0].templateId))
    }

    @Test
    fun `a partner is only skipped once its turn was passed over`() {
        // A1 and A2 logged with B still untouched -> B was skipped, stay on A3.
        val a = exercise("A", done(sets(3), 0, 1))
        val b = exercise("B", sets(3), superset = true)
        val exs = listOf(a, b)
        assertEquals(0 to a.sets[2].templateId, SupersetOrder.nextStepAfter(exs, 0 to a.sets[1].templateId))
    }

    @Test
    fun `a full superset round trip alternates and ends on the partner's last set`() {
        var a = exercise("A", sets(2))
        var b = exercise("B", sets(2), superset = true)
        fun exs() = listOf(a, b)
        // A1 -> B1
        a = exercise("A", done(sets(2).let { a.sets }, 0))
        assertEquals(1 to b.sets[0].templateId, SupersetOrder.nextStepAfter(exs(), 0 to a.sets[0].templateId))
        // B1 -> A2
        b = b.copy(sets = done(b.sets, 0))
        assertEquals(0 to a.sets[1].templateId, SupersetOrder.nextStepAfter(exs(), 1 to b.sets[0].templateId))
        // A2 -> B2 (the partner's second set must not be skipped at the end)
        a = a.copy(sets = done(a.sets, 0, 1))
        assertEquals(1 to b.sets[1].templateId, SupersetOrder.nextStepAfter(exs(), 0 to a.sets[1].templateId))
        // B2 -> nothing left
        b = b.copy(sets = done(b.sets, 0, 1))
        assertNull(SupersetOrder.nextStepAfter(exs(), 1 to b.sets[1].templateId))
    }
}
