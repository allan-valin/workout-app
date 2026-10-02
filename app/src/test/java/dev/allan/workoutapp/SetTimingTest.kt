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
    fun `no tempo books four seconds a rep`() {
        // 02/10 (Allan): "40 seconds is based on 10 reps, if I log 20 reps it should double".
        assertEquals(40, SetTiming.defaultActiveSecs(reps = 10, tempo = ""))
        assertEquals(12, SetTiming.defaultActiveSecs(reps = 3, tempo = ""))
        assertEquals(80, SetTiming.defaultActiveSecs(reps = 20, tempo = ""))
    }

    @Test
    fun `a zero phase in the cadence counts as a one-second pause`() {
        // 02/10 (Allan): "1-1-3-0 or 2-0-4-0 and 20 reps, chances are I'm doing 1-2 s pauses
        // instead of 0". The booked estimate uses the realistic cadence; the nominal one
        // (expectedSecs) stays for the too-fast warning.
        assertEquals(40, SetTiming.defaultActiveSecs(reps = 10, tempo = "1-0-1-0"))
        assertEquals(120, SetTiming.defaultActiveSecs(reps = 20, tempo = "1-1-3-0"))
        assertEquals(160, SetTiming.defaultActiveSecs(reps = 20, tempo = "2-0-4-0"))
        assertEquals(20, SetTiming.expectedSecs(reps = 10, tempo = "1-0-1-0"))
        assertEquals(6, SetTiming.realisticTempoSecs("1-1-3-0"))
        assertEquals(6, SetTiming.realisticTempoSecs("2-X-2-0"))
        assertNull(SetTiming.realisticTempoSecs(""))
    }

    @Test
    fun `the forgot-to-log cap is five minutes or twice the estimate`() {
        assertEquals(300, SetTiming.capSecs(reps = 10, tempo = ""))
        assertEquals(300, SetTiming.capSecs(reps = 20, tempo = "1-1-3-0")) // 2 × 120 < 300
        assertEquals(320, SetTiming.capSecs(reps = 20, tempo = "2-0-4-0")) // 2 × 160
        // The trainer's "one set" that is really 8 movements, logged as 80 reps.
        assertEquals(640, SetTiming.capSecs(reps = 80, tempo = ""))
    }

    @Test
    fun `an untimed gap is booked as measured up to the cap and as the cap beyond it`() {
        assertEquals(145, SetTiming.bookFromGap(gapSecs = 150, reps = 10, tempo = ""))
        assertEquals(300, SetTiming.bookFromGap(gapSecs = 400, reps = 10, tempo = ""))
        assertEquals(305, SetTiming.bookFromGap(gapSecs = 310, reps = 20, tempo = "2-0-4-0"))
        assertEquals(320, SetTiming.bookFromGap(gapSecs = 720, reps = 20, tempo = "2-0-4-0"))
        // A 4:30 "set" of eight movements logged as 10 reps still books 4:25.
        assertEquals(265, SetTiming.bookFromGap(gapSecs = 270, reps = 10, tempo = ""))
    }

    @Test
    fun `tempo drives the default when defined`() {
        assertEquals(40, SetTiming.defaultActiveSecs(reps = 10, tempo = "1-1-1-1"))
        assertEquals(70, SetTiming.defaultActiveSecs(reps = 10, tempo = "3-1-2-1"))
    }

    @Test
    fun `an all-zero tempo books the pauses instead of nothing`() {
        // Four one-second pauses a rep = the same 4 s/rep as no cadence at all.
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
    fun `the workout estimate uses the same cadence rule as the booking`() {
        val slow = dev.allan.workoutapp.ui.session.SessionExercise(
            workoutExerciseId = 1,
            exerciseId = "wger:1",
            name = "Squat",
            weightMode = dev.allan.workoutapp.data.db.WeightMode.TOTAL,
            barWeightKg = 20.0,
            imagePath = null,
            sets = listOf(
                dev.allan.workoutapp.ui.session.SessionSet(
                    templateId = 1,
                    setIndex = 0,
                    type = dev.allan.workoutapp.data.db.SetType.NORMAL,
                    weightKg = 40.0,
                    value = 10,
                    valueUnit = dev.allan.workoutapp.data.db.ValueUnit.REPS,
                    restSecs = 60,
                    targetMin = 10,
                    tempo = "3-1-2-1",
                )
            ),
        )
        // 60 s setup + 10 reps × 7 s + 60 s rest — not the flat 40 s default.
        assertEquals(190, dev.allan.workoutapp.ui.session.estimateWorkoutSecs(listOf(slow)))
    }

    @Test
    fun `exactly at the tolerance edge is still on tempo`() {
        // 40 s expected, 15 % tolerance → 34 s is the first "fast" reading.
        assertEquals(SetTiming.Pace.ON_TEMPO, SetTiming.pace(actualSecs = 34, expectedSecs = 40))
        assertEquals(SetTiming.Pace.FAST, SetTiming.pace(actualSecs = 33, expectedSecs = 40))
    }
}
