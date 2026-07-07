package dev.allan.workoutapp.data.snapshot

import android.content.Context
import dev.allan.workoutapp.data.db.AppDatabase
import dev.allan.workoutapp.data.db.ExerciseTranslation
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class PtAliasFile(val schema: Int, val aliases: Map<String, String> = emptyMap())

/**
 * Claude-generated pt-BR names for wger exercises that ship without a pt translation
 * (assets/pt_aliases.json, built by tools/pt-aliases/generate.py). Merged as pt
 * translation rows so pt search finds them; the English description is carried over
 * (better than an empty sheet). Idempotent: exercises that already have a pt row are
 * skipped, so wger's own translations always win. Re-run after every wger sync,
 * which rebuilds the translation table.
 */
object PtAliases {

    suspend fun merge(context: Context, db: AppDatabase) {
        val json = Json { ignoreUnknownKeys = true }
        val file = context.assets.open("pt_aliases.json").use { stream ->
            json.decodeFromString<PtAliasFile>(stream.readBytes().decodeToString())
        }
        if (file.schema != 1) return

        val dao = db.exerciseDao()
        val hasPt = dao.exerciseIdsWithLang("pt").toSet()
        val rows = mutableListOf<ExerciseTranslation>()
        for ((wgerId, ptName) in file.aliases) {
            val exerciseId = "wger:$wgerId"
            if (exerciseId in hasPt) continue
            val en = dao.translations(exerciseId).firstOrNull { it.lang == "en" } ?: continue
            rows += ExerciseTranslation(
                exerciseId = exerciseId,
                lang = "pt",
                name = ptName,
                description = en.description,
                aliases = emptyList(),
            )
        }
        if (rows.isNotEmpty()) rows.chunked(400).forEach { dao.insertTranslations(it) }
    }
}
