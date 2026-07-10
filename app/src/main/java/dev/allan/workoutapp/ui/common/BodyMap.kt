package dev.allan.workoutapp.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.SvgDecoder
import coil.request.ImageRequest
import dev.allan.workoutapp.R
import dev.allan.workoutapp.data.MuscleMap

/**
 * Anatomical muscle map. Renders wger's own front + back body SVGs (grayscale line art) and
 * overlays each targeted muscle's wger SVG, tinted orange by training load — deeper orange =
 * more exercises hitting it, so over/under-trained muscles read at a glance. Assets bundled in
 * assets/body/ (base: front.svg/back.svg; overlays: muscle-<wgerId>.svg).
 */
@Composable
fun BodyMap(
    load: Map<Int, Float>,
    modifier: Modifier = Modifier,
    title: String? = null,
) {
    val context = LocalContext.current
    val loader = remember { ImageLoader.Builder(context).components { add(SvgDecoder.Factory()) }.build() }
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
            BodyView("body/front.svg", MuscleMap.FRONT_IDS, load, loader, Modifier.weight(1f))
            BodyView("body/back.svg", MuscleMap.BACK_IDS, load, loader, Modifier.weight(1f))
        }
        Legend()
    }
}

@Composable
private fun BodyView(
    baseAsset: String,
    ids: Set<Int>,
    load: Map<Int, Float>,
    loader: ImageLoader,
    modifier: Modifier,
) {
    val context = LocalContext.current
    // The base and overlays have slightly different intrinsic heights; FillBounds forces them
    // into the same box so the overlays register on the body.
    Box(modifier.fillMaxWidth().aspectRatio(200f / 369f)) {
        AsyncImage(
            model = ImageRequest.Builder(context).data("file:///android_asset/$baseAsset").build(),
            imageLoader = loader,
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = Modifier.fillMaxWidth().aspectRatio(200f / 369f),
        )
        ids.forEach { id ->
            val l = load[id] ?: 0f
            if (l <= 0f) return@forEach
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data("file:///android_asset/body/muscle-$id.svg").build(),
                imageLoader = loader,
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                colorFilter = ColorFilter.tint(loadColor(l)),
                modifier = Modifier.fillMaxWidth().aspectRatio(200f / 369f),
            )
        }
    }
}

/** Legend: colored squares mapping shade -> number of exercises hitting the muscle. */
@Composable
private fun Legend() {
    val steps = listOf(
        1f to "1",
        2f to "2",
        3f to "3",
        4f to "4+",
    )
    Column(Modifier.padding(top = 8.dp)) {
        Text(
            androidx.compose.ui.res.stringResource(R.string.muscle_legend_header),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            Modifier.padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            steps.forEach { (load, label) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(14.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(loadColor(load)),
                    )
                    Text(
                        " $label",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            Text(
                androidx.compose.ui.res.stringResource(R.string.muscle_legend_unit),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Light orange at load ~1 up to deep orange at load >=4 — the over/under-trained signal. */
private fun loadColor(load: Float): Color {
    val light = Color(0xFFFFCC80)
    val deep = Color(0xFFE65100)
    val t = ((load - 1f) / 3f).coerceIn(0f, 1f)
    return androidx.compose.ui.graphics.lerp(light, deep, t)
}
