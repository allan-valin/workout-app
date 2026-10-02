package dev.allan.workoutapp.ui.session

/**
 * Forward-fill for a typed weight: later sets that are still 0 kg, undone and of the SAME
 * set type get the value, so one input covers the same-weight-all-sets case without a
 * warm-up weight leaking onto the working sets (Allan, 16/09).
 *
 * OWNS: this one pure rule, unit-tested (WeightFillTest). MUST NEVER: overwrite a weight
 * the user already typed (only 0 kg rows are filled) or touch a logged set.
 */
fun forwardFillWeight(sets: List<SessionSet>, editedIndex: Int, weightKg: Double): List<SessionSet> {
    val edited = sets.getOrNull(editedIndex) ?: return sets
    return sets.mapIndexed { i, s ->
        when {
            i == editedIndex -> s.copy(weightKg = weightKg)
            i > editedIndex && !s.done && s.weightKg == 0.0 && weightKg > 0.0 && s.type == edited.type ->
                s.copy(weightKg = weightKg)
            else -> s
        }
    }
}
