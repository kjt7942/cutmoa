package com.kjt.cutmoa.overlay

import android.view.TextureView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FlipToFront
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.kjt.cutmoa.util.SystemBarsFor
import com.kjt.cutmoa.util.VideoFrameUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

private const val DEFAULT_LAYER_DURATION_MS = 3000L
private const val MAX_UNDO = 50
private val TOOLS_HEIGHT = 132.dp

/** Preview bitmaps are rendered this much bigger than reference size so zooming in stays sharp. */
private const val PREVIEW_PIXEL_SCALE = 1.5f

/** New layers fan out vertically so several added in a row don't sit exactly on top of each other. */
private val NEW_LAYER_Y_OFFSETS = listOf(0f, 0.1f, -0.1f, 0.2f, -0.2f)

private val overlaysSaver = Saver<List<OverlayItem>, String>(
    save = { it.toJson() },
    restore = { parseOverlayItems(it) },
)

private data class RenderKey(val kind: OverlayKind, val text: String, val color: Color, val style: OverlayTextStyle)

private val EditorColors = darkColorScheme(
    primary = PLAYHEAD_COLOR,
    onPrimary = Color.Black,
    secondaryContainer = Color(0xFF3A3A3A),
    onSecondaryContainer = Color.White,
    background = Color(0xFF101010),
    surface = Color(0xFF101010),
)

@Composable
fun OverlayEditorScreen(mergedVideoPath: String, onExported: (String) -> Unit, onBack: () -> Unit) {
    SystemBarsFor(darkContent = true)
    MaterialTheme(colorScheme = EditorColors) {
        EditorContent(mergedVideoPath, onExported, onBack)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorContent(mergedVideoPath: String, onExported: (String) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val workManager = remember { WorkManager.getInstance(context) }

    var durationMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(mergedVideoPath) {
        durationMs = withContext(Dispatchers.IO) { VideoFrameUtil.durationMs(mergedVideoPath) }
    }

    // ---- Player: paused it shows the frame at the playhead; playing it drives the playhead.
    var playheadMs by rememberSaveable { mutableLongStateOf(0L) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val player = remember { ExoPlayer.Builder(context).build() }
    var isPlaying by remember { mutableStateOf(false) }
    var videoAspect by remember { mutableFloatStateOf(9f / 16f) }
    DisposableEffect(Unit) {
        player.setMediaItem(MediaItem.fromUri(mergedVideoPath))
        player.prepare()
        player.seekTo(playheadMs)
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onVideoSizeChanged(size: VideoSize) {
                if (size.height > 0) videoAspect = size.width * size.pixelWidthHeightRatio / size.height
            }
        }
        player.addListener(listener)
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) player.pause()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            player.release()
        }
    }
    LaunchedEffect(isPlaying) {
        while (isPlaying) withFrameMillis { playheadMs = player.currentPosition }
        playheadMs = player.currentPosition
    }

    fun seekTo(ms: Long) {
        player.pause()
        player.seekTo(ms)
        playheadMs = ms
    }

    // ---- Layers + undo/redo.
    var overlays by rememberSaveable(stateSaver = overlaysSaver) { mutableStateOf(emptyList<OverlayItem>()) }
    var selectedId by rememberSaveable { mutableStateOf<Long?>(null) }
    val undoStack = remember { mutableStateListOf<List<OverlayItem>>() }
    val redoStack = remember { mutableStateListOf<List<OverlayItem>>() }
    val selected = overlays.find { it.id == selectedId }

    /** Call once before an edit (or once at the start of a drag) so it can be undone as a unit. */
    fun recordUndo() {
        undoStack.add(overlays)
        if (undoStack.size > MAX_UNDO) undoStack.removeAt(0)
        redoStack.clear()
    }

    fun edit(newOverlays: List<OverlayItem>) {
        recordUndo()
        overlays = newOverlays
    }

    fun replaceItem(updated: OverlayItem) {
        overlays = overlays.map { if (it.id == updated.id) updated else it }
    }

    fun undo() {
        val previous = undoStack.removeLastOrNull() ?: return
        redoStack.add(overlays)
        overlays = previous
        if (overlays.none { it.id == selectedId }) selectedId = null
    }

    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.add(overlays)
        overlays = next
        if (overlays.none { it.id == selectedId }) selectedId = null
    }

    fun select(id: Long?) {
        selectedId = id
        // Selecting a layer the playhead isn't over would show nothing to manipulate — go to it.
        val item = overlays.find { it.id == id } ?: return
        if (!item.isVisibleAt(playheadMs)) seekTo(item.startMs)
    }

    fun addLayer(kind: OverlayKind, text: String, color: Color, style: OverlayTextStyle) {
        val duration = durationMs.coerceAtLeast(MIN_LAYER_DURATION_MS)
        var start = playheadMs.coerceIn(0, duration)
        if (duration - start < 1000) start = (duration - DEFAULT_LAYER_DURATION_MS).coerceAtLeast(0)
        val end = (start + DEFAULT_LAYER_DURATION_MS).coerceAtMost(duration).coerceAtLeast(start + MIN_LAYER_DURATION_MS)
        val alreadyThere = overlays.count { it.isVisibleAt(start) }
        val id = (overlays.maxOfOrNull { it.id } ?: -1L) + 1
        val item = OverlayItem(
            id = id,
            kind = kind,
            text = text,
            color = color,
            style = style,
            startMs = start,
            endMs = end,
            keyframes = listOf(
                Keyframe(timeMs = start, yFraction = 0.5f + NEW_LAYER_Y_OFFSETS[alreadyThere % NEW_LAYER_Y_OFFSETS.size]),
            ),
        )
        edit(overlays + item)
        selectedId = id
        if (!item.isVisibleAt(playheadMs)) seekTo(start)
    }

    // ---- Rendering cache: one bitmap per distinct look, shared by every frame drawn.
    val bitmapCache = remember { HashMap<RenderKey, ImageBitmap>() }
    fun bitmapFor(item: OverlayItem): ImageBitmap =
        bitmapCache.getOrPut(RenderKey(item.kind, item.text, item.color, item.style)) {
            OverlayRenderer.render(item, PREVIEW_PIXEL_SCALE).asImageBitmap()
        }

    // ---- Export (watched by unique name so rotation/re-entry doesn't orphan the job).
    val workInfo by remember {
        workManager.getWorkInfosForUniqueWorkFlow(OverlayExportWorker.UNIQUE_WORK_NAME).map { it.firstOrNull() }
    }.collectAsState(initial = null)
    var exportDialogDismissed by remember { mutableStateOf(true) }
    LaunchedEffect(workInfo?.state) {
        val info = workInfo ?: return@LaunchedEffect
        if (info.state == WorkInfo.State.SUCCEEDED) {
            info.outputData.getString(OverlayExportWorker.KEY_OUTPUT_URI)?.let {
                // Prune so re-entering doesn't replay this finished export.
                workManager.pruneWork()
                onExported(it)
            }
        }
    }
    val isExporting = workInfo?.state == WorkInfo.State.RUNNING || workInfo?.state == WorkInfo.State.ENQUEUED

    // Layers live only as long as this screen is on the back stack, so leaving loses them — ask first.
    var confirmLeave by remember { mutableStateOf(false) }
    BackHandler(enabled = overlays.isNotEmpty()) { confirmLeave = true }
    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("편집을 그만할까요?") },
            text = { Text("추가한 텍스트·이모지가 모두 사라져요.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmLeave = false
                    onBack()
                }) { Text("나가기") }
            },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("계속 편집") } },
        )
    }

    var showTextDialog by remember { mutableStateOf(false) }
    var editingText by remember { mutableStateOf(false) }
    var showEmojiSheet by remember { mutableStateOf(false) }
    var guideX by remember { mutableStateOf(false) }
    var guideY by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = Color(0xFF101010),
        topBar = {
            TopAppBar(
                title = { Text("자막 · 이모지") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF101010)),
                actions = {
                    IconButton(onClick = ::undo, enabled = undoStack.isNotEmpty()) {
                        Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "되돌리기")
                    }
                    IconButton(onClick = ::redo, enabled = redoStack.isNotEmpty()) {
                        Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "다시 실행")
                    }
                    Button(
                        onClick = {
                            player.pause()
                            selectedId = null
                            exportDialogDismissed = false
                            workManager.enqueueUniqueWork(
                                OverlayExportWorker.UNIQUE_WORK_NAME,
                                ExistingWorkPolicy.KEEP,
                                OverlayExportWorker.buildRequest(mergedVideoPath, overlays),
                            )
                        },
                        enabled = durationMs > 0 && !isExporting,
                        modifier = Modifier.padding(end = 8.dp),
                    ) { Text("저장") }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {

            // ================= Preview =================
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color.Black),
            ) {
                val videoRect = OverlayGeometry.fitRect(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat(), videoAspect)
                val latestRect = rememberUpdatedState(videoRect)
                val latestOverlays = rememberUpdatedState(overlays)
                val latestSelectedId = rememberUpdatedState(selectedId)
                val latestPlayhead = rememberUpdatedState(playheadMs)
                val hitSlopPx = with(density) { 14.dp.toPx() }

                fun placementOf(item: OverlayItem, timeMs: Long, rect: ScreenRect): LayerPlacement {
                    val bmp = bitmapFor(item)
                    return OverlayGeometry.placement(rect, item.poseAt(timeMs), bmp.width, bmp.height, PREVIEW_PIXEL_SCALE)
                }

                AndroidView(
                    factory = { TextureView(it).also(player::setVideoTextureView) },
                    modifier = Modifier
                        .offset { IntOffset(videoRect.left.roundToInt(), videoRect.top.roundToInt()) }
                        .size(with(density) { videoRect.width.toDp() }, with(density) { videoRect.height.toDp() }),
                )

                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val rect = latestRect.value
                                val timeMs = latestPlayhead.value
                                val layersNow = latestOverlays.value
                                val previouslySelected = latestSelectedId.value

                                // Topmost visible layer under the finger wins.
                                val hit = layersNow.asReversed().firstOrNull {
                                    it.isVisibleAt(timeMs) &&
                                        OverlayGeometry.hits(placementOf(it, timeMs, rect), down.position.x, down.position.y, hitSlopPx)
                                }
                                if (hit != null) selectedId = hit.id

                                // One finger moves only a layer it grabbed; two fingers resize/rotate
                                // the selected layer from anywhere — no need to land both on a tiny chip.
                                val targetId = hit?.id ?: previouslySelected
                                var raw = layersNow.find { it.id == targetId }?.takeIf { it.isVisibleAt(timeMs) }?.poseAt(timeMs)
                                var pastSlop = false
                                var editing = false
                                var accumulated = Offset.Zero

                                do {
                                    val event = awaitPointerEvent()
                                    val fingers = event.changes.count { it.pressed }
                                    val pan = event.calculatePan()
                                    val zoom = event.calculateZoom()
                                    val rotation = event.calculateRotation()
                                    if (!pastSlop) {
                                        accumulated += pan
                                        if (accumulated.getDistance() > viewConfiguration.touchSlop || zoom != 1f || rotation != 0f) {
                                            pastSlop = true
                                        }
                                    }
                                    val canEdit = hit != null || fingers >= 2
                                    if (pastSlop && canEdit && raw != null) {
                                        if (!editing) {
                                            editing = true
                                            player.pause()
                                            recordUndo()
                                        }
                                        raw = raw.copy(
                                            xFraction = (raw.xFraction + pan.x / rect.width).coerceIn(0f, 1f),
                                            yFraction = (raw.yFraction + pan.y / rect.height).coerceIn(0f, 1f),
                                            scale = (raw.scale * zoom).coerceIn(MIN_SCALE, MAX_SCALE),
                                            rotation = raw.rotation + if (fingers >= 2) rotation else 0f,
                                        )
                                        val snapped = OverlayGeometry.snap(raw)
                                        guideX = snapped.snappedX
                                        guideY = snapped.snappedY
                                        overlays = overlays.map {
                                            if (it.id == targetId) it.withPose(timeMs, snapped.pose) else it
                                        }
                                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                                    }
                                } while (event.changes.any { it.pressed })

                                guideX = false
                                guideY = false
                                if (!pastSlop) {
                                    when {
                                        hit == null -> selectedId = null
                                        // Tapping the already-selected text opens it for editing.
                                        hit.id == previouslySelected && hit.kind == OverlayKind.TEXT -> {
                                            editingText = true
                                            showTextDialog = true
                                        }
                                    }
                                }
                            }
                        },
                ) {
                    val rect = videoRect
                    val strokeOuter = 3.dp.toPx()
                    val strokeInner = 1.5.dp.toPx()
                    val framePad = 6.dp.toPx()
                    clipRect(rect.left, rect.top, rect.left + rect.width, rect.top + rect.height) {
                        overlays.forEach { item ->
                            if (!item.isVisibleAt(playheadMs)) return@forEach
                            val bmp = bitmapFor(item)
                            val p = OverlayGeometry.placement(rect, item.poseAt(playheadMs), bmp.width, bmp.height, PREVIEW_PIXEL_SCALE)
                            translate(p.centerX, p.centerY) {
                                rotate(p.rotation, pivot = Offset.Zero) {
                                    drawImage(
                                        image = bmp,
                                        dstOffset = IntOffset((-p.width / 2f).roundToInt(), (-p.height / 2f).roundToInt()),
                                        dstSize = IntSize(p.width.roundToInt().coerceAtLeast(1), p.height.roundToInt().coerceAtLeast(1)),
                                        filterQuality = FilterQuality.Medium,
                                    )
                                    if (item.id == selectedId) {
                                        val topLeft = Offset(-p.width / 2f - framePad, -p.height / 2f - framePad)
                                        val frame = Size(p.width + framePad * 2, p.height + framePad * 2)
                                        // Dark under white so the frame reads on light and dark video alike.
                                        drawRect(Color.Black.copy(alpha = 0.6f), topLeft, frame, style = Stroke(strokeOuter))
                                        drawRect(Color.White, topLeft, frame, style = Stroke(strokeInner))
                                    }
                                }
                            }
                        }
                        val guideColor = Color(0xFF00E5FF)
                        if (guideX) drawLine(guideColor, Offset(rect.centerX, rect.top), Offset(rect.centerX, rect.top + rect.height), 1.dp.toPx())
                        if (guideY) drawLine(guideColor, Offset(rect.left, rect.centerY), Offset(rect.left + rect.width, rect.centerY), 1.dp.toPx())
                    }
                }

                if (overlays.isEmpty()) {
                    Text(
                        "아래에서 텍스트나 이모지를 추가하세요\n끌어서 옮기고, 두 손가락으로 크기·회전 조절",
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 16.dp)
                            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }

            // ================= Transport + keyframes =================
            Row(
                modifier = Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = {
                    if (isPlaying) {
                        player.pause()
                    } else {
                        if (player.playbackState == Player.STATE_ENDED || playheadMs >= durationMs - 50) player.seekTo(0)
                        player.play()
                    }
                }) {
                    Icon(if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = if (isPlaying) "일시정지" else "재생")
                }
                Text(
                    "${OverlayGeometry.formatTime(playheadMs)} / ${OverlayGeometry.formatTime(durationMs)}",
                    fontSize = 13.sp,
                    color = Color(0xFFDDDDDD),
                )
                Spacer(Modifier.weight(1f))
                if (selected != null) {
                    KeyframeControls(
                        item = selected,
                        playheadMs = playheadMs,
                        onSeek = ::seekTo,
                        onChange = { edit(overlays.map { o -> if (o.id == it.id) it else o }) },
                    )
                }
            }

            // ================= Timeline =================
            OverlayTimeline(
                durationMs = durationMs,
                playheadMs = playheadMs,
                overlays = overlays,
                selectedId = selectedId,
                onScrub = ::seekTo,
                onSelect = ::select,
                onEditStart = { player.pause(); recordUndo() },
                onItemChange = ::replaceItem,
                modifier = Modifier.fillMaxWidth().height(150.dp),
            )

            // ================= Tools =================
            // Same height with or without a selection: a panel that grew on select used to
            // shrink the preview, so the video jumped under the finger right as editing began.
            Box(modifier = Modifier.fillMaxWidth().height(TOOLS_HEIGHT), contentAlignment = Alignment.Center) {
            if (selected == null) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    FilledTonalButton(
                        onClick = { editingText = false; showTextDialog = true },
                        enabled = durationMs > 0,
                        modifier = Modifier.weight(1f).height(52.dp),
                    ) {
                        Icon(Icons.Filled.TextFields, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("텍스트", fontSize = 16.sp)
                    }
                    FilledTonalButton(
                        onClick = { showEmojiSheet = true },
                        enabled = durationMs > 0,
                        modifier = Modifier.weight(1f).height(52.dp),
                    ) {
                        Text("😀", fontSize = 20.sp)
                        Spacer(Modifier.width(8.dp))
                        Text("이모지", fontSize = 16.sp)
                    }
                }
            } else {
                SelectedLayerTools(
                    item = selected,
                    playheadMs = playheadMs,
                    onEditText = { editingText = true; showTextDialog = true },
                    onDuplicate = {
                        val copyId = (overlays.maxOfOrNull { it.id } ?: -1L) + 1
                        val copy = selected.copy(
                            id = copyId,
                            keyframes = selected.keyframes.map {
                                it.copy(xFraction = (it.xFraction + 0.05f).coerceAtMost(1f), yFraction = (it.yFraction + 0.05f).coerceAtMost(1f))
                            },
                        )
                        edit(overlays + copy)
                        selectedId = copyId
                    },
                    onBringToFront = { edit(overlays.filterNot { it.id == selected.id } + selected) },
                    onDelete = {
                        edit(overlays.filterNot { it.id == selected.id })
                        selectedId = null
                    },
                    onDone = { selectedId = null },
                    onScaleStart = { player.pause(); recordUndo() },
                    onScale = { scale ->
                        replaceItem(selected.withPose(playheadMs, selected.poseAt(playheadMs).copy(scale = scale)))
                    },
                )
            }
            }
        }
    }

    if (showTextDialog) {
        val editingItem = if (editingText) selected?.takeIf { it.kind == OverlayKind.TEXT } else null
        TextLayerDialog(
            initial = editingItem,
            onDismiss = { showTextDialog = false },
            onConfirm = { text, color, style ->
                if (editingItem != null) {
                    edit(overlays.map { if (it.id == editingItem.id) it.copy(text = text, color = color, style = style) else it })
                } else {
                    addLayer(OverlayKind.TEXT, text, color, style)
                }
                showTextDialog = false
            },
        )
    }

    if (showEmojiSheet) {
        EmojiPickerSheet(
            onDismiss = { showEmojiSheet = false },
            onPick = { emoji ->
                showEmojiSheet = false
                addLayer(OverlayKind.EMOJI, emoji, Color.White, OverlayTextStyle.OUTLINE)
            },
        )
    }

    val info = workInfo
    if (!exportDialogDismissed && info != null && info.state != WorkInfo.State.SUCCEEDED) {
        val failed = info.state == WorkInfo.State.FAILED
        val progress = info.progress.getInt(OverlayExportWorker.KEY_PROGRESS, 0)
        AlertDialog(
            onDismissRequest = { if (failed) exportDialogDismissed = true },
            title = { Text(if (failed) "저장 실패" else "최종 영상 만드는 중") },
            text = {
                if (failed) {
                    Text(info.outputData.getString(OverlayExportWorker.KEY_ERROR) ?: "알 수 없는 오류")
                } else {
                    Column {
                        LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        Text("$progress%")
                    }
                }
            },
            confirmButton = {
                if (failed) TextButton(onClick = { exportDialogDismissed = true; workManager.pruneWork() }) { Text("닫기") }
            },
        )
    }
}

/** Animation on/off, and when on: jump between keyframes and add/remove one at the playhead. */
@Composable
private fun KeyframeControls(
    item: OverlayItem,
    playheadMs: Long,
    onSeek: (Long) -> Unit,
    onChange: (OverlayItem) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (item.animated) {
            val sorted = item.keyframes.sortedBy { it.timeMs }
            val previous = sorted.lastOrNull { it.timeMs < playheadMs - KEYFRAME_TOLERANCE_MS }
            val next = sorted.firstOrNull { it.timeMs > playheadMs + KEYFRAME_TOLERANCE_MS }
            val atKeyframe = item.keyframeNear(playheadMs) != null
            IconButton(onClick = { previous?.let { onSeek(it.timeMs) } }, enabled = previous != null) {
                Icon(Icons.Filled.ChevronLeft, contentDescription = "이전 키프레임")
            }
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clickable {
                        onChange(
                            if (atKeyframe) {
                                item.withoutKeyframeNear(playheadMs)
                            } else {
                                item.withKeyframeAt(item.poseAt(playheadMs).copy(timeMs = playheadMs))
                            }
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(14.dp)
                        .rotate(45f)
                        .background(if (atKeyframe) PLAYHEAD_COLOR else Color.Transparent)
                        .border(2.dp, PLAYHEAD_COLOR),
                )
            }
            IconButton(onClick = { next?.let { onSeek(it.timeMs) } }, enabled = next != null) {
                Icon(Icons.Filled.ChevronRight, contentDescription = "다음 키프레임")
            }
        }
        Text("움직임", fontSize = 13.sp, color = Color(0xFFDDDDDD), modifier = Modifier.padding(start = 4.dp, end = 6.dp))
        Switch(
            checked = item.animated,
            onCheckedChange = { on -> onChange(item.withAnimation(on, playheadMs)) },
            modifier = Modifier.padding(end = 8.dp),
        )
    }
}

@Composable
private fun SelectedLayerTools(
    item: OverlayItem,
    playheadMs: Long,
    onEditText: () -> Unit,
    onDuplicate: () -> Unit,
    onBringToFront: () -> Unit,
    onDelete: () -> Unit,
    onDone: () -> Unit,
    onScaleStart: () -> Unit,
    onScale: (Float) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        Text(
            "영상 위: 끌어서 이동 · 두 손가락 크기/회전   타임라인: 길게 눌러 이동 · 양 끝 끌어 길이",
            fontSize = 10.sp,
            color = Color(0xFF9E9E9E),
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            if (item.kind == OverlayKind.TEXT) ToolButton(Icons.Filled.Edit, "수정", onEditText)
            ToolButton(Icons.Filled.ContentCopy, "복제", onDuplicate)
            ToolButton(Icons.Filled.FlipToFront, "맨 앞", onBringToFront)
            ToolButton(Icons.Filled.Delete, "삭제", onDelete)
            ToolButton(Icons.Filled.Check, "완료", onDone)
        }
        // Log scale so small sizes get as much slider travel as large ones.
        var sliding by remember { mutableStateOf(false) }
        val scale = item.poseAt(playheadMs).scale
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp)) {
            Text("크기", fontSize = 13.sp, color = Color(0xFFDDDDDD))
            Slider(
                value = ln(scale),
                onValueChange = {
                    if (!sliding) {
                        sliding = true
                        onScaleStart()
                    }
                    val target = exp(it)
                    // Detent at 100% so returning to the original size is easy.
                    onScale(if (abs(target - 1f) < 0.04f) 1f else target)
                },
                onValueChangeFinished = { sliding = false },
                valueRange = ln(MIN_SCALE)..ln(MAX_SCALE),
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            )
            Text("${(scale * 100).roundToInt()}%", fontSize = 13.sp, color = Color(0xFFDDDDDD), modifier = Modifier.width(44.dp))
        }
    }
}

@Composable
private fun ToolButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Icon(icon, contentDescription = label)
        Text(label, fontSize = 11.sp)
    }
}
