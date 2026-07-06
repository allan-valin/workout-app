package dev.allan.workoutapp.data.snapshot

import android.content.Context
import dev.allan.workoutapp.data.db.AppDatabase
import dev.allan.workoutapp.data.db.Equipment
import dev.allan.workoutapp.data.db.Exercise
import dev.allan.workoutapp.data.db.ExerciseTranslation
import dev.allan.workoutapp.data.db.Muscle
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class SnapshotTranslation(
    val name: String,
    val description: String = "",
    val aliases: List<String> = emptyList(),
)

@Serializable
private data class SnapshotExercise(
    val id: Long,
    val category: String? = null,
    val muscles: List<Int> = emptyList(),
    val muscles_secondary: List<Int> = emptyList(),
    val equipment: List<Int> = emptyList(),
    val image: String? = null,
    val license_author: String? = null,
    val translations: Map<String, SnapshotTranslation> = emptyMap(),
)

@Serializable
private data class SnapshotMuscle(
    val id: Int,
    val name: String,
    val name_en: String,
)

@Serializable
private data class SnapshotEquipment(val id: Int, val name: String)

@Serializable
private data class Snapshot(
    val schema: Int,
    val muscles: List<SnapshotMuscle> = emptyList(),
    val equipment: List<SnapshotEquipment> = emptyList(),
    val exercises: List<SnapshotExercise> = emptyList(),
)

/** One-time import of the bundled wger snapshot into Room. Idempotent, safe to re-run. */
object SnapshotImporter {

    suspend fun importIfNeeded(context: Context) {
        val db = AppDatabase.get(context)
        val dao = db.exerciseDao()
        if (dao.snapshotExerciseCount() > 0) return

        val json = Json { ignoreUnknownKeys = true }
        val snapshot = context.assets.open("wger_snapshot.json").use { stream ->
            json.decodeFromString<Snapshot>(stream.readBytes().decodeToString())
        }
        require(snapshot.schema == 1) { "unsupported snapshot schema ${snapshot.schema}" }

        dao.insertMuscles(snapshot.muscles.map { Muscle(it.id, it.name, it.name_en) })
        dao.insertEquipment(snapshot.equipment.map { Equipment(it.id, it.name) })

        val cardioCategories = setOf("Cardio")
        snapshot.exercises.chunked(200).forEach { chunk ->
            dao.insertExercises(chunk.map { ex ->
                Exercise(
                    id = "wger:${ex.id}",
                    category = ex.category,
                    primaryMuscles = ex.muscles,
                    secondaryMuscles = ex.muscles_secondary,
                    equipment = ex.equipment,
                    imageUrl = ex.image,
                    isCustom = false,
                    isCardio = ex.category in cardioCategories,
                    licenseAuthor = ex.license_author,
                )
            })
            dao.insertTranslations(chunk.flatMap { ex ->
                ex.translations.map { (lang, tr) ->
                    ExerciseTranslation(
                        exerciseId = "wger:${ex.id}",
                        lang = lang,
                        name = tr.name,
                        description = tr.description,
                        aliases = tr.aliases,
                    )
                }
            })
        }
    }
}
