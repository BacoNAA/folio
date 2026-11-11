package com.baconaa.folio

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeNavigationTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun opensQuickNoteAndSwitchesBetweenBothTabs() {
        val quickNote = compose.onNodeWithText("随手记")
        val records = compose.onNodeWithText("记录")
        quickNote.assertIsSelected()
        compose.onNodeWithText("文字编辑尚未开放").assertIsDisplayed()

        records.performClick()
        records.assertIsSelected()
        compose.onNodeWithText("记录浏览尚未开放").assertIsDisplayed()

        quickNote.performClick()
        quickNote.assertIsSelected()
        compose.onNodeWithText("文字编辑尚未开放").assertIsDisplayed()
    }
}
