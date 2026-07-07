package dev.allan.workoutapp.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/** Weight entry modes for an exercise inside a workout. */
enum class WeightMode { TOTAL, PER_DUMBBELL, PER_SIDE }

enum class SetType { WARMUP, NORMAL, FAILURE, DROP, SUPERSET }

enum class ValueUnit { REPS, SECS }

enum class SessionStatus { RUNNING, FINISHED, DISCARDED, AUTO_ENDED }

/**
 * Exercise identity row. id is "wger:<id>" for snapshot exercises or "custom:<uuid>"
 * for user-created ones. Names/descriptions live in [ExerciseTranslation].
 */
@Serializable
@Entity(tableName = "exercise")
data class Exercise(
    @PrimaryKey val id: String,
    val category: String?,
    val primaryMuscles: List<Int>,
    val secondaryMuscles: List<Int>,
    val equipment: List<Int>,
    val imageUrl: String?,
    val imagePath: String? = null,
    val isCustom: Boolean = false,
    val isCardio: Boolean = false,
    val licenseAuthor: String? = null,
)

@Serializable
@Entity(
    tableName = "exercise_translation",
    indices = [Index("exerciseId"), Index("lang", "name")],
)
data class ExerciseTranslation(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val exerciseId: String,
    val lang: String, // en | de | pt
    val name: String,
    val description: String,
    val aliases: List<String>,
)

@Serializable
@Entity(tableName = "muscle")
data class Muscle(
    @PrimaryKey val id: Int,
    val nameLatin: String,
    val nameEn: String,
)

@Serializable
@Entity(tableName = "equipment")
data class Equipment(
    @PrimaryKey val id: Int,
    val name: String,
)

@Serializable
@Entity(tableName = "plan")
data class Plan(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val isActive: Boolean = true,
    val cycleWeeks: Int? = null,
    val startedAt: Long? = null,
    val createdAt: Long,
)

@Serializable
@Entity(tableName = "workout", indices = [Index("planId")])
data class Workout(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val planId: Long,
    val name: String,
    val orderIndex: Int,
    /** ISO day numbers 1=Mon..7=Sun. */
    val daysOfWeek: List<Int>,
    /** Archived workouts are hidden from Today and the main plan list but keep their history. */
    val archived: Boolean = false,
)

@Serializable
@Entity(tableName = "workout_exercise", indices = [Index("workoutId"), Index("exerciseId")])
data class WorkoutExercise(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val workoutId: Long,
    val exerciseId: String,
    val orderIndex: Int,
    val note: String = "",
    val weightMode: WeightMode = WeightMode.TOTAL,
    val barWeightKg: Double = 20.0,
    /**
     * Superset link: this exercise alternates with the previous one in the workout —
     * set 1 of the previous exercise, then set 1 of this one (no rest between), rest,
     * set 2 of the previous, set 2 of this one, …
     */
    val supersetWithPrev: Boolean = false,
)

@Serializable
@Entity(tableName = "set_template", indices = [Index("workoutExerciseId")])
data class SetTemplate(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val workoutExerciseId: Long,
    val setIndex: Int,
    val type: SetType = SetType.NORMAL,
    val targetWeightKg: Double = 0.0,
    val targetValue: Int = 10,
    /** Upper bound of the target rep range (null = fixed target, no range). REPS only. */
    val targetValueMax: Int? = null,
    val valueUnit: ValueUnit = ValueUnit.REPS,
    val restSecs: Int = 90,
)

@Serializable
@Entity(tableName = "session", indices = [Index("workoutId"), Index("status")])
data class Session(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val workoutId: Long,
    val startedAt: Long,
    val endedAt: Long? = null,
    val status: SessionStatus = SessionStatus.RUNNING,
    val activeSecs: Int = 0,
    val restSecs: Int = 0,
)

@Serializable
@Entity(tableName = "set_log", indices = [Index("sessionId"), Index("workoutExerciseId")])
data class SetLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val workoutExerciseId: Long,
    val exerciseId: String,
    val setIndex: Int,
    val type: SetType,
    val weightKg: Double,
    val weightMode: WeightMode,
    val barWeightKg: Double,
    val value: Int,
    val valueUnit: ValueUnit,
    val activeSecs: Int? = null,
    val restSecs: Int? = null,
    val completedAt: Long,
)

/**
 * Weight/reps the user typed during a session but hasn't logged yet. Survives leaving
 * the session screen (or an app kill) and is dropped when the session ends.
 */
@Serializable
@Entity(tableName = "session_set_draft", primaryKeys = ["sessionId", "templateId"])
data class SessionSetDraft(
    val sessionId: Long,
    val templateId: Long,
    val weightKg: Double,
    val value: Int,
)

/**
 * User-added video link (e.g. YouTube) per exercise. Lives in its own table so wger
 * snapshot refreshes (which replace the exercise tables) never touch it.
 */
@Serializable
@Entity(tableName = "exercise_link")
data class ExerciseLink(
    @PrimaryKey val exerciseId: String,
    val url: String,
)

@Serializable
@Entity(tableName = "exercise_note", indices = [Index("exerciseId")])
data class ExerciseNote(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val exerciseId: String,
    val sessionId: Long? = null,
    val text: String,
    val updatedAt: Long,
)

@Serializable
@Entity(tableName = "body_metric")
data class BodyMetric(
    /** Epoch day (days since 1970-01-01) — one weight entry per day. */
    @PrimaryKey val epochDay: Long,
    val weightKg: Double,
)
