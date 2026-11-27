package ru.epbloger.trachu.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class OverviewPeriodTest {
    @Test fun unavailableReportAccountFallsBackToAllAccounts() {
        val a=ru.epbloger.trachu.core.Account("a","Карта")
        val b=ru.epbloger.trachu.core.Account("b","Скрытый",includeInStats=false)
        val ledger=ru.epbloger.trachu.core.Ledger.initial().copy(accounts=listOf(a,b))
        assertEquals("a",availableReportAccount(ledger,"a"))
        assertEquals("",availableReportAccount(ledger,"b"))
        assertEquals("",availableReportAccount(ledger,"missing"))
        assertEquals("",availableReportAccount(ledger,""))
    }

    @Test fun reportingMonthUsesConfiguredStartDayAcrossMonthBoundary() {
        val afterStart=periodFor(PeriodType.MONTH,LocalDate.of(2026,11,27).toEpochDay(),25)
        assertEquals(LocalDate.of(2026,11,25).toEpochDay(),afterStart.from)
        assertEquals(LocalDate.of(2026,12,24).toEpochDay(),afterStart.through)

        val beforeStart=periodFor(PeriodType.MONTH,LocalDate.of(2026,11,24).toEpochDay(),25)
        assertEquals(LocalDate.of(2026,10,25).toEpochDay(),beforeStart.from)
        assertEquals(LocalDate.of(2026,11,24).toEpochDay(),beforeStart.through)
    }
}
