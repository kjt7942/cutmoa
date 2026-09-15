package com.kjt.cutmoa.util

import android.app.Activity
import android.content.Context
import android.graphics.Color as AndroidColor
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * For full-screen dark content (camera feed, video playback) drawn behind the system bars.
 * enableEdgeToEdge() already draws content there, but the system still paints a dimming
 * scrim behind button-mode nav bars for legibility by default; drop that and use light
 * (white) bar icons while the caller is composed, then restore.
 */
@Composable
fun LightSystemBarIcons() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = view.context.findActivity()?.window
        val insetsController = window?.let { WindowCompat.getInsetsController(it, view) }
        val previousLightNavIcons = insetsController?.isAppearanceLightNavigationBars
        val previousLightStatusIcons = insetsController?.isAppearanceLightStatusBars

        if (window != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced = false
                window.isStatusBarContrastEnforced = false
            }
            window.navigationBarColor = AndroidColor.TRANSPARENT
        }
        insetsController?.isAppearanceLightNavigationBars = false
        insetsController?.isAppearanceLightStatusBars = false

        onDispose {
            if (window != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced = true
                window.isStatusBarContrastEnforced = true
            }
            previousLightNavIcons?.let { insetsController?.isAppearanceLightNavigationBars = it }
            previousLightStatusIcons?.let { insetsController?.isAppearanceLightStatusBars = it }
        }
    }
}
