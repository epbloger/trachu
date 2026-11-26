package ru.epbloger.trachu.ui

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.ConnectivityManager
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.epbloger.trachu.MainActivity
import ru.epbloger.trachu.core.Kind
import ru.epbloger.trachu.core.Money
import ru.epbloger.trachu.core.today
import ru.epbloger.trachu.data.ExchangeRates
import ru.epbloger.trachu.data.LedgerStore
import ru.epbloger.trachu.security.Preferences

@RunWith(AndroidJUnit4::class)
class NavigationTest {
    @get:Rule val rule=createAndroidComposeRule<MainActivity>()
    private fun capture(name:String) {
        rule.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(500,5_000)
        val directory=requireNotNull(rule.activity.getExternalFilesDir("qa"))
        directory.mkdirs()
        val bitmap=requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try {
            File(directory,"$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) }
        } finally { bitmap.recycle() }
    }
    // Network availability is external to the app. Preserve the deterministic
    // converter assertions and report a separate, real Android HTTPS result.
    private fun recordLiveRateProbe() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val directory=requireNotNull(context.getExternalFilesDir("qa"))
        directory.mkdirs()
        val result=runCatching {
            val connectivity=requireNotNull(context.getSystemService(ConnectivityManager::class.java))
            if(connectivity.activeNetwork==null) "NOT_RUN: no default network"
            else {
                val day=today()
                val rates=ExchangeRates.fetch("RUB",day)
                require(rates.all{it.source=="CBR"&&it.day<=day&&it.unitsOfBasePerUnit.toBigDecimal().signum()>0})
                require(rates.any{it.currency=="USD"}&&rates.any{it.currency=="EUR"})
                "PASS: Android HTTPS CBR feed, ${rates.size} positive rates, publication day ${rates.maxOf{it.day}}, requested day $day"
            }
        }.getOrElse{"UNCONFIRMED: ${it.javaClass.simpleName}: ${it.message.orEmpty().replace('\n',' ').take(240)}"}
        File(directory,"live-cbr.txt").writeText(result+"\n")
        android.util.Log.i("TrachuQA",result)
    }
    private fun selectStructure(income:Boolean){
        val tag=if(income)"structure-income" else "structure-expense"
        val list=rule.onNodeWithTag("analytics-list")
        list.performScrollToNode(hasTestTag(tag))
        rule.onNodeWithTag(tag).assertIsDisplayed().performClick().assertIsSelected()
        val opposite=if(income)"structure-expense" else "structure-income"
        rule.onNodeWithTag(opposite).assertIsNotSelected()
        list.performScrollToNode(hasTestTag(if(income)"income-structure" else "expense-structure"))
        rule.onNodeWithTag(if(income)"income-structure" else "expense-structure").assertExists()
        rule.onNodeWithTag(if(income)"expense-structure" else "income-structure").assertDoesNotExist()
        rule.onNodeWithContentDescription(if(income)"Диаграмма доходов" else "Диаграмма расходов").assertExists()
    }
    @Test fun recordExpenseAndNavigateAfterRotation() {
        val initialOrientation=rule.activity.resources.configuration.orientation
        val store=LedgerStore.get(InstrumentationRegistry.getInstrumentation().targetContext)
        rule.waitUntil(20_000){rule.onAllNodesWithTag("nav-home").fetchSemanticsNodes().isNotEmpty() ||
            rule.onAllNodesWithTag("onboarding-next").fetchSemanticsNodes().isNotEmpty()}
        if(rule.onAllNodesWithTag("onboarding-next").fetchSemanticsNodes().isNotEmpty()) {
            if(runBlocking{Preferences(InstrumentationRegistry.getInstrumentation().targetContext).current().notificationsEnabled==null}){
                rule.waitUntil(10_000){rule.onAllNodesWithTag("onboarding-notifications-skip").fetchSemanticsNodes().isNotEmpty()}
                rule.onNodeWithTag("onboarding-notifications-skip").performClick()
                rule.waitUntil(10_000){rule.onAllNodesWithTag("onboarding-notifications-skip").fetchSemanticsNodes().isEmpty()}
            }
            rule.onNodeWithTag("overview-ad-banner").assertDoesNotExist()
            rule.onNodeWithTag("onboarding-restore").assertIsDisplayed()
            rule.onNodeWithTag("onboarding-restore").performClick()
            rule.onNodeWithText("Пароль резервной копии").assertExists()
            rule.onNodeWithText("Отмена").performClick()
            rule.onNodeWithTag("field:Название счёта").performScrollTo().performTextInput("Тестовый кошелёк")
            rule.onNodeWithTag("field:Название счёта").performImeAction()
            rule.onNodeWithTag("field:Начальный остаток").performScrollTo().performTextReplacement("50")
            rule.onNodeWithTag("field:Начальный остаток").performImeAction()
            capture("onboarding")
            rule.onNodeWithTag("onboarding-next").performClick()
            rule.onNodeWithText("Как пользоваться ТРАЧУ.").assertExists()
            rule.onNodeWithText("Пропустить обучение").performScrollTo().performClick()
            rule.waitUntil(15_000){rule.onAllNodesWithTag("nav-home").fetchSemanticsNodes().isNotEmpty()}
            org.junit.Assert.assertEquals(5000L,runBlocking{store.current().accounts.single().openingMinor})
        } else if(runBlocking{store.current().accounts.none{it.name=="Тестовый кошелёк"}}) {
            rule.onNodeWithTag("nav-more").performClick()
            rule.onNodeWithTag("drawer-accounts").performScrollTo().performClick()
            rule.onNodeWithText("Добавить счёт").performScrollTo().performClick()
            rule.waitUntil(10_000){rule.onAllNodesWithTag("field:Название").fetchSemanticsNodes().isNotEmpty()}
            rule.onNodeWithTag("field:Название").performTextInput("Тестовый кошелёк")
            rule.onNodeWithTag("field:Название").performImeAction()
            rule.onNodeWithTag("form_save").performClick()
            rule.waitUntil(15_000){rule.onAllNodesWithTag("form_save").fetchSemanticsNodes().isEmpty()}
        }
        rule.waitUntil(15_000){runBlocking{store.current().accounts.any{it.name=="Тестовый кошелёк"}}}
        rule.onNodeWithTag("nav-home").performClick()
        rule.onNodeWithTag("add-expense").performScrollTo().performClick()
        rule.onNodeWithTag("overview-ad-banner").assertDoesNotExist()
        val testAccount=runBlocking{store.current().accounts.first{it.name=="Тестовый кошелёк"}}
        val selectedAccount=hasTestTag("choice:Счёт") and hasText(testAccount.name,substring=true)
        if(rule.onAllNodes(selectedAccount).fetchSemanticsNodes().isEmpty()) {
            rule.onNodeWithTag("choice:Счёт").performScrollTo().performClick()
            rule.onNodeWithTag("choice-option:Счёт:${testAccount.id}").assertTextContains("₽ RUB").performClick()
        }
        rule.onNodeWithTag("choice:Счёт").assertTextContains("₽ RUB")
        rule.onNodeWithTag("field:Сумма (RUB)").performTextInput("0")
        rule.onNodeWithTag("field:Сумма (RUB)").performImeAction()
        rule.onNodeWithTag("form_save").performClick()
        rule.onNodeWithText("Сумма должна быть положительной").assertExists()
        rule.onNodeWithTag("field:Сумма (RUB)").performTextReplacement("12.34")
        rule.onNodeWithTag("field:Сумма (RUB)").performImeAction()
        rule.onNodeWithTag("category-quick:transport").performScrollTo().performClick().assertIsSelected()
        capture("quick_entry")
        rule.onNodeWithTag("category-choice:Категория").performScrollTo().performClick()
        rule.onNodeWithTag("field:Поиск категории").performTextInput("Транспорт")
        rule.onNodeWithTag("field:Поиск категории").performImeAction()
        rule.onNodeWithTag("category-list").performScrollToNode(hasTestTag("category-option:transport"))
        rule.onNodeWithTag("category-option:transport").assertTextContains("Транспорт").performClick()
        // The switch itself is the actionable node; its label is exposed as semantics.
        rule.onNodeWithTag("toggle:Разделить по категориям").performScrollTo().performClick()
        rule.onNodeWithTag("field:Часть суммы 1").performScrollTo().performTextReplacement("5")
        rule.onNodeWithTag("field:Часть суммы 1").performImeAction()
        rule.onNodeWithText("Добавить часть").performScrollTo().performClick()
        rule.onNodeWithText("Заполнить остатком").performScrollTo().performClick()
        rule.onNodeWithTag("field:Часть суммы 2").performScrollTo().assertTextContains("7.34")
        rule.onNodeWithTag("category-choice:Категория 2").performScrollTo().performClick()
        rule.onNodeWithTag("category-list").performScrollToNode(hasTestTag("category-option:food"))
        rule.onNodeWithTag("category-option:food").performClick()
        rule.onNodeWithText("Осталось распределить: "+Money.format(0,"RUB")).assertExists()
        capture("split_entry")
        rule.onNodeWithTag("entry-details").performScrollTo().performClick()
        rule.onNodeWithTag("toggle:Запланировать").performScrollTo().performClick()
        rule.onNodeWithTag("toggle:Проводить автоматически").performScrollTo().assertIsOff().performClick().assertIsOn()
        rule.onNodeWithTag("toggle:Проводить автоматически").performClick().assertIsOff()
        rule.onNodeWithTag("toggle:Запланировать").performScrollTo().performClick().assertIsOff()
        rule.onNodeWithTag("form_save").performClick()
        rule.waitUntil(15_000){rule.onAllNodesWithTag("form_save").fetchSemanticsNodes().isEmpty()}
        rule.waitUntil(15_000){rule.onAllNodesWithTag("nav-operations").fetchSemanticsNodes().isNotEmpty()}
        rule.onNodeWithTag("add-income").performScrollTo().performClick()
        rule.onNodeWithTag("field:Сумма (RUB)").performTextInput("25")
        rule.onNodeWithTag("field:Сумма (RUB)").performImeAction()
        rule.onNodeWithTag("category-quick:salary").performScrollTo().performClick()
        rule.onNodeWithTag("form_save").performClick()
        rule.waitUntil(15_000){rule.onAllNodesWithTag("form_save").fetchSemanticsNodes().isEmpty()}
        rule.waitUntil(15_000){runBlocking{store.current().entries.any{it.kind==Kind.INCOME && it.accountId==testAccount.id && it.minor==2500L}}}
        capture("dashboard")
        if(initialOrientation==Configuration.ORIENTATION_PORTRAIT){
            rule.onNodeWithTag("nav-reports").performClick()
            rule.onNodeWithTag("analytics-list").performScrollToNode(hasTestTag("expense-structure"))
            if(rule.activity.resources.configuration.screenWidthDp<840){
                selectStructure(false)
                // Scroll to the selector's own item, rather than a large card ancestor.
                rule.onNodeWithTag("analytics-list").performScrollToIndex(3)
                rule.onNodeWithTag("structure-expense").assertIsSelected().assertIsDisplayed()
                rule.onNodeWithContentDescription("Диаграмма расходов").assertIsDisplayed()
                capture("analytics_expense")
                selectStructure(true)
                rule.onNodeWithText("Зарплата").assertExists()
                rule.onNodeWithTag("analytics-list").performScrollToIndex(3)
                rule.onNodeWithTag("structure-income").assertIsSelected().assertIsDisplayed()
                rule.onNodeWithContentDescription("Диаграмма доходов").assertIsDisplayed()
                capture("analytics_income")
                selectStructure(false)
            }
        }
        rule.onNodeWithTag("nav-operations").performClick()
        val expected="−"+Money.format(1234,"RUB")
        val amountMatcher=hasTestTag("operation-amount") and hasText(expected)
        val rowMatcher=hasTestTag("operation-row") and hasText(expected)
        rule.onNodeWithTag("field:Поиск по сумме, заметке, тегу").performTextInput("12.34")
        rule.onNodeWithTag("field:Поиск по сумме, заметке, тегу").performImeAction()
        for(kind in listOf("Все","Расход","Доход","Перевод"))
            rule.onNodeWithTag("operation-main-type:$kind").assertExists()
        rule.onNodeWithTag("operation-main-type:Расход").assertIsDisplayed().performClick().assertIsSelected()
        rule.onNodeWithTag("operations-list").performScrollToNode(rowMatcher or hasAnyDescendant(rowMatcher))
        rule.onNode(amountMatcher,useUnmergedTree=true).assertIsDisplayed()
        capture("operations")
        if(initialOrientation==Configuration.ORIENTATION_PORTRAIT && rule.activity.resources.configuration.screenWidthDp<760){
            rule.onNode(rowMatcher).performClick()
            rule.onNodeWithText("Редактировать",substring=false).performScrollTo().performClick()
            rule.onNodeWithTag("field:Сумма (RUB)").performScrollTo().assertTextContains("12.34")
            rule.onNodeWithTag("toggle:Разделить по категориям").performScrollTo().assertIsOn()
            capture("entry_edit")
            rule.onNodeWithText("Отмена").performClick()
            rule.waitUntil(15_000){rule.onAllNodesWithTag("form_save").fetchSemanticsNodes().isEmpty()}
        }
        rule.activityRule.scenario.recreate()
        rule.waitUntil(20_000){rule.onAllNodesWithTag("nav-operations").fetchSemanticsNodes().isNotEmpty()}
        rule.onNodeWithTag("nav-operations").assertIsSelected()
        rule.onNodeWithTag("operations-list").performScrollToNode(hasTestTag("field:Поиск по сумме, заметке, тегу") or hasAnyDescendant(hasTestTag("field:Поиск по сумме, заметке, тегу")))
        rule.onNodeWithTag("field:Поиск по сумме, заметке, тегу").assertTextContains("12.34")
        rule.onNodeWithTag("operation-main-type:Расход").assertIsSelected()
        rule.activity.runOnUiThread{rule.activity.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE}
        rule.waitUntil(20_000) {
            rule.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
                rule.onAllNodesWithTag("operations-list").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag("nav-operations").assertIsSelected()
        rule.onNodeWithTag("operations-list").performScrollToNode(hasTestTag("field:Поиск по сумме, заметке, тегу") or hasAnyDescendant(hasTestTag("field:Поиск по сумме, заметке, тегу")))
        rule.onNodeWithTag("field:Поиск по сумме, заметке, тегу").assertTextContains("12.34")
        rule.onNodeWithTag("operation-main-type:Расход").assertIsSelected()
        rule.onNodeWithTag("operations-list").performScrollToNode(rowMatcher or hasAnyDescendant(rowMatcher))
        rule.onNode(amountMatcher,useUnmergedTree=true).assertIsDisplayed()
        capture("operations_landscape")
        rule.onNodeWithTag("nav-reports").performClick()
        rule.onAllNodesWithText("Аналитика").onFirst().assertExists()
        rule.onNodeWithTag("analytics-list").performScrollToIndex(3)
        rule.onNodeWithTag("expense-structure").assertExists()
        if(rule.onAllNodesWithTag("structure-income").fetchSemanticsNodes().isNotEmpty()){
            selectStructure(true)
            selectStructure(false)
        }else rule.onNodeWithTag("income-structure").assertExists()
        capture("analytics_landscape")
        rule.onNodeWithTag("nav-plan").performClick()
        rule.onNodeWithTag("planner-group-payments").performScrollTo().performClick()
        rule.onNodeWithTag("planner-tab-calendar").performScrollTo().performClick()
        rule.onNodeWithTag("calendar-next").performScrollTo().performClick()
        rule.onNodeWithText("Добавить",substring=false).performScrollTo().performClick()
        rule.onNodeWithTag("field:Дата").performScrollTo().assertTextContains(java.time.YearMonth.now().plusMonths(1).atDay(1).toString())
        rule.onNodeWithText("Отмена").performClick()
        if(initialOrientation==Configuration.ORIENTATION_PORTRAIT){
            rule.activity.runOnUiThread{rule.activity.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_PORTRAIT}
            rule.waitUntil(20_000){rule.activity.resources.configuration.orientation==Configuration.ORIENTATION_PORTRAIT}
        }
        rule.onNodeWithTag("nav-more").performClick()
        rule.onNodeWithTag("overview-ad-banner").assertDoesNotExist()
        capture(if(initialOrientation==Configuration.ORIENTATION_PORTRAIT)"drawer" else "drawer_landscape")
        rule.onNodeWithTag("drawer-about").performScrollTo().performClick()
        rule.onNodeWithTag("help-tutorial").performScrollTo().performClick()
        rule.waitUntil(20_000){rule.onAllNodesWithTag("tutorial-target-expense").fetchSemanticsNodes().isNotEmpty()}
        capture(if(initialOrientation==Configuration.ORIENTATION_PORTRAIT)"tutorial" else "tutorial_landscape")
        rule.onNodeWithTag("tutorial-skip").performClick()
        rule.onNodeWithTag("nav-more").performClick()
        rule.onNodeWithTag("drawer-converter").performScrollTo().performClick()
        rule.onNodeWithTag("converter-list").assertExists()
        rule.onNodeWithTag("overview-ad-banner").assertDoesNotExist()
        rule.onNodeWithTag("converter-list").performScrollToNode(hasTestTag("field:Сумма (RUB)"))
        rule.onNodeWithTag("field:Сумма (RUB)").performTextReplacement("200")
        rule.onNodeWithTag("field:Сумма (RUB)").performImeAction()
        // The lazy list can place the result panel below the viewport.
        rule.onNodeWithTag("converter-list").performScrollToNode(hasTestTag("toggle:Ввести курс вручную"))
        rule.onNodeWithTag("toggle:Ввести курс вручную").performClick()
        rule.onNodeWithTag("converter-list").performScrollToNode(hasTestTag("field:USD за 1 RUB"))
        rule.onNodeWithTag("field:USD за 1 RUB").performTextReplacement("0.01")
        rule.onNodeWithTag("field:USD за 1 RUB").performImeAction()
        rule.onNodeWithTag("converter-list").performScrollToNode(hasText(Money.format(200,"USD")))
        rule.onNodeWithText(Money.format(200,"USD")).assertIsDisplayed()
        capture(if(initialOrientation==Configuration.ORIENTATION_PORTRAIT)"converter" else "converter_landscape")
        rule.onNodeWithTag("converter-list").performScrollToNode(hasTestTag("converter-swap"))
        rule.onNodeWithTag("converter-swap").performClick()
        rule.onNodeWithTag("converter-list").performScrollToNode(hasTestTag("field:Сумма (USD)"))
        rule.onNodeWithTag("field:Сумма (USD)").assertTextContains("2.00")
        rule.onNodeWithTag("converter-list").performScrollToNode(hasTestTag("field:RUB за 1 USD"))
        rule.onNodeWithTag("field:RUB за 1 USD").assertTextContains("100")
        rule.onNodeWithTag("converter-list").performScrollToNode(hasText(Money.format(20000,"RUB")))
        rule.onNodeWithText(Money.format(20000,"RUB")).assertIsDisplayed()
        rule.onNodeWithTag("converter-list").performScrollToNode(hasTestTag("converter-rates") or hasAnyDescendant(hasTestTag("converter-rates")))
        rule.onNodeWithTag("converter-rates").performClick()
        rule.onNodeWithText("Курсы валют").assertExists()
        rule.onNodeWithText("Конвертер",substring=false).performClick()
        rule.onNodeWithTag("converter-list").performScrollToNode(hasTestTag("field:Сумма (USD)"))
        rule.onNodeWithTag("field:Сумма (USD)").assertTextContains("2.00")
        val prefs=Preferences(InstrumentationRegistry.getInstrumentation().targetContext)
        val previousTheme=runBlocking{prefs.current().theme}
        try {
            rule.onNodeWithTag("nav-more").performClick()
            rule.onNodeWithTag("drawer-settings").performScrollTo().performClick()
            rule.onNodeWithTag("choice:Тема").performScrollTo().performClick()
            rule.onNodeWithTag("choice-option:Тема:dark").performClick()
            rule.waitUntil(20_000){runBlocking{prefs.current().theme}=="dark"}
            rule.onNodeWithTag("nav-home").performClick()
            capture(if(initialOrientation==Configuration.ORIENTATION_PORTRAIT)"dashboard_dark" else "dashboard_dark_landscape")
        } finally {
            runBlocking{prefs.theme(previousTheme)}
        }
        recordLiveRateProbe()
    }
}
