package com.pokepino.app

import android.app.Activity
import android.app.Instrumentation.ActivityResult
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.content.FileProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import java.io.File

class RuntimePhotoUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private fun capture(name: String) {
        rule.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val folder = File(instrumentation.targetContext.getExternalFilesDir(null), "validation").apply { mkdirs() }
        val image = instrumentation.uiAutomation.takeScreenshot()
        File(folder, name + ".png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }
    @Test fun selectPhotoAndShowActualRecognitionResult() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        rule.waitUntil(30000) { runCatching { rule.onNodeWithTag("tab_photo").fetchSemanticsNode(); true }.getOrDefault(false) }
        capture("01_dex")
        rule.onNodeWithTag("dex_1").performClick()
        capture("02_detail")
        rule.onNodeWithTag("detail_back").performClick()
        rule.onNodeWithTag("tab_owned").performClick()
        capture("03_owned")
        rule.onNodeWithTag("tab_photo").performClick()
        capture("04_photo")
        val file = File(context.cacheDir, "runtime_test.jpg")
        instrumentation.context.assets.open("validation_images/r1163936848.jpg").use { input -> file.outputStream().use { input.copyTo(it) } }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        Intents.init()
        try {
            intending(hasAction(Intent.ACTION_GET_CONTENT)).respondWith(ActivityResult(Activity.RESULT_OK, Intent().setData(uri)))
            rule.onNodeWithText("画像を選ぶ").performClick()
            rule.waitUntil(240000) { runCatching { rule.onNode(hasScrollAction()).performScrollToNode(hasText("総合判定の候補")); true }.getOrDefault(false) }
            rule.onNodeWithText("総合判定の候補").assertIsDisplayed()
            rule.onNode(hasScrollAction()).performScrollToNode(hasText("画像を選ぶ"))
            rule.onNodeWithText("画像を選ぶ").assertIsEnabled()
            rule.onNode(hasScrollAction()).performScrollToNode(hasText("総合判定の候補"))
            capture("05_recognition_result")
        } finally { Intents.release(); file.delete() }
    }
}
