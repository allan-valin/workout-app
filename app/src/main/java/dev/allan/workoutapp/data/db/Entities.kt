package dev.allan.workoutapp.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Weight entry modes for an exercise inside a workout. */
enum class WeightMode { TOTAL, PER_DUMBBELL, PER_SIDE }

enum class SetType { WARMUP, NORMAL, FAILURE, DROP, SUPERSET }

enum class ValueUnit { REPS, SECS }

enum class SessionStatus { RUNNING, FINISHED, DISCARDED, AUTO_ENDED }

/**
 * Exercise identity row. id is "wger:<id>" for snapshot exercises or "custom:<uuid>"
 * for user-created ones. Names/descriptions live in [ExerciseTranslation].
 */
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

@Entity(tableName = "muscle")
data class Muscle(
    @PrimaryKey val id: Int,
    val nameLatin: String,
    val nameEn: String,
)

@Entity(tableName = "equipment")
data class Equipment(
    @PrimaryKey val id: Int,
    val name: String,
)

@Entity(tableName = "plan")
data class Plan(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val isActive: Boolean = true,
    val cycleWeeks: Int? = null,
    val startedAt: Long? = null,
    val createdAt: Long,
)

@Entity(tableName = "workout", indices = [Index("planId")])
data class Workout(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val planId: Long,
    val name: String,
    val orderIndex: Int,
    /** ISO day numbers 1=Mon..7=Sun. */
    val daysOfWeek: List<Int>,
)

@Entity(tableName = "workout_exercise", indices = [Index("workoutId"), Index("exerciseId")])
data class WorkoutExercise(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val workoutId: Long,
    val exerciseId: String,
    val orderIndex: Int,
    val note: String = "",
    val weightMode: WeightMode = WeightMode.TOTAL,
    val barWeightKg: Double = 20.0,
)

@Entity(tableName = "set_template", indices = [Index("workoutExerciseId")])
data class SetTemplate(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val workoutExerciseId: Long,
    val setIndex: Int,
    val type: SetType = SetType.NORMAL,
    val targetWeightKg: Double = 0.0,
    val targetValue: Int = 10,
    val valueUnit: ValueUnit = ValueUnit.REPS,
    val restSecs: Int = 90,
)

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

@Entity(tableName = "exercise_note", indices = [Index("exerciseId")])
data class ExerciseNote(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val exerciseId: String,
    val sessionId: Long? = null,
    val text: String,
    val updatedAt: Long,
)

@Entity(tableName = "body_metric")
data class BodyMetric(
    /** Epoch day (days since 1970-01-01) — one weight entry per day. */
    @PrimaryKey val epochDay: Long,
    val weightKg: Double,
)
