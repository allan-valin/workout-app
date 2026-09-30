package dev.allan.workoutapp

import dev.allan.workoutapp.data.TemplateCarry
import dev.allan.workoutapp.data.db.SetLog
import dev.allan.workoutapp.data.db.SetTemplate
import dev.allan.workoutapp.data.db.SetType
import dev.allan.workoutapp.data.db.ValueUnit
import dev.allan.workoutapp.data.db.WeightMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Allan, 16/09: an archived copy of a workout showed the warm-up weight on every set, and
 * "use this exercise's last config" on a swap brought the wrong weights. Both because a
 * template's targetWeightKg is the day-one target and never learns what was trained; the
 * copy therefore starts at 0 kg and the session forward-fill spreads the first (warm-up)
 * weight over the rest. [TemplateCarry] overlays the latest log per slot before copying.
 */
class TemplateCarryTest {

    private fun template(index: Int, kg: Double = 0.0, type: SetType = SetType.NORMAL) =
        SetTemplate(id = 100L + index, workoutExerciseId = 7, setIndex = index, type = type, targetWeightKg = kg, targetValue = 10)

    private fun log(index: Int, kg: Double, at: Long, type: SetType = SetType.NORMAL) =
        SetLog(
            sessionId = 1, workoutExerciseId = 7, exerciseId = "x", setIndex = index, type = type,
            weightKg = kg, weightMode = WeightMode.TOTAL, barWeightKg = 0.0, value = 10,
            valueUnit = ValueUnit.REPS, completedAt = at,
        )

    @Test
    fun `latest log per slot overrides the template weight`() {
        val templates = listOf(template(0, 20.0, SetType.WARMUP), template(1), template(2))
        val logs = listOf(log(2, 60.0, at = 30), log(1, 60.0, at = 20), log(1, 55.0, at = 10), log(0, 20.0, at = 5, type = SetType.WARMUP))
        val out = TemplateCarry.effective(templates, logs)
        assertEquals(listOf(20.0, 60.0, 60.0), out.map { it.targetWeightKg })
        assertEquals(listOf(SetType.WARMUP, SetType.NORMAL, SetType.NORMAL), out.map { it.type })
        // Template identity is untouched — the caller re-keys ids for the copy.
        assertEquals(templates.map { it.id }, out.map { it.id })
    }

    @Test
    fun `slots never trained keep their template weight`() {
        val templates = listOf(template(0, 40.0), template(1, 40.0), template(2, 42.5))
        val out = TemplateCarry.effective(templates, listOf(log(0, 50.0, at = 1)))
        assertEquals(listOf(50.0, 40.0, 42.5), out.map { it.targetWeightKg })
    }

    @Test
    fun `no logs means templates unchanged`() {
        val templates = listOf(template(0, 40.0), template(1))
        assertEquals(templates, TemplateCarry.effective(templates, emptyList()))
    }

    @Test
    fun `last trained picks the candidate with the newest log, not the newest row`() {
        // weId 3 is the newest row (an archived copy) but was never trained; weId 1 was.
        val logsByWe = mapOf(1L to listOf(log(0, 80.0, at = 100)), 2L to listOf(log(0, 70.0, at = 50)), 3L to emptyList())
        assertEquals(1L, TemplateCarry.lastTrained(listOf(1L, 2L, 3L)) { logsByWe[it].orEmpty() })
    }

    @Test
    fun `last trained is null when nothing was ever logged`() {
        assertNull(TemplateCarry.lastTrained(listOf(1L, 2L)) { emptyList() })
    }
}
