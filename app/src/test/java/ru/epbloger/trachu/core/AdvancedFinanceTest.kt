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
}
