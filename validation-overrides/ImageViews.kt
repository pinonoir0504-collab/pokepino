package com.pokepino.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.roundToInt

/** Each view is owned by the caller; the source is never recycled. */
internal object ImageViews {
    /** Android may return src for a full-image crop; ensure independent ownership. */
    fun ownedCrop(src: Bitmap, left: Int, top: Int, width: Int, height: Int): Bitmap {
        val crop = Bitmap.createBitmap(src, left, top, width, height)
        return if (crop === src) src.copy(Bitmap.Config.ARGB_8888, false) else crop
    }

    fun fit(src: Bitmap): Bitmap {
        val result = Bitmap.createBitmap(224, 224, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawColor(Color.rgb(245, 245, 245))
        val scale = minOf(224f / src.width, 224f / src.height)
        val w = (src.width * scale).roundToInt().coerceAtLeast(1)
        val h = (src.height * scale).roundToInt().coerceAtLeast(1)
        val x = (224 - w) / 2; val y = (224 - h) / 2
        canvas.drawBitmap(src, null, RectF(x.toFloat(), y.toFloat(), (x+w).toFloat(), (y+h).toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
        return result
    }
    fun backgroundFit(src: Bitmap): Bitmap {
        val crop = ForegroundCrop.crop(src)
        return try { fit(crop) } finally { if (crop !== src) crop.recycle() }
    }
    fun packagingViews(src: Bitmap): List<Bitmap> {
        fun view(x: Float, y: Float): Bitmap {
            val left=(src.width*x).toInt(); val top=(src.height*y).toInt()
            val crop=Bitmap.createBitmap(src,left,top,src.width-left,src.height-top)
            return try { fit(crop) } finally { if (crop !== src) crop.recycle() }
        }
        return listOf(view(.3f,.3f),view(0f,.4f),view(.35f,0f))
    }
    fun legacySquare(src: Bitmap): Bitmap {
        val crop = ForegroundCrop.crop(src)
        val scale = 256f / minOf(crop.width, crop.height)
        val w = maxOf(224, (crop.width * scale).roundToInt())
        val h = maxOf(224, (crop.height * scale).roundToInt())
        val resized = Bitmap.createScaledBitmap(crop, w, h, true)
        val square = Bitmap.createBitmap(resized, (w-224)/2, (h-224)/2, 224, 224)
        val result = square.copy(Bitmap.Config.ARGB_8888, false)
        if (square !== resized && square !== crop && square !== src) square.recycle()
        if (resized !== crop && resized !== src) resized.recycle()
        if (crop !== src) crop.recycle()
        return result
    }
}
