package dev.allan.workoutapp

import dev.allan.workoutapp.data.MuscleMap
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * "Full body B has no quads but says it targets it the most" (Allan, 29/08): the body map
 * counted one point per exercise per listed muscle, so three accessory lifts that merely
 * list quads as secondary outranked the muscle with the actual working sets.
 */
class MuscleMapTest {

    private val QUADS = 10
    private val CHEST = 4
    private val GLUTES = 8

    @Test
    fun `load is weighted by working sets and secondary counts half`() {
        val load = MuscleMap.muscleLoad(
            listOf(
                MuscleMap.Entry(primary = listOf(CHEST), secondary = emptyList(), sets = 4),
                MuscleMap.Entry(primary = listOf(GLUTES), secondary = listOf(QUADS), sets = 3),
                MuscleMap.Entry(primary = listOf(GLUTES), secondary = listOf(QUADS), sets = 3),
            )
        )
        assertEquals(4f, load[CHEST])
        assertEquals(6f, load[GLUTES])
        assertEquals(3f, load[QUADS])
    }

    @Test
    fun `an exercise with no working sets adds nothing`() {
        val load = MuscleMap.muscleLoad(listOf(MuscleMap.Entry(listOf(CHEST), emptyList(), sets = 0)))
        assertEquals(emptyMap<Int, Float>(), load)
    }

    @Test
    fun `top shares rank by load and sum over the whole load`() {
        val top = MuscleMap.topShares(mapOf(CHEST to 4f, GLUTES to 6f, QUADS to 3f, 99 to 1f), limit = 3)
        assertEquals(listOf(GLUTES, CHEST, QUADS), top.map { it.first })
        assertEquals(6f / 14f, top[0].second, 1e-6f)
        assertEquals(3f / 14f, top[2].second, 1e-6f)
    }

    @Test
    fun `top shares of nothing is empty`() {
        assertEquals(emptyList<Pair<Int, Float>>(), MuscleMap.topShares(emptyMap(), 3))
    }
}
