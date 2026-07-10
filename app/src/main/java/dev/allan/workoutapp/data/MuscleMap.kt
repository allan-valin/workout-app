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

    /**
     * Load per muscle id across [exercises] (each = primary ids to secondary ids). Primary counts
     * 1.0, secondary 0.5 — so more/heavier-targeted muscles shade deeper. Muscles without a wger
     * overlay are dropped (nothing to draw).
     */
    fun muscleLoad(exercises: List<Pair<List<Int>, List<Int>>>): Map<Int, Float> {
        val acc = mutableMapOf<Int, Float>()
        exercises.forEach { (primary, secondary) ->
            primary.forEach { if (it in DRAWABLE) acc[it] = (acc[it] ?: 0f) + 1f }
            secondary.forEach { if (it in DRAWABLE) acc[it] = (acc[it] ?: 0f) + 0.5f }
        }
        return acc
    }
}
