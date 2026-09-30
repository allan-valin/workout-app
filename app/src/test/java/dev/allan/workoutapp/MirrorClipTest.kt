package dev.allan.workoutapp

import dev.allan.workoutapp.session.MirrorClip
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime

/** Mirror-mode recordings land in the gallery under one folder with sortable names (F1). */
class MirrorClipTest {
    @Test
    fun `clip name is sortable and unique per second`() {
        assertEquals("workout_2026-09-30_15-04-09.mp4", MirrorClip.name(LocalDateTime.of(2026, 9, 30, 15, 4, 9)))
    }

    @Test
    fun `clips go to Movies slash WorkoutApp`() {
        assertEquals("Movies/WorkoutApp", MirrorClip.RELATIVE_PATH)
    }
}
