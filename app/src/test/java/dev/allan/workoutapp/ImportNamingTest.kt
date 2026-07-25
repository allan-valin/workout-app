package dev.allan.workoutapp

import dev.allan.workoutapp.data.transfer.PlanTransfer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Regression tests for the 2026-07-25 import feedback: reimporting a plan silently created
 * same-named twins ("nothing was added"), and honoring the file's `active` flag left two
 * plans active so the superseded one vanished from the Active view AND the Archive.
 */
class ImportNamingTest {

    @Test
    fun `abbreviation takes first letters and keeps standalone numbers`() {
        assertEquals("ScoT4F", PlanTransfer.abbreviate("Seca com o Thales 4 - FORTALECIMENTO"))
    }

    @Test
    fun `abbreviation never emits punctuation from a numbered plan name`() {
        // The first cut split on whitespace/dashes only, so "(2)" contributed "(" and the
        // tag read "ScoT4F(" on Allan's emulator.
        val abbrev = PlanTransfer.abbreviate("Seca com o Thales 4 - FORTALECIMENTO (2)")
        assertEquals("ScoT4F2", abbrev)
        assertFalse(abbrev.contains("("))
    }

    @Test
    fun `abbreviation of an empty name is empty`() {
        assertEquals("", PlanTransfer.abbreviate("   "))
    }

    @Test
    fun `a free name is used as-is`() {
        val name = PlanTransfer.uniqueWorkoutName("CORE", setOf("FULLBODY A"), "ScoT4F", "25/07")
        assertEquals("CORE", name)
    }

    @Test
    fun `a taken name gets the plan abbreviation and date`() {
        val name = PlanTransfer.uniqueWorkoutName("CORE", setOf("CORE"), "ScoT4F", "25/07")
        assertEquals("CORE (ScoT4F 25/07)", name)
    }

    @Test
    fun `collisions are detected case-insensitively`() {
        val name = PlanTransfer.uniqueWorkoutName("core", setOf("CORE"), "ScoT4F", "25/07")
        assertEquals("core (ScoT4F 25/07)", name)
    }

    @Test
    fun `a second import on the same day appends a counter`() {
        val taken = setOf("CORE", "CORE (ScoT4F 25/07)")
        assertEquals("CORE (ScoT4F 25/07 2)", PlanTransfer.uniqueWorkoutName("CORE", taken, "ScoT4F", "25/07"))
    }

    @Test
    fun `a third import keeps counting`() {
        val taken = setOf("CORE", "CORE (ScoT4F 25/07)", "CORE (ScoT4F 25/07 2)")
        assertEquals("CORE (ScoT4F 25/07 3)", PlanTransfer.uniqueWorkoutName("CORE", taken, "ScoT4F", "25/07"))
    }

    @Test
    fun `without a plan abbreviation the date alone tags the name`() {
        assertEquals("CORE (25/07)", PlanTransfer.uniqueWorkoutName("CORE", setOf("CORE"), "", "25/07"))
    }

    @Test
    fun `an imported plan is never activated by the file`() {
        val dto = PlanTransfer.PlanDto(name = "Imported", active = true, cycleWeeks = 8)
        val row = PlanTransfer.newPlanRow(dto, renameTo = null, now = 1_000L)
        assertFalse("imports land archived; activation is an explicit step", row.isActive)
        assertEquals("Imported", row.name)
        assertEquals(8, row.cycleWeeks)
    }

    @Test
    fun `rename wins over the file name and cycle weeks stay in range`() {
        val dto = PlanTransfer.PlanDto(name = "Imported", active = true, cycleWeeks = 999)
        val row = PlanTransfer.newPlanRow(dto, renameTo = "Imported (2)", now = 1_000L)
        assertEquals("Imported (2)", row.name)
        assertEquals(52, row.cycleWeeks)
    }
}
