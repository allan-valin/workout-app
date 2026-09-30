package dev.allan.workoutapp

import dev.allan.workoutapp.data.transfer.PlanTransfer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The transfer schema had no per-set cadence, so the FORTALECIMENTO fase 2 import (2026-09-07)
 * had to park its tempos in the exercise note. `tempo` is an optional, additive set field.
 */
class PlanTransferTempoTest {

    private val file = """
        {"schema_version": 1, "plan": {"name": "T", "workouts": [{"name": "A", "exercises": [
          {"match": {"names": ["Squat"]}, "sets": [
            {"type": "NORMAL", "value": 8, "tempo": "3-1-1-0"},
            {"type": "NORMAL", "value": 8}
          ]}
        ]}]}}
    """.trimIndent()

    @Test
    fun `tempo is read per set and blank when absent`() {
        val parsed = PlanTransfer.parse(file)
        assertTrue(parsed is PlanTransfer.Parsed.PlanFile)
        val sets = (parsed as PlanTransfer.Parsed.PlanFile).plan.workouts[0].exercises[0].sets
        assertEquals("3-1-1-0", sets[0].tempo)
        assertEquals("", sets[1].tempo)
    }

    @Test
    fun `files without tempo still parse`() {
        val old = file.replace(""", "tempo": "3-1-1-0"""", "")
        assertTrue(PlanTransfer.parse(old) is PlanTransfer.Parsed.PlanFile)
    }
}
