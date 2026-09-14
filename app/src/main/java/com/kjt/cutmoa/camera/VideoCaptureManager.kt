package com.kjt.cutmoa.camera

import android.content.ContentValues
import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.util.Rational
import android.util.Size
import android.view.Surface
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.hypot

private const val TAG = "VideoCaptureManager"
private const val TRACK_FRAME_WIDTH = 320
private const val REFOCUS_MOVE_FRACTION = 0.08f
private const val REFOCUS_MIN_INTERVAL_MS = 700L

/**
 * Owns the CameraX use-case graph (Preview + VideoCapture + ImageAnalysis) for one screen.
 * One instance per composition; call [release] when the screen leaves composition.
 */
class VideoCaptureManager(private val context: Context) {

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var previewView: PreviewView? = null

    // Tracking AF. The analyzer thread owns tracker + frame buffer; everything else is main thread.
    // Generation bumps on every start/stop so stale analyzer results are dropped.
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val mainExecutor = ContextCompat.getMainExecutor(context)
    private val tracker = SubjectTracker()
    private var trackFrame: LumaFrame? = null
    private var trackerGeneration = 0
    private val pendingStart = AtomicReference<TrackStart?>(null)
    @Volatile private var bufferMapping: BufferMapping? = null
    @Volatile private var trackingGeneration = 0
    private var lastFocusX = 0f
    private var lastFocusY = 0f
    private var lastFocusAtMs = 0L

    /** Tracked subject in PreviewView pixels; null when nothing is locked. */
    val trackedBox = mutableStateOf<RectF?>(null)

    private class TrackStart(val x: Float, val y: Float, val generation: Int)

    private val recorder: Recorder by lazy {
        // SD quality falls back automatically on devices that can't do HD encode.
        val qualitySelector = QualitySelector.fromOrderedList(
            listOf(Quality.FHD, Quality.HD, Quality.SD),
            FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
        )
        Recorder.Builder()
            .setQualitySelector(qualitySelector)
            .build()
    }

    fun bindToLifecycle(
        lifecycleOwner: LifecycleOwner,
        preview: Preview,
        previewView: PreviewView,
        cameraSelector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA,
    ) {
        this.previewView = previewView
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener(
            {
                val provider = providerFuture.get()
                cameraProvider = provider

                // Electronic video stabilization — CameraX no-ops this quietly on
                // devices/cameras that don't support it, so it's safe to always request.
                val capture = VideoCapture.Builder(recorder)
                    .setVideoStabilizationEnabled(true)
                    .build()
                videoCapture = capture

                // Small 4:3 stream: plenty for template tracking, cheap to scan every frame.
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                            .setResolutionStrategy(
                                ResolutionStrategy(
                                    Size(640, 480),
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                                )
                            )
                            .build()
                    )
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { it.setAnalyzer(analysisExecutor, ::analyzeFrame) }

                // Shared 9:16 viewport (the recorded video's shape) gives every stream the same
                // crop, so analysis crop rect and PreviewView's normalized space describe the same
                // field of view. Without it the 4:3 analysis frame and preview crop disagree.
                // Surface.ROTATION_0 because the activity is locked to portrait.
                val viewPort = ViewPort.Builder(Rational(9, 16), Surface.ROTATION_0).build()
                fun group(vararg useCases: UseCase) = UseCaseGroup.Builder()
                    .setViewPort(viewPort)
                    .apply { useCases.forEach(::addUseCase) }
                    .build()

                provider.unbindAll()
                camera = try {
                    provider.bindToLifecycle(lifecycleOwner, cameraSelector, group(preview, capture, analysis))
                } catch (e: IllegalArgumentException) {
                    // Some devices can't run a third stream next to stabilized preview + video.
                    // Taps then fall back to one-shot focus.
                    Log.w(TAG, "ImageAnalysis unsupported alongside preview+video, tracking AF off", e)
                    provider.unbindAll()
                    provider.bindToLifecycle(lifecycleOwner, cameraSelector, group(preview, capture))
                }
            },
            mainExecutor
        )
    }

    /**
     * Tap on the preview: locks focus onto the tapped subject and keeps following it.
     * Tapping the tracked subject again keeps the lock; tapping anywhere else releases it.
     */
    fun onPreviewTap(x: Float, y: Float) {
        trackedBox.value?.let { box ->
            if (!box.contains(x, y)) stopTracking()
            return
        }
        val view = previewView ?: return
        val viewMatrix = view.outputTransform?.matrix
        val mapping = bufferMapping
        if (viewMatrix == null || mapping == null) {
            focusOn(x, y, lock = false)
            return
        }
        // View pixels -> upright normalized space -> analysis buffer pixels.
        val point = floatArrayOf(x, y)
        Matrix().also { viewMatrix.invert(it) }.mapPoints(point)
        val (bufferX, bufferY) = mapping.toBuffer(point[0], point[1])
        focusOn(x, y, lock = true)
        trackingGeneration++
        pendingStart.set(TrackStart(bufferX, bufferY, trackingGeneration))
    }

    /**
     * Triggers AF + AE + AWB at a PreviewView point. Doesn't interrupt an active recording.
     * [lock] keeps the result until [stopTracking]; otherwise CameraX returns to continuous AF
     * after ~5s.
     */
    private fun focusOn(x: Float, y: Float, lock: Boolean) {
        val view = previewView ?: return
        val cam = camera ?: return
        lastFocusX = x
        lastFocusY = y
        lastFocusAtMs = SystemClock.uptimeMillis()
        val action = FocusMeteringAction.Builder(view.meteringPointFactory.createPoint(x, y))
            .apply { if (lock) disableAutoCancel() }
            .build()
        val result = cam.cameraControl.startFocusAndMetering(action)
        result.addListener({
            try {
                Log.d(TAG, "focus at ($x, $y) success=${result.get().isFocusSuccessful}")
            } catch (e: Exception) {
                // A newer focus request (subject moved) cancels this one — expected while tracking.
                Log.d(TAG, "focus at ($x, $y) superseded")
            }
        }, mainExecutor)
    }

    private fun stopTracking() {
        trackingGeneration++
        pendingStart.set(null)
        trackedBox.value = null
        camera?.cameraControl?.cancelFocusAndMetering()
    }

    private fun analyzeFrame(image: ImageProxy) {
        image.use {
            if (bufferMapping == null) {
                val crop = image.cropRect
                bufferMapping = BufferMapping(
                    image.imageInfo.rotationDegrees, crop.left, crop.top, crop.width(), crop.height()
                )
                Log.d(TAG, "analysis ${image.width}x${image.height} rot=${image.imageInfo.rotationDegrees} crop=$crop")
            }
            // Take the request before reading the generation: main bumps generation first,
            // so a request seen here is never newer than the generation read after it.
            val start = pendingStart.getAndSet(null)
            val generation = trackingGeneration
            if (trackerGeneration != generation) {
                tracker.stop()
                trackerGeneration = generation
            }
            if (start?.generation != generation) {
                if (!tracker.isTracking) return
            }

            val step = maxOf(1, image.width / TRACK_FRAME_WIDTH)
            val frame = readLuma(image, step)
            val alive = if (start?.generation == generation) {
                tracker.start(frame, (start.x / step).toInt(), (start.y / step).toInt())
                true
            } else {
                tracker.update(frame)
            }

            val box = if (alive) {
                RectF(
                    (tracker.left * step).toFloat(),
                    (tracker.top * step).toFloat(),
                    ((tracker.left + tracker.templateSize) * step).toFloat(),
                    ((tracker.top + tracker.templateSize) * step).toFloat(),
                )
            } else {
                null
            }
            mainExecutor.execute { onTrackResult(generation, box) }
        }
    }

    private fun readLuma(image: ImageProxy, step: Int): LumaFrame {
        val width = image.width / step
        val height = image.height / step
        val frame = trackFrame?.takeIf { it.width == width && it.height == height }
            ?: LumaFrame(width, height).also { trackFrame = it }
        val plane = image.planes[0]
        val buffer = plane.buffer
        for (y in 0 until height) {
            val row = y * step * plane.rowStride
            for (x in 0 until width) {
                frame.pixels[y * width + x] = buffer.get(row + x * step * plane.pixelStride).toInt() and 0xFF
            }
        }
        return frame
    }

    private fun onTrackResult(generation: Int, box: RectF?) {
        if (generation != trackingGeneration) return
        if (box == null) {
            stopTracking()
            return
        }
        val view = previewView ?: return
        val viewMatrix = view.outputTransform?.matrix ?: return
        val mapping = bufferMapping ?: return
        val (x0, y0) = mapping.toNormalized(box.left, box.top)
        val (x1, y1) = mapping.toNormalized(box.right, box.bottom)
        val corners = floatArrayOf(x0, y0, x1, y1).also { viewMatrix.mapPoints(it) }
        // Rotation swaps/flips corners, so rebuild the rect from min/max.
        val viewBox = RectF(
            minOf(corners[0], corners[2]), minOf(corners[1], corners[3]),
            maxOf(corners[0], corners[2]), maxOf(corners[1], corners[3]),
        )
        trackedBox.value = viewBox

        // Re-focus only once the subject has moved a meaningful distance: every AF trigger is
        // a short lens scan, so doing it per frame would make the recording pulse.
        // ponytail: a subject moving only toward/away from the camera isn't re-focused;
        // add a periodic re-trigger if that case matters.
        // The interval lets each scan finish; re-triggering sooner cancels it mid-way and focus
        // never settles while the subject keeps moving.
        val moved = hypot(viewBox.centerX() - lastFocusX, viewBox.centerY() - lastFocusY)
        val sinceLastFocus = SystemClock.uptimeMillis() - lastFocusAtMs
        if (moved > view.width * REFOCUS_MOVE_FRACTION && sinceLastFocus >= REFOCUS_MIN_INTERVAL_MS) {
            focusOn(viewBox.centerX(), viewBox.centerY(), lock = true)
        }
    }

    /**
     * Starts recording to MediaStore (Movies/CutMoa) so the clip shows up in the
     * gallery immediately — no manual scan needed.
     *
     * @param onEvent fired on the main thread for start/finalize/status events.
     */
    fun startRecording(onEvent: (VideoRecordEvent) -> Unit) {
        val capture = videoCapture ?: run {
            Log.w(TAG, "startRecording called before camera bound")
            return
        }
        if (activeRecording != null) {
            Log.w(TAG, "startRecording called while a recording is already active")
            return
        }

        val name = "CUTMOA_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())}.mp4"
        val contentValues = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/CutMoa")
        }
        val outputOptions = MediaStoreOutputOptions.Builder(
            context.contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        )
            .setContentValues(contentValues)
            .build()

        val pending = capture.output.prepareRecording(context, outputOptions)
            .apply {
                if (ContextCompat.checkSelfPermission(
                        context,
                        android.Manifest.permission.RECORD_AUDIO
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    withAudioEnabled()
                }
            }

        activeRecording = pending.start(ContextCompat.getMainExecutor(context)) { event ->
            if (event is VideoRecordEvent.Finalize) {
                activeRecording = null
                if (event.hasError()) {
                    Log.e(TAG, "Recording error: ${event.error}", event.cause)
                } else {
                    Log.i(TAG, "Recording saved: ${event.outputResults.outputUri}")
                }
            }
            onEvent(event)
        }
    }

    fun stopRecording() {
        activeRecording?.stop()
        activeRecording = null
    }

    fun isRecording(): Boolean = activeRecording != null

    fun release() {
        stopTracking()
        activeRecording?.stop()
        activeRecording = null
        cameraProvider?.unbindAll()
        cameraProvider = null
        camera = null
        videoCapture = null
        previewView = null
        analysisExecutor.shutdown()
    }
}

/**
 * Maps between analysis buffer pixels and the upright [-1, 1] space that PreviewView's
 * OutputTransform matrix maps into view pixels. Done by hand because CameraX's
 * ImageProxyTransformFactory produced a matrix with swapped axes for a rotated, cropped
 * analysis frame (640x480 buffer mapped as if it were 360x640), skewing the box.
 *
 * [rotationDegrees] is how far the buffer must turn clockwise to be upright.
 */
internal class BufferMapping(
    private val rotationDegrees: Int,
    cropLeft: Int,
    cropTop: Int,
    cropWidth: Int,
    cropHeight: Int,
) {
    private val cx = cropLeft + cropWidth / 2f
    private val cy = cropTop + cropHeight / 2f
    private val halfW = cropWidth / 2f
    private val halfH = cropHeight / 2f

    fun toBuffer(nx: Float, ny: Float): Pair<Float, Float> = when (rotationDegrees) {
        90 -> cx + ny * halfW to cy - nx * halfH
        180 -> cx - nx * halfW to cy - ny * halfH
        270 -> cx - ny * halfW to cy + nx * halfH
        else -> cx + nx * halfW to cy + ny * halfH
    }

    fun toNormalized(bx: Float, by: Float): Pair<Float, Float> = when (rotationDegrees) {
        90 -> -(by - cy) / halfH to (bx - cx) / halfW
        180 -> -(bx - cx) / halfW to -(by - cy) / halfH
        270 -> (by - cy) / halfH to -(bx - cx) / halfW
        else -> (bx - cx) / halfW to (by - cy) / halfH
    }
}
