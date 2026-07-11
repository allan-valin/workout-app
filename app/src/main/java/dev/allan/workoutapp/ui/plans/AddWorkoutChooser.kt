package dev.allan.workoutapp.ui.plans

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.allan.workoutapp.R

/**
 * THE add-workout overlay — plan editor and Archive → Workouts show the same three choices
 * (from scratch / import as-is / use as base). Where the result lands (linked into a plan or
 * stored archived) is the caller's business; the user sees one consistent flow.
 */
@Composable
fun AddWorkoutChooserDialog(
    onDismiss: () -> Unit,
    onCreateScratch: (String) -> Unit,
    onImport: () -> Unit,
    onUseAsBase: () -> Unit,
) {
    var fromScratch by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_workout)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (fromScratch) {
                    // (a) From scratch: name it, then the caller opens the empty workout.
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(stringResource(R.string.workout_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    AddWorkoutOption(
                        icon = Icons.Default.Add,
                        title = stringResource(R.string.add_from_scratch),
                        subtitle = stringResource(R.string.add_from_scratch_hint),
                        onClick = { fromScratch = true },
                    )
                    // (b) Import an existing workout as-is.
                    AddWorkoutOption(
                        icon = Icons.Default.ContentCopy,
                        title = stringResource(R.string.import_workout),
                        subtitle = stringResource(R.string.import_workout_hint),
                        onClick = { onDismiss(); onImport() },
                    )
                    // (c) Use any workout as a base (independent copy).
                    AddWorkoutOption(
                        icon = Icons.Default.ContentCopy,
                        title = stringResource(R.string.use_as_base),
                        subtitle = stringResource(R.string.use_as_base_hint),
                        onClick = { onDismiss(); onUseAsBase() },
                    )
                }
            }
        },
        confirmButton = {
            if (fromScratch) {
                TextButton(
                    onClick = {
                        if (name.isNotBlank()) onCreateScratch(name.trim())
                        onDismiss()
                    }
                ) { Text(stringResource(R.string.ok)) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
internal fun AddWorkoutOption(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(icon, contentDescription = null)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
