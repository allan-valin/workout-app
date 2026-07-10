package dev.allan.workoutapp.ui.plans

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.allan.workoutapp.R
import dev.allan.workoutapp.data.db.Plan
import dev.allan.workoutapp.data.db.Workout

/** Archive hub: two big square buttons — Plans and Workouts. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchiveHubContent(
    onOpenPlans: () -> Unit,
    onOpenWorkouts: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BigSquare(stringResource(R.string.archive_plans), Modifier.weight(1f), onOpenPlans)
        BigSquare(stringResource(R.string.archive_workouts), Modifier.weight(1f), onOpenWorkouts)
    }
}

@Composable
private fun BigSquare(label: String, modifier: Modifier, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = modifier.aspectRatio(1f)) {
        Box(Modifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        }
    }
}

/** Archive → Plans: every inactive plan; activate (one-active rule) or delete. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchivePlansScreen(onBack: () -> Unit, onOpenPlan: (Long) -> Unit) {
    val vm: PlansViewModel = viewModel()
    val plans by vm.inactivePlans.collectAsState()
    val counts by vm.workoutCounts.collectAsState()
    var confirmDelete by remember { mutableStateOf<Plan?>(null) }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.archive_plans)) },
            navigationIcon = { BackButton(onBack) },
        )
    }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (plans.isEmpty()) item { Text(stringResource(R.string.no_inactive_plans), Modifier.padding(top = 16.dp)) }
            items(plans, key = { it.id }) { plan ->
                Card(onClick = { onOpenPlan(plan.id) }, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(plan.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                stringResource(R.string.plan_workout_count, counts[plan.id] ?: 0),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        FilledTonalButton(onClick = { vm.setPlanActive(plan, true); onBack() }) {
                            Text(stringResource(R.string.activate))
                        }
                        IconButton(onClick = { confirmDelete = plan }) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete))
                        }
                    }
                }
            }
        }
    }

    confirmDelete?.let { plan ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.delete_plans_confirm, 1)) },
            confirmButton = {
                TextButton(onClick = { vm.deletePlans(setOf(plan.id)); confirmDelete = null }) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/**
 * Archive → Workouts: every workout (independent of plans). Those already in the active plan
 * are labelled; the rest can be linked into it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchiveWorkoutsScreen(
    onBack: () -> Unit,
    onOpenWorkout: (Long) -> Unit,
    onEditWorkout: (Long) -> Unit = {},
) {
    val vm: PlansViewModel = viewModel()
    val workouts by vm.allWorkouts.collectAsState()
    val activeIds by vm.activeWorkoutIds.collectAsState()
    val exerciseCounts by vm.exerciseCounts.collectAsState()
    val hasActivePlan by vm.activePlan.collectAsState()
    var showAdd by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.archive_workouts)) },
                navigationIcon = { BackButton(onBack) },
                actions = {
                    IconButton(onClick = { showAdd = true }) {
                        Icon(Icons.Default.Add, contentDescription = stringResource(R.string.add_workout))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (workouts.isEmpty()) item { Text(stringResource(R.string.no_workouts_yet), Modifier.padding(top = 16.dp)) }
            items(workouts, key = { it.id }) { w ->
                val inActive = w.id in activeIds
                Card(onClick = { onOpenWorkout(w.id) }, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        ExerciseCountBadge(exerciseCounts[w.id] ?: 0)
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(w.name, style = MaterialTheme.typography.titleMedium)
                            if (inActive) Text(
                                stringResource(R.string.in_active_plan),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        if (!inActive && hasActivePlan != null) {
                            OutlinedButton(onClick = { vm.addWorkoutsToActivePlan(setOf(w.id), asCopy = false) }) {
                                Text(stringResource(R.string.add_to_plan))
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAdd) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text(stringResource(R.string.add_workout)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.workout_name)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank(),
                    onClick = {
                        vm.createArchivedWorkout(name.trim()) { onEditWorkout(it) }
                        showAdd = false
                    },
                ) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/**
 * Two add modes, picked upstream by the plan editor:
 *  - IMPORT: bring an ARCHIVED workout into the plan as a shared link (edits propagate).
 *  - BASE:   copy ANY workout into the plan as an independent starting point.
 */
enum class AddWorkoutMode { IMPORT, BASE }

/**
 * Add-workout screen: multi-select workouts and add them to [planId] per [mode]. IMPORT lists
 * archived workouts only (linked as-is); BASE lists all workouts (copied to edit).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddWorkoutScreen(planId: Long, mode: AddWorkoutMode, onBack: () -> Unit) {
    val vm: PlansViewModel = viewModel()
    val allWorkouts by vm.allWorkouts.collectAsState()
    val activeIds by vm.activeWorkoutIds.collectAsState()
    val exerciseCounts by vm.exerciseCounts.collectAsState()
    // IMPORT: archived only (why duplicate an active one exactly?). BASE: everything.
    val workouts = if (mode == AddWorkoutMode.IMPORT) allWorkouts.filter { it.archived } else allWorkouts
    var selected by remember { mutableStateOf(setOf<Long>()) }
    val titleRes = if (mode == AddWorkoutMode.IMPORT) R.string.import_workout else R.string.use_as_base

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(titleRes)) },
            navigationIcon = { BackButton(onBack) },
        )
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                Modifier.weight(1f).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Text(
                        stringResource(
                            if (mode == AddWorkoutMode.IMPORT) R.string.import_workout_hint
                            else R.string.use_as_base_hint
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                if (workouts.isEmpty()) item { Text(stringResource(R.string.no_workouts_yet)) }
                items(workouts, key = { it.id }) { w ->
                    val checked = w.id in selected
                    Card(
                        onClick = { selected = if (checked) selected - w.id else selected + w.id },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = checked, onCheckedChange = {
                                selected = if (checked) selected - w.id else selected + w.id
                            })
                            ExerciseCountBadge(exerciseCounts[w.id] ?: 0)
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(w.name, style = MaterialTheme.typography.titleMedium)
                                if (w.id in activeIds) Text(
                                    stringResource(R.string.in_active_plan),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
            // Single action button — the mode was already chosen in the plan editor overlay.
            FilledTonalButton(
                onClick = {
                    vm.addWorkoutsToPlan(planId, selected, asCopy = mode == AddWorkoutMode.BASE)
                    onBack()
                },
                enabled = selected.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            ) { Text(stringResource(titleRes)) }
        }
    }
}

@Composable
private fun BackButton(onBack: () -> Unit) {
    IconButton(onClick = onBack) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
    }
}
