package com.kjt.cutmoa.overlay

import androidx.compose.ui.graphics.Color

enum class OverlayKind { TEXT, EMOJI }

/** One point in an overlay's motion: where/how big it is at [timeMs] (absolute video time, ms). */
data class Keyframe(
    val timeMs: Long,
    val xFraction: Float = 0.5f,
    val yFraction: Float = 0.5f,
    val scale: Float = 1f,
)

/**
 * One layer on the timeline. Visible only within [startMs, endMs] of the merged video,
 * animated between [keyframes] (linear interpolation, clamped at the ends) — this is what
 * lets several layers overlap in time while each moves independently.
 */
data class OverlayItem(
    val id: Long,
    val kind: OverlayKind,
    val text: String,
    val color: Color = Color.White,
    val startMs: Long,
    val endMs: Long,
    val keyframes: List<Keyframe>,
) {
    init {
        require(keyframes.isNotEmpty()) { "OverlayItem needs at least one keyframe" }
    }

    fun isVisibleAt(timeMs: Long): Boolean = timeMs in startMs..endMs

    /** Interpolated pose at [timeMs], clamped to the first/last keyframe outside their range. */
    fun poseAt(timeMs: Long): Keyframe {
        val sorted = keyframes.sortedBy { it.timeMs }
        val first = sorted.first()
        val last = sorted.last()
        if (timeMs <= first.timeMs) return first
        if (timeMs >= last.timeMs) return last
        val upperIndex = sorted.indexOfFirst { it.timeMs >= timeMs }
        val lower = sorted[upperIndex - 1]
        val upper = sorted[upperIndex]
        val span = (upper.timeMs - lower.timeMs).coerceAtLeast(1)
        val t = (timeMs - lower.timeMs).toFloat() / span
        return Keyframe(
            timeMs = timeMs,
            xFraction = lower.xFraction + (upper.xFraction - lower.xFraction) * t,
            yFraction = lower.yFraction + (upper.yFraction - lower.yFraction) * t,
            scale = lower.scale + (upper.scale - lower.scale) * t,
        )
    }

    /** Adds a keyframe at [timeMs], or replaces the one already there. */
    fun withKeyframeAt(timeMs: Long, xFraction: Float, yFraction: Float, scale: Float): OverlayItem {
        val updated = Keyframe(timeMs, xFraction, yFraction, scale)
        val withoutOld = keyframes.filterNot { it.timeMs == timeMs }
        return copy(keyframes = (withoutOld + updated).sortedBy { it.timeMs })
    }

    /** Removes the keyframe at [timeMs]; a no-op if it's the last remaining one. */
    fun withoutKeyframeAt(timeMs: Long): OverlayItem {
        if (keyframes.size <= 1) return this
        return copy(keyframes = keyframes.filterNot { it.timeMs == timeMs })
    }
}
