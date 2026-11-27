package ru.epbloger.trachu.core

import org.junit.Assert.*
import org.junit.Test

class DeletionIsolationTest {
    private val day=today()
    private val a=Account("a","Карта",openingMinor=10000)
    private val b=Account("b","Наличные",openingMinor=10000)
    private val base=Ledger.initial().copy(accounts=listOf(a,b),
        goals=listOf(Goal(name="Цель",targetMinor=1000)),
        budgets=listOf(Budget(name="Бюджет",monthlyMinor=1000,startMonth=Period.month(day).from)),
        rates=listOf(FxRate("USD",day,"100")))

    @Test fun accountDeletionCannotRemoveAnyLinkedRecords() {
        val expense=Finance.makeEntry(base,Kind.EXPENSE,a.id,100,day,listOf("food" to 100L))
        val incoming=Finance.makeEntry(base,Kind.TRANSFER,b.id,100,day,targetId=a.id,targetMinor=100)
        val planned=expense.copy(status=EntryStatus.PLANNED,day=day+1)
        val schedule=Schedule("s",incoming,day+1,Frequency.DAILY,enabled=false)
        val debt=Debt(person="Долг",accountId=a.id,owedByMe=true,principalMinor=100)
        val cases=listOf(base.copy(entries=listOf(expense)),base.copy(entries=listOf(incoming)),
            base.copy(entries=listOf(planned)),base.copy(schedules=listOf(schedule)),
            base.copy(debts=listOf(debt)))
        for(ledger in cases){
            ledger.validated()
            assertTrue(Finance.accountHasReferences(ledger,a.id))
            assertTrue(runCatching{Finance.deleteAccount(ledger,a)}.isFailure)
        }
        assertEquals(base.copy(accounts=listOf(b)),Finance.deleteAccount(base,a))
        assertTrue(runCatching{Finance.deleteAccount(base,a.copy(name="Старое имя"))}.isFailure)
    }

    @Test fun expenseWithRefundCannotBeDeletedAndRefundDeletionIsIsolated() {
        val expense=Finance.makeEntry(base,Kind.EXPENSE,a.id,500,day,listOf("food" to 500L))
        val income=Finance.makeEntry(base,Kind.INCOME,b.id,1000,day,listOf("salary" to 1000L))
        val ledger=base.copy(entries=listOf(expense,income))
        val refund=Finance.refund(ledger,expense.id,200,day)
        val withRefund=Finance.putEntry(ledger,refund)
        assertTrue(runCatching{Finance.deleteEntry(withRefund,expense.id)}.isFailure)
        val result=Finance.deleteEntry(withRefund,refund.id)
        assertEquals(ledger,result)
        assertEquals(9500L,Finance.balance(result,a.id))
        assertEquals(11000L,Finance.balance(result,b.id))
    }
}
