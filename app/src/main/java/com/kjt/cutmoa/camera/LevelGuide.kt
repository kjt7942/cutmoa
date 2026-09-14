package com.kjt.cutmoa.camera

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlin.math.sqrt

private const val LEVEL_TOLERANCE_DEG = 1f
private const val TOP_DOWN_TOLERANCE_DEG = 2f
// Past this pitch the camera is looking at the floor/sky, where roll is meaningless —
// switch to the bubble-style top-down guide instead.
private const val TOP_DOWN_THRESHOLD_DEG = 70f
private const val SMOOTHING = 0.2f

private val LINE_COLOR = Color.White
private val SHADOW_COLOR = Color.Black.copy(alpha = 0.25f)
private val LEVEL_COLOR = Color(0xFFFFD600)
private val PITCH_COLOR = Color(0xFFB4E94B)

// Proportions taken from Samsung Camera's level guide.
private val RING_RADIUS = 33.dp
private val HORIZON_HALF = 48.dp
private val TICK_INNER = 30.dp
private val LINE_WIDTH = 1.5.dp
private val PITCH_DP_PER_DEG = 2.dp
/** Pitch ladder rungs inside the ring: (vertical offset, half length), mirrored above/below. */
private val LADDER = listOf(5.5.dp to 5.dp, 11.dp to 9.dp, 17.dp to 12.dp)

/**
 * Horizon ring + pitch ladder drawn over the camera preview, modeled on Samsung Camera.
 * Preview overlay only — it never ends up in the recorded video.
 *
 * Gravity is read in device coordinates (x right, y toward top edge, z out of the screen),
 * so the math below assumes the activity is locked to portrait.
 */
@Composable
fun LevelGuide(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val gx = remember { mutableFloatStateOf(0f) }
    val gy = remember { mutableFloatStateOf(SensorManager.GRAVITY_EARTH) }
    val gz = remember { mutableFloatStateOf(0f) }

    // ponytail: sensor runs while this screen is composed, even if the app is backgrounded;
    // switch to LifecycleResumeEffect if battery drain shows up.
    DisposableEffect(context) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                // Low-pass: TYPE_GRAVITY is already smooth, but the accelerometer fallback jitters.
                gx.floatValue += SMOOTHING * (event.values[0] - gx.floatValue)
                gy.floatValue += SMOOTHING * (event.values[1] - gy.floatValue)
                gz.floatValue += SMOOTHING * (event.values[2] - gz.floatValue)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        sensor?.let { sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME) }
        onDispose { sensorManager.unregisterListener(listener) }
    }

    // State is read inside the draw lambda only, so sensor updates redraw without recomposing.
    Canvas(modifier = modifier) {
        val x = gx.floatValue
        val y = gy.floatValue
        val z = gz.floatValue
        val norm = sqrt(x * x + y * y + z * z).takeIf { it > 0.1f } ?: return@Canvas

        val pitch = pitchDegrees(z, norm)
        if (abs(pitch) >= TOP_DOWN_THRESHOLD_DEG) {
            drawTopDownGuide(x / norm, y / norm)
        } else {
            drawHorizonGuide(rollDegrees(x, y), pitch)
        }
    }
}

/** Angle of the real horizon on screen, in Compose rotation degrees (clockwise positive). */
internal fun rollDegrees(gx: Float, gy: Float): Float =
    Math.toDegrees(atan2(gx, gy).toDouble()).toFloat()

/** Camera elevation above the horizon: 0 = level, negative = aiming down. */
internal fun pitchDegrees(gz: Float, norm: Float): Float =
    Math.toDegrees(asin((-gz / norm).coerceIn(-1f, 1f).toDouble())).toFloat()

/** White line with a faint dark halo so it stays visible over bright scenes. */
private fun DrawScope.guideLine(color: Color, start: Offset, end: Offset, width: Dp = LINE_WIDTH) {
    drawLine(SHADOW_COLOR, start, end, (width + 1.5.dp).toPx(), StrokeCap.Round)
    drawLine(color, start, end, width.toPx(), StrokeCap.Round)
}

private fun DrawScope.guideRing(color: Color, radius: Dp, center: Offset) {
    drawCircle(SHADOW_COLOR, radius.toPx(), center, style = Stroke((LINE_WIDTH + 1.5.dp).toPx()))
    drawCircle(color, radius.toPx(), center, style = Stroke(LINE_WIDTH.toPx()))
}

private fun DrawScope.drawHorizonGuide(roll: Float, pitch: Float) {
    // Holding the phone sideways is a valid framing too — level against the nearest 90°.
    val frameAngle = (roll / 90f).roundToInt() * 90f
    val levelColor = if (abs(roll - frameAngle) < LEVEL_TOLERANCE_DEG) LEVEL_COLOR else LINE_COLOR
    val c = center

    // Ring, end ticks and pitch ladder are fixed to the phone frame.
    rotate(frameAngle, c) {
        guideRing(LINE_COLOR, RING_RADIUS, c)
        val inner = TICK_INNER.toPx()
        val outer = HORIZON_HALF.toPx()
        guideLine(levelColor, Offset(c.x - outer, c.y), Offset(c.x - inner, c.y))
        guideLine(levelColor, Offset(c.x + inner, c.y), Offset(c.x + outer, c.y))

        for ((offset, half) in LADDER) {
            for (sign in intArrayOf(-1, 1)) {
                val y = c.y + sign * offset.toPx()
                guideLine(LINE_COLOR, Offset(c.x - half.toPx(), y), Offset(c.x + half.toPx(), y), 1.dp)
            }
        }

        // Pitch marker slides toward where the horizon sits in the image: up when aiming
        // down, centered (hidden under the horizon line) when the camera is aimed flat.
        val limit = RING_RADIUS.toPx() - 6.dp.toPx()
        val py = c.y + (pitch * PITCH_DP_PER_DEG.toPx()).coerceIn(-limit, limit)
        val dash = 5.dp.toPx()
        guideLine(PITCH_COLOR, Offset(c.x - dash, py), Offset(c.x + dash, py), 2.dp)
    }

    // Long line through the ring follows the true horizon; it meets the end ticks when level.
    rotate(roll, c) {
        val half = HORIZON_HALF.toPx()
        guideLine(levelColor, Offset(c.x - half, c.y), Offset(c.x + half, c.y))
    }
}

/** Bubble level for shooting straight down/up: center the dot in the ring. */
private fun DrawScope.drawTopDownGuide(nx: Float, ny: Float) {
    val tilt = sqrt(nx * nx + ny * ny)
    val aligned = tilt < Math.sin(Math.toRadians(TOP_DOWN_TOLERANCE_DEG.toDouble())).toFloat()
    val color = if (aligned) LEVEL_COLOR else LINE_COLOR
    val scale = 400.dp.toPx()
    val limit = 150.dp.toPx()
    // Dot sits on the raised side, like an air bubble.
    val dot = Offset(
        center.x + (nx * scale).coerceIn(-limit, limit),
        center.y - (ny * scale).coerceIn(-limit, limit),
    )
    guideRing(color, RING_RADIUS, center)
    drawCircle(SHADOW_COLOR, radius = 9.5.dp.toPx(), center = dot)
    drawCircle(color, radius = 8.dp.toPx(), center = dot)
}
