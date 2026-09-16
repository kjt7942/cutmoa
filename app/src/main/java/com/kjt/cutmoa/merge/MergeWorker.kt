package com.kjt.cutmoa.merge

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.kjt.cutmoa.util.ExportNotifications
import java.io.File

/**
 * Runs the clip concat off the main thread via WorkManager, so it survives the
 * app going to background and reports progress back to whoever is observing
 * the WorkInfo (see [ExportNotifications] for the matching foreground notification).
 */
class MergeWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val uriStrings = inputData.getStringArray(KEY_CLIP_URIS)
        if (uriStrings.isNullOrEmpty()) return Result.failure(workDataOf(KEY_ERROR to "no clips"))
        val uris = uriStrings.map { it.toUri() }

        setForeground(ExportNotifications.foregroundInfo(applicationContext, NOTIF_ID, "영상 병합 중", 0))

        val outputFile = File(
            applicationContext.getExternalFilesDir(null),
            "merged_${System.currentTimeMillis()}.mp4",
        )

        return try {
            VideoMerger.merge(applicationContext, uris, outputFile) { percent ->
                setForegroundAsync(
                    ExportNotifications.foregroundInfo(applicationContext, NOTIF_ID, "영상 병합 중", percent)
                )
                setProgressAsync(workDataOf(KEY_PROGRESS to percent))
            }
            Result.success(workDataOf(KEY_OUTPUT_PATH to outputFile.absolutePath))
        } catch (t: Throwable) {
            Result.failure(workDataOf(KEY_ERROR to (t.message ?: "merge failed")))
        }
    }

    companion object {
        const val KEY_CLIP_URIS = "clip_uris"
        const val KEY_PROGRESS = "progress"
        const val KEY_OUTPUT_PATH = "output_path"
        const val KEY_ERROR = "error"
        private const val NOTIF_ID = 1001

        /**
         * Observed by name, not by request id: an id kept in `remember` is lost on rotation
         * or on leaving and returning to the merge screen, orphaning the running job (it keeps
         * merging in the background with no one watching, and the button reappears inviting a
         * second, overlapping run). A stable unique-work name survives all of that for free.
         */
        const val UNIQUE_WORK_NAME = "merge_work"

        fun buildRequest(clipUris: List<Uri>): OneTimeWorkRequest =
            OneTimeWorkRequest.Builder(MergeWorker::class.java)
                .setInputData(
                    workDataOf(KEY_CLIP_URIS to clipUris.map { it.toString() }.toTypedArray())
                )
                .build()
    }
}
