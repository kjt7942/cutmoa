package com.kjt.cutmoa.util

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri

object VideoFrameUtil {
    /** Decodes the first frame of a content:// clip (e.g. from the gallery picker) for list thumbnails. */
    fun frameAt(context: Context, uri: Uri): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.getFrameAtTime(0)
        } catch (t: Throwable) {
            null
        } finally {
            retriever.release()
        }
    }

    /** Decodes the frame nearest [timeMs] for the overlay editor's scrubbed preview. */
    fun frameAt(path: String, timeMs: Long): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            retriever.getFrameAtTime(timeMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } catch (t: Throwable) {
            null
        } finally {
            retriever.release()
        }
    }

    /** Total duration of the video at [path], in milliseconds; 0 if it can't be read. */
    fun durationMs(path: String): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (t: Throwable) {
            0L
        } finally {
            retriever.release()
        }
    }
}
