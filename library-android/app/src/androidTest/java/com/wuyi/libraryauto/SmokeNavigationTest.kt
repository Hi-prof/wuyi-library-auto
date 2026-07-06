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
    fun appLaunchesIntoTodayOverviewScreenAndPrimaryDestinationsAreReachable() {
        composeRule.onNodeWithText("首页").assertIsDisplayed()
        composeRule.onNodeWithText("今日概览").assertIsDisplayed()
        composeRule.onNodeWithText("总账号数").assertIsDisplayed()
        composeRule.onNodeWithText("已预约座位").assertIsDisplayed()
        composeRule.onNodeWithText("一键检查预约").assertIsDisplayed()
        composeRule.onNodeWithText("一键签到").assertIsDisplayed()

        composeRule.onNodeWithText("账号").assertIsDisplayed()
        composeRule.onNodeWithText("预约").assertIsDisplayed()
        composeRule.onNodeWithText("任务").assertIsDisplayed()
        composeRule.onNodeWithText("设置").assertIsDisplayed()

        composeRule.onNodeWithText("预约").performClick()
        composeRule.onNodeWithText("选择账号").assertIsDisplayed()

        composeRule.onNodeWithText("任务").performClick()
        composeRule.onNodeWithText("还没有可用账号").assertIsDisplayed()

        composeRule.onNodeWithText("账号").performClick()
        composeRule.onNodeWithText("账号列表").assertIsDisplayed()

        composeRule.onNodeWithText("设置").performClick()
        composeRule.onNodeWithText("设置中心").assertIsDisplayed()
        composeRule.onNodeWithText("权限").assertIsDisplayed()
    }
}
