package dev.allan.workoutapp.data

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import dev.allan.workoutapp.data.db.AppDatabase
import dev.allan.workoutapp.data.db.ExerciseTranslation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * On-device machine translation (ML Kit) for exercises that have no translation in the
 * app language — most wger descriptions and every free-exercise-db entry are English only
 * (Allan). Runs when a detail sheet opens: translates the English name + description once,
 * caches the result as a `machine = true` translation row, and never touches exercises
 * that already have a human translation. The ~30MB language model downloads once, on
 * Wi-Fi only; until then exercises simply stay English.
 */
object AutoTranslate {

    /** Sheet-open translations shouldn't hang a coroutine on a stuck model download. */
    private const val TIMEOUT_MS = 120_000L

    /**
     * Ensures a translation row for [lang] exists when an English source is available.
     * Returns true when a new machine translation was inserted (caller should re-query).
     */
    suspend fun ensure(db: AppDatabase, exerciseId: String, lang: String): Boolean {
        if (lang == "en") return false
        val target = TranslateLanguage.fromLanguageTag(lang) ?: return false
        val existing = db.exerciseDao().translations(exerciseId)
        if (existing.any { it.lang == lang }) return false
        val source = existing.firstOrNull { it.lang == "en" } ?: return false

        val translated = withTimeoutOrNull(TIMEOUT_MS) {
            runCatching {
                val translator = Translation.getClient(
                    TranslatorOptions.Builder()
                        .setSourceLanguage(TranslateLanguage.ENGLISH)
                        .setTargetLanguage(target)
                        .build()
                )
                translator.use {
                    it.downloadModelIfNeeded(
                        DownloadConditions.Builder().requireWifi().build()
                    ).await()
                    val name = it.translate(source.name).await()
                    val description =
                        if (source.description.isBlank()) ""
                        else it.translate(source.description).await()
                    name to description
                }
            }.getOrNull()
        } ?: return false

        db.exerciseDao().insertTranslations(
            listOf(
                ExerciseTranslation(
                    exerciseId = exerciseId,
                    lang = lang,
                    name = translated.first,
                    description = translated.second,
                    aliases = emptyList(),
                    machine = true,
                )
            )
        )
        return true
    }

    private suspend fun <T> com.google.android.gms.tasks.Task<T>.await(): T =
        suspendCancellableCoroutine { cont ->
            addOnSuccessListener { cont.resume(it) }
            addOnFailureListener { cont.resumeWithException(it) }
            addOnCanceledListener { cont.cancel() }
        }
}
