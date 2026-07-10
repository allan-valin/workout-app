package dev.allan.workoutapp.data

/**
 * Maps wger's (stable) muscle ids to coarse body regions we can draw, and turns a set of
 * exercises into a per-region training load. EXPERIMENTAL — the drawn body is a first pass
 * (see ui/common/BodyMap.kt); regions are approximate, not anatomical overlays.
 *
 * wger has no sub-muscle granularity (flat ~15-muscle list), so a region == one wger muscle
 * or a small merge (deltoids show on both views, calves front+back, etc.).
 */
enum class BodyRegion(val front: Boolean) {
    SHOULDERS_F(true), CHEST(true), BICEPS(true), ABS(true), OBLIQUES(true),
    QUADS(true), CALVES_F(true),
    TRAPS(false), LATS(false), TRICEPS(false), GLUTES(false),
    HAMSTRINGS(false), CALVES_B(false), SHOULDERS_B(false),
}

object MuscleMap {

    /** wger muscle id -> the region(s) it lights up. Ids are stable across wger releases. */
    private val muscleToRegions: Map<Int, List<BodyRegion>> = mapOf(
        1 to listOf(BodyRegion.BICEPS),                              // Biceps brachii
        2 to listOf(BodyRegion.SHOULDERS_F, BodyRegion.SHOULDERS_B), // Anterior deltoid
        3 to listOf(BodyRegion.CHEST),                              // Serratus anterior
        4 to listOf(BodyRegion.CHEST),                              // Pectoralis major
        5 to listOf(BodyRegion.TRICEPS),                            // Triceps brachii
        6 to listOf(BodyRegion.ABS),                                // Rectus abdominis
        7 to listOf(BodyRegion.CALVES_F, BodyRegion.CALVES_B),      // Gastrocnemius
        8 to listOf(BodyRegion.GLUTES),                             // Gluteus maximus
        9 to listOf(BodyRegion.TRAPS),                              // Trapezius
        10 to listOf(BodyRegion.QUADS),                             // Quadriceps femoris
        11 to listOf(BodyRegion.HAMSTRINGS),                        // Biceps femoris
        12 to listOf(BodyRegion.LATS),                              // Latissimus dorsi
        13 to listOf(BodyRegion.BICEPS),                            // Brachialis
        14 to listOf(BodyRegion.OBLIQUES),                          // Obliques
        15 to listOf(BodyRegion.CALVES_F, BodyRegion.CALVES_B),     // Soleus
    )

    /**
     * Region training load across [exercises] (each = primary ids to secondary ids). Primary
     * muscle counts 1.0, secondary 0.5 — so more/heavier-targeted regions get deeper shading.
     */
    fun regionLoad(exercises: List<Pair<List<Int>, List<Int>>>): Map<BodyRegion, Float> {
        val acc = mutableMapOf<BodyRegion, Float>()
        exercises.forEach { (primary, secondary) ->
            primary.forEach { m -> muscleToRegions[m]?.forEach { acc[it] = (acc[it] ?: 0f) + 1f } }
            secondary.forEach { m -> muscleToRegions[m]?.forEach { acc[it] = (acc[it] ?: 0f) + 0.5f } }
        }
        return acc
    }
}
