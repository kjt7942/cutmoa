package com.kjt.cutmoa.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SubjectTrackerTest {

    private fun noiseFrame(seed: Int) = LumaFrame(160, 120).apply {
        val random = Random(seed)
        for (i in pixels.indices) pixels[i] = random.nextInt(256)
    }

    /** Same scene moved by (dx, dy) and slightly brighter, like a subject moving under changing exposure. */
    private fun shifted(src: LumaFrame, dx: Int, dy: Int) = LumaFrame(src.width, src.height).apply {
        for (y in 0 until height) for (x in 0 until width) {
            val sx = (x - dx).coerceIn(0, width - 1)
            val sy = (y - dy).coerceIn(0, height - 1)
            pixels[y * width + x] = src.pixels[sy * width + sx] + 10
        }
    }

    @Test
    fun followsMovingSubject() {
        val tracker = SubjectTracker()
        val first = noiseFrame(1)
        tracker.start(first, 80, 60)
        val startLeft = tracker.left
        val startTop = tracker.top

        assertTrue(tracker.update(shifted(first, 6, -4)))
        assertEquals(startLeft + 6, tracker.left)
        assertEquals(startTop - 4, tracker.top)
    }

    @Test
    fun staysPutOnDarkNoisyScene() {
        // Lens covered / dark room: flat frame with fresh sensor noise every frame.
        fun darkFrame(seed: Int) = LumaFrame(160, 120).apply {
            val random = Random(seed)
            for (i in pixels.indices) pixels[i] = 20 + random.nextInt(-3, 4)
        }
        val tracker = SubjectTracker(maxMissedFrames = 100)
        tracker.start(darkFrame(0), 80, 60)
        val startLeft = tracker.left
        val startTop = tracker.top

        for (seed in 1..30) tracker.update(darkFrame(seed))
        assertEquals(startLeft, tracker.left)
        assertEquals(startTop, tracker.top)
    }

    @Test
    fun releasesWhenSubjectDisappears() {
        val tracker = SubjectTracker(maxMissedFrames = 2)
        tracker.start(noiseFrame(1), 80, 60)
        val unrelated = noiseFrame(2)

        assertTrue(tracker.update(unrelated))
        assertTrue(tracker.update(unrelated))
        assertFalse(tracker.update(unrelated))
        assertFalse(tracker.isTracking)
    }
}
