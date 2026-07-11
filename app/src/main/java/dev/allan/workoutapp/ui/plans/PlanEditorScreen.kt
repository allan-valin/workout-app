package dev.allan.workoutapp.ui.plans

import android.app.Application
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.allan.workoutapp.R
import dev.allan.workoutapp.WorkoutApp
import dev.allan.workoutapp.data.db.Plan
import dev.allan.workoutapp.data.db.Workout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

class PlanEditorViewModel(app: Application, private val planId: Long) : AndroidViewModel(app) {
    private val db = (app as WorkoutApp).db

    private val _plan = MutableStateFlow<Plan?>(null)
    val plan: StateFlow<Plan?> = _plan

    val workouts: StateFlow<List<Workout>> = db.planDao().workouts(planId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** workoutId -> number of exercises, for the count badges. */
    val exerciseCounts: StateFlow<Map<Long, Int>> = db.planDao().exerciseCounts()
        .map { rows -> rows.associate { it.workoutId to it.count } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    init {
        refreshPlan()
    }

    private fun refreshPlan() {
        viewModelScope.launch { _plan.value = db.planDao().plan(planId) }
    }

    fun rename(name: String) = update { it.copy(name = name) }

    /** Exit path: names are unique per kind — dedupe the final cycle name before leaving. */
    fun finalizeName(onDone: () -> Unit) {
        viewModelScope.launch {
            val current = _plan.value
            if (current != null) {
                val unique = dev.allan.workoutapp.data.PlanRepo.uniqueName(
                    db.planDao().planNamesExcept(current.id), current.name,
                )
                if (unique != current.name) {
                    val next = current.copy(name = unique)
                    _plan.value = next
                    db.planDao().updatePlan(next)
                }
            }
            onDone()
        }
    }

    fun setCycleWeeks(weeks: Int?) = update { it.copy(cycleWeeks = weeks) }

    fun setActive(active: Boolean) = update { it.copy(isActive = active) }

    /** Activate this cycle, deactivating any other (one-active-cycle rule). */
    fun activate() {
        val current = _plan.value ?: return
        viewModelScope.launch {
            db.planDao().deactivateAllPlans()
            val next = current.copy(isActive = true)
            _plan.value = next
            db.planDao().updatePlan(next)
        }
    }

    private fun update(transform: (Plan) -> Plan) {
        val current = _plan.value ?: return
        val next = transform(current)
        _plan.value = next
        viewModelScope.launch { db.planDao().updatePlan(next) }
    }

    fun addWorkout(name: String) {
        viewModelScope.launch {
            dev.allan.workoutapp.data.PlanRepo.createWorkout(db, planId, name, emptyList())
        }
    }

    fun deleteWorkouts(ids: Set<Long>) {
        viewModelScope.launch {
            ids.forEach { dev.allan.workoutapp.data.PlanRepo.deleteWorkoutDeep(db, it) }
        }
    }

    /** All workouts across plans (with plan name) — candidates for "copy existing". */
    private val _copyCandidates = MutableStateFlow<List<Pair<Workout, String>>>(emptyList())
    val copyCandidates: StateFlow<List<Pair<Workout, String>>> = _copyCandidates

    fun loadCopyCandidates() {
        viewModelScope.launch {
            val planNames = db.planDao().allPlans().associate { it.id to it.name }
            // A workout can belong to several plans now; label it with its first plan (or —).
            val firstPlanOf = db.planDao().allPlanWorkouts()
                .groupBy { it.workoutId }
                .mapValues { (_, links) -> planNames[links.first().planId] ?: "—" }
            _copyCandidates.value = db.planDao().allWorkoutsList()
                .map { it to (firstPlanOf[it.id] ?: "—") }
        }
    }

    fun copyWorkoutHere(sourceWorkoutId: Long) {
        viewModelScope.launch {
            dev.allan.workoutapp.data.PlanRepo.copyWorkout(db, sourceWorkoutId, planId)
        }
    }

    fun exportWorkout(workoutId: Long, uri: android.net.Uri) {
        viewModelScope.launch {
            val text = dev.allan.workoutapp.data.transfer.PlanTransfer.exportWorkout(db, workoutId)
                ?: return@launch
            getApplication<Application>().contentResolver.openOutputStream(uri)
                ?.use { it.write(text.toByteArray()) }
        }
    }

    fun setArchived(ids: Set<Long>, archived: Boolean) {
        viewModelScope.launch {
            workouts.value.filter { it.id in ids }.forEach {
                db.planDao().updateWorkout(it.copy(archived = archived))
            }
        }
    }

    fun toggleDay(w: Workout, isoDay: Int) {
        val days = if (isoDay in w.daysOfWeek) w.daysOfWeek - isoDay else (w.daysOfWeek + isoDay).sorted()
        viewModelScope.launch { db.planDao().updateWorkout(w.copy(daysOfWeek = days)) }
    }

    /** 1-based current week within the cycle, or null when no cycle is set. */
    fun currentWeek(): Int? {
        val p = _plan.value ?: return null
        val start = p.startedAt ?: return null
        if (p.cycleWeeks == null) return null
        return ((System.currentTimeMillis() - start) / (7L * 24 * 3600 * 1000)).toInt() + 1
    }

    class Factory(private val app: Application, private val planId: Long) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            PlanEditorViewModel(app, planId) as T
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanEditorScreen(
    planId: Long,
    onBack: () -> Unit,
    onOpenWorkout: (Long) -> Unit,
    onAddWorkout: (mode: String) -> Unit = {},
) {
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as Application
    val vm: PlanEditorViewModel =
        viewModel(key = "plan-$planId", factory = PlanEditorViewModel.Factory(app, planId))
    val plan by vm.plan.collectAsState()
    val workouts by vm.workouts.collectAsState()
    val counts by vm.exerciseCounts.collectAsState()
    var showAddWorkout by remember { mutableStateOf(false) }

    // Every exit runs the unique-name check (rename happens live, per keystroke).
    val exit = { vm.finalizeName(onBack) }
    androidx.activity.compose.BackHandler(enabled = true) { exit() }

    // Checkbox selection: no one-tap delete anywhere on this screen.
    var selected by remember { mutableStateOf(setOf<Long>()) }
    val selectionMode = selected.isNotEmpty()
    var confirmDelete by remember { mutableStateOf(false) }

    val exportWorkoutLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val workoutId = selected.firstOrNull()
        if (uri != null && workoutId != null) vm.exportWorkout(workoutId, uri)
        selected = emptySet()
    }

    Scaffold(
        topBar = {
            if (selectionMode) {
                TopAppBar(
                    title = { Text(stringResource(R.string.selected_count, selected.size)) },
                    navigationIcon = {
                        IconButton(onClick = { selected = emptySet() }) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cancel))
                        }
                    },
                    actions = {
                        if (selected.size == 1) {
                            IconButton(onClick = {
                                val name = workouts.firstOrNull { it.id == selected.first() }?.name ?: "workout"
                                exportWorkoutLauncher.launch("workout_${name.replace(' ', '_')}.json")
                            }) {
                                // Down arrow reads as "save to file" — Share was misleading.
                                Icon(
                                    Icons.Default.Download,
                                    contentDescription = stringResource(R.string.export_workout),
                                )
                            }
                        }
                        val anyArchived = workouts.any { it.id in selected && it.archived }
                        IconButton(onClick = {
                            vm.setArchived(selected, archived = !anyArchived)
                            selected = emptySet()
                        }) {
                            Icon(
                                if (anyArchived) Icons.Default.Unarchive else Icons.Outlined.Inventory2,
                                contentDescription = stringResource(
                                    if (anyArchived) R.string.unarchive else R.string.archive
                                ),
                            )
                        }
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete))
                        }
                    },
                )
            } else {
                TopAppBar(
                    title = { Text(plan?.name ?: "") },
                    navigationIcon = {
                        IconButton(onClick = exit) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                        }
                    },
                )
            }
        }
    ) { padding ->
        val p = plan ?: return@Scaffold
        val (active, archived) = workouts.partition { !it.archived }
        dev.allan.workoutapp.ui.common.ScrollbarLazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            edgePadding = 12.dp,
        ) {
            item {
                OutlinedTextField(
                    value = p.name,
                    onValueChange = vm::rename,
                    label = { Text(stringResource(R.string.plan_name)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = p.cycleWeeks?.toString() ?: "",
                        onValueChange = { vm.setCycleWeeks(it.toIntOrNull()?.coerceIn(1, 52)) },
                        label = { Text(stringResource(R.string.cycle_weeks)) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                    // Button (not a toggle) to mirror the Archive screen's Activate action.
                    if (p.isActive) {
                        OutlinedButton(onClick = { vm.setActive(false) }) {
                            Icon(Icons.Default.Archive, contentDescription = null)
                            Text(stringResource(R.string.deactivate), modifier = Modifier.padding(start = 6.dp))
                        }
                    } else {
                        Button(onClick = { vm.activate() }) {
                            Text(stringResource(R.string.activate))
                        }
                    }
                }
            }
            val week = vm.currentWeek()
            if (week != null && p.cycleWeeks != null) {
                item {
                    val deload = week >= p.cycleWeeks!!
                    Card {
                        Text(
                            text = if (deload)
                                stringResource(R.string.deload_banner, week, p.cycleWeeks!!)
                            else
                                stringResource(R.string.cycle_week_banner, week, p.cycleWeeks!!),
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
            // Add-workout entry sits above the list (Allan: below the weeks box, above the hint).
            item {
                Button(onClick = { showAddWorkout = true }) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Text(stringResource(R.string.add_workout), modifier = Modifier.padding(start = 6.dp))
                }
            }
            if (workouts.isNotEmpty()) {
                item {
                    Text(
                        stringResource(R.string.long_press_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(active, key = { it.id }) { w ->
                WorkoutCard(
                    w = w,
                    exerciseCount = counts[w.id] ?: 0,
                    isSelected = w.id in selected,
                    onToggleDay = { vm.toggleDay(w, it) },
                    onClick = {
                        if (selectionMode) {
                            selected = if (w.id in selected) selected - w.id else selected + w.id
                        } else onOpenWorkout(w.id)
                    },
                    onToggleSelect = {
                        selected = if (w.id in selected) selected - w.id else selected + w.id
                    },
                )
            }
            if (archived.isNotEmpty()) {
                item {
                    Text(
                        stringResource(R.string.archived_section),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(archived, key = { it.id }) { w ->
                    WorkoutCard(
                        w = w,
                        exerciseCount = counts[w.id] ?: 0,
                        isSelected = w.id in selected,
                        onToggleDay = { vm.toggleDay(w, it) },
                        onClick = {
                            if (selectionMode) {
                                selected = if (w.id in selected) selected - w.id else selected + w.id
                            } else onOpenWorkout(w.id)
                        },
                        onToggleSelect = {
                            selected = if (w.id in selected) selected - w.id else selected + w.id
                        },
                    )
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_workouts_confirm, selected.size)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteWorkouts(selected)
                    selected = emptySet()
                    confirmDelete = false
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    if (showAddWorkout) {
        // Shared 3-option overlay — same look wherever a workout can be added.
        AddWorkoutChooserDialog(
            onDismiss = { showAddWorkout = false },
            onCreateScratch = { vm.addWorkout(it) },
            onImport = { onAddWorkout("import") },
            onUseAsBase = { onAddWorkout("base") },
        )
    }
}

/** Circled number: how many exercises the workout holds. */
@Composable
fun ExerciseCountBadge(count: Int) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "$count",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WorkoutCard(
    w: Workout,
    exerciseCount: Int,
    isSelected: Boolean,
    onToggleDay: (Int) -> Unit,
    onClick: () -> Unit,
    onToggleSelect: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .combinedClickable(onClick = onClick, onLongClick = onToggleSelect)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                // Always visible — long-press-only selection was too hidden.
                Checkbox(checked = isSelected, onCheckedChange = { onToggleSelect() })
                ExerciseCountBadge(exerciseCount)
                Text(w.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (w.archived) {
                    Icon(
                        Icons.Outlined.Inventory2,
                        contentDescription = stringResource(R.string.archived_section),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                stringResource(R.string.assign_days),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                DayOfWeek.entries.forEach { day ->
                    FilterChip(
                        selected = day.value in w.daysOfWeek,
                        onClick = { onToggleDay(day.value) },
                        label = { Text(day.getDisplayName(TextStyle.NARROW, Locale.getDefault())) },
                    )
                }
            }
        }
    }
}

