package com.kjt.cutmoa.merge

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
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
 * Concatenates clips into one mp4. Every clip is forced through the same
 * [Presentation] (resolution + fit mode) before concat so mismatched source
 * specs — 1080p portrait next to 720p landscape, different frame rates —
 * don't corrupt or stall playback of the merged file.
 */
object VideoMerger {

    suspend fun merge(
        context: Context,
        inputUris: List<Uri>,
        outputFile: File,
        targetWidth: Int = 1080,
        targetHeight: Int = 1920,
        onProgress: (percent: Int) -> Unit,
    ): File = withContext(Dispatchers.Main) {
        require(inputUris.isNotEmpty()) { "merge() needs at least one clip" }

        val presentation = Presentation.createForWidthAndHeight(
            targetWidth,
            targetHeight,
            Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP,
        )

        val editedItems = inputUris.map { uri ->
            EditedMediaItem.Builder(MediaItem.fromUri(uri))
                .setEffects(Effects(emptyList(), listOf(presentation)))
                .build()
        }

        val sequence = EditedMediaItemSequence(editedItems)
        // Gallery picks can have no audio track; forcing one fills those clips with silence
        // so the concat stays in sync instead of failing or drifting.
        val composition = Composition.Builder(sequence).experimentalSetForceAudioTrack(true).build()

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

            transformer.start(composition, outputFile.absolutePath)

            // Transformer only exposes a pollable getter, no progress callback.
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
}
