package dev.allan.workoutapp.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.imePadding
import kotlinx.coroutines.launch
import androidx.compose.ui.focus.onFocusChanged
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
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
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
        // The sheet lives in its own window, created with adjust=nothing, so the keyboard never
        // reported an inset and the note/link field hid behind it (Allan, 29/08; seen with
        // dumpsys window on the emulator). Ask that window to resize; then imePadding BEFORE
        // the scroll shrinks the viewport above the keyboard and the focused field is brought
        // into view.
        resizeForKeyboard()
        // Belt and braces: the IME inset does not reach this window on every device/version,
        // so a focused field is also scrolled to the top of the sheet by hand, with a spacer
        // below the content giving the scroll enough room (verified on the emulator, where the
        // inset never arrived).
        val scrollState = rememberScrollState()
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        var fieldFocused by remember { mutableStateOf(false) }
        // Lift = jump to the end of the scroll range once the spacer below the content is laid
        // out: the spacer is a keyboard's height, so everything above it ends up above the
        // keyboard. Needs no field positions and no window insets.
        fun liftOnFocus() = Modifier.onFocusChanged { f ->
            if (f.isFocused) {
                fieldFocused = true
                scope.launch {
                    val before = scrollState.maxValue
                    repeat(30) { androidx.compose.runtime.withFrameNanos { }; if (scrollState.maxValue > before) return@repeat }
                    scrollState.scrollTo(scrollState.maxValue)
                }
            }
        }
        Column(
            Modifier
                .imePadding()
                .verticalScroll(scrollState)
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
                    modifier = Modifier.fillMaxWidth().then(liftOnFocus()),
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
                modifier = Modifier.fillMaxWidth().then(liftOnFocus()),
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
            // Room for the lift-on-focus scroll (roughly a keyboard's height).
            androidx.compose.foundation.layout.Spacer(
                Modifier.height(if (fieldFocused) 340.dp else 0.dp)
            )
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

/**
 * Make the enclosing dialog/sheet window resize for the soft keyboard. Material3's sheet
 * dialog sets SOFT_INPUT_ADJUST_NOTHING on API 30+ (seen in the 1.3.2 bytecode) and re-applies
 * it whenever its parameters update — every second in a session, because the ticking clock
 * hands it a new onDismiss lambda. With adjust=nothing the window gets no IME inset at all, so
 * imePadding() had nothing to pad. This keeps the mode at ADJUST_RESIZE for as long as the
 * content is composed (one cheap attribute check per frame). Compose dialog windows expose
 * themselves through [androidx.compose.ui.window.DialogWindowProvider]; the host view itself
 * is the provider for a Material3 sheet, a plain Dialog's is one level up.
 */
@Composable
fun resizeForKeyboard() {
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.LaunchedEffect(view) {
        var node: Any? = view
        var window: android.view.Window? = null
        while (node != null && window == null) {
            if (node is androidx.compose.ui.window.DialogWindowProvider) window = node.window
            node = (node as? android.view.View)?.parent
        }
        val w = window ?: return@LaunchedEffect
        val mask = android.view.WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST
        val resize = android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        while (true) {
            if (w.attributes.softInputMode and mask != resize) w.setSoftInputMode(resize)
            androidx.compose.runtime.withFrameNanos { }
        }
    }
}
