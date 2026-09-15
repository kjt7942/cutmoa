package com.kjt.cutmoa.camera

import android.content.Context
import androidx.camera.video.Quality

const val DEFAULT_FPS = 30

/** Display labels, highest first — also the set of qualities the options dialog can offer. */
val QUALITY_LABELS: Map<Quality, String> = linkedMapOf(
    Quality.UHD to "4K",
    Quality.FHD to "1080p",
    Quality.HD to "720p",
    Quality.SD to "480p",
)

/** Recording options from the camera screen's options dialog, remembered across app restarts. */
data class CaptureSettings(
    val showGrid: Boolean = false,
    val quality: Quality = Quality.FHD,
    val fps: Int = DEFAULT_FPS,
) {
    companion object {
        private const val PREFS_NAME = "shorts_prefs" // same file as DurationPrefs
        private const val KEY_GRID = "capture_grid"
        private const val KEY_QUALITY = "capture_quality"
        private const val KEY_FPS = "capture_fps"

        fun load(context: Context): CaptureSettings {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val qualityLabel = prefs.getString(KEY_QUALITY, null)
            return CaptureSettings(
                showGrid = prefs.getBoolean(KEY_GRID, false),
                quality = QUALITY_LABELS.entries.find { it.value == qualityLabel }?.key ?: Quality.FHD,
                fps = prefs.getInt(KEY_FPS, DEFAULT_FPS),
            )
        }

        fun save(context: Context, settings: CaptureSettings) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_GRID, settings.showGrid)
                .putString(KEY_QUALITY, QUALITY_LABELS[settings.quality])
                .putInt(KEY_FPS, settings.fps)
                .apply()
        }
    }
}
