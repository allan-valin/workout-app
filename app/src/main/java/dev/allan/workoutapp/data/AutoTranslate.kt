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
            }.onFailure {
                android.util.Log.w("AutoTranslate", "translate $exerciseId -> $lang failed", it)
            }.getOrNull()
        } ?: return false.also {
            android.util.Log.w("AutoTranslate", "translate $exerciseId -> $lang gave up (timeout or failure)")
        }

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

    /**
     * True when [text] is confidently NOT in [lang], i.e. there is something worth translating.
     *
     * The manual action used to offer itself on every non-machine description, including the
     * genuinely Portuguese ones, which is noise (Allan, 26/07). ML Kit's language identifier
     * ships a bundled model, so this costs no download and no network. Undetermined results
     * ("und", short or mixed text) return false — when in doubt, don't nag.
     */
    suspend fun needsTranslation(text: String, lang: String): Boolean {
        if (lang == "en" || text.isBlank()) return false
        val identified = runCatching {
            com.google.mlkit.nl.languageid.LanguageIdentification.getClient()
                .use { it.identifyLanguage(text).await() }
        }.onFailure {
            android.util.Log.w("AutoTranslate", "language id failed", it)
        }.getOrNull() ?: return false
        // "und" means the identifier could not decide — treat as nothing to offer.
        if (identified == "und") return false
        return identified.substringBefore('-') != lang
    }

    /**
     * User-initiated translation of the description already on screen.
     *
     * WHY this exists separately from [ensure]: an exercise can carry a row tagged with the app
     * language whose description is still English — imported plans write one row using the app
     * language for a generator-supplied Portuguese name plus an English description
     * (PlanRepo.createCustom), and PtAliases copies the English description onto its pt row.
     * [ensure] sees "a row for this language exists" and declines, so those descriptions stayed
     * English forever with nothing offering to translate them (Allan, 26/07).
     *
     * Translates that row's description in place, keeping the name — the name is already in the
     * target language and re-translating it would corrupt it. Unlike [ensure] this downloads the
     * model on any connection: the user asked for it and is waiting.
     *
     * Returns true when the row was rewritten.
     */
    suspend fun translateDescription(db: AppDatabase, exerciseId: String, lang: String): Boolean {
        if (lang == "en") return false
        val target = TranslateLanguage.fromLanguageTag(lang) ?: return false
        val rows = db.exerciseDao().translations(exerciseId)
        val row = rows.firstOrNull { it.lang == lang && !it.machine }
            ?: rows.firstOrNull { it.lang == "en" }
            ?: return false
        if (row.description.isBlank()) return false

        val translated = withTimeoutOrNull(TIMEOUT_MS) {
            runCatching {
                val translator = Translation.getClient(
                    TranslatorOptions.Builder()
                        .setSourceLanguage(TranslateLanguage.ENGLISH)
                        .setTargetLanguage(target)
                        .build()
                )
                translator.use {
                    it.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
                    it.translate(row.description).await()
                }
            }.onFailure {
                android.util.Log.w("AutoTranslate", "manual translate $exerciseId -> $lang failed", it)
            }.getOrNull()
        } ?: return false.also {
            android.util.Log.w("AutoTranslate", "manual translate $exerciseId -> $lang gave up")
        }

        // Same rowId so REPLACE overwrites the row in place rather than adding a duplicate.
        db.exerciseDao().insertTranslations(
            listOf(
                if (row.lang == lang) row.copy(description = translated, machine = true)
                else ExerciseTranslation(
                    exerciseId = exerciseId,
                    lang = lang,
                    name = row.name,
                    description = translated,
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
