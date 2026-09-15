package com.kjt.cutmoa.camera

import android.Manifest
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Gravity
import android.view.MotionEvent
import android.widget.Toast
import androidx.camera.core.Preview
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.kjt.cutmoa.util.LightSystemBarIcons
import kotlin.math.roundToInt
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import kotlinx.coroutines.delay

private val REQUIRED_PERMISSIONS = listOf(
    Manifest.permission.CAMERA,
    Manifest.permission.RECORD_AUDIO,
)
private val FIXED_DURATION_OPTIONS = listOf(2, 3, 4)
private const val MAX_CUSTOM_DURATIONS = 2
private const val MIN_CUSTOM_SECONDS = 1
private const val MAX_CUSTOM_SECONDS = 30

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun CameraScreen(onNavigateToMerge: () -> Unit) {
    val permissionState = rememberMultiplePermissionsState(REQUIRED_PERMISSIONS)

    if (permissionState.allPermissionsGranted) {
        RecordingScreen(onNavigateToMerge = onNavigateToMerge)
    } else {
        PermissionRequestScreen(
            onRequestPermissions = { permissionState.launchMultiplePermissionRequest() }
        )
    }
}

@Composable
private fun PermissionRequestScreen(onRequestPermissions: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Button(onClick = onRequestPermissions) {
            Text("카메라 · 마이크 권한 허용")
        }
    }
    DisposableEffect(Unit) {
        onRequestPermissions()
        onDispose { }
    }
}

@Composable
private fun RecordingScreen(onNavigateToMerge: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val captureManager = remember { VideoCaptureManager(context) }
    var isRecording by remember { mutableStateOf(false) }
    var selectedDuration by remember { mutableIntStateOf(3) }
    var remainingSeconds by remember { mutableIntStateOf(selectedDuration) }
    var recordingProgress by remember { mutableFloatStateOf(0f) }
    var customDurations by remember { mutableStateOf(DurationPrefs.load(context)) }

    // Camera preview fills the whole screen like a native camera app, including behind the nav bar.
    LightSystemBarIcons()

    DisposableEffect(Unit) {
        onDispose { captureManager.release() }
    }

    // Countdown owns the auto-stop: it starts fresh whenever isRecording flips to true,
    // and is cancelled automatically (LaunchedEffect key change) on manual stop. Ticks
    // every 50ms off the wall clock (not a per-second counter) so the progress bar fills
    // smoothly instead of jumping in whole-second steps.
    LaunchedEffect(isRecording, selectedDuration) {
        if (!isRecording) {
            recordingProgress = 0f
            return@LaunchedEffect
        }
        val totalMs = selectedDuration * 1_000L
        val startElapsedMs = System.currentTimeMillis()
        remainingSeconds = selectedDuration
        while (true) {
            val elapsedMs = System.currentTimeMillis() - startElapsedMs
            if (elapsedMs >= totalMs) break
            recordingProgress = elapsedMs.toFloat() / totalMs
            remainingSeconds = selectedDuration - (elapsedMs / 1_000).toInt()
            delay(50)
        }
        recordingProgress = 1f
        remainingSeconds = 0
        captureManager.stopRecording()
        isRecording = false
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                previewView.setOnTouchListener { v, event ->
                    if (event.action == MotionEvent.ACTION_UP) {
                        captureManager.onPreviewTap(event.x, event.y)
                        v.performClick()
                    }
                    true
                }
                val preview = Preview.Builder()
                    .setPreviewStabilizationEnabled(true)
                    .build()
                    .also { it.surfaceProvider = previewView.surfaceProvider }
                captureManager.bindToLifecycle(lifecycleOwner, preview, previewView)
                previewView
            }
        )

        LevelGuide(modifier = Modifier.fillMaxSize())

        // Tracking AF box — drawn in PreviewView pixels, which match this Box's coordinates.
        // Samsung Camera style: thin outline with bold corner brackets.
        Canvas(modifier = Modifier.fillMaxSize()) {
            val box = captureManager.trackedBox.value ?: return@Canvas
            val color = Color(0xFFFFC928)
            val radius = 6.dp.toPx()
            drawRoundRect(
                color = color,
                topLeft = Offset(box.left, box.top),
                size = Size(box.width(), box.height()),
                cornerRadius = CornerRadius(radius),
                style = Stroke(1.dp.toPx()),
            )
            val arm = minOf(box.width(), box.height()) * 0.18f
            val bold = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
            for ((cx, sx) in listOf(box.left to 1f, box.right to -1f)) {
                for ((cy, sy) in listOf(box.top to 1f, box.bottom to -1f)) {
                    val corner = Path().apply {
                        moveTo(cx, cy + sy * arm)
                        lineTo(cx, cy + sy * radius)
                        quadraticTo(cx, cy, cx + sx * radius, cy)
                        lineTo(cx + sx * arm, cy)
                    }
                    drawPath(corner, color, style = bold)
                }
            }
        }

        TextButton(
            onClick = onNavigateToMerge,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(16.dp)
                .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(50)),
        ) {
            Text("병합하러 가기 →", color = Color.White)
        }

        if (isRecording) {
            BoxWithConstraints(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White.copy(alpha = 0.3f)),
            ) {
                // Gradient (not a flat color) so the bar stays readable no matter what
                // color the live camera feed behind the status bar happens to be.
                val fullWidthPx = with(LocalDensity.current) { maxWidth.toPx() }
                Box(
                    modifier = Modifier
                        .fillMaxWidth(recordingProgress.coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(Color(0xFFFF1744), Color(0xFFFF9100)),
                                startX = 0f,
                                endX = fullWidthPx,
                            )
                        ),
                )
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (isRecording) {
                Text(
                    text = "${remainingSeconds}s",
                    color = Color.White,
                    style = androidx.compose.material3.MaterialTheme.typography.headlineMedium,
                )
            } else {
                DurationSelector(
                    fixedOptions = FIXED_DURATION_OPTIONS,
                    // FIFO order governs which custom slot gets evicted next, but shown
                    // sorted so "4초, 21초, 10초" doesn't look broken next to the fixed chips.
                    customOptions = customDurations.sorted(),
                    selected = selectedDuration,
                    onSelect = { selectedDuration = it },
                    onAddCustom = { seconds ->
                        val updated = (customDurations + seconds).takeLast(MAX_CUSTOM_DURATIONS)
                        customDurations = updated
                        DurationPrefs.save(context, updated)
                        selectedDuration = seconds
                    },
                )
            }

            RecordButton(
                isRecording = isRecording,
                onClick = {
                    if (isRecording) {
                        captureManager.stopRecording()
                        isRecording = false
                    } else {
                        captureManager.startRecording { event ->
                            handleRecordEvent(context, event, onFinalized = { isRecording = false })
                        }
                        isRecording = true
                    }
                }
            )
        }
    }
}

@Composable
private fun DurationSelector(
    fixedOptions: List<Int>,
    customOptions: List<Int>,
    selected: Int,
    onSelect: (Int) -> Unit,
    onAddCustom: (Int) -> Unit,
) {
    var showAddDialog by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.Center,
    ) {
        (fixedOptions + customOptions).sorted().forEach { seconds ->
            DurationChip(seconds = seconds, selected = seconds == selected, onClick = { onSelect(seconds) })
        }
        FilterChip(
            modifier = Modifier.padding(horizontal = 4.dp),
            selected = false,
            onClick = { showAddDialog = true },
            label = { Icon(Icons.Filled.Add, contentDescription = "직접 시간 추가") },
            colors = FilterChipDefaults.filterChipColors(
                containerColor = Color.DarkGray,
                labelColor = Color.White,
                iconColor = Color.White,
            ),
        )
    }

    if (showAddDialog) {
        AddCustomDurationDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { seconds ->
                onAddCustom(seconds)
                showAddDialog = false
            },
        )
    }
}

@Composable
private fun DurationChip(seconds: Int, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        modifier = Modifier.padding(horizontal = 4.dp),
        selected = selected,
        onClick = onClick,
        label = { Text("${seconds}초") },
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Color.DarkGray,
            labelColor = Color.White,
            selectedContainerColor = Color.White,
            selectedLabelColor = Color.Black,
        ),
    )
}

@Composable
private fun AddCustomDurationDialog(onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    var value by remember { mutableIntStateOf(5) }
    var text by remember { mutableStateOf("5") }

    fun applyValue(newValue: Int) {
        value = newValue.coerceIn(MIN_CUSTOM_SECONDS, MAX_CUSTOM_SECONDS)
        text = value.toString()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("직접 시간 설정") },
        text = {
            Column {
                Text("${value}초", style = MaterialTheme.typography.headlineMedium)
                Slider(
                    value = value.toFloat(),
                    onValueChange = { applyValue(it.roundToInt()) },
                    valueRange = MIN_CUSTOM_SECONDS.toFloat()..MAX_CUSTOM_SECONDS.toFloat(),
                    steps = MAX_CUSTOM_SECONDS - MIN_CUSTOM_SECONDS - 1,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { input ->
                        val digits = input.filter { it.isDigit() }
                        text = digits
                        digits.toIntOrNull()?.let { value = it.coerceIn(MIN_CUSTOM_SECONDS, MAX_CUSTOM_SECONDS) }
                    },
                    label = { Text("초 (${MIN_CUSTOM_SECONDS}~${MAX_CUSTOM_SECONDS})") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value.coerceIn(MIN_CUSTOM_SECONDS, MAX_CUSTOM_SECONDS)) }) {
                Text("추가")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

@Composable
private fun RecordButton(
    isRecording: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        modifier = modifier.size(72.dp),
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isRecording) Color.Red else Color.White
        )
    ) {
        Icon(
            imageVector = if (isRecording) Icons.Filled.Stop else Icons.Filled.FiberManualRecord,
            contentDescription = if (isRecording) "녹화 중지" else "녹화 시작",
            tint = if (isRecording) Color.White else Color.Red,
        )
    }
}

private fun handleRecordEvent(
    context: android.content.Context,
    event: VideoRecordEvent,
    onFinalized: () -> Unit,
) {
    if (event is VideoRecordEvent.Finalize) {
        onFinalized()
        val message = if (event.hasError()) {
            "저장 실패 (code=${event.error})"
        } else {
            vibrateLight(context)
            "저장 완료: ${event.outputResults.outputUri}"
        }
        Toast.makeText(context, message, Toast.LENGTH_SHORT).apply {
            // Default bottom-center placement sits right on top of the duration chips —
            // push it up above the record button and chip row.
            setGravity(Gravity.BOTTOM, 0, (300 * context.resources.displayMetrics.density).toInt())
        }.show()
    }
}

/** Short, low-amplitude tick marking the end of a recording — deliberately subtle. */
private fun vibrateLight(context: Context) {
    val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Vibrator::class.java)
    }
    vibrator?.vibrate(VibrationEffect.createOneShot(40, 60))
}
