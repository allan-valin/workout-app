package dev.allan.workoutapp

import dev.allan.workoutapp.data.StatsCalc
import dev.allan.workoutapp.data.db.SetLog
import dev.allan.workoutapp.data.db.SetType
import dev.allan.workoutapp.data.db.ValueUnit
import dev.allan.workoutapp.data.db.WeightMode
import dev.allan.workoutapp.ui.session.SessionExercise
import dev.allan.workoutapp.ui.session.SessionSet
import dev.allan.workoutapp.ui.session.SessionUiState
import dev.allan.workoutapp.ui.session.SupersetOrder
import dev.allan.workoutapp.ui.session.openingExercise
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the in-session flow Allan reported broken on 25/07: tapping an
 * overview entry opened the wrong exercise (a cancelled auto-advance left a stale swipe
 * request), plus the superset order rules that batch was supposed to fix, and the summary's
 * active-time fallback.
 */
class SessionFlowRegressionTest {

    private fun set(id: Long, index: Int, done: Boolean = false, unit: ValueUnit = ValueUnit.REPS) =
        SessionSet(
            templateId = id,
            setIndex = index,
            type = SetType.NORMAL,
            weightKg = 0.0,
            value = 10,
            valueUnit = unit,
            restSecs = 60,
            targetMin = 10,
            done = done,
        )

    private fun exercise(
        id: Long,
        name: String,
        sets: List<SessionSet>,
        supersetWithPrev: Boolean = false,
    ) = SessionExercise(
        workoutExerciseId = id,
        exerciseId = "e$id",
        name = name,
        weightMode = WeightMode.TOTAL,
        barWeightKg = 0.0,
        imagePath = null,
        sets = sets,
        supersetWithPrev = supersetWithPrev,
    )

    // ---- bug D: an explicit tap must beat a queued auto-advance ----

    @Test
    fun `opening an exercise clears a queued swipe`() {
        val state = SessionUiState(
            currentIndex = 2,
            showList = true,
            pendingSwipeTo = 2,
            swipeToken = 3,
        )
        val opened = state.openingExercise(5)
        assertEquals(5, opened.currentIndex)
        assertFalse(opened.showList)
        assertNull("a stale advance must not hijack the tapped exercise", opened.pendingSwipeTo)
    }

    @Test
    fun `opening an exercise leaves the swipe token alone`() {
        val state = SessionUiState(swipeToken = 7, pendingSwipeTo = 1)
        assertEquals(7, state.openingExercise(0).swipeToken)
    }

    // ---- superset order: what was verified live on 25/07 ----

    @Test
    fun `a superset pair alternates A1 B1 A2 B2`() {
        val exercises = listOf(
            exercise(1, "Clamshell", listOf(set(11, 0), set(12, 1))),
            exercise(2, "Unilateral Hip Thrust", listOf(set(21, 0), set(22, 1)), supersetWithPrev = true),
        )
        val order = SupersetOrder.interleaved(exercises, SupersetOrder.chain(exercises, 0))
        assertEquals(listOf(0 to 11L, 1 to 21L, 0 to 12L, 1 to 22L), order.map { it.first to it.second.templateId })
    }

    @Test
    fun `logging the first member points at the partner, not at rest`() {
        val exercises = listOf(
            exercise(1, "Clamshell", listOf(set(11, 0, done = true), set(12, 1))),
            exercise(2, "Unilateral Hip Thrust", listOf(set(21, 0), set(22, 1)), supersetWithPrev = true),
        )
        assertEquals(1 to 21L, SupersetOrder.nextStepFrom(exercises, 0))
    }

    @Test
    fun `rest is skipped after the first member and taken after the last`() {
        val exercises = listOf(
            exercise(1, "Clamshell", listOf(set(11, 0, done = true), set(12, 1))),
            exercise(2, "Unilateral Hip Thrust", listOf(set(21, 0), set(22, 1)), supersetWithPrev = true),
        )
        assertTrue(
            "partner still owes round 1 -> no rest yet",
            SupersetOrder.restSkipped(exercises, 0, exercises[0].sets[0]),
        )
        val afterPartner = listOf(
            exercises[0],
            exercise(2, "Unilateral Hip Thrust", listOf(set(21, 0, done = true), set(22, 1)), supersetWithPrev = true),
        )
        assertFalse(
            "round complete -> rest",
            SupersetOrder.restSkipped(afterPartner, 1, afterPartner[1].sets[0]),
        )
    }

    @Test
    fun `after a finished pair the next step is the following exercise`() {
        val exercises = listOf(
            exercise(1, "Clamshell", listOf(set(11, 0, done = true))),
            exercise(2, "Unilateral Hip Thrust", listOf(set(21, 0, done = true)), supersetWithPrev = true),
            exercise(3, "Elliptical", listOf(set(31, 0, unit = ValueUnit.SECS))),
        )
        assertEquals(2 to 31L, SupersetOrder.nextStepFrom(exercises, 0))
    }

    @Test
    fun `skipped earlier exercises come last, not first`() {
        val exercises = listOf(
            exercise(1, "Skipped warmup", listOf(set(11, 0))),
            exercise(2, "Current", listOf(set(21, 0, done = true))),
            exercise(3, "Next", listOf(set(31, 0))),
        )
        assertEquals(2 to 31L, SupersetOrder.nextStepFrom(exercises, 1))
    }

    @Test
    fun `all sets done means no next step`() {
        val exercises = listOf(exercise(1, "Only", listOf(set(11, 0, done = true))))
        assertNull(SupersetOrder.nextStep(exercises))
    }

    // ---- summary: "Active 0:00" after a process death ----

    private fun log(activeSecs: Int?) = SetLog(
        sessionId = 1,
        workoutExerciseId = 1,
        exerciseId = "e1",
        setIndex = 0,
        type = SetType.NORMAL,
        weightKg = 0.0,
        weightMode = WeightMode.TOTAL,
        barWeightKg = 0.0,
        value = 10,
        valueUnit = ValueUnit.REPS,
        activeSecs = activeSecs,
        completedAt = 0L,
    )

    @Test
    fun `logged seconds rescue the active total when the live counter was lost`() {
        assertEquals(72, StatsCalc.effectiveActiveSecs(0, listOf(log(36), log(36))))
    }

    @Test
    fun `a live counter larger than the logs is kept`() {
        assertEquals(400, StatsCalc.effectiveActiveSecs(400, listOf(log(36), log(36))))
    }

    @Test
    fun `untimed logs contribute nothing`() {
        assertEquals(0, StatsCalc.effectiveActiveSecs(0, listOf(log(null), log(null))))
    }
}
