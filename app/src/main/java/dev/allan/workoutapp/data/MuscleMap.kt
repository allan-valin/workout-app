package dev.allan.workoutapp.data

/**
 * Turns a set of exercises into a per-muscle training load, keyed by wger's (stable) muscle id.
 * Drives the anatomical body map (ui/common/BodyMap.kt), which overlays wger's own per-muscle
 * SVGs on the front/back body art. wger has no sub-muscle granularity (flat ~15-muscle list).
 */
object MuscleMap {

    /** wger muscle ids that live on the FRONT body view (is_front = true in the snapshot). */
    val FRONT_IDS = setOf(1, 2, 3, 4, 6, 10, 13, 14)

    /** wger muscle ids on the BACK view. */
    val BACK_IDS = setOf(5, 7, 8, 9, 11, 12, 15)

    private val DRAWABLE = FRONT_IDS + BACK_IDS

    /** One exercise of a workout: its wger muscle ids and how many WORKING sets it has. */
    data class Entry(val primary: List<Int>, val secondary: List<Int>, val sets: Int)

    /**
     * Load per muscle id across [exercises], in working sets: a primary muscle gets the
     * exercise's set count, a secondary one half of it. Counting one point per exercise
     * regardless of sets let three accessory lifts that list quads as secondary outrank the
     * muscle actually being trained (Allan, 29/08: "Full body B has no quads but says it
     * targets it the most"). Muscles without a wger overlay are dropped (nothing to draw).
     */
    fun muscleLoad(exercises: List<Entry>): Map<Int, Float> {
        val acc = mutableMapOf<Int, Float>()
        exercises.forEach { e ->
            if (e.sets <= 0) return@forEach
            e.primary.forEach { if (it in DRAWABLE) acc[it] = (acc[it] ?: 0f) + e.sets }
            e.secondary.forEach { if (it in DRAWABLE) acc[it] = (acc[it] ?: 0f) + e.sets / 2f }
        }
        return acc
    }

    /** The [limit] heaviest muscles as (id, share of the total load), heaviest first. */
    fun topShares(load: Map<Int, Float>, limit: Int): List<Pair<Int, Float>> {
        val total = load.values.sum()
        if (total <= 0f) return emptyList()
        return load.entries.sortedByDescending { it.value }.take(limit).map { it.key to it.value / total }
    }
}
