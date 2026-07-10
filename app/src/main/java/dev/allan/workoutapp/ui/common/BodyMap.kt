package dev.allan.workoutapp.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import dev.allan.workoutapp.data.BodyRegion

/**
 * EXPERIMENTAL muscle map. Draws a front + back wire body and fills each targeted region with
 * orange, shaded by training load (more exercises hitting a region -> deeper orange), so
 * over/under-trained areas are visible at a glance. Regions are approximate blocks, not
 * anatomical overlays — a first pass for Allan to react to before we invest in real SVGs.
 */

/** One drawn block of a region, in a 100 (wide) x 260 (tall) normalized space. */
private data class Blob(val x: Float, val y: Float, val w: Float, val h: Float)

private val FRONT: Map<BodyRegion, List<Blob>> = mapOf(
    BodyRegion.SHOULDERS_F to listOf(Blob(24f, 62f, 14f, 12f), Blob(62f, 62f, 14f, 12f)),
    BodyRegion.CHEST to listOf(Blob(34f, 66f, 15f, 16f), Blob(51f, 66f, 15f, 16f)),
    BodyRegion.BICEPS to listOf(Blob(20f, 78f, 12f, 22f), Blob(68f, 78f, 12f, 22f)),
    BodyRegion.ABS to listOf(Blob(40f, 86f, 20f, 26f)),
    BodyRegion.OBLIQUES to listOf(Blob(33f, 88f, 7f, 22f), Blob(60f, 88f, 7f, 22f)),
    BodyRegion.QUADS to listOf(Blob(35f, 138f, 13f, 40f), Blob(52f, 138f, 13f, 40f)),
    BodyRegion.CALVES_F to listOf(Blob(36f, 196f, 11f, 34f), Blob(53f, 196f, 11f, 34f)),
)

private val BACK: Map<BodyRegion, List<Blob>> = mapOf(
    BodyRegion.TRAPS to listOf(Blob(38f, 58f, 24f, 14f)),
    BodyRegion.SHOULDERS_B to listOf(Blob(24f, 62f, 14f, 12f), Blob(62f, 62f, 14f, 12f)),
    BodyRegion.TRICEPS to listOf(Blob(20f, 78f, 12f, 22f), Blob(68f, 78f, 12f, 22f)),
    BodyRegion.LATS to listOf(Blob(33f, 74f, 15f, 26f), Blob(52f, 74f, 15f, 26f)),
    BodyRegion.GLUTES to listOf(Blob(36f, 116f, 13f, 20f), Blob(51f, 116f, 13f, 20f)),
    BodyRegion.HAMSTRINGS to listOf(Blob(35f, 140f, 13f, 40f), Blob(52f, 140f, 13f, 40f)),
    BodyRegion.CALVES_B to listOf(Blob(36f, 196f, 11f, 34f), Blob(53f, 196f, 11f, 34f)),
)

@Composable
fun BodyMap(
    load: Map<BodyRegion, Float>,
    modifier: Modifier = Modifier,
    title: String? = null,
) {
    val wire = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    Column(modifier) {
        if (title != null) {
            Text(
                title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BodyView(FRONT, load, wire, Modifier.weight(1f))
            BodyView(BACK, load, wire, Modifier.weight(1f))
        }
    }
}

@Composable
private fun BodyView(
    regions: Map<BodyRegion, List<Blob>>,
    load: Map<BodyRegion, Float>,
    wire: Color,
    modifier: Modifier,
) {
    Canvas(modifier.fillMaxWidth().aspectRatio(100f / 260f)) {
        val sx = size.width / 100f
        val sy = size.height / 260f
        drawSilhouette(sx, sy, wire)
        regions.forEach { (region, blobs) ->
            val l = load[region] ?: 0f
            if (l <= 0f) return@forEach
            val color = loadColor(l)
            blobs.forEach { b ->
                drawRoundRect(
                    color = color,
                    topLeft = Offset(b.x * sx, b.y * sy),
                    size = Size(b.w * sx, b.h * sy),
                    cornerRadius = CornerRadius(4f * sx, 4f * sx),
                )
            }
        }
    }
}

/** Light orange at load ~1 up to deep orange at load >=4 — the over/under-trained signal. */
private fun loadColor(load: Float): Color {
    val light = Color(0xFFFFCC80)
    val deep = Color(0xFFE65100)
    val t = ((load - 0.5f) / 3.5f).coerceIn(0f, 1f)
    return lerp(light, deep, t)
}

/** Rough humanoid wire: head, torso, arms, legs. Stroke-only so untrained muscles show through. */
private fun DrawScope.drawSilhouette(sx: Float, sy: Float, wire: Color) {
    val stroke = Stroke(width = 1.5f * sx)
    fun box(x: Float, y: Float, w: Float, h: Float, r: Float = 6f) = drawRoundRect(
        color = wire, topLeft = Offset(x * sx, y * sy), size = Size(w * sx, h * sy),
        cornerRadius = CornerRadius(r * sx, r * sx), style = stroke,
    )
    // head
    drawCircle(wire, radius = 11f * sx, center = Offset(50f * sx, 20f * sy), style = stroke)
    box(38f, 54f, 24f, 62f, 10f)   // torso
    box(34f, 116f, 32f, 24f, 8f)   // hips
    box(18f, 60f, 14f, 60f, 7f)    // left arm
    box(68f, 60f, 14f, 60f, 7f)    // right arm
    box(34f, 138f, 15f, 96f, 7f)   // left leg
    box(51f, 138f, 15f, 96f, 7f)   // right leg
}
