package com.kjt.cutmoa.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

private const val BASE_DP_PER_SECOND = 80f
private const val MIN_ZOOM = 0.25f
private const val MAX_ZOOM = 8f
private const val ROW_HEIGHT_DP = 56
private const val MIN_DURATION_MS = 300L
private const val EDGE_HANDLE_DP = 14

/**
 * Multi-track editor: a ruler, one bar per [OverlayItem] (drag the body to retime, the edges
 * to trim), keyframe diamonds inside each bar (drag to retime a pose, tap to jump the
 * playhead there, long-press to delete), and a playhead line that either side can move.
 * Pinch to zoom in/out for finer control; drag empty track space to pan.
 */
@Composable
fun OverlayTimeline(
    durationMs: Long,
    playheadMs: Long,
    overlays: List<OverlayItem>,
    onSeek: (Long) -> Unit,
    onItemChange: (OverlayItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val safeDurationMs = durationMs.coerceAtLeast(1000)
    var zoomScale by remember { mutableFloatStateOf(1f) }
    var scrollOffsetPx by remember { mutableFloatStateOf(0f) }
    var scrollOffsetYPx by remember { mutableFloatStateOf(0f) }

    val pxPerMs = remember(density, zoomScale) {
        with(density) { (BASE_DP_PER_SECOND * zoomScale).dp.toPx() } / 1000f
    }
    val contentWidthPx = safeDurationMs * pxPerMs
    val contentWidthDp = with(density) { contentWidthPx.toDp() }
    val rowsHeightDp = (ROW_HEIGHT_DP * overlays.size.coerceAtLeast(1)).dp
    val rowsHeightPx = with(density) { rowsHeightDp.toPx() }

    Column(modifier = modifier) {
        // Ruler: horizontally in sync with the rows below (same scroll/zoom state), but
        // never scrolls vertically — it's the fixed reference while layers scroll under it.
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp)
                .clipToBounds(),
        ) {
            val rulerScrollPx = with(density) {
                scrollOffsetPx.coerceIn(0f, (contentWidthPx - maxWidth.toPx()).coerceAtLeast(0f))
            }
            Box(
                modifier = Modifier
                    .width(contentWidthDp)
                    .height(24.dp)
                    .offset { IntOffset(-rulerScrollPx.roundToInt(), 0) }
                    // The ruler is the safe place to scrub — it never overlaps a bar or
                    // keyframe, so a tap or drag here can never be misread as an edit.
                    .pointerInput(pxPerMs, safeDurationMs) {
                        detectTapGestures { offset ->
                            onSeek((offset.x / pxPerMs).toLong().coerceIn(0, safeDurationMs))
                        }
                    }
                    .pointerInput(pxPerMs, safeDurationMs) {
                        detectDragGestures { change, _ ->
                            onSeek((change.position.x / pxPerMs).toLong().coerceIn(0, safeDurationMs))
                        }
                    },
            ) {
                val seconds = (safeDurationMs / 1000).toInt()
                for (s in 0..seconds) {
                    Text(
                        "${s}s",
                        color = Color.Gray,
                        fontSize = 10.sp,
                        modifier = Modifier.offset { IntOffset((s * 1000 * pxPerMs).roundToInt(), 0) },
                    )
                }
                Box(
                    modifier = Modifier
                        .offset { IntOffset((playheadMs * pxPerMs).roundToInt(), 0) }
                        .width(2.dp)
                        .height(24.dp)
                        .background(Color.Red),
                )
            }
        }

        // Rows: the only scrollable area — both axes, plus pinch-zoom, live here.
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clipToBounds(),
        ) {
            val viewportWidthPx = with(density) { maxWidth.toPx() }
            val viewportHeightPx = with(density) { maxHeight.toPx() }
            val maxScrollPx = (contentWidthPx - viewportWidthPx).coerceAtLeast(0f)
            val maxScrollYPx = (rowsHeightPx - viewportHeightPx).coerceAtLeast(0f)
            val clampedScrollOffsetPx = scrollOffsetPx.coerceIn(0f, maxScrollPx)
            val clampedScrollOffsetYPx = scrollOffsetYPx.coerceIn(0f, maxScrollYPx)
            val latestMaxScrollPx = rememberUpdatedState(maxScrollPx)
            val latestMaxScrollYPx = rememberUpdatedState(maxScrollYPx)

            Box(
                modifier = Modifier
                    .width(contentWidthDp)
                    .height(rowsHeightDp)
                    .offset {
                        IntOffset(-clampedScrollOffsetPx.roundToInt(), -clampedScrollOffsetYPx.roundToInt())
                    }
                    // One gesture detector owns zoom + both scroll axes so they never fight —
                    // a plain single-finger drag reports zoom=1 and just pans (any direction).
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            zoomScale = (zoomScale * zoom).coerceIn(MIN_ZOOM, MAX_ZOOM)
                            scrollOffsetYPx = (scrollOffsetYPx - pan.y).coerceIn(0f, latestMaxScrollYPx.value)
                            scrollOffsetPx = (scrollOffsetPx - pan.x).coerceIn(0f, latestMaxScrollPx.value)
                        }
                    }
                    .pointerInput(pxPerMs, safeDurationMs) {
                        detectTapGestures { offset ->
                            onSeek((offset.x / pxPerMs).toLong().coerceIn(0, safeDurationMs))
                        }
                    },
            ) {
                // Divider lines drawn first so layer bars sit visibly on top of them.
                overlays.forEachIndexed { index, item ->
                    key("row-${item.id}") {
                        Box(
                            modifier = Modifier
                                .offset(y = ((index + 1) * ROW_HEIGHT_DP).dp)
                                .width(contentWidthDp)
                                .height(1.dp)
                                .background(Color(0xFF4A4A4A)),
                        )
                    }
                }

                overlays.forEachIndexed { index, item ->
                    key(item.id) {
                        Box(modifier = Modifier.offset(y = (index * ROW_HEIGHT_DP).dp)) {
                            LayerBar(
                                item = item,
                                pxPerMs = pxPerMs,
                                durationMs = safeDurationMs,
                                onChange = onItemChange,
                                onSeek = onSeek,
                            )
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .offset { IntOffset((playheadMs * pxPerMs).roundToInt(), 0) }
                        .width(2.dp)
                        .height(rowsHeightDp)
                        .background(Color.Red),
                )
            }
        }
    }
}

@Composable
private fun LayerBar(
    item: OverlayItem,
    pxPerMs: Float,
    durationMs: Long,
    onChange: (OverlayItem) -> Unit,
    onSeek: (Long) -> Unit,
) {
    val latestItem = rememberUpdatedState(item)
    val startPx = item.startMs * pxPerMs
    val widthPx = (item.endMs - item.startMs) * pxPerMs
    val density = LocalDensity.current
    val barColor = if (item.kind == OverlayKind.EMOJI) Color(0xFFB388FF) else Color(0xFF64B5F6)

    Box(
        modifier = Modifier
            .offset { IntOffset(startPx.roundToInt(), 0) }
            .width(with(density) { widthPx.toDp() })
            .height((ROW_HEIGHT_DP - 8).dp)
            .background(barColor, RoundedCornerShape(8.dp))
            .pointerInput(pxPerMs, durationMs) {
                detectDragGestures { _, dragAmount ->
                    val deltaMs = (dragAmount.x / pxPerMs).toLong()
                    val current = latestItem.value
                    val span = current.endMs - current.startMs
                    val newStart = (current.startMs + deltaMs).coerceIn(0, durationMs - span)
                    val shift = newStart - current.startMs
                    if (shift == 0L) return@detectDragGestures
                    onChange(
                        current.copy(
                            startMs = newStart,
                            endMs = newStart + span,
                            keyframes = current.keyframes.map { it.copy(timeMs = it.timeMs + shift) },
                        )
                    )
                }
            },
    ) {
        Text(
            item.text,
            color = Color.White,
            maxLines = 1,
            fontSize = 12.sp,
            modifier = Modifier.align(Alignment.CenterStart).padding(start = EDGE_HANDLE_DP.dp + 2.dp),
        )

        // Trim handles: narrow drag zones pinned to each edge.
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .width(EDGE_HANDLE_DP.dp)
                .fillMaxHeight()
                .pointerInput(pxPerMs) {
                    detectDragGestures { _, dragAmount ->
                        val deltaMs = (dragAmount.x / pxPerMs).toLong()
                        val current = latestItem.value
                        val newStart = (current.startMs + deltaMs).coerceIn(0, current.endMs - MIN_DURATION_MS)
                        onChange(current.copy(startMs = newStart))
                    }
                },
        )
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(EDGE_HANDLE_DP.dp)
                .fillMaxHeight()
                .pointerInput(pxPerMs, durationMs) {
                    detectDragGestures { _, dragAmount ->
                        val deltaMs = (dragAmount.x / pxPerMs).toLong()
                        val current = latestItem.value
                        val newEnd = (current.endMs + deltaMs).coerceIn(current.startMs + MIN_DURATION_MS, durationMs)
                        onChange(current.copy(endMs = newEnd))
                    }
                },
        )

        item.keyframes.forEach { kf ->
            key(kf.timeMs) {
                val kfX = (kf.timeMs - item.startMs) * pxPerMs
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .offset { IntOffset(kfX.roundToInt() - 6, -4) }
                        .width(12.dp)
                        .height(12.dp)
                        .background(Color.White, RoundedCornerShape(2.dp))
                        .pointerInput(kf.timeMs, pxPerMs) {
                            detectDragGestures { _, dragAmount ->
                                val deltaMs = (dragAmount.x / pxPerMs).toLong()
                                val current = latestItem.value
                                val existing = current.keyframes.find { it.timeMs == kf.timeMs }
                                    ?: return@detectDragGestures
                                val newTimeMs = (existing.timeMs + deltaMs).coerceIn(current.startMs, current.endMs)
                                onChange(current.withKeyframeAt(newTimeMs, existing.xFraction, existing.yFraction, existing.scale).withoutKeyframeAt(existing.timeMs))
                            }
                        }
                        .pointerInput(kf.timeMs) {
                            detectTapGestures(
                                onTap = { onSeek(kf.timeMs) },
                                onLongPress = { onChange(latestItem.value.withoutKeyframeAt(kf.timeMs)) },
                            )
                        },
                )
            }
        }
    }
}
