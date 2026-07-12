package dev.allan.workoutapp.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.allan.workoutapp.R

/**
 * The one exercise-detail surface used everywhere the ℹ button appears (library, workout
 * editor, in-progress session). Always a slide-up bottom sheet — never a popup. Shows the
 * description, an editable video link (blank + save = delete), and Watch / Open buttons
 * whenever a link is saved, so a video can be added straight from the exercise.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExerciseInfoSheet(
    name: String,
    description: String,
    videoUrl: String?,
    onSaveLink: (String) -> Unit,
    onDismiss: () -> Unit,
    /** Persistent per-exercise note (kept across sessions). null hides the note editor. */
    note: String? = null,
    onSaveNote: (String) -> Unit = {},
    /** The shown description is an on-device machine translation — label it as such. */
    machineTranslated: Boolean = false,
    /** Extra rows shown above the link field (muscles, aliases, attribution, image…). */
    extraContent: @Composable ColumnScope.() -> Unit = {},
) {
    var overlayUrl by remember { mutableStateOf<String?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(name, style = MaterialTheme.typography.headlineSmall)
            Text(description.ifBlank { stringResource(R.string.no_description) })
            if (machineTranslated) {
                Text(
                    stringResource(R.string.machine_translated),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            extraContent()

            // Persistent note — pre-filled with what's saved so it survives reopen (Allan's
            // "note comes back empty" bug). Blank + save clears it. Shown in every ℹ sheet.
            if (note != null) {
                var noteText by remember(note) { mutableStateOf(note) }
                OutlinedTextField(
                    value = noteText,
                    onValueChange = { noteText = it },
                    label = { Text(stringResource(R.string.note)) },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (noteText.trim() != note) {
                    Button(onClick = { onSaveNote(noteText.trim()) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.save))
                    }
                }
            }

            // Editable link. The save/delete action is a full-width filled Button (a tick
            // inside the field was invisible against the text — Allan's contrast report).
            var linkText by remember(videoUrl) { mutableStateOf(videoUrl ?: "") }
            OutlinedTextField(
                value = linkText,
                onValueChange = { linkText = it },
                label = { Text(stringResource(R.string.video_link)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (linkText.trim() != (videoUrl ?: "")) {
                Button(onClick = { onSaveLink(linkText) }, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(
                            if (linkText.isBlank() && videoUrl != null) R.string.video_link_delete
                            else R.string.video_link_save
                        )
                    )
                }
            }
            videoUrl?.let { url ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { overlayUrl = url }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Text(stringResource(R.string.watch_video), modifier = Modifier.padding(start = 4.dp))
                    }
                    val ctx = LocalContext.current
                    OutlinedButton(
                        onClick = {
                            runCatching {
                                ctx.startActivity(
                                    android.content.Intent(
                                        android.content.Intent.ACTION_VIEW,
                                        android.net.Uri.parse(url),
                                    )
                                )
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.open_externally), maxLines = 1) }
                }
            }
        }
    }
    overlayUrl?.let { url -> VideoOverlayDialog(url = url, onDismiss = { overlayUrl = null }) }
}

/**
 * Inline YouTube playback via a WebView embed. Needs network (user action only).
 * DOM storage + a WebViewClient are required or the iframe player stays blank.
 * Non-YouTube or unparsable links fall back to loading the URL directly.
 */
@Composable
fun VideoOverlayDialog(url: String, onDismiss: () -> Unit) {
    val embedUrl = remember(url) {
        val id = Regex("""(?:v=|youtu\.be/|shorts/|embed/)([\w-]{11})""").find(url)?.groupValues?.get(1)
        if (id != null) "https://www.youtube.com/embed/$id?autoplay=1&playsinline=1" else url
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        text = {
            androidx.compose.ui.viewinterop.AndroidView(
                factory = { ctx ->
                    android.webkit.WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        webViewClient = android.webkit.WebViewClient()
                        webChromeClient = android.webkit.WebChromeClient()
                        loadUrl(embedUrl)
                    }
                },
                onRelease = { it.destroy() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp),
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        },
    )
}
