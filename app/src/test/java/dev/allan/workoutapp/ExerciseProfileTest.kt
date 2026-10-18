package dev.allan.workoutapp

import dev.allan.workoutapp.data.ExerciseProfile
import dev.allan.workoutapp.data.db.SetLog
import dev.allan.workoutapp.data.db.SetTemplate
import dev.allan.workoutapp.data.db.SetType
import dev.allan.workoutapp.data.db.ValueUnit
import dev.allan.workoutapp.data.db.WeightMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * G2/G3/G4 (Allan, 09–18/10): history is per EXERCISE + PROFILE, the profile being derived from the
 * set pattern (sets × reps/range, in order, warm-ups marked), the cadence, the weight mode and the
 * unilateral flag. Same exercise with a different pattern keeps its own weights; the same pattern
 * in two workouts (or a new cycle's import) shares them.
 */
class ExerciseProfileTest {

    private fun t(idx: Int, v: Int, max: Int? = null, type: SetType = SetType.NORMAL, tempo: String = "", unit: ValueUnit = ValueUnit.REPS) =
        SetTemplate(id = idx.toLong(), workoutExerciseId = 1, setIndex = idx, type = type, targetWeightKg = 0.0,
            targetValue = v, targetValueMax = max, valueUnit = unit, restSecs = 60, tempo = tempo)

    @Test
    fun `pattern compresses equal consecutive sets`() {
        assertEquals("3×10", ExerciseProfile.pattern(listOf(t(0, 10), t(1, 10), t(2, 10))))
    }

    @Test
    fun `pattern keeps the order of different schemes`() {
        assertEquals("1×14 1×12 2×10", ExerciseProfile.pattern(listOf(t(0, 14), t(1, 12), t(2, 10), t(3, 10))))
    }

    @Test
    fun `pattern shows ranges, warm-ups and seconds`() {
        assertEquals("W2×12 3×8–12", ExerciseProfile.pattern(listOf(t(0, 12, type = SetType.WARMUP), t(1, 12, type = SetType.WARMUP), t(2, 8, 12), t(3, 8, 12), t(4, 8, 12))))
        assertEquals("3×60s", ExerciseProfile.pattern(listOf(t(0, 60, unit = ValueUnit.SECS), t(1, 60, unit = ValueUnit.SECS), t(2, 60, unit = ValueUnit.SECS))))
    }

    @Test
    fun `key includes cadence, weight mode and unilateral`() {
        val base = ExerciseProfile.key(listOf(t(0, 10), t(1, 10)), WeightMode.TOTAL, unilateral = false)
        assertEquals(base, ExerciseProfile.key(listOf(t(0, 10), t(1, 10)), WeightMode.TOTAL, unilateral = false))
        assertNotEquals(base, ExerciseProfile.key(listOf(t(0, 10, tempo = "3-1-1-0"), t(1, 10)), WeightMode.TOTAL, unilateral = false))
        assertNotEquals(base, ExerciseProfile.key(listOf(t(0, 10), t(1, 10)), WeightMode.PER_DUMBBELL, unilateral = false))
        assertNotEquals(base, ExerciseProfile.key(listOf(t(0, 10), t(1, 10)), WeightMode.TOTAL, unilateral = true))
        assertEquals("2×10 @3-1-1-0", ExerciseProfile.key(listOf(t(0, 10, tempo = "3-1-1-0"), t(1, 10)), WeightMode.TOTAL, false).label)
    }

    @Test
    fun `members are the uses of the exercise with the same key, self included`() {
        val me = ExerciseProfile.key(listOf(t(0, 10), t(1, 10)), WeightMode.TOTAL, false)
        val other = ExerciseProfile.key(listOf(t(0, 12), t(1, 12)), WeightMode.TOTAL, false)
        assertEquals(listOf(5L, 9L), ExerciseProfile.members(selfId = 5L, selfKey = me, others = listOf(7L to other, 9L to me)))
    }

    @Test
    fun `last-time hint names the other profile and its newest session`() {
        fun log(idx: Int, w: Double, v: Int, at: Long) = SetLog(sessionId = at / 100, workoutExerciseId = 7, exerciseId = "wger:1", setIndex = idx,
            type = SetType.NORMAL, weightKg = w, weightMode = WeightMode.TOTAL, barWeightKg = 0.0, value = v, valueUnit = ValueUnit.REPS, completedAt = at)
        val hint = ExerciseProfile.lastTimeHint(
            otherLabel = "3×12",
            logs = listOf(log(0, 20.0, 12, 200), log(1, 20.0, 11, 201), log(0, 18.0, 12, 100)),
        )
        assertEquals("3×12: 20 kg × 12, 20 kg × 11", hint)
    }
}
