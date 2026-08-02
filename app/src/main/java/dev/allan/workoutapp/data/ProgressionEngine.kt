package dev.allan.workoutapp.data

import dev.allan.workoutapp.data.db.SetLog
import dev.allan.workoutapp.data.db.SetTemplate
import dev.allan.workoutapp.data.db.SetType
import dev.allan.workoutapp.data.db.ValueUnit
import dev.allan.workoutapp.data.db.WeightMode
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Double-progression suggestions, never auto-applied (docs/PROGRESSION.md for sources).
 *
 * Grounded in the ACSM progression position stand ("increase load 2–10% when the current
 * workload can be performed 1–2 reps over target") and the NSCA 2-for-2 rule; load- and
 * rep-progression are equally effective, so the user picks which suggestion to take.
 *
 * Rules as of 02/08 (docs/FEEDBACK_BATCH_2026-08-02.md, B2). One finished session is
 * enough — the suggestion shows at the next workout — but only when that session was
 * UNIFORM: every working set logged, all at the same rep count, all at the same weight.
 * A spread (12/14/14) means the exercise hasn't settled, so nothing is suggested.
 *
 * With a uniform session at reps R, weight W and floor F = targetValue:
 *  - explicit range max M: R >= M          -> ADD_WEIGHT
 *  - no range max:         R >  F + 4      -> ADD_WEIGHT
 *  - R < F                                 -> DROP_WEIGHT (one increment step down)
 *  - otherwise                             -> ADD_REP by max(1, R - F)
 *
 * Increment: ~2.5% (upper body) / ~5% (lower body) of the current weight, rounded to
 * 1.25 kg plates, at least one plate step.
 *
 * Only REPS sets of type NORMAL / FAILURE count ("working sets"). Timed sets, warmups
 * and drops never trigger suggestions.
 */
object ProgressionEngine {

    enum class Kind { ADD_WEIGHT, ADD_REP, DROP_WEIGHT }

    data class Suggestion(
        val kind: Kind,
        val weightIncrementKg: Double = 0.0,
        /** Reps to add for ADD_REP — the real surplus over the target, at least 1. */
        val repIncrement: Int = 0,
    )

    /** wger muscle ids trained by big lower-body lifts — these take bigger jumps. */
    private val lowerBodyMuscles = setOf(7, 8, 10, 11, 15)

    fun plateRound(kg: Double): Double = (kg / 1.25).roundToInt() * 1.25

    fun incrementFor(weightKg: Double, primaryMuscles: List<Int>, weightMode: WeightMode): Double {
        val lower = primaryMuscles.any(lowerBodyMuscles::contains)
        val pct = if (lower) 0.05 else 0.025
        val raw = plateRound(weightKg * pct)
        val minStep = if (weightMode == WeightMode.PER_DUMBBELL) 1.25 else if (lower) 2.5 else 1.25
        return max(raw, minStep)
    }

    /**
     * @param templates current set templates of the exercise (define range + working sets)
     * @param history finished-session logs for this workoutExercise, newest first
     *        (SessionDao.previousLogs order), any number of sessions mixed together
     */
    fun suggest(
        templates: List<SetTemplate>,
        history: List<SetLog>,
        primaryMuscles: List<Int>,
        weightMode: WeightMode,
    ): Suggestion? {
        val working = templates.filter {
            it.valueUnit == ValueUnit.REPS && (it.type == SetType.NORMAL || it.type == SetType.FAILURE)
        }
        if (working.isEmpty()) return null
        val workingIndexes = working.map { it.setIndex }.toSet()

        // Only the newest session is read: waiting for two in a row was too slow (Allan, 02/08).
        val last = history
            .filter { it.valueUnit == ValueUnit.REPS && it.setIndex in workingIndexes }
            .groupBy { it.sessionId }
            .values
            .maxByOrNull { logs -> logs.maxOf { it.completedAt } }
            ?: return null

        if (last.size < working.size) return null            // exercise not completed
        val reps = last.first().value
        if (last.any { it.value != reps }) return null        // rep counts not uniform
        val weight = last.first().weightKg
        if (last.any { it.weightKg != weight }) return null   // weights not uniform

        val floor = working.minOf { it.targetValue }
        val rangeMax = working.mapNotNull { it.targetValueMax }.maxOrNull()
        val step = incrementFor(weight, primaryMuscles, weightMode)

        return when {
            rangeMax != null && reps >= rangeMax -> Suggestion(Kind.ADD_WEIGHT, step)
            rangeMax == null && reps > floor + 4 -> Suggestion(Kind.ADD_WEIGHT, step)
            reps < floor -> Suggestion(Kind.DROP_WEIGHT, step)
            else -> Suggestion(Kind.ADD_REP, repIncrement = maxOf(1, reps - floor))
        }
    }
}
