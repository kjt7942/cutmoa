package com.kjt.cutmoa.overlay

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.kjt.cutmoa.util.VideoFrameUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.math.roundToInt

private val PRESET_COLORS = listOf(Color.White, Color.Black, Color.Red, Color.Yellow, Color(0xFF3DDC84))
private val PRESET_EMOJIS = listOf("😀", "😂", "🔥", "❤️", "👍", "🎉", "😍", "😎", "🥳", "⭐")
private const val DEFAULT_LAYER_DURATION_MS = 2000L
private const val MIN_LAYER_DURATION_MS = 300L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OverlayEditorScreen(mergedVideoPath: String, onExported: (String) -> Unit) {
    val context = LocalContext.current
    val workManager = remember { WorkManager.getInstance(context) }

    var durationMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(mergedVideoPath) {
        durationMs = withContext(Dispatchers.IO) { VideoFrameUtil.durationMs(mergedVideoPath) }
    }

    var playheadMs by remember { mutableLongStateOf(0L) }
    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(playheadMs, mergedVideoPath) {
        delay(80) // debounce fast scrubbing so we don't decode a frame per pixel of drag
        previewBitmap = withContext(Dispatchers.IO) { VideoFrameUtil.frameAt(mergedVideoPath, playheadMs) }
    }

    var overlays by remember { mutableStateOf<List<OverlayItem>>(emptyList()) }
    var showTextDialog by remember { mutableStateOf(false) }
    var editingItemId by remember { mutableStateOf<Long?>(null) }
    var selectedItemId by remember { mutableStateOf<Long?>(null) }
    var nextId by remember { mutableLongStateOf(0L) }
    var activeWorkId by remember { mutableStateOf<UUID?>(null) }

    val workInfoFlow = remember(activeWorkId) {
        activeWorkId?.let { workManager.getWorkInfoByIdFlow(it) } ?: flowOf(null)
    }
    val workInfo by workInfoFlow.collectAsState(initial = null)

    LaunchedEffect(workInfo?.state) {
        val info = workInfo ?: return@LaunchedEffect
        if (info.state == WorkInfo.State.SUCCEEDED) {
            info.outputData.getString(OverlayExportWorker.KEY_OUTPUT_URI)?.let(onExported)
        }
    }

    val isExporting = workInfo?.state == WorkInfo.State.RUNNING || workInfo?.state == WorkInfo.State.ENQUEUED
    val exportProgress = workInfo?.progress?.getInt(OverlayExportWorker.KEY_PROGRESS, 0) ?: 0
    val exportFailed = workInfo?.state == WorkInfo.State.FAILED

    fun addLayer(kind: OverlayKind, text: String, color: Color) {
        val start = playheadMs.coerceIn(0, (durationMs - MIN_LAYER_DURATION_MS).coerceAtLeast(0))
        val end = (start + DEFAULT_LAYER_DURATION_MS).coerceAtMost(durationMs).coerceAtLeast(start + MIN_LAYER_DURATION_MS)
        val id = nextId++
        overlays = overlays + OverlayItem(
            id = id,
            kind = kind,
            text = text,
            color = color,
            startMs = start,
            endMs = end,
            keyframes = listOf(Keyframe(timeMs = start, xFraction = 0.5f, yFraction = 0.5f, scale = 1f)),
        )
        selectedItemId = id
    }

    Scaffold(topBar = { TopAppBar(title = { Text("자막 · 이모지") }) }) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            var canvasZoom by remember { mutableFloatStateOf(1f) }
            var canvasPanX by remember { mutableFloatStateOf(0f) }
            var canvasPanY by remember { mutableFloatStateOf(0f) }

            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color.Black)
                    .clipToBounds(),
            ) {
                val density = LocalDensity.current
                val containerWidthPx = with(density) { maxWidth.toPx() }
                val containerHeightPx = with(density) { maxHeight.toPx() }
                val maxPanX = containerWidthPx * (canvasZoom - 1f) / 2f
                val maxPanY = containerHeightPx * (canvasZoom - 1f) / 2f

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        // Pinch anywhere on empty video/background to zoom in for fine
                        // placement — a chip under the finger consumes the gesture first
                        // (see DraggableOverlay), so this only fires on empty canvas.
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                canvasZoom = (canvasZoom * zoom).coerceIn(1f, 5f)
                                canvasPanX = (canvasPanX + pan.x).coerceIn(-maxPanX, maxPanX)
                                canvasPanY = (canvasPanY + pan.y).coerceIn(-maxPanY, maxPanY)
                            }
                        }
                        // Tapping empty canvas deselects — a chip's own tap-to-select
                        // consumes the tap first, so this only fires off any chip.
                        .pointerInput(Unit) {
                            detectTapGestures { selectedItemId = null }
                        }
                        .graphicsLayer(
                            scaleX = canvasZoom,
                            scaleY = canvasZoom,
                            translationX = canvasPanX,
                            translationY = canvasPanY,
                        ),
                ) {
                    if (previewBitmap != null) {
                        Image(
                            bitmap = previewBitmap!!.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit,
                        )
                    } else {
                        Text(
                            "미리보기 로딩 중…",
                            color = Color.White,
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }

                    overlays.filter { it.isVisibleAt(playheadMs) }.forEach { item ->
                        key(item.id) {
                            DraggableOverlay(
                                item = item,
                                pose = item.poseAt(playheadMs),
                                containerWidthPx = containerWidthPx,
                                containerHeightPx = containerHeightPx,
                                canvasZoom = canvasZoom,
                                isSelected = selectedItemId == item.id,
                                onSelect = { selectedItemId = item.id },
                                onChange = { x, y, scale ->
                                    overlays = overlays.map {
                                        if (it.id == item.id) it.withKeyframeAt(playheadMs, x, y, scale) else it
                                    }
                                },
                                onRemove = {
                                    overlays = overlays.filterNot { it.id == item.id }
                                    if (selectedItemId == item.id) selectedItemId = null
                                },
                                onEdit = if (item.kind == OverlayKind.TEXT) {
                                    { editingItemId = item.id; showTextDialog = true }
                                } else null,
                            )
                        }
                    }
                }
            }

            OverlayTimeline(
                durationMs = durationMs,
                playheadMs = playheadMs,
                overlays = overlays,
                onSeek = { playheadMs = it },
                onItemChange = { updated -> overlays = overlays.map { if (it.id == updated.id) updated else it } },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .padding(vertical = 8.dp),
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = { showTextDialog = true }) { Text("텍스트 추가") }
            }

            LazyRow(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                items(PRESET_EMOJIS) { emoji ->
                    TextButton(onClick = { addLayer(OverlayKind.EMOJI, emoji, Color.White) }) {
                        Text(emoji, fontSize = 28.sp)
                    }
                }
            }

            if (isExporting) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    LinearProgressIndicator(
                        progress = { exportProgress / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("최종 영상 만드는 중… $exportProgress%")
                }
            } else {
                Button(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    onClick = {
                        val request = OverlayExportWorker.buildRequest(mergedVideoPath, overlays)
                        activeWorkId = request.id
                        workManager.enqueue(request)
                    }
                ) {
                    Text("최종 영상 만들기")
                }
            }

            if (exportFailed) {
                Text(
                    "내보내기 실패: ${workInfo?.outputData?.getString(OverlayExportWorker.KEY_ERROR)}",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
    }

    if (showTextDialog) {
        val editingItem = editingItemId?.let { id -> overlays.find { it.id == id } }
        AddTextDialog(
            initialText = editingItem?.text ?: "",
            initialColor = editingItem?.color ?: PRESET_COLORS.first(),
            isEditing = editingItem != null,
            onDismiss = { showTextDialog = false; editingItemId = null },
            onConfirm = { text, color ->
                if (editingItem != null) {
                    overlays = overlays.map {
                        if (it.id == editingItem.id) it.copy(text = text, color = color) else it
                    }
                } else {
                    addLayer(OverlayKind.TEXT, text, color)
                }
                showTextDialog = false
                editingItemId = null
            }
        )
    }
}

@Composable
private fun DraggableOverlay(
    item: OverlayItem,
    pose: Keyframe,
    containerWidthPx: Float,
    containerHeightPx: Float,
    canvasZoom: Float,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onChange: (xFraction: Float, yFraction: Float, scale: Float) -> Unit,
    onRemove: () -> Unit,
    onEdit: (() -> Unit)?,
) {
    val latestPose = rememberUpdatedState(pose)
    val offsetX = pose.xFraction * containerWidthPx
    val offsetY = pose.yFraction * containerHeightPx

    // Only a selected item responds to pinch/drag — otherwise the same gesture falls
    // through untouched to the canvas behind it, which zooms/pans the video instead.
    val gestureModifier = if (isSelected) {
        Modifier.pointerInput(containerWidthPx, containerHeightPx) {
            detectTransformGestures { _, pan, zoom, _ ->
                val current = latestPose.value
                val newX = ((current.xFraction * containerWidthPx + pan.x) / containerWidthPx)
                    .coerceIn(0f, 1f)
                val newY = ((current.yFraction * containerHeightPx + pan.y) / containerHeightPx)
                    .coerceIn(0f, 1f)
                val newScale = (current.scale * zoom).coerceIn(0.3f, 4f)
                onChange(newX, newY, newScale)
            }
        }
    } else {
        Modifier.pointerInput(item.id) {
            detectTapGestures { onSelect() }
        }
    }

    // Icon buttons counter-scale against the canvas zoom so they stay a fixed, tappable
    // size on screen no matter how far the video is zoomed in.
    val iconCounterScale = 1f / canvasZoom

    Box(
        modifier = Modifier
            .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
            .then(gestureModifier)
            // Extra invisible margin around the chip so a pinch-to-resize has room to grab
            // even when the text/emoji itself renders small.
            .padding(16.dp)
    ) {
        Text(
            text = item.text,
            color = if (item.kind == OverlayKind.TEXT) item.color else Color.Unspecified,
            fontSize = (if (item.kind == OverlayKind.EMOJI) 40 else 24).sp * pose.scale,
            modifier = Modifier
                .background(if (item.kind == OverlayKind.TEXT) Color.Black.copy(alpha = 0.25f) else Color.Transparent)
                .then(
                    if (isSelected) {
                        Modifier.border(2.dp, Color(0xFFFFC107), RoundedCornerShape(4.dp))
                    } else {
                        Modifier
                    }
                )
                .padding(4.dp)
        )
        if (isSelected) {
            IconButton(
                onClick = onRemove,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 8.dp, y = (-8).dp)
                    .size(20.dp)
                    .graphicsLayer(scaleX = iconCounterScale, scaleY = iconCounterScale)
                    .background(Color.White, CircleShape),
            ) {
                Icon(Icons.Filled.Close, contentDescription = "제거", tint = Color.Black, modifier = Modifier.size(14.dp))
            }
            if (onEdit != null) {
                IconButton(
                    onClick = onEdit,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .offset(x = (-8).dp, y = (-8).dp)
                        .size(20.dp)
                        .graphicsLayer(scaleX = iconCounterScale, scaleY = iconCounterScale)
                        .background(Color.White, CircleShape),
                ) {
                    Icon(Icons.Filled.Edit, contentDescription = "수정", tint = Color.Black, modifier = Modifier.size(12.dp))
                }
            }
        }
    }
}

@Composable
private fun AddTextDialog(
    initialText: String = "",
    initialColor: Color = PRESET_COLORS.first(),
    isEditing: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (String, Color) -> Unit,
) {
    var text by remember { mutableStateOf(initialText) }
    var selectedColor by remember { mutableStateOf(initialColor) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isEditing) "텍스트 수정" else "텍스트 추가") },
        text = {
            Column {
                OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("자막") })
                Row(
                    modifier = Modifier.padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PRESET_COLORS.forEach { color ->
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(color)
                                .border(
                                    width = if (color == selectedColor) 3.dp else 0.dp,
                                    color = Color.Gray,
                                    shape = CircleShape,
                                )
                                .clickable { selectedColor = color }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (text.isNotBlank()) onConfirm(text, selectedColor) },
                enabled = text.isNotBlank(),
            ) { Text(if (isEditing) "수정" else "추가") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}
