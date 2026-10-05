package com.pokepino.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivitySmokeTest {
    @get:Rule
    val rule=createAndroidComposeRule<MainActivity>()

    @Test
    fun launchAndNavigateMainTabs(){
        rule.waitUntil(timeoutMillis=8000){
            runCatching {
                rule.onNodeWithText("ポケピーノ").fetchSemanticsNode()
                true
            }.getOrDefault(false)
        }

        rule.onNodeWithText("ポケピーノ").assertIsDisplayed()
        rule.onNodeWithText("図鑑").assertIsDisplayed()
        rule.onNodeWithText("所持").performClick()
        rule.onNodeWithText("所持 0件").assertIsDisplayed()
        rule.onNodeWithText("判定").performClick()
        rule.onNodeWithText("写真で判定").assertIsDisplayed()
        rule.onNodeWithText("画像を選ぶ").assertIsDisplayed()
        rule.onNodeWithText("写真を撮る").assertIsDisplayed()
        rule.onNodeWithText("図鑑").performClick()
        rule.onNodeWithText("名前・図鑑No.で検索").assertIsDisplayed()
    }
}
