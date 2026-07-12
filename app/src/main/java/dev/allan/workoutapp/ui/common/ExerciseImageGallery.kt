package dev.allan.workoutapp.ui.common

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import dev.allan.workoutapp.R
import dev.allan.workoutapp.WorkoutApp
import dev.allan.workoutapp.data.db.ExerciseImagePref
import dev.allan.workoutapp.data.db.ExerciseUserImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

/**
 * The images available for an exercise: the wger illustration (when downloaded) plus any
 * user-linked gallery photos. The representative image = the saved pref when valid, else
 * the first available.
 */
@Composable
fun rememberExerciseImages(exerciseId: String, wgerPath: String?): Pair<List<String>, String?> {
    val context = LocalContext.current
    val db = (context.applicationContext as WorkoutApp).db
    val userImages by db.exerciseDao().userImagesFlow(exerciseId)
        .collectAsState(initial = emptyList())
    val pref by db.exerciseDao().imagePrefFlow(exerciseId).collectAsState(initial = null)
    // Callers without the wger path at hand pass null; resolve it here.
    val wger by androidx.compose.runtime.produceState(initialValue = wgerPath, exerciseId) {
        if (value == null) value = db.exerciseDao().exercise(exerciseId)?.imagePath
    }
    val images = remember(wger, userImages) {
        (listOfNotNull(wger) + userImages.map { it.path }).filter { File(it).exists() }
    }
    val display = pref?.takeIf { it in images } ?: images.firstOrNull()
    return images to display
}

/** Photo-picker launcher: copies the pick into app storage and links (+ prefers) it. */
@Composable
fun rememberUserImagePicker(exerciseId: String): () -> Unit {
    val context = LocalContext.current
    val db = (context.applicationContext as WorkoutApp).db
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) scope.launch(Dispatchers.IO) {
            val dir = File(context.filesDir, "exercise_media").apply { mkdirs() }
            val out = File(dir, "user_${System.currentTimeMillis()}.jpg")
            context.contentResolver.openInputStream(uri)?.use { input ->
                out.outputStream().use { input.copyTo(it) }
            }
            if (out.exists() && out.length() > 0) {
                db.exerciseDao().insertUserImage(
                    ExerciseUserImage(exerciseId = exerciseId, path = out.path)
                )
                db.exerciseDao().upsertImagePref(ExerciseImagePref(exerciseId, out.path))
            }
        }
    }
    return {
        launcher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }
}

/**
 * Gallery for the ℹ sheets: side triangles page through the exercise's images (wrapping at
 * the ends); the LAST page is a big circular + that links a photo from the device gallery.
 * The image last viewed here becomes the exercise's representative image ("last viewed
 * wins" — Allan), shown in-session and anywhere a single image appears.
 */
@Composable
fun ExerciseImageGallery(exerciseId: String, wgerPath: String?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val db = (context.applicationContext as WorkoutApp).db
    val scope = rememberCoroutineScope()
    val (images, display) = rememberExerciseImages(exerciseId, wgerPath)
    val pick = rememberUserImagePicker(exerciseId)
    val pageCount = images.size + 1 // + trailing add-page
    var page by remember(images.size, display) {
        mutableIntStateOf(images.indexOf(display).coerceAtLeast(0))
    }
    // Landing on an image page makes it the representative image.
    LaunchedEffect(page, images) {
        images.getOrNull(page)?.let { path ->
            scope.launch { db.exerciseDao().upsertImagePref(ExerciseImagePref(exerciseId, path)) }
        }
    }
    Column(modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.choose_image),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (pageCount > 1) {
                IconButton(onClick = { page = (page - 1 + pageCount) % pageCount }) {
                    Icon(Icons.Default.KeyboardArrowLeft, contentDescription = null)
                }
            }
            Box(
                Modifier
                    .weight(1f)
                    .height(180.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                val path = images.getOrNull(page)
                if (path != null) {
                    AsyncImage(
                        model = File(path),
                        imageLoader = AppImageLoader.get(context),
                        contentDescription = null,
                        modifier = Modifier.fillMaxWidth().height(180.dp),
                    )
                } else {
                    // Trailing add-page: big circular +.
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        FilledIconButton(onClick = pick, modifier = Modifier.size(64.dp)) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = stringResource(R.string.add_own_image),
                                modifier = Modifier.size(32.dp),
                            )
                        }
                        Text(
                            stringResource(R.string.add_own_image),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
            if (pageCount > 1) {
                IconButton(onClick = { page = (page + 1) % pageCount }) {
                    Icon(Icons.Default.KeyboardArrowRight, contentDescription = null)
                }
            }
        }
    }
}
