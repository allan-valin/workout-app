package dev.allan.workoutapp.data

import dev.allan.workoutapp.data.db.SetLog
import dev.allan.workoutapp.data.db.SetTemplate
import dev.allan.workoutapp.data.db.SetType
import dev.allan.workoutapp.data.db.ValueUnit
import dev.allan.workoutapp.data.db.WeightMode

/**
 * A "profile" of an exercise = the way it is programmed: its set pattern (sets × reps or range,
 * in order, warm-ups marked, seconds for timed sets), its cadence, how the weight is read and
 * whether it is one side at a time. DERIVED from the set templates — there is no profile table,
 * nothing to create or migrate (Allan, 18/10). Session history is shared between every use of an
 * exercise with the same profile (two workouts, a new cycle's import) and kept apart otherwise,
 * so "3×10" never prefills "1×14 1×12 2×10" (docs/FEEDBACK_BATCH_2026-10-17.md G2/G3/G4).
 *
 * OWNS: the key, the label, member selection and the "last time" hint text — pure, unit-tested
 * (ExerciseProfileTest). MUST NEVER: touch the database.
 */
object ExerciseProfile {

    data class Key(val label: String, val weightMode: WeightMode, val unilateral: Boolean)

    /** "3×10", "1×14 1×12 2×10", "W2×12 3×8–12", "3×60s". */
    fun pattern(templates: List<SetTemplate>): String {
        val tokens = templates.sortedBy { it.setIndex }.map { t ->
            val scheme = when (t.valueUnit) {
                ValueUnit.SECS -> "${t.targetValue}s"
                ValueUnit.REPS -> t.targetValueMax?.let { "${t.targetValue}–$it" } ?: "${t.targetValue}"
            }
            (if (t.type == SetType.WARMUP) "W" else "") to scheme
        }
        val out = mutableListOf<String>()
        var i = 0
        while (i < tokens.size) {
            var j = i
            while (j + 1 < tokens.size && tokens[j + 1] == tokens[i]) j++
            out += "${tokens[i].first}${j - i + 1}×${tokens[i].second}"
            i = j + 1
        }
        return out.joinToString(" ")
    }

    fun key(templates: List<SetTemplate>, weightMode: WeightMode, unilateral: Boolean): Key {
        val tempo = templates.sortedBy { it.setIndex }.firstOrNull { it.tempo.isNotBlank() }?.tempo
        val label = pattern(templates) + (tempo?.let { " @$it" } ?: "")
        return Key(label, weightMode, unilateral)
    }

    /** Workout-exercise ids whose profile equals [selfKey]; [selfId] always first. */
    fun members(selfId: Long, selfKey: Key, others: List<Pair<Long, Key>>): List<Long> =
        listOf(selfId) + others.filter { it.first != selfId && it.second == selfKey }.map { it.first }

    /**
     * "3×12: 20 kg × 12, 20 kg × 11" — the newest session among [logs] (any order), one entry
     * per set, so the two profiles can be compared without switching workouts (G3).
     */
    fun lastTimeHint(otherLabel: String, logs: List<SetLog>): String? {
        val newest = logs.maxByOrNull { it.completedAt }?.sessionId ?: return null
        val sets = logs.filter { it.sessionId == newest }.sortedBy { it.setIndex }.joinToString(", ") { l ->
            val w = if (l.weightKg == 0.0) "BW" else "${fmt(l.weightKg)} kg"
            val v = if (l.valueUnit == ValueUnit.SECS) "${l.value}s" else "${l.value}"
            "$w × $v"
        }
        return "$otherLabel: $sets"
    }

    private fun fmt(d: Double) = if (d % 1.0 == 0.0) d.toInt().toString() else d.toString()
}
