package com.kjt.cutmoa.overlay

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Axis-aligned rectangle in screen px. Plain floats so the math stays JVM-testable. */
data class ScreenRect(val left: Float, val top: Float, val width: Float, val height: Float) {
    val centerX get() = left + width / 2f
    val centerY get() = top + height / 2f
}

/**
 * Screen-space placement of a layer: center, on-screen size of its rendered bitmap, and
 * clockwise rotation. Shared by drawing, hit-testing and the export math so all three agree.
 */
data class LayerPlacement(val centerX: Float, val centerY: Float, val width: Float, val height: Float, val rotation: Float)

object OverlayGeometry {

    /** The video's letterboxed rectangle inside a [containerW]×[containerH] box (fit, centered). */
    fun fitRect(containerW: Float, containerH: Float, videoAspect: Float): ScreenRect {
        if (containerW <= 0f || containerH <= 0f || videoAspect <= 0f) return ScreenRect(0f, 0f, 0f, 0f)
        val w = min(containerW, containerH * videoAspect)
        val h = w / videoAspect
        return ScreenRect((containerW - w) / 2f, (containerH - h) / 2f, w, h)
    }

    /**
     * Where a layer lands on screen. Bitmaps are rendered in [OverlayRenderer.REFERENCE_WIDTH]
     * units times [renderPixelScale]; the video rect maps that reference width to its own width.
     */
    fun placement(videoRect: ScreenRect, pose: Keyframe, bitmapW: Int, bitmapH: Int, renderPixelScale: Float): LayerPlacement {
        val refToScreen = videoRect.width / OverlayRenderer.REFERENCE_WIDTH
        val factor = refToScreen * pose.scale / renderPixelScale
        return LayerPlacement(
            centerX = videoRect.left + pose.xFraction * videoRect.width,
            centerY = videoRect.top + pose.yFraction * videoRect.height,
            width = bitmapW * factor,
            height = bitmapH * factor,
            rotation = pose.rotation,
        )
    }

    /** True if ([x],[y]) falls inside the (rotated) layer, grown by [slop] px on every side. */
    fun hits(p: LayerPlacement, x: Float, y: Float, slop: Float): Boolean {
        val rad = Math.toRadians(-p.rotation.toDouble())
        val dx = x - p.centerX
        val dy = y - p.centerY
        val localX = (dx * cos(rad) - dy * sin(rad)).toFloat()
        val localY = (dx * sin(rad) + dy * cos(rad)).toFloat()
        return abs(localX) <= p.width / 2f + slop && abs(localY) <= p.height / 2f + slop
    }

    /** Result of snapping a raw gesture pose; the flags drive the guide lines on screen. */
    data class Snapped(val pose: Keyframe, val snappedX: Boolean, val snappedY: Boolean)

    /**
     * Pulls the center onto the video's vertical/horizontal center line when within
     * [thresholdFraction], and rotation onto the nearest 90° when within [rotationThreshold].
     */
    fun snap(raw: Keyframe, thresholdFraction: Float = 0.02f, rotationThreshold: Float = 5f): Snapped {
        val snapX = abs(raw.xFraction - 0.5f) <= thresholdFraction
        val snapY = abs(raw.yFraction - 0.5f) <= thresholdFraction
        val nearestRight = (raw.rotation / 90f).roundToInt() * 90f
        val rotation = if (abs(raw.rotation - nearestRight) <= rotationThreshold) nearestRight else raw.rotation
        return Snapped(
            pose = raw.copy(
                xFraction = if (snapX) 0.5f else raw.xFraction,
                yFraction = if (snapY) 0.5f else raw.yFraction,
                rotation = rotation,
            ),
            snappedX = snapX,
            snappedY = snapY,
        )
    }

    fun formatTime(ms: Long): String {
        val safe = ms.coerceAtLeast(0)
        val totalTenths = safe / 100
        val minutes = totalTenths / 600
        val seconds = (totalTenths / 10) % 60
        val tenths = totalTenths % 10
        return "%02d:%02d.%d".format(minutes, seconds, tenths)
    }
}
