package dev.allan.workoutapp.data.transfer

import dev.allan.workoutapp.data.PlanRepo
import dev.allan.workoutapp.data.db.AppDatabase
import dev.allan.workoutapp.data.db.Plan
import dev.allan.workoutapp.data.db.SetTemplate
import dev.allan.workoutapp.data.db.SetType
import dev.allan.workoutapp.data.db.ValueUnit
import dev.allan.workoutapp.data.db.WeightMode
import dev.allan.workoutapp.data.db.Workout
import dev.allan.workoutapp.data.db.WorkoutExercise
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Plan JSON import/export. The schema contract lives in docs/WORKOUT_PLAN_GENERATOR.md —
 * browser Claude generates these files; never change field names without bumping
 * schema_version and keeping this importer backward-compatible.
 */
object PlanTransfer {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    // ---- Schema v1 DTOs ----

    @Serializable
    data class File(
        @SerialName("schema_version") val schemaVersion: Int = 1,
        val plan: PlanDto,
    )

    @Serializable
    data class PlanDto(
        val name: String,
        val active: Boolean = true,
        @SerialName("cycle_weeks") val cycleWeeks: Int? = null,
        val workouts: List<WorkoutDto> = emptyList(),
    )

    @Serializable
    data class WorkoutDto(
        val name: String,
        @SerialName("days_of_week") val daysOfWeek: List<String> = emptyList(),
        val exercises: List<ExerciseDto> = emptyList(),
    )

    @Serializable
    data class ExerciseDto(
        val match: MatchDto,
        @SerialName("custom_fallback") val customFallback: CustomFallbackDto? = null,
        @SerialName("weight_mode") val weightMode: String = "TOTAL",
        @SerialName("bar_weight_kg") val barWeightKg: Double = 20.0,
        val note: String = "",
        val sets: List<SetDto> = emptyList(),
    )

    @Serializable
    data class MatchDto(
        @SerialName("wger_id") val wgerId: Long? = null,
        val names: List<String> = emptyList(),
    )

    @Serializable
    data class CustomFallbackDto(
        @SerialName("primary_muscle") val primaryMuscle: String? = null,
        @SerialName("secondary_muscles") val secondaryMuscles: List<String> = emptyList(),
        @SerialName("is_cardio") val isCardio: Boolean = false,
        val description: String = "",
    )

    @Serializable
    data class SetDto(
        val type: String = "NORMAL",
        @SerialName("weight_kg") val weightKg: Double = 0.0,
        val value: Int = 10,
        val unit: String = "REPS",
        @SerialName("rest_secs") val restSecs: Int = 90,
    )

    data class ImportReport(
        val planId: Long?,
        val workouts: Int,
        val exercises: Int,
        val createdCustom: List<String>,
        val skipped: List<String>,
        val error: String? = null,
    )

    private val dayMap = mapOf(
        "MON" to 1, "TUE" to 2, "WED" to 3, "THU" to 4, "FRI" to 5, "SAT" to 6, "SUN" to 7,
    )

    /** Muscle-enum slug (generator doc) -> wger muscle id, for custom_fallback. */
    private val muscleSlugToWgerId = mapOf(
        "biceps" to 1, "front_delts" to 2, "side_delts" to 2, "rear_delts" to 2,
        "chest" to 4, "triceps" to 5, "abs" to 6, "calves" to 7, "glutes" to 8,
        "traps" to 9, "quads" to 10, "hamstrings" to 11, "lats" to 12, "upper_back" to 12,
        "brachialis" to 13, "obliques" to 14, "soleus" to 15, "forearms" to 13,
        "lower_back" to 12, "adductors" to 10, "abductors" to 8, "neck" to 9,
    )

    suspend fun import(db: AppDatabase, text: String, lang: String): ImportReport {
        val file = try {
            json.decodeFromString<File>(text)
        } catch (e: Exception) {
            return ImportReport(null, 0, 0, emptyList(), emptyList(), error = e.message ?: "parse error")
        }
        if (file.schemaVersion != 1) {
            return ImportReport(null, 0, 0, emptyList(), emptyList(), error = "unsupported schema_version ${file.schemaVersion}")
        }
        if (file.plan.name.isBlank()) {
            return ImportReport(null, 0, 0, emptyList(), emptyList(), error = "plan.name missing")
        }

        val createdCustom = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        var exerciseCount = 0

        val planId = db.planDao().insertPlan(
            Plan(
                name = file.plan.name,
                isActive = file.plan.active,
                cycleWeeks = file.plan.cycleWeeks?.coerceIn(1, 52),
                startedAt = System.currentTimeMillis(),
                createdAt = System.currentTimeMillis(),
            )
        )

        file.plan.workouts.forEachIndexed { wIndex, w ->
            val workoutId = db.planDao().insertWorkout(
                Workout(
                    planId = planId,
                    name = w.name.ifBlank { "Workout ${wIndex + 1}" },
                    orderIndex = wIndex,
                    daysOfWeek = w.daysOfWeek.mapNotNull { dayMap[it.uppercase()] }.distinct().sorted(),
                )
            )
            w.exercises.forEachIndexed { eIndex, e ->
                val exerciseId = resolveExercise(db, e, lang, createdCustom)
                if (exerciseId == null) {
                    skipped += e.match.names.firstOrNull() ?: "exercise ${eIndex + 1}"
                    return@forEachIndexed
                }
                val weId = db.planDao().insertWorkoutExercise(
                    WorkoutExercise(
                        workoutId = workoutId,
                        exerciseId = exerciseId,
                        orderIndex = eIndex,
                        note = e.note,
                        weightMode = runCatching { WeightMode.valueOf(e.weightMode) }.getOrDefault(WeightMode.TOTAL),
                        barWeightKg = e.barWeightKg,
                    )
                )
                val sets = e.sets.ifEmpty { listOf(SetDto(), SetDto(), SetDto()) }
                sets.forEachIndexed { sIndex, s ->
                    db.planDao().insertSetTemplate(
                        SetTemplate(
                            workoutExerciseId = weId,
                            setIndex = sIndex,
                            type = runCatching { SetType.valueOf(s.type) }.getOrDefault(SetType.NORMAL),
                            targetWeightKg = s.weightKg.coerceAtLeast(0.0),
                            targetValue = s.value.coerceAtLeast(0),
                            valueUnit = runCatching { ValueUnit.valueOf(s.unit) }.getOrDefault(ValueUnit.REPS),
                            restSecs = s.restSecs.coerceIn(0, 3600),
                        )
                    )
                }
                exerciseCount++
            }
        }
        return ImportReport(planId, file.plan.workouts.size, exerciseCount, createdCustom, skipped)
    }

    /** wger id -> exact name (any language) -> exact alias -> custom_fallback -> null. */
    private suspend fun resolveExercise(
        db: AppDatabase,
        e: ExerciseDto,
        lang: String,
        createdCustom: MutableList<String>,
    ): String? {
        e.match.wgerId?.let { id ->
            if (db.exerciseDao().exercise("wger:$id") != null) return "wger:$id"
        }
        e.match.names.forEach { name ->
            db.exerciseDao().exerciseIdByName(name.trim())?.let { return it }
        }
        e.match.names.forEach { name ->
            val target = name.trim().lowercase()
            db.exerciseDao().translationsWithAliasLike(name.trim()).forEach { tr ->
                if (tr.aliases.any { it.trim().lowercase() == target }) return tr.exerciseId
            }
        }
        val fallback = e.customFallback ?: return null
        val displayName = e.match.names.firstOrNull()?.trim() ?: return null
        val id = PlanRepo.createCustomExercise(
            db = db,
            name = displayName,
            description = fallback.description,
            primaryMuscleId = fallback.primaryMuscle?.let { muscleSlugToWgerId[it.lowercase()] },
            isCardio = fallback.isCardio,
            lang = lang,
        )
        createdCustom += displayName
        return id
    }

    /** Exports a plan back to schema-v1 JSON (round-trips with import). */
    suspend fun export(db: AppDatabase, planId: Long): String? {
        val plan = db.planDao().plan(planId) ?: return null
        val dayNames = mapOf(1 to "MON", 2 to "TUE", 3 to "WED", 4 to "THU", 5 to "FRI", 6 to "SAT", 7 to "SUN")
        val workouts = db.planDao().workoutsList(planId).map { w ->
            val exercises = db.planDao().workoutExercisesList(w.id).map { we ->
                val translations = db.exerciseDao().translations(we.exerciseId)
                val exercise = db.exerciseDao().exercise(we.exerciseId)
                ExerciseDto(
                    match = MatchDto(
                        wgerId = we.exerciseId.removePrefix("wger:").toLongOrNull()
                            .takeIf { we.exerciseId.startsWith("wger:") },
                        names = translations.map { it.name }.distinct(),
                    ),
                    customFallback = if (exercise?.isCustom == true) CustomFallbackDto(
                        isCardio = exercise.isCardio,
                        description = translations.firstOrNull()?.description ?: "",
                    ) else null,
                    weightMode = we.weightMode.name,
                    barWeightKg = we.barWeightKg,
                    note = we.note,
                    sets = db.planDao().setTemplatesList(we.id).map { s ->
                        SetDto(
                            type = s.type.name,
                            weightKg = s.targetWeightKg,
                            value = s.targetValue,
                            unit = s.valueUnit.name,
                            restSecs = s.restSecs,
                        )
                    },
                )
            }
            WorkoutDto(
                name = w.name,
                daysOfWeek = w.daysOfWeek.mapNotNull { dayNames[it] },
                exercises = exercises,
            )
        }
        return json.encodeToString(
            File(
                plan = PlanDto(
                    name = plan.name,
                    active = plan.isActive,
                    cycleWeeks = plan.cycleWeeks,
                    workouts = workouts,
                )
            )
        )
    }
}
