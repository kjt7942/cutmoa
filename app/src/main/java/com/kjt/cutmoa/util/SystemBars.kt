package com.kjt.cutmoa.util

import android.app.Activity
import android.content.Context
import android.graphics.Color as AndroidColor
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Every screen declares its own system bar look, applied each time its nav destination
 * resumes. The old version saved the previous look and restored it on dispose, but during a
 * navigation transition the leaving screen is disposed *after* the entering one has applied
 * its look — so e.g. Result → Camera ended with the result screen's "restore" putting a white
 * scrim and dark icons over the full-screen camera preview.
 *
 * [darkContent] = full-screen camera/video behind the bars: no scrim, white icons.
 * Otherwise the light Material screens: system scrim on, dark icons.
 */
@Composable
fun SystemBarsFor(darkContent: Boolean) {
    val view = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(view, lifecycleOwner, darkContent) {
        val observer = LifecycleEventObserver { _, event ->
            if (event != Lifecycle.Event.ON_RESUME) return@LifecycleEventObserver
            val window = view.context.findActivity()?.window ?: return@LifecycleEventObserver
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced = !darkContent
                window.isStatusBarContrastEnforced = !darkContent
            }
            window.navigationBarColor = AndroidColor.TRANSPARENT
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightNavigationBars = !darkContent
                isAppearanceLightStatusBars = !darkContent
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}
