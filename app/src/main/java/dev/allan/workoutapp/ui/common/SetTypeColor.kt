package dev.allan.workoutapp.ui.common

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import dev.allan.workoutapp.data.db.SetType

/**
 * Colour code per set type so the single letters scan at a glance — the same wherever a
 * set type is shown (session rows AND the workout editor; Allan, 29/08: D1).
 */
@Composable
fun setTypeColor(type: SetType): Color = when (type) {
    SetType.WARMUP -> Color(0xFFEF6C00)   // deep orange
    SetType.NORMAL -> MaterialTheme.colorScheme.onSurface
    SetType.FAILURE -> MaterialTheme.colorScheme.error
    SetType.DROP -> Color(0xFF8E24AA)     // deep purple
    SetType.SUPERSET -> MaterialTheme.colorScheme.tertiary
}
