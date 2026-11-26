package ru.epbloger.trachu.core

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class AdvancedFinanceTest {
    private fun d(s:String)=LocalDate.parse(s).toEpochDay()
    private val day=d("2026-11-01")
    private val a=Account("a","Карта",openingMinor=1000000)
    private val usd=Account("usd","USD","USD",openingMinor=10000)
    private val eur=Account("eur","EUR","EUR")
    private val l=Ledger.initial().copy(accounts=listOf(a,usd,eur),rates=listOf(FxRate("USD",day,"90.1234"),FxRate("EUR",day,"100")))
    @Test fun historicalRatesAndCrossCurrencyTransfer() {
        val expense=Finance.makeEntry(l,Kind.EXPENSE,"usd",1234,day,listOf("food" to 1234))
        val state=Finance.putEntry(l,expense)
        val newer=state.copy(rates=state.rates+FxRate("USD",day+1,"110"))
        assertEquals(111212L,expense.baseMinor)
        assertEquals(expense.baseMinor,Finance.totals(newer,Period.month(day)).expense)
        val transfer=Finance.makeEntry(newer,Kind.TRANSFER,"usd",1000,day+1,targetId="eur",targetMinor=900)
        val next=Finance.putEntry(newer,transfer)
        assertEquals(7766L,Finance.balance(next,"usd",day+1))
        assertEquals(900L,Finance.balance(next,"eur",day+1))
        assertEquals(expense.baseMinor,Finance.totals(next,Period.month(day)).expense)
    }
    @Test fun publishedRatesPreserveManualOverridesAndHistory() {
        val e=Finance.makeEntry(l,Kind.EXPENSE,"usd",100,day,listOf("food" to 100))
        val old=Finance.putEntry(l,e)
        val updated=Finance.mergePublishedRates(old,"RUB",listOf(
            FxRate("USD",day,"91","CBR"), FxRate("USD",day+1,"92","CBR")))
        assertEquals("90.1234",Finance.rate(updated,"USD",day))
        assertEquals("92",Finance.rate(updated,"USD",day+1))
        assertEquals(e,updated.entries.single())
        assertEquals(updated,Finance.mergePublishedRates(updated,"RUB",listOf(FxRate("USD",day+1,"92","CBR"))))
    }
    @Test fun loansAndInterestNeverDoubleCountPrincipal() {
        val debt=Debt("d","Кредитор","a",true,50000,"12",openedDay=day)
        val withDebt=l.copy(debts=listOf(debt))
        val opened=Finance.putEntry(withDebt,Finance.makeEntry(withDebt,Kind.LOAN_IN,"a",50000,day,debtId=debt.id,principalMinor=50000))
        assertEquals(50000L,Finance.debtRemaining(opened,debt))
        assertEquals(1050000L,Finance.balance(opened,"a",day))
        assertEquals(0L,Finance.totals(opened,Period.month(day)).income)
        val payment=Finance.makeEntry(opened,Kind.DEBT_OUT,"a",12000,day+1,debtId=debt.id,principalMinor=10000)
        val paid=Finance.putEntry(opened,payment)
        assertEquals(40000L,Finance.debtRemaining(paid,debt))
        assertEquals(2000L,Finance.totals(paid,Period.month(day)).expense)
        assertEquals(2000L,Money.sum(Finance.byCategory(paid,Period.month(day)).values))
        assertEquals("Уплаченные проценты",Finance.categoryLabel(paid,Finance.byCategory(paid,Period.month(day)).keys.single()))
        assertEquals(1038000L,Finance.balance(paid,"a",day+1))
        assertTrue(runCatching{Finance.deleteEntry(paid,opened.entries.first().id)}.isFailure)
    }
    @Test fun customPeriodAndExactBudgetThresholds() {
        val p=Period.month(d("2026-02-28"),31)
        assertEquals(d("2026-02-28"),p.from)
        assertEquals(d("2026-03-30"),p.through)
        assertEquals(1,BudgetProgress(10000,8000).level)
        assertEquals(2,BudgetProgress(10000,10000).level)
        assertEquals(3,BudgetProgress(10000,10001).level)
        assertEquals(100,BudgetProgress(10000,10001).percent)
    }
    @Test fun sparseBudgetHistoryDoesNotRequireMonthByMonthIteration() {
        val budget=Budget(name="Долгий план",monthlyMinor=100,startMonth=d("1900-01-01"),rollover=true)
        val progress=Finance.budget(l,budget,d("2026-11-01"))
        val months=java.time.temporal.ChronoUnit.MONTHS.between(java.time.YearMonth.of(1900,1),java.time.YearMonth.of(2026,11))+1
        assertEquals(months*100,progress.available)
        assertEquals(0L,progress.spent)
    }
    @Test fun rolloverRejectsOverflowInsteadOfWrappingOnOlderAndroid() {
        val budget=Budget(name="Лимит",monthlyMinor=Long.MAX_VALUE,startMonth=d("2026-01-01"),rollover=true)
        assertEquals(Long.MAX_VALUE,Finance.budget(l,budget,d("2026-01-31")).available)
        assertTrue(runCatching{Finance.budget(l,budget,d("2026-02-01"))}.exceptionOrNull() is ArithmeticException)
    }
    @Test fun refundCanTargetOneCategoryAndPreserveHistoricalRounding() {
        val original=Finance.makeEntry(l,Kind.EXPENSE,"usd",333,day,listOf("food" to 111L,"home" to 222L))
        val purchased=Finance.putEntry(l,original)
        val refund=Finance.refund(purchased,original.id,100,day+1,categoryAmounts=listOf("home" to 100L))
        val refunded=Finance.putEntry(purchased,refund)
        assertEquals("home",refund.splits.single().categoryId)
        assertEquals(111L,Finance.refundableSplits(refunded,original.id).first{it.categoryId=="food"}.minor)
        assertEquals(122L,Finance.refundableSplits(refunded,original.id).first{it.categoryId=="home"}.minor)
        assertTrue(runCatching{Finance.refund(refunded,original.id,123,day+2,categoryAmounts=listOf("home" to 123L))}.isFailure)
        val closed=Finance.putEntry(refunded,Finance.refund(refunded,original.id,233,day+2))
        assertEquals(0L,Finance.totals(closed,Period.month(day)).expense)
        assertEquals(usd.openingMinor,Finance.balance(closed,"usd",day+2))
        assertTrue(Finance.refundableSplits(closed,original.id).isEmpty())
    }
    @Test fun accruedInterestUsesHistoricalPrincipalRatherThanCurrentBalance() {
        val debt=Debt("interest","Кредитор","a",true,100000,"36.5",openedDay=day)
        val initial=l.copy(debts=listOf(debt))
        val opened=Finance.putEntry(initial,Finance.makeEntry(initial,Kind.LOAN_IN,"a",100000,day,debtId=debt.id,principalMinor=100000))
        val payment=Finance.makeEntry(opened,Kind.DEBT_OUT,"a",51000,day+10,debtId=debt.id,principalMinor=50000)
        val paid=Finance.putEntry(opened,payment)
        assertEquals(1500L,Finance.accruedInterest(paid,debt,day+20))
        assertEquals(1000L,Finance.paidInterest(paid,debt,day+20))
        assertEquals(500L,Finance.estimatedInterest(paid,debt,10))
        assertEquals(null,Finance.accruedInterest(l,debt.copy(openedDay=null),day+20))
    }
    @Test fun monthDaysAndAlternatingWeeks() {
        val monday=d("2026-11-02")
        val t=Finance.makeEntry(l,Kind.EXPENSE,"a",100,monday,listOf("food" to 100),status=EntryStatus.PLANNED)
        val weekly=Schedule("s",t,monday,Frequency.WEEKLY,2,weekdays=listOf(1,4),alternateWeekdays=listOf(2))
        assertEquals(d("2026-11-05"),Finance.nextDate(weekly,monday))
        assertEquals(d("2026-11-10"),Finance.nextDate(weekly,d("2026-11-05")))
        assertEquals(d("2026-11-16"),Finance.nextDate(weekly,d("2026-11-10")))
        val monthly=Schedule("m",t,d("2026-01-15"),Frequency.MONTHLY,monthDays=listOf(15,31))
        assertEquals(d("2026-01-31"),Finance.nextDate(monthly,d("2026-01-15")))
        assertEquals(d("2026-02-15"),Finance.nextDate(monthly,d("2026-01-31")))
        assertEquals(d("2026-02-28"),Finance.nextDate(monthly,d("2026-02-15")))
    }
    @Test fun plannedAndExcludedAccountsDoNotChangeReports() {
        val e=Finance.makeEntry(l,Kind.EXPENSE,"a",1000,day,listOf("food" to 1000),status=EntryStatus.PLANNED)
        val planned=Finance.putEntry(l,e)
        assertEquals(a.openingMinor,Finance.balance(planned,"a",day))
        val actual=Finance.putEntry(planned,e.copy(status=EntryStatus.POSTED))
        assertEquals(1000L,Finance.totals(actual,Period.month(day)).expense)
        val excluded=actual.copy(accounts=actual.accounts.map{if(it.id=="a")it.copy(includeInStats=false) else it})
        assertEquals(0L,Finance.totals(excluded,Period.month(day)).expense)
        assertEquals(999000L,Finance.balance(excluded,"a",day))
    }
    @Test fun singleFuturePaymentPostsOnlyWhenDueAndOnlyOnce() {
        val e=Finance.makeEntry(l,Kind.EXPENSE,"a",1000,day,listOf("food" to 1000),
            status=EntryStatus.PLANNED,timeMinutes=720,autoPostPlanned=true)
        val state=Finance.putEntry(l,e)
        assertEquals(state,Finance.generate(state,day,throughMinutes=719))
        assertEquals(state,Finance.generate(state,day,forecast=true))
        val actual=Finance.generate(state,day,throughMinutes=720).validated()
        assertEquals(EntryStatus.POSTED,actual.entries.single().status)
        assertFalse(actual.entries.single().autoPostPlanned)
        assertEquals(999000L,Finance.balance(actual,"a",day))
        assertEquals(actual,Finance.generate(actual,day+1))
        val archived=state.copy(accounts=state.accounts.map{if(it.id=="a")it.copy(archived=true)else it})
        val skipped=Finance.generate(archived,day,throughMinutes=720).validated()
        assertEquals(EntryStatus.PLANNED,skipped.entries.single().status)
        assertFalse(skipped.entries.single().autoPostPlanned)
    }
    @Test fun projectedBalanceIncludesTransfersWithoutCountingThemAsIncome() {
        val transfer=Finance.makeEntry(l,Kind.TRANSFER,"a",9000,day+1,targetId="usd",targetMinor=100,status=EntryStatus.PLANNED)
        val state=Finance.putEntry(l,transfer)
        val actual=Finance.valuation(state,day)
        val projected=Finance.projectedValuation(state,listOf(transfer),day+2,day)
        assertEquals(actual.minor-9000L+Money.convert(100,"USD","RUB","90.1234"),projected.minor)
        assertEquals(0L,Finance.totals(state,Period.month(day)).net)
        assertEquals(EntryStatus.PLANNED,state.entries.single().status)
    }
    @Test fun goalChangesAndRefundEditStayExact() {
        val g=Goal(name="Цель",targetMinor=10000,savedMinor=1000,adjustments=listOf(GoalAdjustment(minor=500),GoalAdjustment(minor=-200)))
        assertEquals(1300L,Finance.goalSaved(g))
        val expense=Finance.makeEntry(l,Kind.EXPENSE,"a",333,day,listOf("food" to 333))
        val state=Finance.putEntry(l,expense)
        val r=Finance.refund(state,expense.id,100,day+1)
        val withRefund=Finance.putEntry(state,r)
        val edited=Finance.putEntry(withRefund,Finance.refund(withRefund,expense.id,120,day+1,id=r.id))
        assertEquals(213L,Finance.totals(edited,Period.month(day)).expense)
        assertEquals(2,edited.entries.size)
    }
    @Test fun forecastIncludesOverdueWithoutMutatingActualLedger() {
        val old=Finance.makeEntry(l,Kind.EXPENSE,"a",200,day-1,listOf("food" to 200L),status=EntryStatus.PLANNED)
        val next=Finance.makeEntry(l,Kind.INCOME,"a",500,day+2,listOf("salary" to 500L),status=EntryStatus.PLANNED)
        val state=Finance.putEntry(Finance.putEntry(l,old),next)
        val planned=Finance.forecastEntries(state,day+3)
        assertEquals(setOf(old.id,next.id),planned.map{it.id}.toSet())
        assertEquals(Finance.valuation(state,day).minor+300L,Finance.projectedValuation(state,planned,day+3,day).minor)
        assertEquals(a.openingMinor,Finance.balance(state,a.id,day+3))
    }
    @Test fun budgetAsOfDateIgnoresLaterPostedMovementsAndOverflowDoesNotInvalidateLedger() {
        val entry=Finance.makeEntry(l,Kind.EXPENSE,"a",200,day+3,listOf("food" to 200L))
        val state=Finance.putEntry(l,entry)
        val budget=Budget(name="Лимит",monthlyMinor=1000,startMonth=day)
        assertEquals(0L,Finance.budget(state,budget,day).spent)
        assertEquals(200L,Finance.budget(state,budget,day+3).spent)
        val huge=budget.copy(monthlyMinor=Long.MAX_VALUE,rollover=true,startMonth=day-365)
        val stored=state.copy(budgets=listOf(huge)).validated()
        assertTrue(runCatching{Finance.budget(stored,huge,day)}.isFailure)
        assertEquals(999800L,Finance.balance(stored,"a",day+3))
    }
    @Test fun malformedImportedDatesAndReceiptDataAreRejected() {
        assertTrue(runCatching{l.copy(goals=listOf(Goal(name="Цель",targetMinor=100,dueDay=Long.MAX_VALUE))).validated()}.isFailure)
        assertTrue(runCatching{l.copy(receipts=listOf(Receipt(mime="image/png",base64="!!=="))).validated()}.isFailure)
        assertTrue(runCatching{l.copy(rates=l.rates+FxRate("USD",Long.MAX_VALUE,"1")).validated()}.isFailure)
    }
}
