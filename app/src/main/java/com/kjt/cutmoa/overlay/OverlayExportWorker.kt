package com.kjt.cutmoa.overlay

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.kjt.cutmoa.util.ExportNotifications
import com.kjt.cutmoa.util.MediaStoreExport
import java.io.File

class OverlayExportWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val sourcePath = inputData.getString(KEY_SOURCE_PATH)
            ?: return Result.failure(workDataOf(KEY_ERROR to "missing source"))
        val overlaysJson = inputData.getString(KEY_OVERLAYS_JSON) ?: "[]"
        val overlays = parseOverlayItems(overlaysJson)

        setForeground(ExportNotifications.foregroundInfo(applicationContext, NOTIF_ID, "최종 영상 만드는 중", 0))

        val tempOutput = File(applicationContext.cacheDir, "final_${System.currentTimeMillis()}.mp4")

        return try {
            OverlayExporter.export(applicationContext, File(sourcePath), overlays, tempOutput) { percent ->
                setForegroundAsync(
                    ExportNotifications.foregroundInfo(applicationContext, NOTIF_ID, "최종 영상 만드는 중", percent)
                )
                setProgressAsync(workDataOf(KEY_PROGRESS to percent))
            }
            val galleryUri = MediaStoreExport.copyToGallery(
                applicationContext, tempOutput, "CUTMOA_FINAL_${System.currentTimeMillis()}.mp4"
            )
            Result.success(workDataOf(KEY_OUTPUT_URI to galleryUri.toString()))
        } catch (t: Throwable) {
            Result.failure(workDataOf(KEY_ERROR to (t.message ?: "export failed")))
        }
    }

    companion object {
        const val KEY_SOURCE_PATH = "source_path"
        const val KEY_OVERLAYS_JSON = "overlays_json"
        const val KEY_PROGRESS = "progress"
        const val KEY_OUTPUT_URI = "output_uri"
        const val KEY_ERROR = "error"
        private const val NOTIF_ID = 1002

        fun buildRequest(sourcePath: String, overlays: List<OverlayItem>): OneTimeWorkRequest {
            val data = workDataOf(
                KEY_SOURCE_PATH to sourcePath,
                KEY_OVERLAYS_JSON to overlays.toJson(),
            )
            return OneTimeWorkRequest.Builder(OverlayExportWorker::class.java)
                .setInputData(data)
                .build()
        }
    }
}
