package ru.epbloger.trachu.ui

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
import ru.epbloger.trachu.MainActivity
import ru.epbloger.trachu.core.*
import ru.epbloger.trachu.data.LedgerStore
import ru.epbloger.trachu.security.Preferences

@RunWith(AndroidJUnit4::class)
class PlannerUxTest {
    @get:Rule val rule=createAndroidComposeRule<MainActivity>()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val store get()=LedgerStore.get(context)
    @Before fun setup(){runBlocking{Preferences(context).completeOnboarding()}}
    private fun awaitTag(tag:String){
        rule.waitUntil(20_000){rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()}
    }
    private fun awaitLedger(predicate:(Ledger)->Boolean){
        lateinit var vm:AppViewModel
        rule.activityRule.scenario.onActivity{vm=ViewModelProvider(it)[AppViewModel::class.java]}
        rule.waitUntil(20_000){vm.ledger.value?.let(predicate)==true}
    }
    private fun plannerClick(tag:String){
        rule.onNodeWithTag("planner-list").performScrollToNode(hasTestTag(tag) or hasAnyDescendant(hasTestTag(tag)))
        rule.onNodeWithTag(tag).performClick()
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
        val goal=Goal(name="Цель UX ${newId()}",targetMinor=10000,savedMinor=1000)
        val account=Account(name="Накопительный счёт UX ${newId()}",currency="RUB",openingMinor=50000)
        runBlocking{store.change{it.copy(goals=it.goals+goal,accounts=it.accounts+account)}}
        try{
            awaitLedger{it.goals.any{g->g.id==goal.id}}
            openPlanner()
            rule.onNodeWithTag("planner-group-budget").assertExists()
            plannerClick("planner-group-payments")
            rule.onNodeWithTag("planner-tab-calendar").assertIsSelected()
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
        }finally{
            rule.activityRule.scenario.onActivity{ViewModelProvider(it)[AppViewModel::class.java].editor=null}
            runBlocking{store.change{it.copy(goals=it.goals.filterNot{g->g.id==goal.id},accounts=it.accounts.filterNot{a->a.id==account.id},entries=it.entries.filterNot{e->e.accountId==account.id||e.targetAccountId==account.id})}}
        }
    }

    @Test fun principalAndInterestAreRecordedExactlyOnce(){
        val account=Account(name="Счёт UX ${newId()}",currency=runBlocking{store.current().baseCurrency},openingMinor=50000)
        val debt=Debt(person="Долг UX ${newId()}",accountId=account.id,owedByMe=true,principalMinor=10000)
        runBlocking{store.change{it.copy(accounts=it.accounts+account,debts=it.debts+debt)}}
        try{
            awaitLedger{it.debts.any{d->d.id==debt.id}}
            openPlanner()
            plannerClick("planner-group-savings")
            plannerClick("planner-tab-debt")
            plannerClick("debt-pay:${debt.id}")
            awaitTag("field:Основной долг")
            val principal=Money.input(2500,account.currency)
            val interest=Money.input(500,account.currency)
            enter("field:Основной долг",principal)
            rule.onNodeWithText("Добавить проценты").performScrollTo().performClick()
            enter("field:Проценты",interest)
            rule.onNodeWithText("Всего списать: "+Money.format(3000,account.currency)).performScrollTo().assertIsDisplayed()
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
}
