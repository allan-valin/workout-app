package dev.allan.workoutapp.ui.plans

import dev.allan.workoutapp.R

/**
 * "Suggest a split" generator. Pure function: days/week -> workout skeletons.
 * Day numbers are ISO (1=Mon..7=Sun). Users edit everything afterwards.
 */
data class SplitDay(val labelRes: Int, val suffix: String = "", val isoDay: Int, val isCardio: Boolean = false)

object SplitWizard {

    fun generate(daysPerWeek: Int): List<SplitDay> = when (daysPerWeek.coerceIn(1, 7)) {
        1 -> listOf(SplitDay(R.string.split_full_body, isoDay = 1))
        2 -> listOf(
            SplitDay(R.string.split_full_body, " A", 1),
            SplitDay(R.string.split_full_body, " B", 4),
        )
        3 -> listOf(
            SplitDay(R.string.split_push, isoDay = 1),
            SplitDay(R.string.split_pull, isoDay = 3),
            SplitDay(R.string.split_legs, isoDay = 5),
        )
        4 -> listOf(
            SplitDay(R.string.split_upper, " A", 1),
            SplitDay(R.string.split_lower, " A", 2),
            SplitDay(R.string.split_upper, " B", 4),
            SplitDay(R.string.split_lower, " B", 5),
        )
        5 -> listOf(
            SplitDay(R.string.split_push, isoDay = 1),
            SplitDay(R.string.split_pull, isoDay = 2),
            SplitDay(R.string.split_legs, isoDay = 3),
            SplitDay(R.string.split_upper, isoDay = 4),
            SplitDay(R.string.split_lower, isoDay = 5),
        )
        6 -> listOf(
            SplitDay(R.string.split_push, " A", 1),
            SplitDay(R.string.split_pull, " A", 2),
            SplitDay(R.string.split_legs, " A", 3),
            SplitDay(R.string.split_push, " B", 4),
            SplitDay(R.string.split_pull, " B", 5),
            SplitDay(R.string.split_legs, " B", 6),
        )
        else -> listOf(
            SplitDay(R.string.split_chest, isoDay = 1),
            SplitDay(R.string.split_back, isoDay = 2),
            SplitDay(R.string.split_cardio_core, " A", 3, isCardio = true),
            SplitDay(R.string.split_legs, isoDay = 4),
            SplitDay(R.string.split_shoulders, isoDay = 5),
            SplitDay(R.string.split_arms, isoDay = 6),
            SplitDay(R.string.split_cardio_core, " B", 7, isCardio = true),
        )
    }
}
