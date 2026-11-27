package ru.epbloger.trachu.widget

import org.junit.Assert.*
import org.junit.Test
import ru.epbloger.trachu.core.*

class WidgetSummaryTest {
    @Test fun widgetBalanceIsVisibleByDefault() {
        assertTrue(ru.epbloger.trachu.security.Settings().showWidgetBalance)
    }
    @Test fun hiddenWidgetDoesNotReadLedgerOrExposeAmounts() {
        val s=widgetSummary(false,{error("Must not read hidden ledger")})
        assertEquals("••••••",s.balance)
        assertNull(s.income)
        assertNull(s.expense)
    }
    @Test fun visibleWidgetUsesFinancialMonthAndExistingFinanceCalculations() {
        val day=java.time.LocalDate.of(2026,11,27).toEpochDay()
        val base=Ledger.initial().copy(accounts=listOf(Account("a","Карта",openingMinor=10000)))
        val income=Finance.makeEntry(base,Kind.INCOME,"a",5000,day,listOf("salary" to 5000L))
        val expense=Finance.makeEntry(base,Kind.EXPENSE,"a",1000,day,listOf("food" to 1000L))
        val earlier=Finance.makeEntry(base,Kind.EXPENSE,"a",2000,day-5,listOf("food" to 2000L))
        val ledger=base.copy(entries=listOf(income,expense,earlier)).validated()
        val s=widgetSummary(true,{ledger},25,day)
        assertEquals(Money.format(12000,"RUB"),s.balance)
        assertEquals(Money.format(5000,"RUB"),s.income)
        assertEquals(Money.format(1000,"RUB"),s.expense)
        assertFalse(s.partial)
    }
}
