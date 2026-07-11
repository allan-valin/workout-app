package dev.allan.workoutapp.ui.common

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Fast scrollbar for every listing screen (Allan): a thin thumb is always visible on the
 * right while the content overflows; while the list scrolls (or the thumb is touched) it
 * grows into a draggable fast-scroll handle. In big mode, pressing the track above/below
 * the thumb scrolls steadily in that direction (like a desktop scrollbar track press),
 * never jumping straight to the pressed spot.
 *
 * Wrap the list in a Box and overlay:
 * ```
 * Box { LazyColumn(state = st) { … }; LazyScrollbar(st, Modifier.align(Alignment.TopEnd)) }
 * ```
 */
/** Drop-in LazyColumn with the fast scrollbar overlaid — 1-line swap at call sites. */
@Composable
fun ScrollbarLazyColumn(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    edgePadding: androidx.compose.ui.unit.Dp = 0.dp,
    content: LazyListScope.() -> Unit,
) {
    Box(modifier) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = state,
            verticalArrangement = verticalArrangement,
            content = content,
        )
        LazyScrollbar(state, Modifier.align(Alignment.TopEnd), edgePadding = edgePadding)
    }
}

/** Drop-in scrollable Column with the fast scrollbar overlaid. */
@Composable
fun ScrollbarColumn(
    modifier: Modifier = Modifier,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    state: ScrollState = rememberScrollState(),
    edgePadding: androidx.compose.ui.unit.Dp = 0.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(modifier) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(state),
            verticalArrangement = verticalArrangement,
            content = content,
        )
        ColumnScrollbar(state, Modifier.align(Alignment.TopEnd), edgePadding = edgePadding)
    }
}

@Composable
fun LazyScrollbar(
    state: LazyListState,
    modifier: Modifier = Modifier,
    /** Content's right padding to cancel out so the bar hugs the screen edge. */
    edgePadding: androidx.compose.ui.unit.Dp = 0.dp,
) {
    val info = state.layoutInfo
    val total = info.totalItemsCount
    val visible = info.visibleItemsInfo
    if (total == 0 || visible.isEmpty()) return
    val avgSize = visible.sumOf { it.size }.toFloat() / visible.size
    val viewportPx = (info.viewportEndOffset - info.viewportStartOffset).toFloat()
    val estContentPx = avgSize * total
    if (estContentPx <= viewportPx + 1f) return // nothing to scroll

    val thumbFrac = (viewportPx / estContentPx).coerceIn(0.06f, 1f)
    val scrolledPx = state.firstVisibleItemIndex * avgSize + state.firstVisibleItemScrollOffset
    val denom = (estContentPx - viewportPx).coerceAtLeast(1f)
    val offsetFrac = (scrolledPx / denom).coerceIn(0f, 1f)
    val scope = rememberCoroutineScope()

    ScrollbarTrack(
        modifier = modifier.offset(x = edgePadding),
        thumbFrac = thumbFrac,
        offsetFrac = offsetFrac,
        scrolling = state.isScrollInProgress,
        scope = scope,
        onDragDelta = { deltaFrac -> state.scrollBy(deltaFrac * estContentPx) },
        onPageStep = { dir -> state.animateScrollBy(dir * viewportPx * 0.85f) },
    )
}

/** Same behavior for plain scrollable Columns. */
@Composable
fun ColumnScrollbar(
    state: ScrollState,
    modifier: Modifier = Modifier,
    /** Content's right padding to cancel out so the bar hugs the screen edge. */
    edgePadding: androidx.compose.ui.unit.Dp = 0.dp,
) {
    if (state.maxValue <= 0 || state.maxValue == Int.MAX_VALUE) return
    var viewportPx by remember { mutableFloatStateOf(0f) }
    val estContentPx = state.maxValue + viewportPx
    val thumbFrac = if (estContentPx > 0f) (viewportPx / estContentPx).coerceIn(0.06f, 1f) else 1f
    val offsetFrac = (state.value.toFloat() / state.maxValue).coerceIn(0f, 1f)
    val scope = rememberCoroutineScope()

    ScrollbarTrack(
        modifier = modifier.offset(x = edgePadding).onSizeChanged { viewportPx = it.height.toFloat() },
        thumbFrac = thumbFrac,
        offsetFrac = offsetFrac,
        scrolling = state.isScrollInProgress,
        scope = scope,
        onDragDelta = { deltaFrac -> state.scrollBy(deltaFrac * estContentPx) },
        onPageStep = { dir -> state.animateScrollBy(dir * viewportPx * 0.85f) },
    )
}

@Composable
private fun ScrollbarTrack(
    modifier: Modifier,
    thumbFrac: Float,
    offsetFrac: Float,
    scrolling: Boolean,
    scope: CoroutineScope,
    /** deltaFrac = drag delta as a fraction of the track height. */
    onDragDelta: suspend (Float) -> Unit,
    /** One paged scroll step toward dir (-1 up / +1 down); called repeatedly while pressed. */
    onPageStep: suspend (Float) -> Unit,
) {
    var interacting by remember { mutableStateOf(false) }
    val big = interacting || scrolling
    val width by animateDpAsState(if (big) 10.dp else 3.dp, label = "sbWidth")
    // Idle thumb fades to a faint hint; slow tween so it visibly recedes after scrolling.
    val alpha by animateFloatAsState(
        if (big) 0.9f else 0.15f,
        animationSpec = androidx.compose.animation.core.tween(600),
        label = "sbAlpha",
    )
    val density = LocalDensity.current
    var trackHeightPx by remember { mutableIntStateOf(0) }

    Box(
        modifier
            .fillMaxHeight()
            .width(16.dp) // touch target wider than the visual bar
            .onSizeChanged { trackHeightPx = it.height }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val h = size.height.toFloat()
                    val thumbTop = offsetFrac * (1f - thumbFrac) * h
                    val thumbBottom = thumbTop + thumbFrac * h
                    interacting = true
                    var pager: Job? = null
                    if (down.position.y in thumbTop..thumbBottom) {
                        drag(down.id) { change ->
                            val delta = change.position.y - change.previousPosition.y
                            scope.launch { onDragDelta(delta / h) }
                            change.consume()
                        }
                    } else {
                        // Track press: creep toward the finger, never jump.
                        val dir = if (down.position.y < thumbTop) -1f else 1f
                        pager = scope.launch { while (isActive) onPageStep(dir) }
                        drag(down.id) { it.consume() } // hold until release/cancel
                    }
                    pager?.cancel()
                    interacting = false
                }
            },
    ) {
        val thumbHeightDp = with(density) { (thumbFrac * trackHeightPx).toDp() }
        val thumbOffsetPx = (offsetFrac * (1f - thumbFrac) * trackHeightPx).toInt()
        Box(
            Modifier
                .offset { IntOffset(0, thumbOffsetPx) }
                .width(width)
                .height(thumbHeightDp)
                .alpha(alpha)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.onSurfaceVariant),
        )
    }
}
