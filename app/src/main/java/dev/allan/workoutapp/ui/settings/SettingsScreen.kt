package dev.allan.workoutapp.ui.settings

import android.app.Application
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.allan.workoutapp.R
import dev.allan.workoutapp.WorkoutApp
import dev.allan.workoutapp.data.db.Plan
import dev.allan.workoutapp.data.sync.WgerSync
import dev.allan.workoutapp.data.transfer.Backup
import dev.allan.workoutapp.data.transfer.CsvExport
import dev.allan.workoutapp.data.transfer.PlanTransfer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val db = (app as WorkoutApp).db
    private val context get() = getApplication<Application>()

    val plans: StateFlow<List<Plan>> = db.planDao().plans(true)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val muscles: StateFlow<List<dev.allan.workoutapp.data.db.Muscle>> = db.exerciseDao().muscles()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing

    fun refreshWger() {
        if (_syncing.value) return
        viewModelScope.launch {
            _syncing.value = true
            val result = WgerSync.refresh(db)
            // Sync rebuilds the wger translation table; restore generated pt names.
            result.onSuccess {
                dev.allan.workoutapp.data.snapshot.PtAliases.merge(context, db)
            }
            _syncing.value = false
            _message.value = result.fold(
                onSuccess = { context.getString(R.string.wger_refresh_done, it.exercises, it.translations) },
                onFailure = { context.getString(R.string.wger_refresh_failed, it.message ?: it.javaClass.simpleName) },
            )
        }
    }

    fun clearMessage() {
        _message.value = null
    }

    private fun write(uri: Uri, content: String) {
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(content.toByteArray()) }
    }

    private fun writeBytes(uri: Uri, content: ByteArray) {
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(content) }
    }

    fun exportPlanPdf(planId: Long, uri: Uri, lang: String) {
        viewModelScope.launch {
            val bytes = dev.allan.workoutapp.data.transfer.PdfExport.plan(db, planId, lang)
            if (bytes == null) { _message.value = "plan not found"; return@launch }
            writeBytes(uri, bytes)
            _message.value = context.getString(R.string.export_done)
        }
    }

    /** Copies the bundled LLM plan-generator instructions (markdown) to a user file. */
    fun exportGeneratorDoc(uri: Uri) {
        viewModelScope.launch {
            val text = context.assets.open("workout_plan_generator.md")
                .use { it.readBytes().decodeToString() }
            write(uri, text)
            _message.value = context.getString(R.string.export_done)
        }
    }

    private fun read(uri: Uri): String? =
        context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }

    /** A plan import whose name collides with an existing plan; user must choose. */
    data class PendingPlanImport(
        val plan: PlanTransfer.PlanDto,
        val existingPlanId: Long,
        val suggestedName: String,
    )

    private val _pendingPlanImport = MutableStateFlow<PendingPlanImport?>(null)
    val pendingPlanImport: StateFlow<PendingPlanImport?> = _pendingPlanImport

    /** A single-workout file waiting for a target plan choice. */
    private val _pendingWorkoutImport = MutableStateFlow<PlanTransfer.WorkoutDto?>(null)
    val pendingWorkoutImport: StateFlow<PlanTransfer.WorkoutDto?> = _pendingWorkoutImport

    private var importLang: String = "en"

    /** Parses the file, auto-detects plan vs workout, and routes collisions to dialogs. */
    fun importPlan(uri: Uri, lang: String) {
        importLang = lang
        viewModelScope.launch {
            val text = read(uri) ?: run { _message.value = "read failed"; return@launch }
            when (val parsed = PlanTransfer.parse(text)) {
                is PlanTransfer.Parsed.Error ->
                    _message.value = context.getString(R.string.import_failed, parsed.message)
                is PlanTransfer.Parsed.PlanFile -> {
                    val existing = db.planDao().planByName(parsed.plan.name)
                    if (existing == null) {
                        report(PlanTransfer.importPlan(db, parsed.plan, lang))
                    } else {
                        _pendingPlanImport.value = PendingPlanImport(
                            plan = parsed.plan,
                            existingPlanId = existing.id,
                            suggestedName = uniquePlanName(parsed.plan.name),
                        )
                    }
                }
                is PlanTransfer.Parsed.WorkoutFile -> _pendingWorkoutImport.value = parsed.workout
            }
        }
    }

    /** Collision resolution: rename = import as a new plan; merge = append workouts. */
    fun resolvePlanCollision(rename: Boolean) {
        val pending = _pendingPlanImport.value ?: return
        _pendingPlanImport.value = null
        viewModelScope.launch {
            report(
                if (rename) PlanTransfer.importPlan(db, pending.plan, importLang, renameTo = pending.suggestedName)
                else PlanTransfer.importPlan(db, pending.plan, importLang, mergeIntoPlanId = pending.existingPlanId)
            )
        }
    }

    fun cancelPendingImport() {
        _pendingPlanImport.value = null
        _pendingWorkoutImport.value = null
    }

    /** Workout file: import into [planId], or a fresh plan when null. */
    fun importWorkoutInto(planId: Long?) {
        val workout = _pendingWorkoutImport.value ?: return
        _pendingWorkoutImport.value = null
        viewModelScope.launch {
            val targetId = planId ?: db.planDao().insertPlan(
                Plan(
                    name = uniquePlanName(workout.name.ifBlank { "Imported" }),
                    startedAt = System.currentTimeMillis(),
                    createdAt = System.currentTimeMillis(),
                )
            )
            report(PlanTransfer.importWorkout(db, workout, targetId, importLang))
        }
    }

    private suspend fun uniquePlanName(base: String): String {
        if (db.planDao().planByName(base) == null) return base
        var n = 2
        while (db.planDao().planByName("$base ($n)") != null) n++
        return "$base ($n)"
    }

    private fun report(report: PlanTransfer.ImportReport) {
        _message.value = report.error?.let { context.getString(R.string.import_failed, it) }
            ?: context.getString(
                R.string.import_report,
                report.workouts, report.exercises, report.createdCustom.size, report.skipped.size,
            ) + (if (report.skipped.isNotEmpty()) "\n" + report.skipped.joinToString() else "")
    }

    fun exportPlan(planId: Long, uri: Uri) {
        viewModelScope.launch {
            val text = PlanTransfer.export(db, planId)
            if (text == null) { _message.value = "plan not found"; return@launch }
            write(uri, text)
            _message.value = context.getString(R.string.export_done)
        }
    }

    fun exportCsv(kind: String, uri: Uri, lang: String) {
        viewModelScope.launch {
            val content = when (kind) {
                "sets" -> CsvExport.sets(db, lang)
                "sessions" -> CsvExport.sessions(db)
                else -> CsvExport.body(db)
            }
            write(uri, content)
            _message.value = context.getString(R.string.export_done)
        }
    }

    fun exportBackup(uri: Uri) {
        viewModelScope.launch {
            write(uri, Backup.export(context, db))
            _message.value = context.getString(R.string.export_done)
        }
    }

    fun restoreBackup(uri: Uri) {
        viewModelScope.launch {
            val text = read(uri) ?: run { _message.value = "read failed"; return@launch }
            val error = Backup.restore(context, db, text)
            _message.value = error?.let { context.getString(R.string.import_failed, it) }
                ?: context.getString(R.string.restore_done)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(appLang: String, onBack: () -> Unit, vm: SettingsViewModel = viewModel()) {
    val plans by vm.plans.collectAsState()
    val syncing by vm.syncing.collectAsState()
    var planPickerFor by remember { mutableStateOf<Long?>(null) } // planId chosen for export
    var showPlanPicker by remember { mutableStateOf(false) }
    var planPickerKind by remember { mutableStateOf("json") } // json | pdf
    var showLlmInstructions by remember { mutableStateOf(false) }

    val importPlanLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.importPlan(it, appLang) }
    }
    val exportPlanLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val planId = planPickerFor
        if (uri != null && planId != null) vm.exportPlan(planId, uri)
        planPickerFor = null
    }
    val exportPdfLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        val planId = planPickerFor
        if (uri != null && planId != null) vm.exportPlanPdf(planId, uri, appLang)
        planPickerFor = null
    }
    val exportGeneratorLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/markdown")
    ) { uri -> uri?.let { vm.exportGeneratorDoc(it) } }
    var csvKind by remember { mutableStateOf("sets") }
    val exportCsvLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri -> uri?.let { vm.exportCsv(csvKind, it, appLang) } }
    val backupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> uri?.let { vm.exportBackup(it) } }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.restoreBackup(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
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
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.plans_transfer), fontWeight = FontWeight.Bold)
                    OutlinedButton(
                        onClick = { importPlanLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.import_plan)) }
                    OutlinedButton(
                        onClick = { planPickerKind = "json"; showPlanPicker = true },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = plans.isNotEmpty(),
                    ) { Text(stringResource(R.string.export_plan)) }
                    OutlinedButton(
                        onClick = { planPickerKind = "pdf"; showPlanPicker = true },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = plans.isNotEmpty(),
                    ) { Text(stringResource(R.string.export_plan_pdf)) }
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.llm_title), fontWeight = FontWeight.Bold)
                    Text(
                        stringResource(R.string.llm_hint),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(
                        onClick = { showLlmInstructions = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.llm_show_instructions)) }
                    OutlinedButton(
                        onClick = { exportGeneratorLauncher.launch("workout_plan_generator.md") },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.llm_export_md)) }
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.history_export), fontWeight = FontWeight.Bold)
                    OutlinedButton(
                        onClick = { csvKind = "sets"; exportCsvLauncher.launch("workout_sets.csv") },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.export_sets_csv)) }
                    OutlinedButton(
                        onClick = { csvKind = "sessions"; exportCsvLauncher.launch("workout_sessions.csv") },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.export_sessions_csv)) }
                    OutlinedButton(
                        onClick = { csvKind = "body"; exportCsvLauncher.launch("body_weight.csv") },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.export_body_csv)) }
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.backup), fontWeight = FontWeight.Bold)
                    OutlinedButton(
                        onClick = { backupLauncher.launch("workout_backup.json") },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.export_backup)) }
                    OutlinedButton(
                        onClick = { restoreLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.restore_backup)) }
                    Text(
                        stringResource(R.string.restore_hint),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.session_settings), fontWeight = FontWeight.Bold)
                    val context = androidx.compose.ui.platform.LocalContext.current
                    val prevNext by dev.allan.workoutapp.data.Settings.prevNextButtons(context)
                        .collectAsState(initial = false)
                    val scope = androidx.compose.runtime.rememberCoroutineScope()
                    androidx.compose.foundation.layout.Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(R.string.prev_next_setting),
                            modifier = Modifier.weight(1f),
                        )
                        androidx.compose.material3.Switch(
                            checked = prevNext,
                            onCheckedChange = { value ->
                                scope.launch {
                                    dev.allan.workoutapp.data.Settings.setPrevNextButtons(context, value)
                                }
                            },
                        )
                    }
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.injuries), fontWeight = FontWeight.Bold)
                    Text(
                        stringResource(R.string.injuries_hint),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    val context = androidx.compose.ui.platform.LocalContext.current
                    val muscles by vm.muscles.collectAsState()
                    val injured by dev.allan.workoutapp.data.Settings.injuredMuscles(context)
                        .collectAsState(initial = emptySet())
                    val scope = androidx.compose.runtime.rememberCoroutineScope()
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        muscles.forEach { m ->
                            androidx.compose.material3.FilterChip(
                                selected = m.id in injured,
                                onClick = {
                                    val next = if (m.id in injured) injured - m.id else injured + m.id
                                    scope.launch {
                                        dev.allan.workoutapp.data.Settings.setInjuredMuscles(context, next)
                                    }
                                },
                                label = {
                                    Text(dev.allan.workoutapp.data.MuscleNames.display(m.nameEn, appLang))
                                },
                            )
                        }
                    }
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.exercise_db), fontWeight = FontWeight.Bold)
                    OutlinedButton(
                        onClick = vm::refreshWger,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !syncing,
                    ) {
                        Text(stringResource(if (syncing) R.string.wger_refreshing else R.string.refresh_wger))
                    }
                    Text(
                        stringResource(R.string.refresh_wger_hint),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Text(stringResource(R.string.wger_attribution), style = MaterialTheme.typography.labelSmall)
        }
    }

    if (showPlanPicker) {
        AlertDialog(
            onDismissRequest = { showPlanPicker = false },
            title = {
                Text(
                    stringResource(
                        if (planPickerKind == "pdf") R.string.export_plan_pdf else R.string.export_plan
                    )
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    plans.forEach { plan ->
                        OutlinedButton(
                            onClick = {
                                planPickerFor = plan.id
                                showPlanPicker = false
                                val base = "plan_${plan.name.replace(' ', '_')}"
                                if (planPickerKind == "pdf") exportPdfLauncher.launch("$base.pdf")
                                else exportPlanLauncher.launch("$base.json")
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(plan.name) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showPlanPicker = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    if (showLlmInstructions) {
        AlertDialog(
            onDismissRequest = { showLlmInstructions = false },
            title = { Text(stringResource(R.string.llm_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.llm_instructions_body))
                }
            },
            confirmButton = {
                TextButton(onClick = { showLlmInstructions = false }) { Text(stringResource(R.string.ok)) }
            },
        )
    }

    PlanImportDialogs(vm)
}

/**
 * Import flow dialogs — plan-name collision, single-workout target picker, result
 * message. Shared by SettingsScreen and the new-cycle dialog on the main screen.
 */
@Composable
fun PlanImportDialogs(vm: SettingsViewModel) {
    val plans by vm.plans.collectAsState()
    val pendingPlanImport by vm.pendingPlanImport.collectAsState()
    val pendingWorkoutImport by vm.pendingWorkoutImport.collectAsState()
    val message by vm.message.collectAsState()

    // Imported plan name already taken: rename it or merge its workouts into the existing plan.
    pendingPlanImport?.let { pending ->
        AlertDialog(
            onDismissRequest = vm::cancelPendingImport,
            title = { Text(stringResource(R.string.import_collision_title, pending.plan.name)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { vm.resolvePlanCollision(rename = true) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.import_rename, pending.suggestedName)) }
                    OutlinedButton(
                        onClick = { vm.resolvePlanCollision(rename = false) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.import_merge)) }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = vm::cancelPendingImport) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    // Single-workout file: pick which plan receives it (or spin up a new one).
    pendingWorkoutImport?.let {
        AlertDialog(
            onDismissRequest = vm::cancelPendingImport,
            title = { Text(stringResource(R.string.choose_plan_for_workout)) },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    plans.forEach { plan ->
                        OutlinedButton(
                            onClick = { vm.importWorkoutInto(plan.id) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(plan.name) }
                    }
                    OutlinedButton(
                        onClick = { vm.importWorkoutInto(null) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.new_plan)) }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = vm::cancelPendingImport) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    message?.let { msg ->
        AlertDialog(
            onDismissRequest = vm::clearMessage,
            text = { Text(msg) },
            confirmButton = {
                TextButton(onClick = vm::clearMessage) { Text(stringResource(R.string.ok)) }
            },
        )
    }
}
