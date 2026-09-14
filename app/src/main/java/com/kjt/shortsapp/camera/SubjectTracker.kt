package com.kjt.shortsapp.camera

import kotlin.math.abs

// Noise-only score dips stay within ~10% of the stay score; real motion beats it by far more.
private const val MOVE_RATIO = 0.8f

/** Grayscale frame, row-major, values 0..255. Reused across frames to avoid GC churn. */
class LumaFrame(val width: Int, val height: Int) {
    val pixels = IntArray(width * height)
}

/**
 * Follows a tapped patch across frames by template matching (mean-removed SAD) inside a
 * small window around its last position. No ML, so it locks onto any texture, but it loses
 * fast motion, big scale changes and occlusion — then it gives up and reports lost.
 */
class SubjectTracker(
    val templateSize: Int = 32,
    private val searchRadius: Int = 16,
    // ponytail: calibration knob — higher keeps the lock longer but drifts onto background.
    private val lostScore: Float = 0.5f,
    private val maxMissedFrames: Int = 8,
    private val templateBlend: Float = 0.1f,
) {
    private val template = FloatArray(templateSize * templateSize)
    private var missed = 0

    var isTracking = false
        private set

    /** Top-left of the tracked patch in frame coordinates. */
    var left = 0
        private set
    var top = 0
        private set

    fun start(frame: LumaFrame, centerX: Int, centerY: Int) {
        left = (centerX - templateSize / 2).coerceIn(0, frame.width - templateSize)
        top = (centerY - templateSize / 2).coerceIn(0, frame.height - templateSize)
        for (y in 0 until templateSize) {
            val row = (top + y) * frame.width + left
            for (x in 0 until templateSize) template[y * templateSize + x] = frame.pixels[row + x].toFloat()
        }
        missed = 0
        isTracking = true
    }

    fun stop() {
        isTracking = false
    }

    /** Moves to the best match near the last position. Returns false once the subject is lost. */
    fun update(frame: LumaFrame): Boolean {
        if (!isTracking) return false
        val tMean = template.average().toFloat()
        var tDev = 0f
        for (v in template) tDev += abs(v - tMean)
        // Flat patch (plain wall): don't divide sensor noise by ~0.
        tDev = maxOf(tDev, template.size.toFloat())

        // Only move for a clearly better match. On flat or dark patches every candidate scores
        // about the same, and chasing sensor-noise minima makes the box wander off.
        val stayScore = meanRemovedSad(frame, left, top, tMean) / tDev
        var bestScore = stayScore
        var bestX = left
        var bestY = top
        for (py in (top - searchRadius).coerceAtLeast(0)..(top + searchRadius).coerceAtMost(frame.height - templateSize)) {
            for (px in (left - searchRadius).coerceAtLeast(0)..(left + searchRadius).coerceAtMost(frame.width - templateSize)) {
                val score = meanRemovedSad(frame, px, py, tMean) / tDev
                if (score < bestScore && score < stayScore * MOVE_RATIO) {
                    bestScore = score
                    bestX = px
                    bestY = py
                }
            }
        }

        if (bestScore > lostScore) {
            if (++missed > maxMissedFrames) isTracking = false
            return isTracking
        }
        missed = 0
        left = bestX
        top = bestY
        // Slowly absorb appearance changes (lighting, slight turn) without jumping onto background.
        for (y in 0 until templateSize) {
            val row = (top + y) * frame.width + left
            for (x in 0 until templateSize) {
                val i = y * templateSize + x
                template[i] += templateBlend * (frame.pixels[row + x] - template[i])
            }
        }
        return true
    }

    // Mean-removed so exposure shifts while the subject moves don't break the match.
    private fun meanRemovedSad(frame: LumaFrame, px: Int, py: Int, tMean: Float): Float {
        var sum = 0
        for (y in 0 until templateSize) {
            val row = (py + y) * frame.width + px
            for (x in 0 until templateSize) sum += frame.pixels[row + x]
        }
        val offset = sum.toFloat() / template.size - tMean
        var sad = 0f
        for (y in 0 until templateSize) {
            val row = (py + y) * frame.width + px
            val tRow = y * templateSize
            for (x in 0 until templateSize) sad += abs(frame.pixels[row + x] - offset - template[tRow + x])
        }
        return sad
    }
}
