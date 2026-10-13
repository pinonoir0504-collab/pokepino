package com.pokepino.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EmbeddingRecognizerFramingTest {
    @Test
    fun scanComparesFourFramingsAndReturnsFiniteCandidates() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(238, 232, 218))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(40, 120, 210) }
        canvas.drawOval(255f, 70f, 380f, 390f, paint)
        paint.color = Color.rgb(245, 190, 35)
        canvas.drawCircle(318f, 120f, 48f, paint)

        val recognizer = EmbeddingRecognizer(context)
        try {
            val result = recognizer.recognize(bitmap)
            assertEquals("legacy crop + full-frame + center crop + foreground letterbox", 4, result.queryViews.size)
            assertFalse(result.speciesCandidates.isEmpty())
            assertTrue(result.speciesCandidates.all { it.score.isFinite() })
        } finally {
            recognizer.close()
            bitmap.recycle()
        }
    }
}
