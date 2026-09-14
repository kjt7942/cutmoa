package com.kjt.cutmoa.overlay

import android.content.Context
import android.net.Uri
import android.text.SpannableString
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import androidx.compose.ui.graphics.toArgb
import androidx.media3.common.MediaItem
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.TextOverlay
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.google.common.collect.ImmutableList
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

    private const val BASE_TEXT_PX = 64f
    private const val BASE_EMOJI_PX = 140f

    suspend fun export(
        context: Context,
        sourceFile: File,
        overlays: List<OverlayItem>,
        outputFile: File,
        onProgress: (percent: Int) -> Unit,
    ): File = withContext(Dispatchers.Main) {
        val textureOverlays: List<TextureOverlay> = overlays.map { it.toTextureOverlay() }

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

    private fun OverlayItem.toTextureOverlay(): TextureOverlay {
        val basePx = if (kind == OverlayKind.EMOJI) BASE_EMOJI_PX else BASE_TEXT_PX
        val spannable = SpannableString(text).apply {
            setSpan(AbsoluteSizeSpan(basePx.toInt()), 0, text.length, 0)
            if (kind == OverlayKind.TEXT) {
                setSpan(ForegroundColorSpan(color.toArgb()), 0, text.length, 0)
            }
        }
        return KeyframedTextOverlay(this, spannable)
    }

    /**
     * Media3 calls [getText]/[getOverlaySettings] once per output frame with the frame's
     * presentation time, which is exactly the hook keyframed motion needs — no baking
     * required, [OverlayItem.poseAt] is evaluated live during export.
     */
    private class KeyframedTextOverlay(
        private val item: OverlayItem,
        private val spannable: SpannableString,
    ) : TextOverlay() {

        override fun getText(presentationTimeUs: Long): SpannableString = spannable

        override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
            val timeMs = presentationTimeUs / 1000
            val pose = item.poseAt(timeMs)

            // Media3's overlay frame is NDC: (-1,-1) bottom-left, (1,1) top-right, y-up.
            // The editor tracks fractional top-left coordinates (0..1, y-down) — flip Y.
            val ndcX = pose.xFraction * 2f - 1f
            val ndcY = 1f - pose.yFraction * 2f
            val alpha = if (item.isVisibleAt(timeMs)) 1f else 0f

            return OverlaySettings.Builder()
                .setBackgroundFrameAnchor(ndcX, ndcY)
                .setScale(pose.scale, pose.scale)
                .setAlphaScale(alpha)
                .build()
        }
    }
}
