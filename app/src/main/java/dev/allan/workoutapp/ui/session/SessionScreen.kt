package dev.allan.workoutapp.ui.session

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.HideSource
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.TimerOff
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
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
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
    onEditExercise: (Long) -> Unit = {},
) {
    val app = LocalContext.current.applicationContext as Application
    val vm: SessionViewModel = viewModel(
        key = "session-$workoutId",
        factory = SessionViewModel.Factory(app, workoutId, appLang),
    )
    val state by vm.state.collectAsState()
    var confirmEnd by remember { mutableStateOf<Boolean?>(null) } // null=hidden, true=save, false=discard
    // After the session save/discard choice, if the plan was edited mid-session, ask whether
    // to keep those edits or treat them as one-time. Holds the session-save choice meanwhile.
    var askKeepPlan by remember { mutableStateOf<Boolean?>(null) }
    val finishWith = { save: Boolean ->
        confirmEnd = null
        if (state.templatesChanged) askKeepPlan = save
        else vm.endSession(save, keepPlanChanges = true) { onFinished(it) }
    }

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
    // Templates can change while we're away (per-exercise edit mid-session) — reload
    // when this nav entry comes back to the foreground.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) vm.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
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
                    title = {
                        Text(state.workoutName + "  ·  " + fmt(state.elapsedSecs) + "  /  ≈" + fmt(state.estimatedTotalSecs))
                    },
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
                                IconButton(onClick = { onEditExercise(ex.workoutExerciseId) }) {
                                    Icon(
                                        Icons.Default.Edit,
                                        contentDescription = stringResource(R.string.edit_training),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
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
        // Auto-advance after logging: superset partner, next exercise, or back here.
        // Keyed on swipeToken (not the target index) so two advances to the same page
        // still fire — see SessionUiState.swipeToken (bug B).
        LaunchedEffect(state.swipeToken) {
            state.pendingSwipeTo?.let { target ->
                if (target in state.exercises.indices) pagerState.animateScrollToPage(target)
                vm.clearPendingSwipe()
            }
        }
        val prevNextEnabled by dev.allan.workoutapp.data.Settings.prevNextButtons(context)
            .collectAsState(initial = false)
        val pagerScope = androidx.compose.runtime.rememberCoroutineScope()

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
                if (prevNextEnabled) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        IconButton(
                            enabled = pagerState.currentPage > 0,
                            onClick = {
                                pagerScope.launch {
                                    pagerState.animateScrollToPage(pagerState.currentPage - 1)
                                }
                            },
                        ) {
                            Icon(
                                Icons.Default.ChevronLeft,
                                contentDescription = stringResource(R.string.prev_exercise),
                            )
                        }
                        IconButton(
                            enabled = pagerState.currentPage < state.exercises.lastIndex,
                            onClick = {
                                pagerScope.launch {
                                    pagerState.animateScrollToPage(pagerState.currentPage + 1)
                                }
                            },
                        ) {
                            Icon(
                                Icons.Default.ChevronRight,
                                contentDescription = stringResource(R.string.next_exercise),
                            )
                        }
                    }
                }
                if (state.timerPanelVisible) {
                    TimerPanel(vm, state)
                }
            }
        }
    }

    state.descriptionSheet?.let { desc ->
        // Same detail sheet as the library/editor: editable link + watch/open, so a video
        // can be added straight from the exercise mid-session.
        dev.allan.workoutapp.ui.common.ExerciseInfoSheet(
            name = state.exercises.getOrNull(state.currentIndex)?.name ?: "",
            description = desc,
            videoUrl = state.descriptionVideoUrl,
            onSaveLink = { url -> state.descriptionExerciseId?.let { vm.saveVideoLink(it, url) } },
            onDismiss = vm::closeDescription,
            note = state.descriptionNote,
            onSaveNote = { txt -> state.descriptionExerciseId?.let { vm.saveNote(it, txt) } },
        )
    }

    if (confirmEnd != null) {
        // One dialog for both outcomes: colored save/discard buttons, plus a warning
        // when open sets remain so an accidental early end is obvious.
        val totalSets = state.exercises.sumOf { it.sets.size }
        val doneSets = state.exercises.sumOf { s -> s.sets.count { it.done } }
        AlertDialog(
            onDismissRequest = { confirmEnd = null },
            title = { Text(stringResource(R.string.end_workout)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (doneSets < totalSets) {
                        Text(
                            stringResource(R.string.end_incomplete_warning, totalSets - doneSets, totalSets),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Button(
                        onClick = { finishWith(true) },
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                            containerColor = DoneGreen,
                            contentColor = androidx.compose.ui.graphics.Color.White,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.end_save)) }
                    Button(
                        onClick = { finishWith(false) },
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.end_discard)) }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { confirmEnd = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    askKeepPlan?.let { save ->
        AlertDialog(
            onDismissRequest = { askKeepPlan = null },
            title = { Text(stringResource(R.string.keep_plan_changes_title)) },
            text = { Text(stringResource(R.string.keep_plan_changes_msg)) },
            confirmButton = {
                TextButton(onClick = {
                    askKeepPlan = null
                    vm.endSession(save, keepPlanChanges = true) { onFinished(it) }
                }) { Text(stringResource(R.string.keep_changes)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    askKeepPlan = null
                    vm.endSession(save, keepPlanChanges = false) { onFinished(it) }
                }) { Text(stringResource(R.string.one_time_only)) }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionTopBar(vm: SessionViewModel, state: SessionUiState, onEnd: (Boolean) -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    val current = state.exercises.getOrNull(state.currentIndex)
    val ctx = LocalContext.current
    val showClock by dev.allan.workoutapp.data.Settings.showClock(ctx).collectAsState(initial = true)
    val clockScope = androidx.compose.runtime.rememberCoroutineScope()

    TopAppBar(
        title = {
            if (showClock) Text(fmt(state.elapsedSecs) + "  /  ≈" + fmt(state.estimatedTotalSecs))
        },
        navigationIcon = {
            IconButton(onClick = vm::showList) {
                Icon(Icons.AutoMirrored.Filled.List, contentDescription = stringResource(R.string.back))
            }
        },
        actions = {
            // Clock show/hide (default shown).
            IconButton(onClick = {
                clockScope.launch { dev.allan.workoutapp.data.Settings.setShowClock(ctx, !showClock) }
            }) {
                Icon(
                    if (showClock) Icons.Default.Schedule else Icons.Default.HideSource,
                    contentDescription = stringResource(R.string.tempo_show_clock),
                )
            }
            // Info sheet hosts description + persistent note + video, so one button covers all.
            TextButton(onClick = { current?.let { vm.openDescription(it.exerciseId) } }) {
                Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.note), modifier = Modifier.padding(start = 4.dp))
            }
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = null)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                // One action — the confirmation dialog is where save-vs-discard is chosen.
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.end_workout)) },
                    onClick = { menuOpen = false; onEnd(true) },
                )
            }
        },
    )

}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ExercisePage(page: Int, vm: SessionViewModel, state: SessionUiState) {
    val ex = state.exercises.getOrNull(page) ?: return
    var editTarget by remember { mutableStateOf<Pair<SessionSet, String>?>(null) } // set + field
    // In-session plan editing (mirrors the editor): type picker, target dialog, long-press remove.
    var typeMenuFor by remember { mutableStateOf<Long?>(null) }   // templateId
    var targetEditFor by remember { mutableStateOf<SessionSet?>(null) }
    var removeSetTarget by remember { mutableStateOf<SessionSet?>(null) }

    // Image collapses when the sets table scrolls up and reappears on scroll-down at the top.
    val tableScroll = rememberScrollState()
    var imageVisible by remember { mutableStateOf(true) }
    val tableScrollConnection = remember(tableScroll) {
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            override fun onPreScroll(
                available: androidx.compose.ui.geometry.Offset,
                source: androidx.compose.ui.input.nestedscroll.NestedScrollSource,
            ): androidx.compose.ui.geometry.Offset {
                if (available.y < -4 && tableScroll.maxValue > 0) imageVisible = false
                else if (available.y > 4 && tableScroll.value == 0) imageVisible = true
                return androidx.compose.ui.geometry.Offset.Zero
            }
        }
    }
    val imageHeight by androidx.compose.animation.core.animateDpAsState(
        targetValue = if (imageVisible) 165.dp else 0.dp,
        label = "imageHeight",
    )

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        // Image slot (~1/6 height): offline copy downloaded when the exercise was added.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(imageHeight)
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

        // Story-style progress bar: one segment per exercise; tap a segment to jump there.
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
                        .height(8.dp)
                        .clickable { vm.requestSwipe(i) },
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

        // Superset chain: name the partner(s) so the alternation is visible.
        val chain = SupersetOrder.chain(state.exercises, page)
        if (chain.size > 1) {
            Text(
                stringResource(
                    R.string.superset_chain,
                    chain.joinToString(" ↔ ") { state.exercises[it].name },
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }

        // Progression hint (never auto-applied): tap to take it, ✕ to dismiss.
        ex.suggestion?.let { s ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.AssistChip(
                    onClick = { vm.applySuggestion(page) },
                    label = {
                        Text(
                            if (s.kind == dev.allan.workoutapp.data.ProgressionEngine.Kind.ADD_WEIGHT)
                                stringResource(
                                    R.string.suggestion_add_weight,
                                    if (s.weightIncrementKg % 1.0 == 0.0) "${s.weightIncrementKg.toInt()}"
                                    else "${s.weightIncrementKg}",
                                )
                            else stringResource(R.string.suggestion_add_rep)
                        )
                    },
                )
                IconButton(onClick = { vm.dismissSuggestion(page) }) {
                    Icon(
                        androidx.compose.material.icons.Icons.Default.Close,
                        contentDescription = stringResource(R.string.cancel),
                    )
                }
            }
        }

        // Cadence/tempo reminder for the current set — big, between the clock and the sets.
        val curTempo = ex.sets.firstOrNull { state.currentStep == page to it.templateId }?.tempo?.takeIf { it.isNotBlank() }
            ?: ex.sets.firstOrNull { it.tempo.isNotBlank() }?.tempo
        if (curTempo != null) {
            Text(
                curTempo,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            )
        }

        // Sets table — the only scrollable part of the page.
        Column(
            Modifier
                .weight(1f)
                .nestedScroll(tableScrollConnection)
                .verticalScroll(tableScroll),
        ) {
            // Column captions: what to tap, what the numbers mean.
            val doneHeader = when {
                ex.sets.isNotEmpty() && ex.sets.all { it.valueUnit == ValueUnit.SECS } ->
                    stringResource(R.string.secs)
                ex.sets.any { it.valueUnit == ValueUnit.SECS } ->
                    stringResource(R.string.reps) + "/" + stringResource(R.string.secs)
                else -> stringResource(R.string.reps)
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                SessionHeader(stringResource(R.string.header_type), Modifier.width(28.dp))
                SessionHeader(
                    stringResource(R.string.kg) + " · " + when (ex.weightMode) {
                        WeightMode.TOTAL -> stringResource(R.string.weight_total)
                        WeightMode.PER_DUMBBELL -> stringResource(R.string.weight_per_dumbbell)
                        WeightMode.PER_SIDE -> stringResource(R.string.weight_per_side)
                    }.lowercase(),
                    Modifier.weight(1.5f),
                )
                SessionHeader(doneHeader, Modifier.weight(1f))
                SessionHeader(stringResource(R.string.header_target), Modifier.width(52.dp))
                SessionHeader("", Modifier.width(48.dp))
            }
            ex.sets.forEach { set ->
                val isCurrent = state.currentStep == page to set.templateId
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            // Highlight = "you are here" in the (superset-aware) set order.
                            if (isCurrent) MaterialTheme.colorScheme.primaryContainer
                            else androidx.compose.ui.graphics.Color.Transparent,
                            RoundedCornerShape(10.dp),
                        )
                        // Long-press a set to remove it (mirrors the editor's delete).
                        .combinedClickable(onClick = {}, onLongClick = { removeSetTarget = set })
                        .padding(vertical = 3.dp, horizontal = 2.dp),
                ) {
                    // Tap the type letter to change the set type in-session.
                    Box(Modifier.width(28.dp)) {
                        Text(
                            when (set.type) {
                                SetType.WARMUP -> stringResource(R.string.set_warmup)
                                SetType.NORMAL -> stringResource(R.string.set_normal)
                                SetType.FAILURE -> stringResource(R.string.set_failure)
                                SetType.DROP -> stringResource(R.string.set_drop)
                                SetType.SUPERSET -> stringResource(R.string.set_superset)
                            }.take(1),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = setTypeColor(set.type),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { typeMenuFor = set.templateId },
                        )
                        DropdownMenu(expanded = typeMenuFor == set.templateId, onDismissRequest = { typeMenuFor = null }) {
                            listOf(SetType.WARMUP, SetType.NORMAL, SetType.FAILURE, SetType.DROP).forEach { ty ->
                                DropdownMenuItem(
                                    text = { Text(ty.name) },
                                    onClick = { typeMenuFor = null; vm.setSetType(set, ty) },
                                )
                            }
                        }
                    }
                    // − weight + quick-steppers around the tap-to-edit weight button.
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1.5f),
                    ) {
                        IconButton(
                            onClick = { vm.updateWeight(page, set, (set.weightKg - 1.0).coerceAtLeast(0.0)) },
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(
                                Icons.Default.Remove,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        OutlinedButton(
                            onClick = { editTarget = set to "weight" },
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("${if (set.weightKg % 1.0 == 0.0) set.weightKg.toInt() else set.weightKg}")
                        }
                        IconButton(
                            onClick = { vm.updateWeight(page, set, set.weightKg + 1.0) },
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    OutlinedButton(
                        onClick = { editTarget = set to "value" },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("${set.value}")
                    }
                    // Reference goal from the plan (what you aim for, not what you log) — tap to edit.
                    Text(
                        if (set.valueUnit == ValueUnit.REPS)
                            set.targetMax?.let { "${set.targetMin}–$it" } ?: "${set.targetMin}"
                        else "${set.targetMin}s",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.width(52.dp).clickable { targetEditFor = set },
                    )
                    if (set.valueUnit == ValueUnit.SECS) {
                        IconButton(onClick = { vm.startSetCountdown(set) }) {
                            Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.start_timer))
                        }
                    }
                    IconButton(onClick = { vm.logSet(page, set) }) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = stringResource(R.string.log_set),
                            // Done = medium green; undone = primary so it stays visible on
                            // the primaryContainer current-set highlight (gray vanished there).
                            tint = if (set.done) DoneGreen
                            else MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            // Row-wide add-set button (mirrors the editor). Long-press a row to remove.
            TextButton(
                onClick = { vm.addSessionSet(page) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.add_set), modifier = Modifier.padding(start = 6.dp))
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
                if (field == "weight") vm.updateWeight(page, set, newValue.coerceAtLeast(0.0))
                else vm.updateSet(page, set.copy(value = newValue.toInt().coerceAtLeast(0)))
                editTarget = null
            },
        )
    }

    // Edit the plan goal (reps range / seconds) mid-session.
    targetEditFor?.let { set ->
        var minText by remember(set.templateId) { mutableStateOf(set.targetMin.toString()) }
        var maxText by remember(set.templateId) { mutableStateOf(set.targetMax?.toString() ?: "") }
        AlertDialog(
            onDismissRequest = { targetEditFor = null },
            title = { Text(stringResource(R.string.header_target)) },
            text = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = minText, onValueChange = { minText = it.filter(Char::isDigit).take(3) },
                        label = { Text(stringResource(R.string.reps)) }, singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    if (set.valueUnit == ValueUnit.REPS) {
                        Text("–")
                        OutlinedTextField(
                            value = maxText, onValueChange = { maxText = it.filter(Char::isDigit).take(3) },
                            label = { Text("max") }, singleLine = true, modifier = Modifier.weight(1f),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val min = minText.toIntOrNull()?.coerceAtLeast(1) ?: set.targetMin
                    vm.setSetTarget(set, min, maxText.toIntOrNull())
                    targetEditFor = null
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = { TextButton(onClick = { targetEditFor = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    removeSetTarget?.let { set ->
        AlertDialog(
            onDismissRequest = { removeSetTarget = null },
            title = { Text(stringResource(R.string.delete)) },
            confirmButton = {
                TextButton(onClick = { vm.removeSessionSet(set); removeSetTarget = null }) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = { TextButton(onClick = { removeSetTarget = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun SessionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        textAlign = TextAlign.Center,
        modifier = modifier,
    )
}

/** Medium green for completed states — visible on the current-set highlight in both themes. */
internal val DoneGreen = androidx.compose.ui.graphics.Color(0xFF43A047)

/** Color code per set type so the single letters scan at a glance. */
@Composable
private fun setTypeColor(type: SetType): androidx.compose.ui.graphics.Color = when (type) {
    SetType.WARMUP -> androidx.compose.ui.graphics.Color(0xFFFF9800)   // orange
    SetType.NORMAL -> MaterialTheme.colorScheme.onSurface
    SetType.FAILURE -> MaterialTheme.colorScheme.error
    SetType.DROP -> androidx.compose.ui.graphics.Color(0xFFAB47BC)     // purple
    SetType.SUPERSET -> MaterialTheme.colorScheme.tertiary
}

@Composable
private fun TimerPanel(vm: SessionViewModel, state: SessionUiState) {
    // One centered timer wearing three hats: rest countdown → timed-set countdown →
    // set-duration stopwatch. The header names the current role; the session's active
    // total is deliberately absent here (shown only in the end-of-workout summary).
    val restRunning = state.restRemainingSecs != null
    val setCountdownRunning = state.setCountdownRemainingSecs != null
    Surface(tonalElevation = 4.dp, modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(
                    when {
                        restRunning -> R.string.rest
                        setCountdownRunning -> R.string.set_timer
                        else -> R.string.log_set_duration
                    }
                ),
                style = MaterialTheme.typography.labelMedium,
            )
            Text(
                when {
                    restRunning -> fmt(state.restRemainingSecs ?: 0)
                    setCountdownRunning -> fmt(state.setCountdownRemainingSecs ?: 0)
                    else -> fmt(state.stopwatchSecs)
                },
                style = MaterialTheme.typography.displaySmall,
                textAlign = TextAlign.Center,
                color = if (restRunning) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when {
                    restRunning -> TextButton(onClick = vm::stopRest) {
                        Icon(Icons.Default.TimerOff, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(stringResource(R.string.stop_rest), modifier = Modifier.padding(start = 4.dp))
                    }
                    else -> {
                        IconButton(onClick = vm::toggleStopwatch, enabled = !setCountdownRunning) {
                            Icon(
                                if (state.stopwatchRunning) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = stringResource(R.string.stopwatch),
                            )
                        }
                        IconButton(
                            onClick = vm::resetStopwatch,
                            enabled = !setCountdownRunning && (state.stopwatchRunning || state.stopwatchSecs > 0),
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = stringResource(R.string.stopwatch_reset))
                        }
                    }
                }
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
