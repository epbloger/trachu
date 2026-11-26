package ru.epbloger.trachu.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class OverviewPeriodTest {
    @Test fun reportingMonthUsesConfiguredStartDayAcrossMonthBoundary() {
        val afterStart=periodFor(PeriodType.MONTH,LocalDate.of(2026,11,27).toEpochDay(),25)
        assertEquals(LocalDate.of(2026,11,25).toEpochDay(),afterStart.from)
        assertEquals(LocalDate.of(2026,12,24).toEpochDay(),afterStart.through)

        val beforeStart=periodFor(PeriodType.MONTH,LocalDate.of(2026,11,24).toEpochDay(),25)
        assertEquals(LocalDate.of(2026,10,25).toEpochDay(),beforeStart.from)
        assertEquals(LocalDate.of(2026,11,24).toEpochDay(),beforeStart.through)
    }
}
