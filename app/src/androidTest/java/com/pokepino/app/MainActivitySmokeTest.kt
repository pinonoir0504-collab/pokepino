package com.pokepino.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
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

    private fun exists(tag:String){
        rule.onNodeWithTag(tag).fetchSemanticsNode()
    }

    @Test
    fun launchAndNavigateMainTabs(){
        rule.waitUntil(timeoutMillis=8000){
            runCatching {
                rule.onNodeWithTag("tab_dex").fetchSemanticsNode()
                true
            }.getOrDefault(false)
        }

        rule.onNodeWithText("ポケピーノ").assertIsDisplayed()
        rule.onNodeWithTag("tab_dex").assertIsDisplayed()
        rule.onNodeWithText("名前・図鑑No.で検索").assertIsDisplayed()

        rule.onNodeWithTag("dex_1").performClick()
        exists("detail_screen")
        rule.onNodeWithTag("plus_PKP-00001").performClick()
        rule.waitUntil(timeoutMillis=3000){
            runCatching {
                rule.onNodeWithTag("owned_state_PKP-00001").fetchSemanticsNode()
                    .config.any { it.value.toString().contains("所持 ×1") }
            }.getOrDefault(false)
        }
        exists("owned_state_PKP-00001")
        rule.onNodeWithTag("detail_back").performClick()

        rule.onNodeWithTag("tab_owned").performClick()
        exists("owned_screen")
        exists("owned_item_PKP-00001")

        rule.onNodeWithTag("tab_photo").performClick()
        rule.onNodeWithText("写真で判定").assertIsDisplayed()
        rule.onNodeWithText("画像を選ぶ").assertIsDisplayed()
        rule.onNodeWithText("写真を撮る").assertIsDisplayed()

        rule.onNodeWithTag("tab_dex").performClick()
        rule.onNodeWithText("名前・図鑑No.で検索").assertIsDisplayed()
    }
}
