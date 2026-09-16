package com.kjt.cutmoa.overlay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Draws a layer into a bitmap with plain android.graphics. The editor preview and the export
 * both use this exact bitmap, so what's on screen is what gets baked into the video — the old
 * editor drew with Compose Text (screen sp, a translucent box) while export used a Media3
 * TextOverlay (fixed px, no box), and the two never matched in size or look.
 */
object OverlayRenderer {

    /**
     * Sizes are defined against a 1080px-wide frame. Preview and export each map this to
     * their real width, so a layer covers the same fraction of the video in both.
     */
    const val REFERENCE_WIDTH = 1080f

    private const val TEXT_PX = 72f
    private const val EMOJI_PX = 160f
    private const val MAX_BITMAP_SIDE = 4096

    /** Renders [item] at [pixelScale]× the reference size (use >1 for crispness when scaled up). */
    fun render(item: OverlayItem, pixelScale: Float): Bitmap {
        val isEmoji = item.kind == OverlayKind.EMOJI
        val textSize = (if (isEmoji) EMOJI_PX else TEXT_PX) * pixelScale
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.textSize = textSize
            typeface = if (isEmoji) Typeface.DEFAULT else Typeface.DEFAULT_BOLD
            color = if (isEmoji) android.graphics.Color.BLACK else item.color.toArgb()
        }

        val text = item.text.ifEmpty { " " }
        val lineWidth = text.split('\n').maxOf { Layout.getDesiredWidth(it, paint) }
        val layoutWidth = ceil(lineWidth).toInt().coerceAtLeast(1)
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, layoutWidth)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(false)
            .build()

        val style = if (isEmoji) null else item.style
        val pad = when (style) {
            OverlayTextStyle.BOX -> textSize * 0.35f
            OverlayTextStyle.OUTLINE -> textSize * 0.12f
            OverlayTextStyle.SHADOW -> textSize * 0.15f
            null -> textSize * 0.05f
        }
        val width = min(ceil(layout.width + pad * 2).toInt(), MAX_BITMAP_SIDE)
        val height = min(ceil(layout.height + pad * 2).toInt(), MAX_BITMAP_SIDE)
        val bitmap = Bitmap.createBitmap(max(width, 1), max(height, 1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // A dark text gets light decoration and vice versa, so it reads on any background.
        val contrast = if (item.color.luminance() > 0.5f) Color.Black else Color.White

        if (style == OverlayTextStyle.BOX) {
            val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = contrast.copy(alpha = 0.6f).toArgb() }
            val radius = textSize * 0.3f
            canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), radius, radius, boxPaint)
        }

        canvas.save()
        canvas.translate(pad, pad)
        when (style) {
            OverlayTextStyle.OUTLINE -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = textSize * 0.16f
                paint.strokeJoin = Paint.Join.ROUND
                paint.color = contrast.toArgb()
                layout.draw(canvas)
                paint.style = Paint.Style.FILL
                paint.strokeWidth = 0f
                paint.color = item.color.toArgb()
                layout.draw(canvas)
            }
            OverlayTextStyle.SHADOW -> {
                paint.setShadowLayer(textSize * 0.08f, 0f, textSize * 0.04f, contrast.copy(alpha = 0.8f).toArgb())
                layout.draw(canvas)
            }
            OverlayTextStyle.BOX, null -> layout.draw(canvas)
        }
        canvas.restore()
        return bitmap
    }
}
