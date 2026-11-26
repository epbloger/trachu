package ru.epbloger.trachu.ui

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
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
    private fun capture(name:String) {
        rule.waitForIdle()
        val directory=requireNotNull(rule.activity.getExternalFilesDir("qa"))
        directory.mkdirs()
        val bitmap=requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try {
            File(directory,"$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) }
        } finally { bitmap.recycle() }
    }
    @Test fun recordExpenseAndNavigateAfterRotation() {
        rule.waitUntil(20_000){rule.onAllNodesWithTag("add-expense").fetchSemanticsNodes().isNotEmpty()}
        rule.onNodeWithTag("add-expense").performClick()
        rule.waitUntil(10_000){rule.onAllNodesWithTag("field:Название").fetchSemanticsNodes().isNotEmpty()}
        rule.onNodeWithTag("field:Название").performTextInput("Тестовый кошелёк")
        rule.onNodeWithTag("field:Название").performImeAction()
        rule.onNodeWithTag("form_save").performClick()
        rule.waitUntil(15_000){rule.onAllNodesWithTag("form_save").fetchSemanticsNodes().isEmpty()}
        rule.waitUntil(15_000){rule.onAllNodesWithText("Тестовый кошелёк").fetchSemanticsNodes().isNotEmpty()}
        rule.onNodeWithTag("nav-home").performClick()
        rule.onNodeWithTag("add-expense").performClick()
        rule.onNodeWithTag("field:Сумма (RUB)").performTextInput("0")
        rule.onNodeWithTag("field:Сумма (RUB)").performImeAction()
        rule.onNodeWithTag("form_save").performClick()
        rule.onNodeWithText("Сумма должна быть положительной").assertExists()
        rule.onNodeWithTag("field:Сумма (RUB)").performTextReplacement("12.34")
        rule.onNodeWithTag("field:Сумма (RUB)").performImeAction()
        rule.onNodeWithTag("category-choice:Категория").performScrollTo().performClick()
        rule.onNodeWithTag("field:Поиск категории").performTextInput("Транспорт")
        rule.onNodeWithTag("field:Поиск категории").performImeAction()
        rule.onNodeWithTag("category-list").performScrollToNode(hasTestTag("category-option:transport"))
        rule.onNodeWithTag("category-option:transport").assertTextContains("Транспорт").performClick()
        // The switch itself is the actionable node; its label is exposed as semantics.
        rule.onNodeWithTag("toggle:Разделить по категориям").performScrollTo().performClick()
        rule.onNodeWithTag("field:Часть суммы 1").performScrollTo().performTextReplacement("5")
        rule.onNodeWithTag("field:Часть суммы 1").performImeAction()
        rule.onNodeWithText("Добавить категорию").performScrollTo().performClick()
        rule.onNodeWithText("Заполнить остатком").performScrollTo().performClick()
        rule.onNodeWithTag("field:Часть суммы 2").performScrollTo().assertTextContains("7.34")
        rule.onNodeWithText("Осталось распределить: "+Money.format(0,"RUB")).assertExists()
        capture("split_entry")
        rule.onNodeWithText("Дата, комментарий, теги и чек").performScrollTo().performClick()
        rule.onNodeWithTag("toggle:Запланировать").performScrollTo().performClick()
        rule.onNodeWithTag("toggle:Проводить автоматически").performScrollTo().assertIsOff().performClick().assertIsOn()
        rule.onNodeWithTag("toggle:Проводить автоматически").performClick().assertIsOff()
        rule.onNodeWithTag("toggle:Запланировать").performScrollTo().performClick().assertIsOff()
        rule.onNodeWithTag("form_save").performClick()
        rule.waitUntil(15_000){rule.onAllNodesWithTag("form_save").fetchSemanticsNodes().isEmpty()}
        rule.waitUntil(15_000){rule.onAllNodesWithTag("nav-operations").fetchSemanticsNodes().isNotEmpty()}
        capture("dashboard")
        rule.onNodeWithTag("nav-operations").performClick()
        val expected="−"+Money.format(1234,"RUB")
        val amountMatcher=hasTestTag("operation-amount") and hasText(expected)
        val rowMatcher=hasTestTag("operation-row") and hasText(expected)
        rule.onNodeWithTag("field:Поиск по сумме, заметке, тегу").performTextInput("12.34")
        rule.onNodeWithTag("field:Поиск по сумме, заметке, тегу").performImeAction()
        rule.onNodeWithText("Расход",substring=false).performClick()
        rule.onNodeWithTag("operations-list").performScrollToNode(rowMatcher or hasAnyDescendant(rowMatcher))
        rule.onNode(amountMatcher,useUnmergedTree=true).assertIsDisplayed()
        capture("operations")
        rule.activityRule.scenario.recreate()
        rule.waitUntil(20_000){rule.onAllNodesWithTag("nav-operations").fetchSemanticsNodes().isNotEmpty()}
        rule.onNodeWithTag("nav-operations").assertIsSelected()
        rule.onNodeWithTag("operations-list").performScrollToNode(hasTestTag("field:Поиск по сумме, заметке, тегу") or hasAnyDescendant(hasTestTag("field:Поиск по сумме, заметке, тегу")))
        rule.onNodeWithTag("field:Поиск по сумме, заметке, тегу").assertTextContains("12.34")
        rule.onNodeWithText("Расход",substring=false).assertIsSelected()
        rule.activity.runOnUiThread{rule.activity.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE}
        rule.waitUntil(20_000) {
            rule.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
                rule.onAllNodesWithTag("operations-list").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag("nav-operations").assertIsSelected()
        rule.onNodeWithTag("operations-list").performScrollToNode(hasTestTag("field:Поиск по сумме, заметке, тегу") or hasAnyDescendant(hasTestTag("field:Поиск по сумме, заметке, тегу")))
        rule.onNodeWithTag("field:Поиск по сумме, заметке, тегу").assertTextContains("12.34")
        rule.onNodeWithText("Расход",substring=false).assertIsSelected()
        rule.onNodeWithTag("operations-list").performScrollToNode(rowMatcher or hasAnyDescendant(rowMatcher))
        rule.onNode(amountMatcher,useUnmergedTree=true).assertIsDisplayed()
        capture("operations_landscape")
        rule.onNodeWithTag("nav-reports").performClick()
        rule.onNodeWithText("Аналитика").assertExists()
        capture("analytics_landscape")
    }
}
