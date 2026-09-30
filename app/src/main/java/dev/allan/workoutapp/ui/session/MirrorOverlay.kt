package dev.allan.workoutapp.ui.session

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.MirrorMode
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import dev.allan.workoutapp.R
import dev.allan.workoutapp.session.MirrorClip
import java.time.LocalDateTime

/**
 * Mirror mode (Allan, 19/08): the front camera as a full-screen mirror over the session,
 * with an optional recording. The recording is VIDEO ONLY on purpose — no audio source means
 * no audio focus request, so Spotify keeps playing (the stock camera app pauses it). Clips
 * go to the gallery under Movies/WorkoutApp and are never touched again by the app.
 */
@Composable
fun MirrorOverlay(onClose: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        if (!ok) {
            Toast.makeText(context, R.string.camera_permission_needed, Toast.LENGTH_LONG).show()
            onClose()
        }
    }
    LaunchedEffect(Unit) { if (!granted) permission.launch(Manifest.permission.CAMERA) }

    // Recorder and capture use case live as long as the overlay does.
    val videoCapture = remember {
        val recorder = Recorder.Builder()
            .setQualitySelector(
                QualitySelector.from(Quality.HD, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD))
            )
            .build()
        VideoCapture.Builder(recorder)
            // The clip looks like the mirror did, not flipped.
            .setMirrorMode(MirrorMode.MIRROR_MODE_ON_FRONT_ONLY)
            .build()
    }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var canRecord by remember { mutableStateOf(false) }
    var recordingSecs by remember { mutableStateOf(0L) }
    val savedMsg = stringResource(R.string.recording_saved)
    DisposableEffect(Unit) { onDispose { recording?.stop() } }
    BackHandler { onClose() }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (granted) {
                AndroidView(
                    factory = { ctx ->
                        PreviewView(ctx).apply {
                            scaleType = PreviewView.ScaleType.FILL_CENTER
                            val future = ProcessCameraProvider.getInstance(ctx)
                            future.addListener({
                                val provider = future.get()
                                val preview = Preview.Builder().build().also { it.surfaceProvider = surfaceProvider }
                                provider.unbindAll()
                                // Front camera first; a device without one falls back to the back
                                // camera; one that refuses preview+video keeps the preview only; no
                                // camera at all says so and closes (crashed the emulator otherwise).
                                val selector = listOf(CameraSelector.DEFAULT_FRONT_CAMERA, CameraSelector.DEFAULT_BACK_CAMERA)
                                    .firstOrNull { runCatching { provider.hasCamera(it) }.getOrDefault(false) }
                                if (selector == null) {
                                    Toast.makeText(ctx, R.string.no_camera, Toast.LENGTH_LONG).show()
                                    onClose()
                                    return@addListener
                                }
                                val bound = runCatching {
                                    provider.bindToLifecycle(lifecycleOwner, selector, preview, videoCapture)
                                }.isSuccess
                                if (!bound) runCatching { provider.bindToLifecycle(lifecycleOwner, selector, preview) }
                                canRecord = bound
                            }, ContextCompat.getMainExecutor(ctx))
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            IconButton(
                onClick = onClose,
                modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp),
            ) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close), tint = Color.White)
            }
            Column(
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (recording != null) {
                    Text(
                        String.format(java.util.Locale.ROOT, "● %d:%02d", recordingSecs / 60, recordingSecs % 60),
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val active = recording != null
                    Button(
                        enabled = granted && canRecord,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (active) Color.White else MaterialTheme.colorScheme.error,
                            contentColor = if (active) Color.Black else Color.White,
                        ),
                        onClick = {
                            val current = recording
                            if (current != null) {
                                current.stop()
                                recording = null
                            } else {
                                val values = ContentValues().apply {
                                    put(MediaStore.MediaColumns.DISPLAY_NAME, MirrorClip.name(LocalDateTime.now()))
                                    put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                                    put(MediaStore.MediaColumns.RELATIVE_PATH, MirrorClip.RELATIVE_PATH)
                                }
                                val output = MediaStoreOutputOptions
                                    .Builder(context.contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                                    .setContentValues(values)
                                    .build()
                                recordingSecs = 0
                                // No withAudioEnabled(): silent clip, music untouched.
                                recording = videoCapture.output
                                    .prepareRecording(context, output)
                                    .start(ContextCompat.getMainExecutor(context)) { event ->
                                        when (event) {
                                            is VideoRecordEvent.Status ->
                                                recordingSecs = event.recordingStats.recordedDurationNanos / 1_000_000_000L
                                            is VideoRecordEvent.Finalize -> {
                                                recording = null
                                                if (!event.hasError()) {
                                                    Toast.makeText(context, savedMsg, Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                            else -> {}
                                        }
                                    }
                            }
                        },
                    ) {
                        Icon(
                            if (active) Icons.Default.Stop else Icons.Default.FiberManualRecord,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            "  " + stringResource(if (active) R.string.stop_recording else R.string.record),
                        )
                    }
                }
            }
        }
    }
}
