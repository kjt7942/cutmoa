package com.kjt.cutmoa.camera

import android.content.Context

/** Remembers the user's custom recording-duration chips across app restarts. */
object DurationPrefs {
    private const val PREFS_NAME = "shorts_prefs"
    private const val KEY_CUSTOM_DURATIONS = "custom_durations"

    fun load(context: Context): List<Int> {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_CUSTOM_DURATIONS, null) ?: return emptyList()
        return raw.split(",").mapNotNull { it.toIntOrNull() }
    }

    fun save(context: Context, durations: List<Int>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CUSTOM_DURATIONS, durations.joinToString(","))
            .apply()
    }
}
