package com.kjt.cutmoa.overlay

import android.content.Context
import android.net.Uri
import android.graphics.Bitmap
import androidx.media3.common.MediaItem
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.google.common.collect.ImmutableList
import com.kjt.cutmoa.util.VideoFrameUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Bakes text/emoji overlays into the merged clip as one Media3 Transformer pass,
 * producing the final, fully-composited mp4.
 */
object OverlayExporter {

    suspend fun export(
        context: Context,
        sourceFile: File,
        overlays: List<OverlayItem>,
        outputFile: File,
        onProgress: (percent: Int) -> Unit,
    ): File = withContext(Dispatchers.Main) {
        // Frame width after rotation is applied — the space Media3's overlay pixels live in.
        val frameWidth = VideoFrameUtil.displayWidth(sourceFile.absolutePath).takeIf { it > 0 } ?: 1080
        val textureOverlays: List<TextureOverlay> = overlays.map { KeyframedBitmapOverlay(it, frameWidth) }

        val editedItem = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(sourceFile)))
            .setEffects(
                Effects(
                    emptyList(),
                    if (textureOverlays.isEmpty()) {
                        emptyList()
                    } else {
                        listOf(OverlayEffect(ImmutableList.copyOf(textureOverlays)))
                    }
                )
            )
            .build()

        if (outputFile.exists()) outputFile.delete()
        outputFile.parentFile?.mkdirs()

        suspendCancellableCoroutine { continuation ->
            var progressJob: Job? = null

            val transformer = Transformer.Builder(context)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        progressJob?.cancel()
                        if (continuation.isActive) continuation.resume(outputFile)
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException,
                    ) {
                        progressJob?.cancel()
                        if (continuation.isActive) continuation.resumeWithException(exportException)
                    }
                })
                .build()

            continuation.invokeOnCancellation {
                progressJob?.cancel()
                transformer.cancel()
            }

            transformer.start(editedItem, outputFile.absolutePath)

            progressJob = CoroutineScope(Dispatchers.Main).launch {
                val holder = ProgressHolder()
                while (isActive) {
                    val state = transformer.getProgress(holder)
                    if (state == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress)
                    if (state == Transformer.PROGRESS_STATE_NOT_STARTED) break
                    delay(300)
                }
            }
        }
    }

    /**
     * Media3 draws an overlay bitmap at its own pixel size relative to the output frame and
     * calls [getOverlaySettings] once per frame with that frame's timestamp — so keyframed
     * motion is just [OverlayItem.poseAt] evaluated live.
     *
     * The bitmap is rendered once, at the largest scale any keyframe reaches (so scaling up
     * never stretches a small texture), and each frame scales it back down to the pose.
     */
    private class KeyframedBitmapOverlay(private val item: OverlayItem, frameWidth: Int) : BitmapOverlay() {

        private val maxScale = item.keyframes.maxOf { it.scale }.coerceIn(1f, MAX_SCALE)
        private val bitmap: Bitmap = OverlayRenderer.render(item, frameWidth / OverlayRenderer.REFERENCE_WIDTH * maxScale)

        override fun getBitmap(presentationTimeUs: Long): Bitmap = bitmap

        override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
            val timeMs = presentationTimeUs / 1000
            val pose = item.poseAt(timeMs)

            // Media3's overlay frame is NDC: (-1,-1) bottom-left, (1,1) top-right, y-up.
            // The editor tracks the layer center as fractions (0..1, y-down) — flip Y.
            val ndcX = pose.xFraction * 2f - 1f
            val ndcY = 1f - pose.yFraction * 2f
            val alpha = if (item.isVisibleAt(timeMs)) 1f else 0f
            val scale = pose.scale / maxScale

            return OverlaySettings.Builder()
                .setBackgroundFrameAnchor(ndcX, ndcY)
                .setScale(scale, scale)
                // Editor rotation is clockwise on a y-down screen; Media3's is counter-clockwise.
                .setRotationDegrees(-pose.rotation)
                .setAlphaScale(alpha)
                .build()
        }
    }
}
