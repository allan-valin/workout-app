package dev.allan.workoutapp

import dev.allan.workoutapp.data.db.ExerciseTranslation
import dev.allan.workoutapp.data.transfer.PlanTransfer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 30/09 (Allan): "Source exercises are already given in pt-br but when selecting exercises
 * it gets selected using English from database and literally translating names". The plan
 * file carries the Portuguese name; the import matched the wger row (by id or English name)
 * and the app then showed the on-device machine translation of the English name. The name
 * written by the plan author wins over a machine translation or a missing one.
 */
class ImportLocalNameTest {

    private fun tr(lang: String, name: String, machine: Boolean = false, aliases: List<String> = emptyList()) =
        ExerciseTranslation(exerciseId = "wger:1", lang = lang, name = name, description = "", aliases = aliases, machine = machine)

    @Test
    fun `the file's unknown name replaces a machine-translated one`() {
        val existing = listOf(tr("en", "Bench Press"), tr("de", "Bankdrücken"), tr("pt", "Imprensa de banco", machine = true))
        val names = listOf("Bench Press", "Supino reto", "Bankdrücken")
        assertEquals("Supino reto", PlanTransfer.localNameFor(names, existing, "pt"))
    }

    @Test
    fun `the file's unknown name fills a missing translation`() {
        val existing = listOf(tr("en", "Standing Calf Raise"))
        assertEquals("Panturrilha em pé", PlanTransfer.localNameFor(listOf("Panturrilha em pé", "Standing Calf Raise"), existing, "pt"))
    }

    @Test
    fun `a human translation is never overwritten`() {
        val existing = listOf(tr("en", "Push Up"), tr("pt", "Flexão de braço"))
        assertNull(PlanTransfer.localNameFor(listOf("Push Up", "Flexão"), existing, "pt"))
    }

    @Test
    fun `names already known in another language are not candidates`() {
        // Only English and German given: nothing to install for Portuguese.
        val existing = listOf(tr("en", "Bench Press"), tr("de", "Bankdrücken", aliases = listOf("Bankdrücken flach")))
        assertNull(PlanTransfer.localNameFor(listOf("bench press", "Bankdrücken flach"), existing, "pt"))
    }

    @Test
    fun `the machine name itself is not a candidate`() {
        val existing = listOf(tr("en", "Bench Press"), tr("pt", "Imprensa de banco", machine = true))
        assertNull(PlanTransfer.localNameFor(listOf("Bench Press", "Imprensa de banco"), existing, "pt"))
    }
}
