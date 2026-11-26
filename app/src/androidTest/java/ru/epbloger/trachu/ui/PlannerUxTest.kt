package ru.epbloger.trachu.ui

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.epbloger.trachu.AppViewModel
import ru.epbloger.trachu.Editor
import ru.epbloger.trachu.MainActivity
import ru.epbloger.trachu.core.*
import ru.epbloger.trachu.data.LedgerStore
import ru.epbloger.trachu.security.Preferences
import java.io.File

@RunWith(AndroidJUnit4::class)
class PlannerUxTest {
    @get:Rule val rule=createAndroidComposeRule<MainActivity>()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val store get()=LedgerStore.get(context)
    @Before fun setup(){runBlocking{Preferences(context).completeOnboarding()}}
    private fun awaitTag(tag:String){
        rule.waitUntil(20_000){rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()}
    }
    private fun capture(name:String){
        rule.waitForIdle()
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        // Compose idleness alone does not wait for the system IME to finish closing.
        instrumentation.uiAutomation.waitForIdle(500,10_000)
        rule.waitForIdle()
        val directory=requireNotNull(context.getExternalFilesDir("qa"))
        directory.mkdirs()
        val bitmap=requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try{
            File(directory,"$name.png").outputStream().use{check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}
        }finally{bitmap.recycle()}
    }
    private fun awaitLedger(predicate:(Ledger)->Boolean){
        lateinit var vm:AppViewModel
        rule.activityRule.scenario.onActivity{vm=ViewModelProvider(it)[AppViewModel::class.java]}
        rule.waitUntil(20_000){vm.ledger.value?.let(predicate)==true}
    }
    private fun plannerReveal(tag:String):SemanticsNodeInteraction{
        rule.onNodeWithTag("planner-list").performScrollToNode(hasTestTag(tag))
        return rule.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
    }
    private fun plannerScrollTop(){
        rule.onNodeWithTag("planner-list").performScrollToIndex(0)
        rule.waitForIdle()
    }
    private fun plannerClick(tag:String){
        plannerReveal(tag).performClick()
    }
    private fun historyReveal(matcher:SemanticsMatcher){
        rule.onNodeWithTag("goal-history-list").performScrollToNode(matcher)
        rule.onNode(matcher).performScrollTo().assertIsDisplayed()
    }
    private fun enter(tag:String,value:String){
        rule.onNodeWithTag(tag).performScrollTo().performTextReplacement(value)
        rule.onNodeWithTag(tag).performImeAction()
    }
    private fun save(){
        rule.onNodeWithTag("form_save").performClick()
        rule.waitUntil(20_000){rule.onAllNodesWithTag("form_save").fetchSemanticsNodes().isEmpty()}
    }
    private fun openPlanner(){
        awaitTag("nav-plan")
        rule.onNodeWithTag("nav-plan").performClick()
        awaitTag("planner-list")
    }

    @Test fun groupedNavigationAndGoalAdjustments(){
        val goal=Goal(name="Отпуск",targetMinor=10000,savedMinor=1000)
        val account=Account(name="Накопительный счёт",currency="RUB",openingMinor=50000)
        runBlocking{store.change{it.copy(goals=it.goals+goal,accounts=it.accounts+account)}}
        try{
            awaitLedger{it.goals.any{g->g.id==goal.id}}
            openPlanner()
            rule.onNodeWithTag("planner-group-budget").assertExists()
            plannerClick("planner-group-payments")
            rule.onNodeWithTag("planner-tab-calendar").assertIsSelected()
            capture("planner_calendar")
            plannerClick("planner-tab-recurring")
            rule.onNodeWithTag("planner-tab-recurring").assertIsSelected()
            plannerClick("planner-group-savings")
            rule.onNodeWithTag("planner-tab-goal").assertIsSelected()
            val before=runBlocking{store.current()}
            fun assertNoFinancialChanges(after:Ledger){
                assertEquals(before.entries,after.entries)
                assertEquals(before.accounts,after.accounts)
                assertEquals(50000L,Finance.balance(after,account.id))
                before.accounts.forEach{a->assertEquals(Finance.balance(before,a.id),Finance.balance(after,a.id))}
            }
            plannerClick("goal-fund:${goal.id}")
            awaitTag("field:Сумма (RUB)")
            enter("field:Сумма (RUB)","25")
            capture("goal_funding")
            rule.activityRule.scenario.recreate()
            awaitTag("field:Сумма (RUB)")
            rule.onNodeWithTag("field:Сумма (RUB)").assertTextContains("25")
            save()
            awaitLedger{it.goals.find{g->g.id==goal.id}?.adjustments?.size==1}
            val fundedLedger=runBlocking{store.current()}
            assertNoFinancialChanges(fundedLedger)
            val funded=fundedLedger.goals.first{it.id==goal.id}
            assertEquals(3500L,Finance.goalSaved(funded))
            assertEquals(2500L,funded.adjustments.single().minor)
            plannerReveal("goal-fund:${goal.id}")
            plannerScrollTop()
            capture("goals")
            plannerClick("goal-history:${goal.id}")
            historyReveal(hasText("Пополнение",substring=false))
            historyReveal(hasText(Money.format(2500,"RUB"),substring=false))
            capture("goal_history")
            rule.onNodeWithText("Закрыть",substring=false).performClick()
            plannerClick("goal-withdraw:${goal.id}")
            awaitTag("field:Сумма (RUB)")
            enter("field:Сумма (RUB)","36")
            rule.onNodeWithTag("form_save").performClick()
            rule.onNodeWithText("Нельзя снять больше накопленной суммы").assertExists()
            assertNoFinancialChanges(runBlocking{store.current()})
            enter("field:Сумма (RUB)","5")
            save()
            awaitLedger{it.goals.find{g->g.id==goal.id}?.adjustments?.size==2}
            val afterLedger=runBlocking{store.current()}
            assertNoFinancialChanges(afterLedger)
            val after=afterLedger.goals.first{it.id==goal.id}
            assertEquals(3000L,Finance.goalSaved(after))
            assertEquals(listOf(2500L,-500L),after.adjustments.map{it.minor})
            assertEquals(goal.savedMinor,after.savedMinor)
            plannerClick("goal-history:${goal.id}")
            historyReveal(hasText("Снятие",substring=false))
            historyReveal(hasText(Money.format(-500,"RUB"),substring=false))
            rule.onNodeWithText("Закрыть",substring=false).performClick()
            // A separate persisted long history, including the actual oldest adjustment,
            // proves the dialog is not limited to the first visible rows.
            val older=(1..20).map{GoalAdjustment(minor=100L,note="Архивная запись $it")}
            runBlocking{store.change{l->l.copy(goals=l.goals.map{g->if(g.id==goal.id)g.copy(adjustments=older+g.adjustments) else g})}}
            awaitLedger{it.goals.find{g->g.id==goal.id}?.adjustments?.size==22}
            plannerClick("goal-history:${goal.id}")
            historyReveal(hasText("Архивная запись 1",substring=false))
            rule.onNodeWithTag("goal-history-entry:${older.first().id}").assertExists()
            historyReveal(hasText("Архивная запись 20",substring=false))
            rule.onNodeWithText("Закрыть",substring=false).performClick()
            assertNoFinancialChanges(runBlocking{store.current()})
        }finally{
            rule.activityRule.scenario.onActivity{ViewModelProvider(it)[AppViewModel::class.java].editor=null}
            runBlocking{store.change{it.copy(goals=it.goals.filterNot{g->g.id==goal.id},accounts=it.accounts.filterNot{a->a.id==account.id},entries=it.entries.filterNot{e->e.accountId==account.id||e.targetAccountId==account.id})}}
        }
    }

    @Test fun deletingRecordedDebtExplainsItsAccountBalanceEffect(){
        val account=Account(name="Счёт для займа ${newId()}",currency=runBlocking{store.current().baseCurrency},openingMinor=50000)
        val debt=Debt(person="Проверка удаления",accountId=account.id,owedByMe=true,principalMinor=10000,openedDay=today())
        val seeded=runBlocking{store.current().copy(accounts=store.current().accounts+account,debts=store.current().debts+debt)}
        val opening=Finance.makeEntry(seeded,Kind.LOAN_IN,account.id,debt.principalMinor,today(),
            note="Получение займа",debtId=debt.id,principalMinor=debt.principalMinor)
        runBlocking{store.change{it.copy(accounts=it.accounts+account,debts=it.debts+debt,entries=it.entries+opening)}}
        try{
            awaitLedger{it.debts.any{d->d.id==debt.id}&&it.entries.any{e->e.id==opening.id}}
            rule.activityRule.scenario.onActivity{ViewModelProvider(it)[AppViewModel::class.java].editor=Editor("debt",debt.id)}
            awaitTag("field:Кому я должен")
            rule.onNodeWithText("Удалить долг").performScrollTo().performClick()
            rule.onNodeWithText("Связанная запись получения или выдачи денег тоже будет удалена, и остаток счёта изменится.")
                .assertIsDisplayed()
            rule.onNodeWithText("Подтвердить").performClick()
            awaitLedger{l->l.debts.none{it.id==debt.id}&&l.entries.none{it.debtId==debt.id}}
            val after=runBlocking{store.current()}
            assertFalse(after.debts.any{it.id==debt.id})
            assertFalse(after.entries.any{it.debtId==debt.id})
            assertEquals(50000L,Finance.balance(after,account.id))
        }finally{
            rule.activityRule.scenario.onActivity{ViewModelProvider(it)[AppViewModel::class.java].editor=null}
            runBlocking{store.change{it.copy(entries=it.entries.filterNot{e->e.debtId==debt.id},debts=it.debts.filterNot{d->d.id==debt.id},accounts=it.accounts.filterNot{a->a.id==account.id})}}
        }
    }

    @Test fun deletingDebtCannotRemoveAPaymentRecordedAfterConfirmationOpened(){
        val account=Account(name="Счёт с параллельным платежом ${newId()}",currency=runBlocking{store.current().baseCurrency},openingMinor=50000)
        val openedDay=today()-5
        val debt=Debt(person="Проверка параллельного платежа",accountId=account.id,owedByMe=true,principalMinor=10000,openedDay=openedDay)
        val seeded=runBlocking{store.current().copy(accounts=store.current().accounts+account,debts=store.current().debts+debt)}
        val opening=Finance.makeEntry(seeded,Kind.LOAN_IN,account.id,10000,openedDay,debtId=debt.id,principalMinor=10000)
        runBlocking{store.change{it.copy(accounts=it.accounts+account,debts=it.debts+debt,entries=it.entries+opening)}}
        try{
            awaitLedger{it.debts.any{d->d.id==debt.id}&&it.entries.any{e->e.id==opening.id}}
            rule.activityRule.scenario.onActivity{ViewModelProvider(it)[AppViewModel::class.java].editor=Editor("debt",debt.id)}
            awaitTag("field:Кому я должен")
            rule.onNodeWithText("Удалить долг").performScrollTo().performClick()
            rule.onNodeWithText("Связанная запись получения или выдачи денег тоже будет удалена, и остаток счёта изменится.").assertIsDisplayed()
            val beforePayment=runBlocking{store.current()}
            val payment=Finance.makeEntry(beforePayment,Kind.DEBT_OUT,account.id,2000,today(),debtId=debt.id,principalMinor=2000)
            runBlocking{store.change{Finance.putEntry(it,payment)}}
            rule.onNodeWithText("Подтвердить").performClick()
            rule.waitUntil(10_000){rule.onAllNodesWithText("По долгу уже есть платежи. Удалить его нельзя.").fetchSemanticsNodes().isNotEmpty()}
            val after=runBlocking{store.current()}
            assertTrue(after.debts.any{it.id==debt.id})
            assertTrue(after.entries.any{it.id==payment.id})
            assertEquals(58000L,Finance.balance(after,account.id))
        }finally{
            rule.activityRule.scenario.onActivity{ViewModelProvider(it)[AppViewModel::class.java].editor=null;ViewModelProvider(it)[AppViewModel::class.java].clearMessage()}
            runBlocking{store.change{it.copy(entries=it.entries.filterNot{e->e.debtId==debt.id},debts=it.debts.filterNot{d->d.id==debt.id},accounts=it.accounts.filterNot{a->a.id==account.id})}}
        }
    }

    @Test fun debtEditorRejectsStaleChangesInsteadOfOverwritingTheLatestDebt(){
        val account=Account(name="Счёт для проверки долга ${newId()}",currency=runBlocking{store.current().baseCurrency},openingMinor=50000)
        val debt=Debt(person="Исходный кредитор",accountId=account.id,owedByMe=true,principalMinor=10000,note="Исходная запись")
        runBlocking{store.change{it.copy(accounts=it.accounts+account,debts=it.debts+debt)}}
        try{
            awaitLedger{it.debts.any{d->d.id==debt.id}}
            rule.activityRule.scenario.onActivity{ViewModelProvider(it)[AppViewModel::class.java].editor=Editor("debt",debt.id)}
            awaitTag("field:Кому я должен")
            runBlocking{store.change{l->l.copy(debts=l.debts.map{if(it.id==debt.id)it.copy(note="Обновлено отдельно") else it})}}
            rule.onNodeWithTag("form_save").performClick()
            rule.waitUntil(10_000){rule.onAllNodesWithText("Долг изменился. Откройте его снова.").fetchSemanticsNodes().isNotEmpty()}
            rule.onNodeWithText("Долг изменился. Откройте его снова.").assertExists()
            val latest=runBlocking{store.current().debts.single{it.id==debt.id}}
            assertEquals("Обновлено отдельно",latest.note)
            assertEquals(10000L,latest.principalMinor)
        }finally{
            rule.activityRule.scenario.onActivity{ViewModelProvider(it)[AppViewModel::class.java].editor=null;ViewModelProvider(it)[AppViewModel::class.java].clearMessage()}
            runBlocking{store.change{it.copy(debts=it.debts.filterNot{d->d.id==debt.id},accounts=it.accounts.filterNot{a->a.id==account.id})}}
        }
    }

    @Test fun principalAndInterestAreRecordedExactlyOnce(){
        val account=Account(name="Основная карта",currency=runBlocking{store.current().baseCurrency},openingMinor=50000)
        val debt=Debt(person="Алексей (пример)",accountId=account.id,owedByMe=true,principalMinor=10000)
        runBlocking{store.change{it.copy(accounts=it.accounts+account,debts=it.debts+debt)}}
        try{
            awaitLedger{it.debts.any{d->d.id==debt.id}}
            openPlanner()
            plannerClick("planner-group-savings")
            plannerClick("planner-tab-debt")
            plannerReveal("debt-pay:${debt.id}")
            plannerScrollTop()
            capture("debts")
            plannerClick("debt-pay:${debt.id}")
            awaitTag("field:Основной долг")
            val principal=Money.input(2500,account.currency)
            val interest=Money.input(500,account.currency)
            enter("field:Основной долг",principal)
            rule.onNodeWithText("Добавить проценты").performScrollTo().performClick()
            enter("field:Проценты",interest)
            enter("field:Основной долг","-1")
            rule.onNodeWithTag("form_save").performClick()
            rule.onNodeWithText("Сумма погашения не может быть отрицательной").assertExists()
            enter("field:Основной долг",principal)
            enter("field:Проценты","-1")
            rule.onNodeWithTag("form_save").performClick()
            rule.onNodeWithText("Проценты не могут быть отрицательными").assertExists()
            enter("field:Проценты",interest)
            rule.onNodeWithText("Всего списать: "+Money.format(3000,account.currency)).performScrollTo().assertIsDisplayed()
            rule.onNodeWithText("Останется: "+Money.format(7500,account.currency)).performScrollTo().assertIsDisplayed()
            capture("debt_payment")
            save()
            val after=runBlocking{store.current()}
            val payment=after.entries.single{it.debtId==debt.id}
            assertEquals(Kind.DEBT_OUT,payment.kind)
            assertEquals(2500L,payment.principalMinor)
            assertEquals(3000L,payment.minor)
            assertEquals(7500L,Finance.debtRemaining(after,debt))
            assertEquals(47000L,Finance.balance(after,account.id))
            assertEquals(500L,Finance.totals(after.copy(entries=listOf(payment)),Period(today(),today())).expense)
        }finally{
            rule.activityRule.scenario.onActivity{ViewModelProvider(it)[AppViewModel::class.java].editor=null}
            runBlocking{store.change{it.copy(entries=it.entries.filterNot{e->e.debtId==debt.id},debts=it.debts.filterNot{d->d.id==debt.id},accounts=it.accounts.filterNot{a->a.id==account.id})}}
        }
    }

    @Test fun debtInterestCanBePaidAfterPrincipalIsCleared(){
        val currency=runBlocking{store.current().baseCurrency}
        val account=Account(name="Счёт для процентов",currency=currency,openingMinor=50000)
        val openedDay=today()-10
        val paymentDay=today()-5
        val debt=Debt(person="Кредитор (пример)",accountId=account.id,owedByMe=true,
            principalMinor=10000,annualPercent="36.5",openedDay=openedDay)
        runBlocking{
            store.change{old->
                val withDebt=old.copy(accounts=old.accounts+account,debts=old.debts+debt)
                val opening=Finance.makeEntry(withDebt,Kind.LOAN_IN,account.id,debt.principalMinor,openedDay,
                    debtId=debt.id,principalMinor=debt.principalMinor)
                val withOpening=Finance.putEntry(withDebt,opening)
                val principalPayment=Finance.makeEntry(withOpening,Kind.DEBT_OUT,account.id,10010,paymentDay,
                    debtId=debt.id,principalMinor=10000)
                Finance.putEntry(withOpening,principalPayment)
            }
        }
        try{
            awaitLedger{it.debts.any{d->d.id==debt.id}&&Finance.debtRemaining(it,debt)==0L}
            val before=runBlocking{store.current()}
            assertEquals(50L,Finance.accruedInterest(before,debt))
            assertEquals(10L,Finance.paidInterest(before,debt))
            assertEquals(40L,Finance.accruedInterest(before,debt)!!-Finance.paidInterest(before,debt))
            openPlanner()
            plannerClick("planner-group-savings")
            plannerClick("planner-tab-debt")
            plannerReveal("debt-pay:${debt.id}").assertIsEnabled().performClick()
            awaitTag("field:Основной долг")
            enter("field:Основной долг","0")
            enter("field:Проценты",Money.input(40,currency))
            rule.onNodeWithText("Всего списать: "+Money.format(40,currency)).performScrollTo().assertIsDisplayed()
            save()
            awaitLedger{it.entries.count{e->e.debtId==debt.id}==3}
            val after=runBlocking{store.current()}
            val interestPayment=after.entries.single{it.debtId==debt.id&&it.principalMinor==0L}
            assertEquals(40L,interestPayment.minor)
            assertEquals(0L,Finance.debtRemaining(after,debt))
            assertEquals(50L,Finance.paidInterest(after,debt))
            // Other navigation tests keep their own operations in the shared ledger.
            // Check both this debt's interest and the change in the complete report.
            val period=Period(paymentDay,today())
            val debtEntries=after.entries.filter{it.debtId==debt.id}
            assertEquals(50L,Finance.totals(after.copy(entries=debtEntries),period).expense)
            assertEquals(Money.add(Finance.totals(before,period).expense,40L),Finance.totals(after,period).expense)
            assertEquals(49950L,Finance.balance(after,account.id))
            plannerReveal("debt-pay:${debt.id}").assertIsNotEnabled()
        }finally{
            rule.activityRule.scenario.onActivity{ViewModelProvider(it)[AppViewModel::class.java].editor=null}
            runBlocking{store.change{it.copy(entries=it.entries.filterNot{e->e.debtId==debt.id},debts=it.debts.filterNot{d->d.id==debt.id},accounts=it.accounts.filterNot{a->a.id==account.id})}}
        }
    }
}
