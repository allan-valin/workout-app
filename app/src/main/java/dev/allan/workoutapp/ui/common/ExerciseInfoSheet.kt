package dev.allan.workoutapp.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.Alignment
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
    /** Current pin state; null = this screen doesn't offer pinning, so the toggle is hidden. */
    notePinned: Boolean? = null,
    onSaveNote: (String, Boolean?) -> Unit = { _, _ -> },
    /** The shown description is an on-device machine translation — label it as such. */
    machineTranslated: Boolean = false,
    /**
     * Translate the shown description on demand. null hides the action. Needed because an
     * exercise can carry a row tagged with the app language whose description is still English
     * (imported plans, pt aliases), which AutoTranslate.ensure declines to touch — leaving the
     * user staring at English with no way to ask (Allan, 26/07).
     */
    onTranslate: (() -> Unit)? = null,
    translating: Boolean = false,
    /** Extra rows shown above the link field (muscles, aliases, attribution, image…). */
    extraContent: @Composable ColumnScope.() -> Unit = {},
) {
    var overlayUrl by remember { mutableStateOf<String?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        // Scrollable: a long description used to eat the sheet's whole height and squeeze the
        // note and link fields into overlapping slivers with their labels clipped away
        // (Allan, 26/07). Without a scroll the Column has no way to overflow, so the children
        // are what gets compressed.
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(name, style = MaterialTheme.typography.headlineSmall)
            Text(description.ifBlank { stringResource(R.string.no_description) })
            if (machineTranslated) {
                Text(
                    stringResource(R.string.machine_translated),
                    style = MaterialTheme.typography.labelSmall,
                )
            } else if (onTranslate != null && description.isNotBlank()) {
                OutlinedButton(
                    onClick = onTranslate,
                    enabled = !translating,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        stringResource(
                            if (translating) R.string.translating else R.string.translate_description
                        )
                    )
                }
            }
            extraContent()

            // Persistent note — pre-filled with what's saved so it survives reopen (Allan's
            // "note comes back empty" bug). Blank + save clears it. Shown in every ℹ sheet.
            if (note != null) {
                var noteText by remember(note) { mutableStateOf(note) }
                var pinned by remember(note, notePinned) { mutableStateOf(notePinned ?: false) }
                OutlinedTextField(
                    value = noteText,
                    onValueChange = { noteText = it },
                    label = { Text(stringResource(R.string.note)) },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                // Pin toggle only where the note can actually be shown (in-session).
                if (notePinned != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Switch(
                            checked = pinned,
                            onCheckedChange = { pinned = it },
                        )
                        Text(
                            stringResource(R.string.pin_note),
                            modifier = Modifier.padding(start = 8.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                // Flipping the toggle arms the button too, otherwise an unedited note
                // could never be pinned.
                if (noteText.trim() != note || pinned != (notePinned ?: false)) {
                    Button(
                        onClick = { onSaveNote(noteText.trim(), notePinned?.let { pinned }) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
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
