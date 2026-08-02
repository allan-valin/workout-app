package dev.allan.workoutapp

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.allan.workoutapp.data.db.SetType
import dev.allan.workoutapp.data.db.ValueUnit
import dev.allan.workoutapp.data.db.WeightMode
import dev.allan.workoutapp.ui.session.SessionExercise
import dev.allan.workoutapp.ui.session.SessionSet
import dev.allan.workoutapp.ui.session.SessionUiState
import dev.allan.workoutapp.ui.session.TimerReadout
import dev.allan.workoutapp.ui.session.pendingTimedSet
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The state behind the panel is covered by SessionFlowRegressionTest; this asserts the part
 * only a rendering can answer — that the timed set's duration actually reaches the screen as
 * "Set timer / 0:45" rather than the stopwatch (Allan, 02/08, item A1).
 */
@RunWith(AndroidJUnit4::class)
class TimerReadoutTest {

    @get:Rule
    val compose = createComposeRule()

    private fun set(id: Long, unit: ValueUnit, value: Int, done: Boolean = false) = SessionSet(
        templateId = id,
        setIndex = 0,
        type = SetType.NORMAL,
        weightKg = 0.0,
        value = value,
        valueUnit = unit,
        restSecs = 60,
        targetMin = 10,
        done = done,
    )

    private fun stateWith(s: SessionSet) = SessionUiState(
        exercises = listOf(
            SessionExercise(
                workoutExerciseId = 1,
                exerciseId = "e1",
                name = "Plank",
                weightMode = WeightMode.TOTAL,
                barWeightKg = 0.0,
                imagePath = null,
                sets = listOf(s),
                supersetWithPrev = false,
            )
        ),
        currentStep = 0 to s.templateId,
    )

    @Test
    fun timedSetShowsItsCountdownReadyToStart() {
        val state = stateWith(set(1, ValueUnit.SECS, 45))
        compose.setContent { TimerReadout(state, state.pendingTimedSet()) }

        compose.onNodeWithText("Set timer").assertIsDisplayed()
        compose.onNodeWithText("0:45").assertIsDisplayed()
    }

    @Test
    fun repSetShowsTheStopwatchInstead() {
        val state = stateWith(set(1, ValueUnit.REPS, 10))
        compose.setContent { TimerReadout(state, state.pendingTimedSet()) }

        compose.onNodeWithText("Log set duration").assertIsDisplayed()
        compose.onNodeWithText("0:00").assertIsDisplayed()
    }

    @Test
    fun aRunningRestOutranksAPendingTimedSet() {
        val base = stateWith(set(1, ValueUnit.SECS, 45))
        val state = base.copy(restRemainingSecs = 83)
        compose.setContent { TimerReadout(state, null) }

        compose.onNodeWithText("Rest").assertIsDisplayed()
        compose.onNodeWithText("1:23").assertIsDisplayed()
    }
}
