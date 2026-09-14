package com.kjt.cutmoa.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class BufferMappingTest {

    @Test
    fun backCameraPortraitMatchesDevice() {
        // Values logged on a Galaxy S25: 640x480 buffer, rot=90, 9:16 viewport crop (0,60)-(640,420).
        val mapping = BufferMapping(90, 0, 60, 640, 360)

        // Upright top edge is the buffer's left edge; upright right edge is the buffer's top of crop.
        assertPair(0f to 240f, mapping.toBuffer(0f, -1f))
        assertPair(320f to 60f, mapping.toBuffer(1f, 0f))
        // A tap below center lands to the right of buffer center, on the crop's vertical middle.
        assertPair(410.24f to 240f, mapping.toBuffer(0f, 0.282f))
    }

    @Test
    fun roundTripsForEveryRotation() {
        for (rotation in intArrayOf(0, 90, 180, 270)) {
            val mapping = BufferMapping(rotation, 0, 60, 640, 360)
            val (bx, by) = mapping.toBuffer(0.3f, -0.7f)
            assertPair(0.3f to -0.7f, mapping.toNormalized(bx, by))
        }
    }

    private fun assertPair(expected: Pair<Float, Float>, actual: Pair<Float, Float>) {
        assertEquals(expected.first, actual.first, 0.01f)
        assertEquals(expected.second, actual.second, 0.01f)
    }
}
