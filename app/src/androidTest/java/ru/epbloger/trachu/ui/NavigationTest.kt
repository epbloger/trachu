package ru.epbloger.trachu.ui

import android.content.pm.ActivityInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.epbloger.trachu.MainActivity
import ru.epbloger.trachu.core.Money

@RunWith(AndroidJUnit4::class)
class NavigationTest {
    @get:Rule val rule=createAndroidComposeRule<MainActivity>()
    @Test fun recordExpenseAndNavigateAfterRotation() {
        rule.waitUntil(20_000){rule.onAllNodesWithTag("add-expense").fetchSemanticsNodes().isNotEmpty()}
        rule.onNodeWithTag("add-expense").performClick()
        rule.waitUntil(10_000){rule.onAllNodesWithTag("field:Название").fetchSemanticsNodes().isNotEmpty()}
        rule.onNodeWithTag("field:Название").performTextInput("Тестовый кошелёк")
        rule.onNodeWithTag("form_save").performClick()
        rule.waitUntil(15_000){rule.onAllNodesWithText("Тестовый кошелёк").fetchSemanticsNodes().isNotEmpty()}
        rule.onNodeWithTag("nav-home").performClick()
        rule.onNodeWithTag("add-expense").performClick()
        rule.onNodeWithTag("field:Сумма (RUB)").performTextInput("12.34")
        rule.onNodeWithTag("form_save").performClick()
        rule.waitUntil(15_000){rule.onAllNodesWithTag("nav-operations").fetchSemanticsNodes().isNotEmpty()}
        rule.onNodeWithTag("nav-operations").performClick()
        val expected="−"+Money.format(1234,"RUB")
        rule.waitUntil(15_000){rule.onAllNodesWithText(expected).fetchSemanticsNodes().isNotEmpty()}
        rule.activity.runOnUiThread{rule.activity.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE}
        rule.waitForIdle()
        rule.onNodeWithTag("nav-operations").assertExists()
        rule.onNodeWithText(expected).assertExists()
        rule.onNodeWithTag("nav-reports").performClick()
        rule.onNodeWithText("Аналитика").assertExists()
    }
}
