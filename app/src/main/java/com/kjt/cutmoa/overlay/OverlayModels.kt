package com.kjt.cutmoa.overlay

import androidx.compose.ui.graphics.Color
import kotlin.math.abs

enum class OverlayKind { TEXT, EMOJI }

/** How a text layer is made readable over arbitrary video. Emoji ignore this. */
enum class OverlayTextStyle { OUTLINE, BOX, SHADOW }

/**
 * One point in an overlay's motion: where/how big/how rotated it is at [timeMs]
 * (absolute video time, ms). [xFraction]/[yFraction] are the layer's *center* as a fraction
 * of the video frame (0..1, y-down); [rotation] is clockwise degrees.
 */
data class Keyframe(
    val timeMs: Long,
    val xFraction: Float = 0.5f,
    val yFraction: Float = 0.5f,
    val scale: Float = 1f,
    val rotation: Float = 0f,
)

/** Two keyframes closer than this are treated as the same one (a frame or two at 30fps). */
const val KEYFRAME_TOLERANCE_MS = 40L
const val MIN_LAYER_DURATION_MS = 300L
const val MIN_SCALE = 0.2f
const val MAX_SCALE = 6f

/**
 * One layer on the timeline. Visible only within [startMs, endMs] of the merged video.
 *
 * A static layer ([animated] false) has one pose — moving it just moves it, whatever the
 * playhead. Once the user turns animation on, edits land on (or create) the keyframe at the
 * playhead, with linear interpolation between keyframes and the first/last pose held outside
 * them — so "move here at 2s" travels there and stays, rather than drifting back.
 * Animation is opt-in because auto-creating a keyframe on every drag made layers drift around
 * on their own, which is what made the old editor feel uncontrollable.
 */
data class OverlayItem(
    val id: Long,
    val kind: OverlayKind,
    val text: String,
    val color: Color = Color.White,
    val style: OverlayTextStyle = OverlayTextStyle.OUTLINE,
    val startMs: Long,
    val endMs: Long,
    val keyframes: List<Keyframe>,
    val animated: Boolean = false,
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
        if (timeMs <= first.timeMs) return first.copy(timeMs = timeMs)
        if (timeMs >= last.timeMs) return last.copy(timeMs = timeMs)
        val upperIndex = sorted.indexOfFirst { it.timeMs >= timeMs }
        val lower = sorted[upperIndex - 1]
        val upper = sorted[upperIndex]
        val span = (upper.timeMs - lower.timeMs).coerceAtLeast(1)
        val t = (timeMs - lower.timeMs).toFloat() / span
        return Keyframe(
            timeMs = timeMs,
            xFraction = lerp(lower.xFraction, upper.xFraction, t),
            yFraction = lerp(lower.yFraction, upper.yFraction, t),
            scale = lerp(lower.scale, upper.scale, t),
            rotation = lerp(lower.rotation, upper.rotation, t),
        )
    }

    /** The keyframe within [KEYFRAME_TOLERANCE_MS] of [timeMs], if any. */
    fun keyframeNear(timeMs: Long): Keyframe? =
        keyframes.filter { abs(it.timeMs - timeMs) <= KEYFRAME_TOLERANCE_MS }.minByOrNull { abs(it.timeMs - timeMs) }

    /**
     * Applies an edited pose seen at [playheadMs]: a static layer just takes the new pose;
     * an animated one updates (or creates) the keyframe at the playhead.
     */
    fun withPose(playheadMs: Long, pose: Keyframe): OverlayItem {
        if (!animated) return copy(keyframes = listOf(pose.copy(timeMs = keyframes.first().timeMs)))
        val at = keyframeNear(playheadMs)?.timeMs ?: playheadMs
        return withKeyframeAt(pose.copy(timeMs = at))
    }

    /** Adds [keyframe], replacing one already at (about) the same time. */
    fun withKeyframeAt(keyframe: Keyframe): OverlayItem {
        val existing = keyframeNear(keyframe.timeMs)
        val withoutOld = if (existing == null) keyframes else keyframes - existing
        return copy(keyframes = (withoutOld + keyframe).sortedBy { it.timeMs })
    }

    /** Turns animation on with one keyframe at the playhead (clamped into the layer), or off keeping the pose there. */
    fun withAnimation(on: Boolean, playheadMs: Long): OverlayItem {
        val at = playheadMs.coerceIn(startMs, endMs)
        val pose = poseAt(at)
        return if (on) copy(animated = true, keyframes = listOf(pose.copy(timeMs = at)))
        else copy(animated = false, keyframes = listOf(pose.copy(timeMs = startMs)))
    }

    /** Removes the keyframe near [timeMs]; the last remaining keyframe can't be removed. */
    fun withoutKeyframeNear(timeMs: Long): OverlayItem {
        val existing = keyframeNear(timeMs) ?: return this
        if (keyframes.size <= 1) return this
        return copy(keyframes = keyframes - existing)
    }

    /** Shifts the whole layer (and its keyframes) to start at [newStartMs], kept inside the video. */
    fun movedTo(newStartMs: Long, durationMs: Long): OverlayItem {
        val span = endMs - startMs
        val start = newStartMs.coerceIn(0, (durationMs - span).coerceAtLeast(0))
        val shift = start - startMs
        return copy(startMs = start, endMs = start + span, keyframes = keyframes.map { it.copy(timeMs = it.timeMs + shift) })
    }

    fun trimmedStart(newStartMs: Long): OverlayItem =
        copy(startMs = newStartMs.coerceIn(0, endMs - MIN_LAYER_DURATION_MS))

    fun trimmedEnd(newEndMs: Long, durationMs: Long): OverlayItem =
        copy(endMs = newEndMs.coerceIn(startMs + MIN_LAYER_DURATION_MS, durationMs.coerceAtLeast(startMs + MIN_LAYER_DURATION_MS)))
}

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
