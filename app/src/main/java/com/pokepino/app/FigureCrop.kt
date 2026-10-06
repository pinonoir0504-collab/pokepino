package com.pokepino.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.max
import kotlin.math.sqrt

/** Preserve the complete figure and suppress disconnected background patches. */
internal object FigureCrop {
    fun classifierSquare(src: Bitmap): Bitmap {
        val size = 96
        val sample = Bitmap.createScaledBitmap(src, size, size, true)
        val px = IntArray(size * size)
        sample.getPixels(px, 0, size, 0, 0, size, size)
        val rs = ArrayList<Int>(); val gs = ArrayList<Int>(); val bs = ArrayList<Int>()
        fun add(p: Int) { rs.add(Color.red(p)); gs.add(Color.green(p)); bs.add(Color.blue(p)) }
        for (x in 0 until size) { add(px[x]); add(px[(size - 1) * size + x]) }
        for (y in 0 until size) { add(px[y * size]); add(px[y * size + size - 1]) }
        fun median(a: List<Int>): Float {
            val sorted = a.sorted()
            return (sorted[(sorted.size - 1) / 2] + sorted[sorted.size / 2]) / 2f
        }
        val br = median(rs); val bg = median(gs); val bb = median(bs)
        val distances = FloatArray(px.size) { i ->
            val dr = Color.red(px[i]) - br; val dg = Color.green(px[i]) - bg; val db = Color.blue(px[i]) - bb
            sqrt(dr * dr + dg * dg + db * db)
        }
        val sorted = distances.sorted()
        val pos = (sorted.size - 1) * .55f
        val low = pos.toInt(); val frac = pos - low
        val threshold = max(32f, sorted[low] * (1f - frac) + sorted[minOf(low + 1, sorted.lastIndex)] * frac)
        val mask = BooleanArray(px.size) { distances[it] > threshold }
        val seen = BooleanArray(px.size)
        val queue = IntArray(px.size)
        var bestValue = -1.0; var bx0 = 0; var by0 = 0; var bx1 = size; var by1 = size; var bestCount = 0
        for (start in px.indices) {
            if (!mask[start] || seen[start]) continue
            var read = 0; var write = 1; queue[0] = start; seen[start] = true
            var x0 = size; var y0 = size; var x1 = -1; var y1 = -1; var sx = 0L; var sy = 0L
            fun visit(i: Int) { if (mask[i] && !seen[i]) { seen[i] = true; queue[write++] = i } }
            while (read < write) {
                val i = queue[read++]; val x = i % size; val y = i / size
                x0 = minOf(x0, x); y0 = minOf(y0, y); x1 = maxOf(x1, x); y1 = maxOf(y1, y); sx += x; sy += y
                if (y + 1 < size) visit(i + size)
                if (y > 0) visit(i - size)
                if (x + 1 < size) visit(i + 1)
                if (x > 0) visit(i - 1)
            }
            val cx = sx.toDouble() / write - size / 2.0; val cy = sy.toDouble() / write - size / 2.0
            val value = write * (1.0 - .6 * (cx * cx + cy * cy) / 4608.0)
            if (value > bestValue) {
                bestValue = value; bx0 = max(0, x0 - 6); by0 = max(0, y0 - 6)
                bx1 = minOf(size, x1 + 7); by1 = minOf(size, y1 + 7); bestCount = write
            }
        }
        if (sample !== src) sample.recycle()
        val useCrop = bestCount >= 100 && (bx1 - bx0) * (by1 - by0) >= size * size * .1
        val crop = if (useCrop) {
            val x0 = bx0 * src.width / size; val y0 = by0 * src.height / size
            val x1 = (bx1 * src.width / size).coerceIn(x0 + 1, src.width)
            val y1 = (by1 * src.height / size).coerceIn(y0 + 1, src.height)
            Bitmap.createBitmap(src, x0, y0, x1 - x0, y1 - y0)
        } else src
        val result = Bitmap.createBitmap(224, 224, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result); canvas.drawColor(Color.rgb(245, 245, 245))
        val scale = minOf(224f / crop.width, 224f / crop.height)
        val width = crop.width * scale; val height = crop.height * scale
        canvas.drawBitmap(crop, null, RectF((224 - width) / 2f, (224 - height) / 2f, (224 + width) / 2f, (224 + height) / 2f), Paint(Paint.FILTER_BITMAP_FLAG))
        if (crop !== src) crop.recycle()
        return result
    }
}
