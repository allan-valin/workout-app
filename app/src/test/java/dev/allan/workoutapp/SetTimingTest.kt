package dev.allan.workoutapp

import dev.allan.workoutapp.data.SetTiming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Active-time rules from Allan's 02/08 batch (docs/FEEDBACK_BATCH_2026-08-02.md, A4–A6). */
class SetTimingTest {

    @Test
    fun `tempo phases add up`() {
        assertEquals(4, SetTiming.tempoSecs("1-1-1-1"))
        assertEquals(2, SetTiming.tempoSecs("1-0-1-0"))
        assertEquals(7, SetTiming.tempoSecs("3-1-2-1"))
    }

    @Test
    fun `an X hold counts as zero`() {
        assertEquals(4, SetTiming.tempoSecs("2-X-2-0"))
    }

    @Test
    fun `blank or unparseable tempo has no seconds`() {
        assertNull(SetTiming.tempoSecs(""))
        assertNull(SetTiming.tempoSecs("slow"))
    }

    @Test
    fun `no tempo falls back to forty seconds`() {
        assertEquals(40, SetTiming.defaultActiveSecs(reps = 10, tempo = ""))
        assertEquals(40, SetTiming.defaultActiveSecs(reps = 3, tempo = ""))
    }

    @Test
    fun `tempo drives the default when defined`() {
        assertEquals(40, SetTiming.defaultActiveSecs(reps = 10, tempo = "1-1-1-1"))
        assertEquals(20, SetTiming.defaultActiveSecs(reps = 10, tempo = "1-0-1-0"))
        assertEquals(70, SetTiming.defaultActiveSecs(reps = 10, tempo = "3-1-2-1"))
    }

    @Test
    fun `an all-zero tempo falls back instead of booking nothing`() {
        assertEquals(40, SetTiming.defaultActiveSecs(reps = 10, tempo = "0-0-0-0"))
    }

    @Test
    fun `measured time loses five seconds for getting into position`() {
        assertEquals(35, SetTiming.measuredActiveSecs(40))
        assertEquals(5, SetTiming.measuredActiveSecs(7))
        assertEquals(5, SetTiming.measuredActiveSecs(2))
    }

    @Test
    fun `more than fifteen percent under the tempo estimate is too fast`() {
        assertEquals(SetTiming.Pace.FAST, SetTiming.pace(actualSecs = 30, expectedSecs = 40))
        assertEquals(SetTiming.Pace.ON_TEMPO, SetTiming.pace(actualSecs = 35, expectedSecs = 40))
    }

    @Test
    fun `slower than the estimate is fine`() {
        assertEquals(SetTiming.Pace.ON_TEMPO, SetTiming.pace(actualSecs = 90, expectedSecs = 40))
    }

    @Test
    fun `a set with no cadence has no expected duration`() {
        assertNull(SetTiming.expectedSecs(reps = 10, tempo = ""))
    }

    @Test
    fun `exactly at the tolerance edge is still on tempo`() {
        // 40 s expected, 15 % tolerance → 34 s is the first "fast" reading.
        assertEquals(SetTiming.Pace.ON_TEMPO, SetTiming.pace(actualSecs = 34, expectedSecs = 40))
        assertEquals(SetTiming.Pace.FAST, SetTiming.pace(actualSecs = 33, expectedSecs = 40))
    }
}
