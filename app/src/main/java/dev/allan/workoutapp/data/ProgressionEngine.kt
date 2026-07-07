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
 * Rules, based on the ACSM progression position stand ("increase load 2–10% when the
 * current workload can be performed 1–2 reps over target on consecutive sessions") and
 * the NSCA 2-for-2 rule; load- and rep-progression are equally effective, so the user
 * picks which suggestion to take:
 *
 * 1. ADD_WEIGHT — the last two sessions used the same weight AND every working set hit
 *    the top of the rep range both times. Increment: ~2.5% (upper body) / ~5% (lower
 *    body) of the current weight, rounded to 1.25 kg plates, at least one plate step.
 * 2. ADD_REP — the last session hit the bottom of the range on every working set but
 *    not yet the top: add one rep before adding weight.
 *
 * Only REPS sets of type NORMAL / FAILURE count ("working sets"). Timed sets, warmups
 * and drops never trigger suggestions. Without an explicit range (targetValueMax null)
 * the fixed target + 2 acts as the ceiling (2-for-2).
 */
object ProgressionEngine {

    enum class Kind { ADD_WEIGHT, ADD_REP }

    data class Suggestion(val kind: Kind, val weightIncrementKg: Double = 0.0)

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

        // Split history into sessions, newest first; keep only working-set logs.
        val sessions = history
            .filter { it.valueUnit == ValueUnit.REPS && it.setIndex in workingIndexes }
            .groupBy { it.sessionId }
            .values
            .sortedByDescending { logs -> logs.maxOf { it.completedAt } }
        if (sessions.isEmpty()) return null

        val last = sessions[0]
        if (last.size < working.size) return null // exercise not completed last time

        fun ceilingFor(setIndex: Int): Int {
            val t = working.first { it.setIndex == setIndex }
            return t.targetValueMax ?: (t.targetValue + 2)
        }

        fun floorFor(setIndex: Int): Int = working.first { it.setIndex == setIndex }.targetValue

        val lastAtCeiling = last.all { it.value >= ceilingFor(it.setIndex) }
        val lastWeight = last.maxOf { it.weightKg }

        if (lastAtCeiling && sessions.size >= 2) {
            val prev = sessions[1]
            val prevAtCeiling = prev.size >= working.size &&
                prev.all { it.value >= ceilingFor(it.setIndex) }
            val sameWeight = prev.maxOf { it.weightKg } == lastWeight
            if (prevAtCeiling && sameWeight) {
                return Suggestion(
                    Kind.ADD_WEIGHT,
                    incrementFor(lastWeight, primaryMuscles, weightMode),
                )
            }
        }
        if (!lastAtCeiling && last.all { it.value >= floorFor(it.setIndex) }) {
            return Suggestion(Kind.ADD_REP)
        }
        return null
    }
}
