package dev.allan.workoutapp.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.allan.workoutapp.R

/**
 * Cadence (tempo) editor — the SAME overlay in the workout editor and in-session. Four
 * digit boxes (eccentric-pause-concentric-pause), each with an up-triangle above and a
 * down-triangle below; typing opens the numeric keyboard. OK sits on the same row.
 * Confirms "a-b-c-d", or "" when all four are zero (clears the cadence).
 */
@Composable
fun CadenceDialog(
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val values = remember(initial) {
        val parts = initial.split('-').map { it.trim().filter(Char::isDigit) }
        mutableStateListOf(*List(4) { i -> parts.getOrNull(i)?.takeIf { it.isNotEmpty() } ?: "0" }
            .toTypedArray())
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tempo_label)) },
        text = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                repeat(4) { i ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.weight(1f),
                    ) {
                        IconButton(onClick = {
                            values[i] = ((values[i].toIntOrNull() ?: 0) + 1).coerceAtMost(99).toString()
                        }) { Icon(Icons.Default.ArrowDropUp, contentDescription = null) }
                        OutlinedTextField(
                            value = values[i],
                            onValueChange = { v -> values[i] = v.filter(Char::isDigit).take(2) },
                            singleLine = true,
                            textStyle = LocalTextStyle.current.copy(textAlign = TextAlign.Center),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                        IconButton(onClick = {
                            values[i] = ((values[i].toIntOrNull() ?: 0) - 1).coerceAtLeast(0).toString()
                        }) { Icon(Icons.Default.ArrowDropDown, contentDescription = null) }
                    }
                }
                TextButton(onClick = {
                    val nums = values.map { it.toIntOrNull() ?: 0 }
                    onConfirm(if (nums.all { it == 0 }) "" else nums.joinToString("-"))
                    onDismiss()
                }) { Text(stringResource(R.string.ok)) }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
