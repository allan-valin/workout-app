package dev.allan.workoutapp.session

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Naming/location of mirror-mode recordings — the user finds them in the gallery. */
object MirrorClip {
    /** MediaStore relative path: Movies/WorkoutApp, never auto-deleted by the app. */
    const val RELATIVE_PATH = "Movies/WorkoutApp"

    private val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")

    fun name(now: LocalDateTime): String = "workout_${fmt.format(now)}.mp4"
}
