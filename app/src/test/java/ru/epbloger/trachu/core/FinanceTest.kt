package ru.epbloger.trachu.core

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class FinanceTest {
    private val day = LocalDate.of(2026, 1, 31).toEpochDay()
    private val a = Account("a", "Наличные", openingMinor = 100000)
    private val b = Account("b", "Карта", type = AccountType.CARD)
    private val l = Ledger.initial().copy(accounts = listOf(a,b))
    private fun expense(amount: Long = 10001) = Finance.makeEntry(l, Kind.EXPENSE, a.id, amount, day, listOf("food" to 5000, "home" to (amount-5000)))
    @Test fun transferDoesNotChangeNet() {
        val e = Finance.makeEntry(l, Kind.TRANSFER, "a", 12500, day, targetId="b", targetMinor=12500)
        val state = Finance.putEntry(l,e)
        assertEquals(87500L, Finance.balance(state,"a",day))
        assertEquals(12500L, Finance.balance(state,"b",day))
        assertEquals(0L, Finance.totals(state,Period.month(day)).net)
    }
    @Test fun refundAllocatesHistoricalAmountsExactly() {
        val original = expense()
        var state = Finance.putEntry(l,original)
        state = Finance.putEntry(state,Finance.refund(state,original.id,4001,day))
        state = Finance.putEntry(state,Finance.refund(state,original.id,6000,day))
        assertEquals(100000L, Finance.balance(state,"a",day))
        assertEquals(0L, Finance.totals(state,Period.month(day)).expense)
        assertTrue(runCatching { Finance.refund(state, original.id,1,day) }.isFailure)
        assertTrue(runCatching { Finance.deleteEntry(state, original.id) }.isFailure)
    }
    @Test fun moneySupportsDifferentFractionDigits() {
        assertEquals(1500L, Money.parse("15,00","RUB"))
        assertEquals(150L, Money.parse("150","JPY"))
        assertEquals(9900L, Money.convert(100,"JPY","RUB","0.99"))
        assertTrue(runCatching { Money.parse("1.001","RUB") }.isFailure)
    }
    @Test fun monthlyAnchorAndIdempotentGeneration() {
        val s = Schedule("s",expense(),day,Frequency.MONTHLY, autoPost=true)
        val through = LocalDate.of(2026,3,31).toEpochDay()
        val generated = Finance.generate(l.copy(schedules=listOf(s)),through).validated()
        assertEquals(listOf(day,LocalDate.of(2026,2,28).toEpochDay(),through),generated.entries.map { it.day })
        assertEquals(generated,Finance.generate(generated,through))
    }
    @Test fun rolloverAndDebt() {
        val state = Finance.putEntry(l,expense())
        val budget = Budget(name="Месяц",monthlyMinor=20000,startMonth=day,rollover=true)
        val feb = LocalDate.of(2026,2,1).toEpochDay()
        assertEquals(29999L, Finance.budget(state,budget,feb).available)
        val d = Debt("d","Друг","a",true,10000)
        val withDebt = state.copy(debts=listOf(d))
        val payment = Finance.makeEntry(withDebt,Kind.DEBT_OUT,"a",3000,day,debtId="d",principalMinor=2500)
        val paid = Finance.putEntry(withDebt,payment)
        assertEquals(7500L,Finance.debtRemaining(paid,d))
        assertEquals(10501L,Finance.totals(paid,Period.month(day)).expense)
    }
}
