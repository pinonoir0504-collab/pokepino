package com.pokepino.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
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
        rule.onNodeWithText("名前・図鑑No.で検索").assertIsDisplayed()

        rule.onNodeWithText("No.001").performClick()
        rule.onNodeWithText("フシギダネ").assertIsDisplayed()
        rule.onAllNodesWithText("＋")[0].performClick()
        rule.onNodeWithText("所持 ×1").assertIsDisplayed()
        rule.onNodeWithText("←").performClick()

        rule.onNodeWithTag("tab_owned").performClick()
        rule.onNodeWithText("所持 1件").assertIsDisplayed()
        rule.onNodeWithText("フシギダネ").assertIsDisplayed()

        rule.onNodeWithTag("tab_photo").performClick()
        rule.onNodeWithText("写真で判定").assertIsDisplayed()
        rule.onNodeWithText("画像を選ぶ").assertIsDisplayed()
        rule.onNodeWithText("写真を撮る").assertIsDisplayed()

        rule.onNodeWithTag("tab_dex").performClick()
        rule.onNodeWithText("名前・図鑑No.で検索").assertIsDisplayed()
    }
}
