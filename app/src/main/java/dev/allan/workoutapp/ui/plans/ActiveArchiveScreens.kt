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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
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

/** Archive → Plans: every inactive plan; activate (one-active rule), delete, or create new. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchivePlansScreen(
    onBack: () -> Unit,
    onOpenPlan: (Long) -> Unit,
    onOpenWorkout: (Long) -> Unit = {},
) {
    val vm: PlansViewModel = viewModel()
    val plans by vm.inactivePlans.collectAsState()
    val counts by vm.workoutCounts.collectAsState()
    val planWorkoutIds by vm.planWorkoutIds.collectAsState()
    val planLastTrained by vm.planLastTrained.collectAsState()
    val allWorkouts by vm.allWorkouts.collectAsState()
    var expanded by remember { mutableStateOf(setOf<Long>()) }
    var confirmDelete by remember { mutableStateOf<Plan?>(null) }
    var showNewPlan by remember { mutableStateOf(false) }
    var pendingCreate by remember { mutableStateOf<PendingCycle?>(null) }

    // Same new-cycle overlay (and import pipeline) as the Active tab FAB.
    val settingsVm: dev.allan.workoutapp.ui.settings.SettingsViewModel = viewModel()
    val importPlanLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { settingsVm.importPlan(it, dev.allan.workoutapp.currentAppLang()) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.archive_plans)) },
                navigationIcon = { BackButton(onBack) },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showNewPlan = true }) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.new_plan))
            }
        },
    ) { padding ->
        dev.allan.workoutapp.ui.common.ScrollbarLazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (plans.isEmpty()) item { Text(stringResource(R.string.no_inactive_plans), Modifier.padding(top = 16.dp)) }
            items(plans, key = { it.id }) { plan ->
                val isExpanded = plan.id in expanded
                Card(onClick = { onOpenPlan(plan.id) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
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
                            ExpandChevron(isExpanded) {
                                expanded = if (isExpanded) expanded - plan.id else expanded + plan.id
                            }
                        }
                        planLastTrained[plan.id]?.let { millis ->
                            Text(
                                stringResource(R.string.last_trained, dev.allan.workoutapp.formatDateShort(millis)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.End,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        // Expanded: the plan's workouts, each a button into that workout.
                        if (isExpanded) {
                            val members = planWorkoutIds[plan.id].orEmpty()
                                .mapNotNull { id -> allWorkouts.firstOrNull { it.id == id } }
                            if (members.isEmpty()) {
                                Text(
                                    stringResource(R.string.no_workouts_yet),
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(top = 6.dp),
                                )
                            }
                            members.forEach { w ->
                                OutlinedButton(
                                    onClick = { onOpenWorkout(w.id) },
                                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                                ) { Text(w.name) }
                            }
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

    if (showNewPlan) {
        NewPlanDialog(
            onDismiss = { showNewPlan = false },
            onCreateBlank = { name, weeks ->
                pendingCreate = PendingCycle(name, weeks, null)
                showNewPlan = false
            },
            onCreateWizard = { name, weeks, days ->
                pendingCreate = PendingCycle(name, weeks, days)
                showNewPlan = false
            },
            onImport = {
                showNewPlan = false
                importPlanLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
            },
            // The list behind this dialog IS the archive — activating from here already works,
            // so the overlay's reactivate entry would be circular; hide it.
            onReactivate = null,
        )
    }
    // Created from the ARCHIVE: don't silently steal the active slot — ask first.
    pendingCreate?.let { pending ->
        val create = { activate: Boolean ->
            if (pending.days == null)
                vm.createBlankPlan(pending.name, pending.weeks, activate) { onOpenPlan(it) }
            else
                vm.createWizardPlan(pending.name, pending.weeks, pending.days, activate) { onOpenPlan(it) }
            pendingCreate = null
        }
        AlertDialog(
            onDismissRequest = { pendingCreate = null },
            title = { Text(stringResource(R.string.activate_new_cycle)) },
            confirmButton = {
                TextButton(onClick = { create(true) }) { Text(stringResource(R.string.activate)) }
            },
            dismissButton = {
                TextButton(onClick = { create(false) }) { Text(stringResource(R.string.keep_archived)) }
            },
        )
    }
    dev.allan.workoutapp.ui.settings.PlanImportDialogs(settingsVm)
}

/** A cycle configured in the new-cycle overlay, awaiting the activate-or-archive choice. */
private data class PendingCycle(
    val name: String,
    val weeks: Int?,
    val days: List<Pair<String, Int>>?,
)

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
    onAddToArchive: (mode: String) -> Unit = {},
    onOpenPlan: (Long) -> Unit = {},
) {
    val vm: PlansViewModel = viewModel()
    val workouts by vm.allWorkouts.collectAsState()
    val activeIds by vm.activeWorkoutIds.collectAsState()
    val exerciseCounts by vm.exerciseCounts.collectAsState()
    val hasActivePlan by vm.activePlan.collectAsState()
    val lastTrained by vm.lastTrained.collectAsState()
    val planWorkoutIds by vm.planWorkoutIds.collectAsState()
    val allPlans by vm.allPlans.collectAsState()
    var expanded by remember { mutableStateOf(setOf<Long>()) }
    var showAdd by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.archive_workouts)) },
                navigationIcon = { BackButton(onBack) },
            )
        },
        // Same "+" FAB as the Active tab — one add affordance everywhere.
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.add_workout))
            }
        },
    ) { padding ->
        dev.allan.workoutapp.ui.common.ScrollbarLazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (workouts.isEmpty()) item { Text(stringResource(R.string.no_workouts_yet), Modifier.padding(top = 16.dp)) }
            items(workouts, key = { it.id }) { w ->
                val inActive = w.id in activeIds
                val isExpanded = w.id in expanded
                Card(onClick = { onOpenWorkout(w.id) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
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
                            ExpandChevron(isExpanded) {
                                expanded = if (isExpanded) expanded - w.id else expanded + w.id
                            }
                        }
                        lastTrained[w.id]?.let { millis ->
                            Text(
                                stringResource(R.string.last_trained, dev.allan.workoutapp.formatDateShort(millis)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.End,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        // Expanded: cycles this workout belongs to, each a button into that cycle.
                        if (isExpanded) {
                            val cycles = allPlans.filter { p -> w.id in planWorkoutIds[p.id].orEmpty() }
                            if (cycles.isEmpty()) {
                                Text(
                                    stringResource(R.string.no_cycles_linked),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 6.dp),
                                )
                            }
                            cycles.forEach { p ->
                                OutlinedButton(
                                    onClick = { onOpenPlan(p.id) },
                                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                                ) { Text(p.name) }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAdd) {
        // Same 3-option overlay as the plan editor — the archive path just stores the
        // result archived; the user never sees that difference.
        AddWorkoutChooserDialog(
            onDismiss = { showAdd = false },
            onCreateScratch = { name -> vm.createArchivedWorkout(name) { onEditWorkout(it) } },
            onImport = { onAddToArchive("import") },
            onUseAsBase = { onAddToArchive("base") },
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
 * A null [planId] targets the ARCHIVE instead: IMPORT moves non-archived workouts in as-is
 * (detached from their plans), BASE stores independent archived copies.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddWorkoutScreen(planId: Long?, mode: AddWorkoutMode, onBack: () -> Unit) {
    val vm: PlansViewModel = viewModel()
    val allWorkouts by vm.allWorkouts.collectAsState()
    val activeIds by vm.activeWorkoutIds.collectAsState()
    val exerciseCounts by vm.exerciseCounts.collectAsState()
    // IMPORT into a plan: archived only (why duplicate an active one exactly?). IMPORT into
    // the archive: the ones not archived yet. BASE: everything.
    val workouts = when {
        mode != AddWorkoutMode.IMPORT -> allWorkouts
        planId != null -> allWorkouts.filter { it.archived }
        else -> allWorkouts.filter { !it.archived }
    }
    var selected by remember { mutableStateOf(setOf<Long>()) }
    val titleRes = if (mode == AddWorkoutMode.IMPORT) R.string.import_workout else R.string.use_as_base

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(titleRes)) },
            navigationIcon = { BackButton(onBack) },
        )
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            dev.allan.workoutapp.ui.common.ScrollbarLazyColumn(
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
            // Single action button — the mode was already chosen in the add overlay.
            FilledTonalButton(
                onClick = {
                    when {
                        planId != null ->
                            vm.addWorkoutsToPlan(planId, selected, asCopy = mode == AddWorkoutMode.BASE)
                        mode == AddWorkoutMode.IMPORT -> vm.moveWorkoutsToArchive(selected)
                        else -> vm.copyWorkoutsToArchive(selected)
                    }
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

/** Down/up chevron marking an expandable archive row. */
@Composable
private fun ExpandChevron(expanded: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            contentDescription = null,
        )
    }
}
