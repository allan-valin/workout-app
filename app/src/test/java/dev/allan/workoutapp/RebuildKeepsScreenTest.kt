package dev.allan.workoutapp

import dev.allan.workoutapp.ui.session.prefillValue
import dev.allan.workoutapp.ui.session.prefillWeight
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * G1 (Allan, 09/10): "Adjusting the goal changes the last input of how many sets were done.
 * Correct if 0, otherwise change goal and keep last set." A rebuild of the session rows (goal
 * edit, set type, add/remove/move set) must keep what is on screen unless it is 0.
 */
class RebuildKeepsScreenTest {

    @Test
    fun `goal change keeps a non-zero reps input`() {
        assertEquals(10, prefillValue(screen = 10, draft = null, previous = null, target = 15))
    }

    @Test
    fun `zero reps on screen take the new goal`() {
        assertEquals(15, prefillValue(screen = 0, draft = null, previous = null, target = 15))
    }

    @Test
    fun `first paint prefers draft, then previous log, then target`() {
        assertEquals(8, prefillValue(screen = null, draft = 8, previous = 12, target = 15))
        assertEquals(12, prefillValue(screen = null, draft = null, previous = 12, target = 15))
        assertEquals(15, prefillValue(screen = null, draft = null, previous = null, target = 15))
    }

    @Test
    fun `a typed weight survives a rebuild`() {
        assertEquals(22.5, prefillWeight(screen = 22.5, draft = null, previous = 20.0, template = 16.0), 0.0)
    }

    @Test
    fun `zero weight on screen falls back like a first paint`() {
        assertEquals(20.0, prefillWeight(screen = 0.0, draft = null, previous = 20.0, template = 16.0), 0.0)
        assertEquals(16.0, prefillWeight(screen = null, draft = null, previous = null, template = 16.0), 0.0)
    }
}
