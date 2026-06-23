package com.wuyi.libraryauto

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test

class SmokeNavigationTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun appLaunchesIntoAccountListScreen() {
        composeRule.onNodeWithText("账号列表").assertIsDisplayed()
        composeRule.onNodeWithText("手动预约").assertIsDisplayed()
        composeRule.onNodeWithText("自动任务").assertIsDisplayed()
        composeRule.onNodeWithText("设置").assertIsDisplayed()
        composeRule.onNodeWithText("设置").performClick()
        composeRule.onNodeWithText("构建信息").assertIsDisplayed()
        composeRule.onNodeWithText("权限").assertIsDisplayed()
        composeRule.onNodeWithText("自动预约说明").assertIsDisplayed()
        composeRule.onNodeWithText("诊断日志").assertIsDisplayed()
        composeRule.onNodeWithText("权限").performClick()
        composeRule.onNodeWithText("申请权限").assertIsDisplayed()
        composeRule.onNodeWithText("无障碍入口").assertIsDisplayed()
    }
}
