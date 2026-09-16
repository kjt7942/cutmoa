package com.kjt.cutmoa.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayLogicTest {

    private fun layer(vararg keyframes: Keyframe, start: Long = 0, end: Long = 4000) =
        OverlayItem(1, OverlayKind.TEXT, "hi", startMs = start, endMs = end, keyframes = keyframes.toList(), animated = keyframes.size > 1)

    @Test
    fun staticLayerMovesWholeLayerWhateverThePlayhead() {
        val item = layer(Keyframe(0, 0.5f, 0.5f))
        val moved = item.withPose(3000, Keyframe(3000, 0.2f, 0.8f, scale = 2f))
        assertEquals(1, moved.keyframes.size)
        assertEquals(0L, moved.keyframes.single().timeMs)
        assertEquals(0.2f, moved.poseAt(1000).xFraction, 1e-4f)
        assertEquals(2f, moved.poseAt(0).scale, 1e-4f)
    }

    @Test
    fun animatedLayerEditsKeyframeAtPlayheadAndReusesNearbyOne() {
        val item = layer(Keyframe(0, 0f, 0f), Keyframe(4000, 1f, 1f))
        val added = item.withPose(2000, Keyframe(2000, 0.9f, 0.1f))
        assertEquals(3, added.keyframes.size)
        // Within tolerance of 2000 → same keyframe, not a fourth one.
        val tweaked = added.withPose(2030, Keyframe(2030, 0.3f, 0.3f))
        assertEquals(3, tweaked.keyframes.size)
        assertEquals(0.3f, tweaked.poseAt(2000).xFraction, 1e-4f)
    }

    @Test
    fun poseInterpolatesRotationAndClampsOutsideRange() {
        val item = layer(Keyframe(1000, rotation = 0f, scale = 1f), Keyframe(3000, rotation = 90f, scale = 3f))
        assertEquals(45f, item.poseAt(2000).rotation, 1e-3f)
        assertEquals(2f, item.poseAt(2000).scale, 1e-3f)
        assertEquals(0f, item.poseAt(0).rotation, 1e-3f)
        assertEquals(90f, item.poseAt(9000).rotation, 1e-3f)
    }

    @Test
    fun turningAnimationOnThenMovingLaterTravelsAndHolds() {
        val item = layer(Keyframe(0, 0.5f, 0.5f)).withAnimation(on = true, playheadMs = 0)
        val moved = item.withPose(2000, Keyframe(2000, 0.9f, 0.9f))
        assertEquals(0.5f, moved.poseAt(0).xFraction, 1e-4f)
        assertEquals(0.7f, moved.poseAt(1000).xFraction, 1e-4f)
        // Past the last keyframe it stays put instead of drifting back.
        assertEquals(0.9f, moved.poseAt(3900).xFraction, 1e-4f)
        val off = moved.withAnimation(on = false, playheadMs = 1000)
        assertFalse(off.animated)
        assertEquals(0.7f, off.poseAt(3900).xFraction, 1e-4f)
    }

    @Test
    fun lastKeyframeCannotBeRemoved() {
        val item = layer(Keyframe(0))
        assertEquals(item, item.withoutKeyframeNear(0))
    }

    @Test
    fun moveKeepsLayerInsideVideoAndShiftsKeyframes() {
        val item = layer(Keyframe(500), Keyframe(1500), start = 500, end = 1500)
        val moved = item.movedTo(9500, durationMs = 10000)
        assertEquals(9000L, moved.startMs)
        assertEquals(10000L, moved.endMs)
        assertEquals(listOf(9000L, 10000L), moved.keyframes.map { it.timeMs })
    }

    @Test
    fun trimNeverGoesBelowMinimumDuration() {
        val item = layer(Keyframe(0), start = 1000, end = 2000)
        assertEquals(2000L - MIN_LAYER_DURATION_MS, item.trimmedStart(5000).startMs)
        assertEquals(1000L + MIN_LAYER_DURATION_MS, item.trimmedEnd(0, 10000).endMs)
    }

    @Test
    fun fitRectLetterboxesPortraitVideoInWideBox() {
        val rect = OverlayGeometry.fitRect(1000f, 1000f, 9f / 16f)
        assertEquals(562.5f, rect.width, 1e-3f)
        assertEquals(1000f, rect.height, 1e-3f)
        assertEquals(218.75f, rect.left, 1e-3f)
        assertEquals(0f, rect.top, 1e-3f)
    }

    @Test
    fun placementScalesReferenceSizeToVideoRect() {
        val rect = ScreenRect(0f, 0f, 540f, 960f) // half of a 1080-wide frame
        // A bitmap rendered at 1.5x reference: 300px wide = 200 reference px → 100 screen px at scale 1.
        val p = OverlayGeometry.placement(rect, Keyframe(0, 0.5f, 0.25f, scale = 2f), 300, 150, 1.5f)
        assertEquals(270f, p.centerX, 1e-3f)
        assertEquals(240f, p.centerY, 1e-3f)
        assertEquals(200f, p.width, 1e-3f)
        assertEquals(100f, p.height, 1e-3f)
    }

    @Test
    fun hitTestFollowsRotation() {
        // 200×20 bar centered at (100,100). Unrotated, (100,150) is outside; rotated 90° it's inside.
        val flat = LayerPlacement(100f, 100f, 200f, 20f, 0f)
        val upright = flat.copy(rotation = 90f)
        assertFalse(OverlayGeometry.hits(flat, 100f, 150f, 0f))
        assertTrue(OverlayGeometry.hits(upright, 100f, 150f, 0f))
        assertTrue(OverlayGeometry.hits(flat, 190f, 100f, 0f))
        assertFalse(OverlayGeometry.hits(upright, 190f, 100f, 0f))
    }

    @Test
    fun snapPullsCenterAndRightAngles() {
        val s = OverlayGeometry.snap(Keyframe(0, 0.51f, 0.7f, rotation = 87f))
        assertTrue(s.snappedX)
        assertFalse(s.snappedY)
        assertEquals(0.5f, s.pose.xFraction, 1e-4f)
        assertEquals(0.7f, s.pose.yFraction, 1e-4f)
        assertEquals(90f, s.pose.rotation, 1e-4f)
        assertEquals(30f, OverlayGeometry.snap(Keyframe(0, rotation = 30f)).pose.rotation, 1e-4f)
    }

    @Test
    fun formatsTimeWithTenths() {
        assertEquals("00:00.0", OverlayGeometry.formatTime(0))
        assertEquals("00:09.1", OverlayGeometry.formatTime(9130))
        assertEquals("01:05.5", OverlayGeometry.formatTime(65_500))
    }
}
