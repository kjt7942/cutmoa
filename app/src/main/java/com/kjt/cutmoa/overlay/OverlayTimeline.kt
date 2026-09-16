package com.kjt.cutmoa.overlay

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.roundToInt

private const val BASE_DP_PER_SECOND = 100f
private const val MIN_ZOOM = 0.3f
private const val MAX_ZOOM = 6f
private val ROW_HEIGHT = 44.dp
private val RULER_HEIGHT = 22.dp
private val HANDLE_WIDTH = 22.dp
private val SNAP_DISTANCE = 10.dp

private val TEXT_BAR = Color(0xFF3D7BD9)
private val EMOJI_BAR = Color(0xFF8E5BD9)
val PLAYHEAD_COLOR = Color(0xFFFFC107)

/**
 * Layer timeline with the playhead pinned to the center: dragging anywhere scrubs the video
 * under it, pinch zooms. Newest (front-most) layer is the top row.
 *
 * A plain drag always scrubs, even across a bar — so scrubbing never retimes a layer by
 * accident. Tap a bar to select it; long-press-and-drag a bar to move it in time; drag the
 * wide end handles of the selected bar to trim. Moved/trimmed edges snap to the playhead.
 */
@Composable
fun OverlayTimeline(
    durationMs: Long,
    playheadMs: Long,
    overlays: List<OverlayItem>,
    selectedId: Long?,
    onScrub: (Long) -> Unit,
    onSelect: (Long) -> Unit,
    onEditStart: () -> Unit,
    onItemChange: (OverlayItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val safeDurationMs = durationMs.coerceAtLeast(1)
    var zoom by remember { mutableFloatStateOf(1f) }
    val pxPerMs = with(density) { BASE_DP_PER_SECOND.dp.toPx() } * zoom / 1000f

    val latestPlayhead = rememberUpdatedState(playheadMs)
    val latestPxPerMs = rememberUpdatedState(pxPerMs)
    val latestDuration = rememberUpdatedState(safeDurationMs)
    val latestOnScrub = rememberUpdatedState(onScrub)

    BoxWithConstraints(
        modifier = modifier
            .background(Color(0xFF161616))
            .clipToBounds()
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val startPlayhead = latestPlayhead.value
                    var totalDx = 0f
                    var scrubbing = false
                    var multiTouch = false
                    do {
                        val event = awaitPointerEvent()
                        // A selected bar handled this drag itself.
                        if (event.changes.any { it.isConsumed }) break
                        if (event.changes.count { it.pressed } >= 2) {
                            multiTouch = true
                            zoom = (zoom * event.calculateZoom()).coerceIn(MIN_ZOOM, MAX_ZOOM)
                            event.changes.forEach { it.consume() }
                        } else if (!multiTouch) {
                            totalDx += event.calculatePan().x
                            if (!scrubbing && abs(totalDx) > viewConfiguration.touchSlop) scrubbing = true
                            if (scrubbing) {
                                // From the drag start, not per event: several events can arrive
                                // before the new playhead recomposes back in.
                                val target = startPlayhead - (totalDx / latestPxPerMs.value).toLong()
                                latestOnScrub.value(target.coerceIn(0, latestDuration.value))
                                event.changes.forEach { it.consume() }
                            }
                        }
                    } while (event.changes.any { it.pressed })

                    // Tap on the ruler jumps there.
                    if (!scrubbing && !multiTouch && down.position.y <= RULER_HEIGHT.toPx()) {
                        val center = size.width / 2f
                        val target = latestPlayhead.value + ((down.position.x - center) / latestPxPerMs.value).toLong()
                        latestOnScrub.value(target.coerceIn(0, latestDuration.value))
                    }
                }
            },
    ) {
        val viewportWidthPx = constraints.maxWidth.toFloat()
        // Screen x of video time 0.
        val originPx = viewportWidthPx / 2f - playheadMs * pxPerMs

        // The video's extent, so it's clear where the clip starts and ends.
        Box(
            modifier = Modifier
                .offset { IntOffset(originPx.roundToInt(), 0) }
                // Unbounded: at most zoom levels the clip is wider than the viewport, and a
                // plain width() would be clamped to it (the band stopped partway through).
                .wrapContentWidth(Alignment.Start, unbounded = true)
                .width(with(density) { (safeDurationMs * pxPerMs).toDp() })
                .fillMaxHeight()
                .background(Color(0xFF242424)),
        )

        Column(modifier = Modifier.fillMaxSize()) {
            Ruler(originPx = originPx, pxPerMs = pxPerMs, durationMs = safeDurationMs)

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (overlays.isEmpty()) {
                    Box(Modifier.fillMaxWidth().height(ROW_HEIGHT), contentAlignment = Alignment.Center) {
                        Text("추가한 텍스트·이모지가 여기에 레이어로 표시돼요", color = Color.Gray, fontSize = 12.sp)
                    }
                }
                overlays.asReversed().forEach { item ->
                    key(item.id) {
                        LayerRow(
                            item = item,
                            isSelected = item.id == selectedId,
                            originPx = originPx,
                            pxPerMs = pxPerMs,
                            playheadMs = playheadMs,
                            durationMs = safeDurationMs,
                            onSelect = { onSelect(item.id) },
                            onScrub = onScrub,
                            onEditStart = onEditStart,
                            onItemChange = onItemChange,
                        )
                    }
                }
            }
        }

        // Fixed center playhead.
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .width(2.dp)
                .fillMaxHeight()
                .background(PLAYHEAD_COLOR),
        )
        Canvas(modifier = Modifier.align(Alignment.TopCenter).size(12.dp, 8.dp)) {
            drawPath(
                Path().apply {
                    moveTo(0f, 0f); lineTo(size.width, 0f); lineTo(size.width / 2f, size.height); close()
                },
                PLAYHEAD_COLOR,
            )
        }
    }
}

@Composable
private fun Ruler(originPx: Float, pxPerMs: Float, durationMs: Long) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val labelStyle = TextStyle(color = Color(0xFFAAAAAA), fontSize = 10.sp)
    // Labels at least ~56dp apart whatever the zoom.
    val minLabelGapPx = with(density) { 56.dp.toPx() }
    val labelStepMs = listOf(250L, 500L, 1000L, 2000L, 5000L, 10000L, 30000L)
        .firstOrNull { it * pxPerMs >= minLabelGapPx } ?: 60000L
    val tickStepMs = labelStepMs / 2

    Canvas(modifier = Modifier.fillMaxWidth().height(RULER_HEIGHT)) {
        val firstTick = ((-originPx / pxPerMs) / tickStepMs).toLong().coerceAtLeast(0) * tickStepMs
        var t = firstTick
        while (t <= durationMs) {
            val x = originPx + t * pxPerMs
            if (x > size.width) break
            val isLabel = t % labelStepMs == 0L
            drawLine(
                color = Color(0xFF777777),
                start = Offset(x, size.height),
                end = Offset(x, size.height - if (isLabel) 8.dp.toPx() else 4.dp.toPx()),
                strokeWidth = 1.dp.toPx(),
            )
            if (isLabel) {
                val label = if (t % 1000 == 0L) "${t / 1000}s" else "%.1fs".format(t / 1000f)
                // Measured unconstrained: drawText(measurer, String) limits width to what's
                // left of the canvas, which goes negative for a label past the right edge.
                drawText(measurer.measure(label, labelStyle), topLeft = Offset(x + 3.dp.toPx(), 1.dp.toPx()))
            }
            t += tickStepMs
        }
    }
}

@Composable
private fun LayerRow(
    item: OverlayItem,
    isSelected: Boolean,
    originPx: Float,
    pxPerMs: Float,
    playheadMs: Long,
    durationMs: Long,
    onSelect: () -> Unit,
    onScrub: (Long) -> Unit,
    onEditStart: () -> Unit,
    onItemChange: (OverlayItem) -> Unit,
) {
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val latestItem = rememberUpdatedState(item)
    val latestPxPerMs = rememberUpdatedState(pxPerMs)
    val latestPlayhead = rememberUpdatedState(playheadMs)
    val latestDuration = rememberUpdatedState(durationMs)
    val snapPx = with(density) { SNAP_DISTANCE.toPx() }

    val barLeftPx = originPx + item.startMs * pxPerMs
    val barWidthPx = ((item.endMs - item.startMs) * pxPerMs).coerceAtLeast(1f)
    val baseColor = if (item.kind == OverlayKind.EMOJI) EMOJI_BAR else TEXT_BAR

    /** Snaps [ms] onto the playhead when it's within a few dp of it. */
    fun snapToPlayhead(ms: Long): Long =
        if (abs(ms - latestPlayhead.value) * latestPxPerMs.value <= snapPx) latestPlayhead.value else ms

    Box(modifier = Modifier.fillMaxWidth().height(ROW_HEIGHT)) {
        Box(
            modifier = Modifier
                .offset { IntOffset(barLeftPx.roundToInt(), 0) }
                .align(Alignment.CenterStart)
                .wrapContentWidth(Alignment.Start, unbounded = true)
                .width(with(density) { barWidthPx.toDp() })
                .height(ROW_HEIGHT - 8.dp)
                .background(if (isSelected) baseColor else baseColor.copy(alpha = 0.45f), RoundedCornerShape(6.dp))
                .then(if (isSelected) Modifier.border(2.dp, Color.White, RoundedCornerShape(6.dp)) else Modifier)
                .pointerInput(Unit) { detectTapGestures { onSelect() } }
                .pointerInput(Unit) {
                    var origin = latestItem.value
                    var dragged = 0f
                    detectDragGesturesAfterLongPress(
                        onDragStart = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onSelect()
                            onEditStart()
                            origin = latestItem.value
                            dragged = 0f
                        },
                    ) { change, dragAmount ->
                        change.consume()
                        dragged += dragAmount.x
                        var newStart = origin.startMs + (dragged / latestPxPerMs.value).toLong()
                        val span = origin.endMs - origin.startMs
                        val snappedStart = snapToPlayhead(newStart)
                        newStart = if (snappedStart != newStart) snappedStart else snapToPlayhead(newStart + span) - span
                        onItemChange(origin.movedTo(newStart, latestDuration.value))
                    }
                },
        ) {
            // Keep the label on screen when the bar starts left of the viewport.
            val labelInsetPx = (-barLeftPx).coerceAtLeast(0f)
            Text(
                item.text.lineSequence().first(),
                color = Color.White,
                fontSize = if (item.kind == OverlayKind.EMOJI) 16.sp else 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset { IntOffset(labelInsetPx.roundToInt(), 0) }
                    .padding(horizontal = if (isSelected) HANDLE_WIDTH + 4.dp else 8.dp),
            )

            if (isSelected) {
                TrimHandle(
                    modifier = Modifier.align(Alignment.CenterStart),
                    onDragStart = onEditStart,
                    onDrag = { totalDx, origin ->
                        val raw = origin.startMs + (totalDx / latestPxPerMs.value).toLong()
                        onItemChange(origin.trimmedStart(snapToPlayhead(raw)))
                    },
                    latestItem = latestItem,
                )
                TrimHandle(
                    modifier = Modifier.align(Alignment.CenterEnd),
                    onDragStart = onEditStart,
                    onDrag = { totalDx, origin ->
                        val raw = origin.endMs + (totalDx / latestPxPerMs.value).toLong()
                        onItemChange(origin.trimmedEnd(snapToPlayhead(raw), latestDuration.value))
                    },
                    latestItem = latestItem,
                )

                // Keyframe markers (only when animated): tap one to jump the playhead to it.
                if (item.animated) {
                    item.keyframes.forEach { kf ->
                        key(kf.timeMs) {
                            val xPx = (kf.timeMs - item.startMs) * pxPerMs
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .offset { IntOffset((xPx - 7.dp.toPx()).roundToInt(), 5.dp.roundToPx()) }
                                    .size(14.dp)
                                    .pointerInput(kf.timeMs) { detectTapGestures { onScrub(kf.timeMs) } },
                                contentAlignment = Alignment.Center,
                            ) {
                                Box(
                                    Modifier
                                        .size(9.dp)
                                        .rotate(45f)
                                        .background(PLAYHEAD_COLOR)
                                        .border(1.dp, Color.Black),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TrimHandle(
    modifier: Modifier,
    onDragStart: () -> Unit,
    onDrag: (totalDx: Float, origin: OverlayItem) -> Unit,
    latestItem: androidx.compose.runtime.State<OverlayItem>,
) {
    Box(
        modifier = modifier
            .width(HANDLE_WIDTH)
            .fillMaxHeight()
            .background(Color.White.copy(alpha = 0.92f), RoundedCornerShape(5.dp))
            .pointerInput(Unit) {
                var origin = latestItem.value
                var dragged = 0f
                detectHorizontalDragGestures(
                    onDragStart = { onDragStart(); origin = latestItem.value; dragged = 0f },
                ) { change, dx ->
                    change.consume()
                    dragged += dx
                    onDrag(dragged, origin)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.width(2.dp).height(14.dp).background(Color(0xFF555555)))
    }
}
