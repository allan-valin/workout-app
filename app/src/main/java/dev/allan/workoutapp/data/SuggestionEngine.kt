package dev.allan.workoutapp.data

import dev.allan.workoutapp.data.db.AppDatabase

/** Workout focus a suggestion fills; mirrors the split-wizard day types. */
enum class SuggestionFocus {
    FULL_BODY, PUSH, PULL, LEGS, UPPER, LOWER, CHEST, BACK, SHOULDERS, ARMS, CARDIO_CORE,
}

/**
 * Auto workout suggestions: picks exercises from the local library per focus recipe.
 * Injured muscles are respected (primary or secondary hit excluded). Suggestions only
 * append — existing exercises in the workout stay and are never duplicated.
 */
object SuggestionEngine {

    /** Sentinel muscle id meaning "pick from the cardio pool" (wger ids are positive). */
    const val CARDIO = -1

    // wger muscle ids: 1 biceps, 2 shoulders, 4 chest, 5 triceps, 6 abs, 7 calves,
    // 8 glutes, 9 trapezius, 10 quads, 11 hamstrings, 12 lats, 14 obliques.
    val recipes: Map<SuggestionFocus, List<Pair<Int, Int>>> = mapOf(
        SuggestionFocus.FULL_BODY to listOf(4 to 1, 12 to 1, 2 to 1, 10 to 1, 11 to 1, 6 to 1),
        SuggestionFocus.PUSH to listOf(4 to 2, 2 to 2, 5 to 1),
        SuggestionFocus.PULL to listOf(12 to 2, 9 to 1, 1 to 2),
        SuggestionFocus.LEGS to listOf(10 to 2, 11 to 2, 8 to 1, 7 to 1),
        SuggestionFocus.UPPER to listOf(4 to 1, 12 to 1, 2 to 1, 1 to 1, 5 to 1),
        SuggestionFocus.LOWER to listOf(10 to 2, 11 to 1, 8 to 1, 7 to 1),
        SuggestionFocus.CHEST to listOf(4 to 3, 2 to 1, 5 to 1),
        SuggestionFocus.BACK to listOf(12 to 3, 9 to 1, 1 to 1),
        SuggestionFocus.SHOULDERS to listOf(2 to 3, 9 to 1),
        SuggestionFocus.ARMS to listOf(1 to 2, 5 to 2),
        SuggestionFocus.CARDIO_CORE to listOf(CARDIO to 2, 6 to 2, 14 to 1),
    )

    /**
     * Scales a muscle order to exactly [total] exercises by cycling round-robin
     * (e.g. PUSH recipe chest/chest/shoulders/shoulders/triceps at total=3 →
     * chest, shoulders, triceps).
     */
    fun scaledCounts(order: List<Pair<Int, Int>>, total: Int): List<Pair<Int, Int>> {
        val counts = LinkedHashMap<Int, Int>()
        var remaining = total.coerceIn(1, 30)
        while (remaining > 0) {
            for ((muscleId, cap) in order) {
                if (remaining == 0) break
                val soFar = counts[muscleId] ?: 0
                // First pass respects recipe proportions; extra passes ignore caps.
                if (soFar < cap || counts.size == order.size) {
                    counts[muscleId] = soFar + 1
                    remaining--
                }
            }
            if (counts.values.sum() == 0) break
        }
        return counts.toList()
    }

    fun scaledRecipe(focus: SuggestionFocus, total: Int): List<Pair<Int, Int>> =
        scaledCounts(recipes.getValue(focus), total)

    /** Union of several focus recipes (muscle caps summed, first-seen order kept). */
    fun mergedRecipe(foci: Collection<SuggestionFocus>): List<Pair<Int, Int>> {
        val merged = LinkedHashMap<Int, Int>()
        foci.forEach { focus ->
            recipes.getValue(focus).forEach { (id, cap) -> merged[id] = (merged[id] ?: 0) + cap }
        }
        return merged.toList()
    }

    /**
     * Appends suggested exercises to the workout. Returns how many were added.
     * [total] = exact number of exercises to add; null uses the recipe's default counts.
     */
    suspend fun fillWorkout(
        db: AppDatabase,
        workoutId: Long,
        focus: SuggestionFocus,
        injured: Set<Int>,
        total: Int? = null,
    ): Int {
        val recipe = if (total == null) recipes.getValue(focus) else scaledRecipe(focus, total)
        return fillWorkoutByMuscles(db, workoutId, recipe, injured)
    }

    /**
     * Appends [counts] (muscleId → how many) exercises to the workout.
     * [compound] steers full-body picks: true prefers multi-muscle exercises,
     * false prefers isolation (no secondary muscles), null doesn't care.
     */
    suspend fun fillWorkoutByMuscles(
        db: AppDatabase,
        workoutId: Long,
        counts: List<Pair<Int, Int>>,
        injured: Set<Int>,
        compound: Boolean? = null,
    ): Int {
        val already = db.planDao().workoutExercisesList(workoutId).map { it.exerciseId }.toMutableSet()
        var added = 0
        for ((muscleId, count) in counts) {
            if (muscleId in injured) continue
            val pool = if (muscleId == CARDIO) {
                db.exerciseDao().cardioHits()
            } else {
                db.exerciseDao().search("", null, "%,$muscleId,%")
                    .filter { muscleId in it.primaryMuscles }
            }
            val safe = pool.filter { hit ->
                hit.id !in already &&
                    hit.primaryMuscles.none(injured::contains) &&
                    hit.secondaryMuscles.none(injured::contains)
            }
            // Prefer illustrated exercises; compound/isolation preference second;
            // shuffle a small head pool for variety per tap.
            val ranked = safe.sortedWith(
                compareByDescending<dev.allan.workoutapp.data.db.ExerciseHit> { it.imageUrl != null }
                    .thenByDescending { hit ->
                        val muscles = (hit.primaryMuscles + hit.secondaryMuscles).distinct().size
                        when (compound) {
                            true -> muscles          // more muscle groups first
                            false -> -muscles        // isolation first
                            null -> 0
                        }
                    }
            )
            val picks = ranked.take(12).shuffled().take(count)
            for (hit in picks) {
                PlanRepo.addExerciseToWorkout(db, workoutId, hit.id)
                already += hit.id
                added++
            }
        }
        return added
    }
}
