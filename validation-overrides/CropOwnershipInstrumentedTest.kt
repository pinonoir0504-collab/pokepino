package com.pokepino.app

import android.graphics.Bitmap
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Test

class CropOwnershipInstrumentedTest {
    @Test fun releasingFullImageCropPreservesDisplayedSource() {
        checkCrop(0, 0, 128, 80)
    }
    @Test fun releasingPartialCropPreservesDisplayedSource() {
        checkCrop(8, 4, 60, 40)
    }
    private fun checkCrop(left: Int, top: Int, width: Int, height: Int) {
        val source = Bitmap.createBitmap(128, 80, Bitmap.Config.ARGB_8888)
        try {
            val crop = ImageViews.ownedCrop(source, left, top, width, height)
            assertNotSame(source, crop)
            crop.recycle()
            assertFalse(source.isRecycled)
            source.getPixel(0, 0)
        } finally { source.recycle() }
    }
}
