package dev.allan.workoutapp.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dev.allan.workoutapp.data.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Exercise image pipeline: download once when an exercise is added to a workout,
 * downscale to screen width, store in app-internal files for offline use.
 * wger hosts only — refuses other URLs.
 */
object MediaStore {

    private const val MAX_WIDTH = 1080
    private const val MAX_BYTES = 15_000_000

    suspend fun ensureImage(context: Context, db: AppDatabase, exerciseId: String): String? =
        withContext(Dispatchers.IO) {
            val exercise = db.exerciseDao().exercise(exerciseId) ?: return@withContext null
            exercise.imagePath?.let { path ->
                if (File(path).exists()) return@withContext path
            }
            val url = exercise.imageUrl ?: return@withContext null
            if (!url.startsWith("https://wger.de/")) return@withContext null

            val dir = File(context.filesDir, "exercise_media").apply { mkdirs() }
            val isGif = url.substringBefore('?').endsWith(".gif", ignoreCase = true)
            val target = File(dir, exerciseId.replace(':', '_') + if (isGif) ".gif" else ".jpg")
            runCatching {
                val bytes = download(url) ?: return@withContext null
                if (isGif) {
                    // Keep GIFs verbatim — re-encoding would drop the animation.
                    target.writeBytes(bytes)
                } else {
                    val bitmap = decodeScaled(bytes) ?: return@withContext null
                    target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                }
                db.exerciseDao().setImagePath(exerciseId, target.absolutePath)
                target.absolutePath
            }.getOrNull()
        }

    /**
     * Ref-count sweep: delete media files whose exercise is no longer in any workout.
     * Stale Exercise.imagePath is harmless — ensureImage re-checks file existence.
     */
    suspend fun sweep(context: Context, db: AppDatabase) = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "exercise_media")
        if (!dir.isDirectory) return@withContext
        val referenced = db.planDao().referencedExerciseIds()
            .map { it.replace(':', '_') }
            .toSet()
        dir.listFiles()?.forEach { file ->
            if (file.nameWithoutExtension !in referenced) file.delete()
        }
    }

    private fun download(url: String): ByteArray? {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        conn.instanceFollowRedirects = true
        return try {
            if (conn.responseCode != 200) return null
            if (conn.contentLengthLong > MAX_BYTES) return null
            conn.inputStream.use { it.readBytes().takeIf { b -> b.size <= MAX_BYTES } }
        } finally {
            conn.disconnect()
        }
    }

    private fun decodeScaled(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= MAX_WIDTH) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }
}
