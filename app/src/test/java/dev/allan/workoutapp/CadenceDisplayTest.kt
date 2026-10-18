package dev.allan.workoutapp

import dev.allan.workoutapp.data.db.SetType
import dev.allan.workoutapp.data.db.ValueUnit
import dev.allan.workoutapp.ui.session.SessionSet
import dev.allan.workoutapp.ui.session.shownCadence
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * C1 (Allan, 10/10): "Supersets are using the same cadence, should be individual. Now you
 * change one and both are altered." The page's cadence pill shows the page's OWN cadence,
 * never the superset partner's (Phase 34 A6 borrowed it whenever the marker sat on the partner).
 */
class CadenceDisplayTest {

    private fun set(tempo: String) = SessionSet(
        templateId = 1, setIndex = 0, type = SetType.NORMAL, weightKg = 0.0, value = 10,
        valueUnit = ValueUnit.REPS, restSecs = 60, targetMin = 10, tempo = tempo,
    )

    @Test
    fun `own cadence is shown`() {
        assertEquals("2-0-2-0", shownCadence(own = listOf(set("2-0-2-0")), partner = listOf(set("3-0-1-0"))))
    }

    @Test
    fun `partner cadence is never shown as own`() {
        assertEquals("", shownCadence(own = listOf(set("")), partner = listOf(set("3-0-1-0"))))
    }

    @Test
    fun `no partner, no cadence, empty pill`() {
        assertEquals("", shownCadence(own = listOf(set("")), partner = null))
    }
}
