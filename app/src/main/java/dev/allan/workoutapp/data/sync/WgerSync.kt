package dev.allan.workoutapp.data.sync

import androidx.room.withTransaction
import dev.allan.workoutapp.data.db.AppDatabase
import dev.allan.workoutapp.data.db.Equipment
import dev.allan.workoutapp.data.db.Exercise
import dev.allan.workoutapp.data.db.ExerciseTranslation
import dev.allan.workoutapp.data.db.Muscle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

@Serializable
private data class WPage<T>(val next: String? = null, val results: List<T> = emptyList())

@Serializable
private data class WLang(val id: Int, val short_name: String)

@Serializable
private data class WMuscle(val id: Int, val name: String, val name_en: String? = null)

@Serializable
private data class WEquipment(val id: Int, val name: String)

@Serializable
private data class WRef(val id: Int)

@Serializable
private data class WCategory(val name: String? = null)

@Serializable
private data class WImage(val image: String? = null, val is_main: Boolean = false)

@Serializable
private data class WAlias(val alias: String? = null)

@Serializable
private data class WTranslation(
    val language: Int? = null,
    val name: String? = null,
    val description: String? = null,
    val aliases: List<WAlias> = emptyList(),
)

@Serializable
private data class WExercise(
    val id: Long,
    val category: WCategory? = null,
    val muscles: List<WRef> = emptyList(),
    val muscles_secondary: List<WRef> = emptyList(),
    val equipment: List<WRef> = emptyList(),
    val images: List<WImage> = emptyList(),
    val translations: List<WTranslation> = emptyList(),
)

/**
 * Explicit "Refresh exercise database" sync against the wger public API.
 * Mirrors tools/wger-snapshot/fetch_wger.py.
 *
 * Safety contract (IMPLEMENTATION_PLAN §3): HTTPS to wger.de only, per-response
 * size cap, strict parse — any failure aborts the whole sync before the first
 * DB write, keeping old data. Custom exercises are never touched (different id
 * namespace); existing imagePath/licenseAuthor survive the upsert. Sync is
 * additive: exercises removed upstream stay locally (may be referenced by plans).
 */
object WgerSync {

    data class Report(val exercises: Int, val translations: Int)

    private const val BASE = "https://wger.de/api/v2"
    private const val MAX_BYTES = 10_000_000
    private const val MAX_PAGES = 200
    private val wantedLangs = setOf("en", "de", "pt")
    private val tagRegex = Regex("<[^>]+>")
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun refresh(db: AppDatabase): Result<Report> = withContext(Dispatchers.IO) {
        runCatching {
            val langById = paginate("$BASE/language/?limit=100", WLang.serializer())
                .associate { it.id to it.short_name }
            val muscles = paginate("$BASE/muscle/?limit=100", WMuscle.serializer())
                .map { Muscle(it.id, it.name, it.name_en?.ifBlank { null } ?: it.name) }
            val equipment = paginate("$BASE/equipment/?limit=100", WEquipment.serializer())
                .map { Equipment(it.id, it.name) }
            val raw = paginate("$BASE/exerciseinfo/?limit=100", WExercise.serializer())

            val existing = db.exerciseDao().snapshotExercises().associateBy { it.id }
            val exercises = ArrayList<Exercise>(raw.size)
            val translations = ArrayList<ExerciseTranslation>(raw.size * 2)
            for (ex in raw) {
                val perLang = LinkedHashMap<String, WTranslation>()
                for (tr in ex.translations) {
                    val short = langById[tr.language] ?: continue
                    if (short !in wantedLangs) continue
                    val name = tr.name?.trim().orEmpty()
                    if (name.isEmpty()) continue
                    perLang.putIfAbsent(short, tr)
                }
                if ("en" !in perLang) continue // unnamed-in-English entries are junk data

                val id = "wger:${ex.id}"
                val old = existing[id]
                val wgerImages = ex.images.filter { it.image?.startsWith("https://wger.de/") == true }
                val mainImage = wgerImages.firstOrNull { it.is_main }?.image
                    ?: wgerImages.firstOrNull()?.image
                exercises += Exercise(
                    id = id,
                    category = ex.category?.name,
                    primaryMuscles = ex.muscles.map { it.id },
                    secondaryMuscles = ex.muscles_secondary.map { it.id },
                    equipment = ex.equipment.map { it.id },
                    imageUrl = mainImage,
                    imagePath = old?.imagePath,
                    isCustom = false,
                    isCardio = ex.category?.name == "Cardio",
                    licenseAuthor = old?.licenseAuthor,
                )
                perLang.forEach { (lang, tr) ->
                    translations += ExerciseTranslation(
                        exerciseId = id,
                        lang = lang,
                        name = tr.name!!.trim(),
                        description = stripHtml(tr.description.orEmpty()).take(2000),
                        aliases = tr.aliases.mapNotNull { it.alias?.trim()?.ifEmpty { null } }.distinct().sorted(),
                    )
                }
            }
            check(exercises.size >= 500) { "suspiciously few exercises (${exercises.size}), aborting" }

            db.withTransaction {
                val dao = db.exerciseDao()
                dao.insertMuscles(muscles)
                dao.insertEquipment(equipment)
                dao.deleteWgerTranslations()
                exercises.chunked(200).forEach { dao.insertExercises(it) }
                translations.chunked(400).forEach { dao.insertTranslations(it) }
            }
            Report(exercises.size, translations.size)
        }
    }

    private fun <T> paginate(startUrl: String, elementSerializer: KSerializer<T>): List<T> {
        val pageSerializer = WPage.serializer(elementSerializer)
        val results = ArrayList<T>()
        var url: String? = startUrl
        repeat(MAX_PAGES) {
            val page = json.decodeFromString(pageSerializer, get(url!!))
            results += page.results
            url = page.next ?: return results
            require(url!!.startsWith("https://wger.de/")) { "refusing non-wger pagination url" }
            Thread.sleep(300) // be polite to wger.de
        }
        error("pagination bound exceeded for $startUrl")
    }

    private fun get(url: String): String {
        require(url.startsWith("https://wger.de/")) { "refusing non-wger url" }
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 60000
        conn.setRequestProperty("User-Agent", "workout-app-sync/1.0")
        conn.setRequestProperty("Accept", "application/json")
        return try {
            check(conn.responseCode == 200) { "HTTP ${conn.responseCode} for $url" }
            val bytes = conn.inputStream.use { it.readBytes() }
            check(bytes.size <= MAX_BYTES) { "response too large: $url" }
            bytes.decodeToString()
        } finally {
            conn.disconnect()
        }
    }

    private fun stripHtml(text: String): String =
        tagRegex.replace(text, "").replace("&nbsp;", " ").trim()
}
