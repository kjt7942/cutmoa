package com.kjt.shortsapp.camera

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import android.util.Log
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.MeteringPoint
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.text.SimpleDateFormat
import java.util.Locale

private const val TAG = "VideoCaptureManager"

/**
 * Owns the CameraX use-case graph (Preview + VideoCapture) for one screen.
 * One instance per composition; call [release] when the screen leaves composition.
 */
class VideoCaptureManager(private val context: Context) {

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null

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
        cameraSelector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA,
    ) {
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

                provider.unbindAll()
                camera = provider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, capture)
            },
            ContextCompat.getMainExecutor(context)
        )
    }

    /**
     * Tap-to-focus: one-shot AF + AE on [point]. CameraX auto-cancels after ~5s and falls
     * back to continuous video AF, and it doesn't interrupt an active recording.
     */
    fun focusAt(point: MeteringPoint) {
        camera?.cameraControl?.startFocusAndMetering(FocusMeteringAction.Builder(point).build())
    }

    /**
     * Starts recording to MediaStore (Movies/ShortsApp) so the clip shows up in the
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

        val name = "SHORTS_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())}.mp4"
        val contentValues = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ShortsApp")
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
        activeRecording?.stop()
        activeRecording = null
        cameraProvider?.unbindAll()
        cameraProvider = null
        camera = null
        videoCapture = null
    }
}
