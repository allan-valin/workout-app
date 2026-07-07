package dev.allan.workoutapp.ui.session

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.allan.workoutapp.R
import dev.allan.workoutapp.data.db.SetType
import dev.allan.workoutapp.data.db.ValueUnit
import dev.allan.workoutapp.data.db.WeightMode

private fun fmt(secs: Int): String = "%d:%02d".format(secs / 60, secs % 60)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionScreen(
    workoutId: Long,
    appLang: String,
    onExit: () -> Unit,
    onFinished: (Long) -> Unit,
) {
    val app = LocalContext.current.applicationContext as Application
    val vm: SessionViewModel = viewModel(
        key = "session-$workoutId",
        factory = SessionViewModel.Factory(app, workoutId, appLang),
    )
    val state by vm.state.collectAsState()
    var confirmEnd by remember { mutableStateOf<Boolean?>(null) } // null=hidden, true=save, false=discard

    // Countdown notification needs POST_NOTIFICATIONS (runtime since API 33).
    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) {}
    LaunchedEffect(Unit) {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            permissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // One-time HyperOS onboarding: without these exemptions Xiaomi kills the timers.
    val context = LocalContext.current
    var showBatteryOnboarding by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!dev.allan.workoutapp.data.Settings.batteryOnboardingShown(context).first()) {
            showBatteryOnboarding = true
        }
    }
    if (showBatteryOnboarding) {
        BatteryOnboardingDialog(onDismiss = {
            showBatteryOnboarding = false
            (context.applicationContext as dev.allan.workoutapp.WorkoutApp).appScope.launch {
                dev.allan.workoutapp.data.Settings.setBatteryOnboardingShown(context)
            }
        })
    }

    if (state.showList) {
        // ---- Exercise list mode ----
        BackHandler { onExit() }
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(state.workoutName + "  ·  " + fmt(state.elapsedSecs)) },
                    navigationIcon = {
                        IconButton(onClick = onExit) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                        }
                    },
                )
            }
        ) { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.exercises, key = { it.workoutExerciseId }) { ex ->
                        val index = state.exercises.indexOf(ex)
                        Card(onClick = { vm.openExercise(index) }, modifier = Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(ex.name, style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        "${ex.sets.count { it.done }}/${ex.sets.size}",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                if (ex.sets.isNotEmpty() && ex.sets.all { it.done }) {
                                    Icon(Icons.Default.Check, contentDescription = null)
                                }
                            }
                        }
                    }
                }
                Button(
                    onClick = { confirmEnd = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                ) { Text(stringResource(R.string.end_workout)) }
            }
        }
    } else {
        // ---- Exercise pager mode ----
        BackHandler { vm.showList() }
        val pagerState = rememberPagerState(
            initialPage = state.currentIndex,
            pageCount = { state.exercises.size },
        )
        LaunchedEffect(pagerState.currentPage) { vm.setCurrentIndex(pagerState.currentPage) }

        Scaffold(
            topBar = {
                SessionTopBar(vm, state, onEnd = { save -> confirmEnd = save })
            },
        ) { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                HorizontalPager(state = pagerState, modifier = Modifier.weight(1f)) { page ->
                    ExercisePage(page, vm, state)
                }
                if (state.timerPanelVisible) {
                    TimerPanel(vm, state)
                }
            }
        }
    }

    confirmEnd?.let { save ->
        AlertDialog(
            onDismissRequest = { confirmEnd = null },
            title = { Text(stringResource(if (save) R.string.end_save_confirm else R.string.end_discard_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmEnd = null
                    vm.endSession(save) { onFinished(it) }
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmEnd = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionTopBar(vm: SessionViewModel, state: SessionUiState, onEnd: (Boolean) -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    var noteOpen by remember { mutableStateOf(false) }
    val current = state.exercises.getOrNull(state.currentIndex)

    TopAppBar(
        title = { Text(fmt(state.elapsedSecs)) },
        navigationIcon = {
            IconButton(onClick = vm::showList) {
                Icon(Icons.AutoMirrored.Filled.List, contentDescription = stringResource(R.string.back))
            }
        },
        actions = {
            IconButton(onClick = { noteOpen = true }) {
                Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.note))
            }
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = null)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.end_and_save)) },
                    onClick = { menuOpen = false; onEnd(true) },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.end_and_discard)) },
                    onClick = { menuOpen = false; onEnd(false) },
                )
            }
        },
    )

    if (noteOpen && current != null) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { noteOpen = false },
            title = { Text(stringResource(R.string.note)) },
            text = {
                OutlinedTextField(value = text, onValueChange = { text = it }, minLines = 3)
            },
            confirmButton = {
                TextButton(onClick = {
                    if (text.isNotBlank()) vm.saveNote(current.exerciseId, text.trim())
                    noteOpen = false
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { noteOpen = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun ExercisePage(page: Int, vm: SessionViewModel, state: SessionUiState) {
    val ex = state.exercises.getOrNull(page) ?: return
    var editTarget by remember { mutableStateOf<Pair<SessionSet, String>?>(null) } // set + field

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        // Image slot (~1/6 height): offline copy downloaded when the exercise was added.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(110.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            val mediaFile = remember(ex.imagePath) {
                ex.imagePath?.let { java.io.File(it) }?.takeIf { it.exists() }
            }
            if (mediaFile != null) {
                val context = androidx.compose.ui.platform.LocalContext.current
                // GIF-capable loader (minSdk 29 ⇒ ImageDecoder is always available).
                val gifLoader = remember {
                    coil.ImageLoader.Builder(context)
                        .components { add(coil.decode.ImageDecoderDecoder.Factory()) }
                        .build()
                }
                coil.compose.AsyncImage(
                    model = mediaFile,
                    imageLoader = gifLoader,
                    contentDescription = ex.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                )
            } else {
                Icon(Icons.Default.FitnessCenter, contentDescription = null, modifier = Modifier.size(48.dp))
            }
        }

        // Story-style progress bar: one segment per exercise.
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            state.exercises.forEachIndexed { i, e ->
                LinearProgressIndicator(
                    progress = {
                        when {
                            e.sets.isNotEmpty() && e.sets.all { it.done } -> 1f
                            i == page -> 0.5f
                            else -> 0f
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(4.dp),
                )
            }
        }

        Text(ex.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            when (ex.weightMode) {
                WeightMode.TOTAL -> stringResource(R.string.weight_total)
                WeightMode.PER_DUMBBELL -> stringResource(R.string.weight_per_dumbbell)
                WeightMode.PER_SIDE -> stringResource(R.string.weight_per_side) +
                    " (${stringResource(R.string.bar_weight)}: ${ex.barWeightKg})"
            },
            style = MaterialTheme.typography.labelMedium,
        )

        // Sets table — the only scrollable part of the page.
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            ex.sets.forEach { set ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp),
                ) {
                    Text(
                        when (set.type) {
                            SetType.WARMUP -> stringResource(R.string.set_warmup)
                            SetType.NORMAL -> stringResource(R.string.set_normal)
                            SetType.FAILURE -> stringResource(R.string.set_failure)
                            SetType.DROP -> stringResource(R.string.set_drop)
                            SetType.SUPERSET -> stringResource(R.string.set_superset)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.width(width = 64.dp),
                    )
                    OutlinedButton(onClick = { editTarget = set to "weight" }, modifier = Modifier.weight(1.2f)) {
                        Text("${if (set.weightKg % 1.0 == 0.0) set.weightKg.toInt() else set.weightKg} kg")
                    }
                    OutlinedButton(onClick = { editTarget = set to "value" }, modifier = Modifier.weight(1f)) {
                        Text(
                            "${set.value} " + if (set.valueUnit == ValueUnit.REPS)
                                stringResource(R.string.reps) else stringResource(R.string.secs)
                        )
                    }
                    if (set.valueUnit == ValueUnit.SECS) {
                        IconButton(onClick = { vm.startSetCountdown(set) }) {
                            Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.start_timer))
                        }
                    }
                    IconButton(onClick = { vm.logSet(page, set) }) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = stringResource(R.string.log_set),
                            tint = if (set.done) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                }
            }
        }

        // Bottom action row: timer panel toggle + log next undone set.
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = vm::toggleTimerPanel) {
                Icon(Icons.Default.Timer, contentDescription = stringResource(R.string.timer))
            }
            Button(
                onClick = {
                    ex.sets.firstOrNull { !it.done }?.let { vm.logSet(page, it) }
                },
                enabled = ex.sets.any { !it.done },
            ) { Text(stringResource(R.string.log_set)) }
        }
    }

    editTarget?.let { (set, field) ->
        NumberPadDialog(
            initial = if (field == "weight") set.weightKg else set.value.toDouble(),
            isWeight = field == "weight",
            onDismiss = { editTarget = null },
            onConfirm = { newValue ->
                val updated = if (field == "weight") set.copy(weightKg = newValue.coerceAtLeast(0.0))
                else set.copy(value = newValue.toInt().coerceAtLeast(0))
                vm.updateSet(page, updated)
                editTarget = null
            },
        )
    }
}

@Composable
private fun TimerPanel(vm: SessionViewModel, state: SessionUiState) {
    Surface(tonalElevation = 4.dp, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.rest), style = MaterialTheme.typography.labelSmall)
                Text(
                    state.restRemainingSecs?.let(::fmt) ?: "–",
                    style = MaterialTheme.typography.headlineMedium,
                    textAlign = TextAlign.Center,
                )
            }
            if (state.setCountdownRemainingSecs != null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.set_timer), style = MaterialTheme.typography.labelSmall)
                    Text(fmt(state.setCountdownRemainingSecs), style = MaterialTheme.typography.headlineMedium)
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.stopwatch), style = MaterialTheme.typography.labelSmall)
                Text(fmt(state.stopwatchSecs), style = MaterialTheme.typography.headlineMedium)
            }
            IconButton(onClick = vm::toggleStopwatch) {
                Icon(
                    if (state.stopwatchRunning) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = stringResource(R.string.stopwatch),
                )
            }
            IconButton(onClick = vm::stopRest) {
                Icon(Icons.Default.Stop, contentDescription = stringResource(R.string.stop_rest))
            }
        }
    }
}

/**
 * One-time dialog pointing at HyperOS battery-exemption + autostart settings —
 * without both, MIUI/HyperOS kills the foreground service and the rest timers die.
 */
@Composable
private fun BatteryOnboardingDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.battery_onboarding_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.battery_onboarding_text))
                OutlinedButton(
                    onClick = {
                        runCatching {
                            context.startActivity(
                                android.content.Intent(
                                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                    android.net.Uri.parse("package:${context.packageName}"),
                                )
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.battery_exemption_button)) }
                OutlinedButton(
                    onClick = {
                        // Xiaomi/HyperOS autostart manager; falls back to app details.
                        val autostart = android.content.Intent().setClassName(
                            "com.miui.securitycenter",
                            "com.miui.permcenter.autostart.AutoStartManagementActivity",
                        )
                        runCatching { context.startActivity(autostart) }.onFailure {
                            runCatching {
                                context.startActivity(
                                    android.content.Intent(
                                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        android.net.Uri.parse("package:${context.packageName}"),
                                    )
                                )
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.autostart_button)) }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) }
        },
    )
}

/** Tap-to-edit overlay: ± quick increments plus direct numeric input. */
@Composable
private fun NumberPadDialog(
    initial: Double,
    isWeight: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (Double) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    val increments = if (isWeight) listOf(1.25, 2.5, 5.0, 10.0, 15.0, 20.0) else listOf(1.0, 5.0, 10.0)

    AlertDialog(
        onDismissRequest = onDismiss,
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = if (value % 1.0 == 0.0) value.toInt().toString() else value.toString(),
                    onValueChange = { value = it.replace(',', '.').toDoubleOrNull() ?: value },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal
                    ),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.headlineSmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    increments.forEach { inc ->
                        OutlinedButton(
                            onClick = { value += inc },
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(2.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                "+${if (inc % 1.0 == 0.0) inc.toInt() else inc}",
                                maxLines = 1,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    increments.forEach { inc ->
                        OutlinedButton(
                            onClick = { value = (value - inc).coerceAtLeast(0.0) },
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(2.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                "-${if (inc % 1.0 == 0.0) inc.toInt() else inc}",
                                maxLines = 1,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value) }) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
