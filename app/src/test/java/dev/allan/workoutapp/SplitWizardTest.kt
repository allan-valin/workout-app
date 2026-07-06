package dev.allan.workoutapp

import dev.allan.workoutapp.ui.plans.SplitWizard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitWizardTest {

    @Test
    fun `generates the requested number of workouts`() {
        (1..7).forEach { days ->
            assertEquals(days, SplitWizard.generate(days).size)
        }
    }

    @Test
    fun `clamps out-of-range input`() {
        assertEquals(1, SplitWizard.generate(0).size)
        assertEquals(7, SplitWizard.generate(12).size)
    }

    @Test
    fun `assigned iso days are unique and valid`() {
        (1..7).forEach { days ->
            val isoDays = SplitWizard.generate(days).map { it.isoDay }
            assertEquals(isoDays.size, isoDays.distinct().size)
            assertTrue(isoDays.all { it in 1..7 })
        }
    }

    @Test
    fun `seven day split has two cardio days`() {
        assertEquals(2, SplitWizard.generate(7).count { it.isCardio })
    }
}
