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

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    fun clearMessage() {
        _message.value = null
    }

    private fun write(uri: Uri, content: String) {
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(content.toByteArray()) }
    }

    private fun read(uri: Uri): String? =
        context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }

    fun importPlan(uri: Uri, lang: String) {
        viewModelScope.launch {
            val text = read(uri) ?: run { _message.value = "read failed"; return@launch }
            val report = PlanTransfer.import(db, text, lang)
            _message.value = report.error?.let { context.getString(R.string.import_failed, it) }
                ?: context.getString(
                    R.string.import_report,
                    report.workouts, report.exercises, report.createdCustom.size, report.skipped.size,
                ) + (if (report.skipped.isNotEmpty()) "\n" + report.skipped.joinToString() else "")
        }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(appLang: String, onBack: () -> Unit, vm: SettingsViewModel = viewModel()) {
    val plans by vm.plans.collectAsState()
    val message by vm.message.collectAsState()
    var planPickerFor by remember { mutableStateOf<Long?>(null) } // planId chosen for export
    var showPlanPicker by remember { mutableStateOf(false) }

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
                        onClick = { showPlanPicker = true },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = plans.isNotEmpty(),
                    ) { Text(stringResource(R.string.export_plan)) }
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
            Text(stringResource(R.string.wger_attribution), style = MaterialTheme.typography.labelSmall)
        }
    }

    if (showPlanPicker) {
        AlertDialog(
            onDismissRequest = { showPlanPicker = false },
            title = { Text(stringResource(R.string.export_plan)) },
            text = {
                Column {
                    plans.forEach { plan ->
                        TextButton(onClick = {
                            planPickerFor = plan.id
                            showPlanPicker = false
                            exportPlanLauncher.launch("plan_${plan.name.replace(' ', '_')}.json")
                        }) { Text(plan.name) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showPlanPicker = false }) { Text(stringResource(R.string.cancel)) }
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
