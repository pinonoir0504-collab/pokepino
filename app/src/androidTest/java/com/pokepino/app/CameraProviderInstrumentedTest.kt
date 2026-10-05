package com.pokepino.app

import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class CameraProviderInstrumentedTest {
    @Test
    fun cameraCacheFileCanBeSharedThroughFileProvider(){
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val file=File(context.cacheDir,"camera_provider_test.jpg")
        file.writeBytes(byteArrayOf(1,2,3,4))
        try{
            val uri=FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            assertEquals("content",uri.scheme)
            assertEquals("${context.packageName}.fileprovider",uri.authority)
            assertTrue(uri.path?.contains("camera_provider_test.jpg")==true)
        }finally{
            file.delete()
        }
    }
}
