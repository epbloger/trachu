package ru.epbloger.trachu.core

import java.math.BigDecimal
import org.junit.Assert.*
import org.junit.Test

class CurrencyConversionTest {
    private val day = 20000L
    private val ledger = Ledger.initial().copy(rates = listOf(
        FxRate("USD", day, "90"),
        FxRate("EUR", day, "100"),
        FxRate("JPY", day, "0.6"),
        FxRate("USD", day + 1, "95")
    ))

    @Test fun crossCurrencyUsesHistoricalRateAndMinorUnits() {
        val usdToEur = requireNotNull(CurrencyConversion.quote(ledger, "USD", "EUR", day))
        assertEquals(BigDecimal("0.9"), usdToEur)
        assertEquals(900L, CurrencyConversion.convert(1000, "USD", "EUR", usdToEur))
        assertEquals(150L, CurrencyConversion.convert(100, "USD", "JPY", requireNotNull(CurrencyConversion.quote(ledger,"USD","JPY",day))))
        assertEquals(9000L, CurrencyConversion.convert(100,"USD","RUB",requireNotNull(CurrencyConversion.quote(ledger,"USD","RUB",day))))
    }

    @Test fun nonRubBaseAndSameCurrency() {
        val l = Ledger.initial().copy(baseCurrency = "EUR", rates = listOf(FxRate("USD",day,"0.8"),FxRate("RUB",day,"0.01")))
        assertEquals(8000L, CurrencyConversion.convert(100,"USD","RUB",requireNotNull(CurrencyConversion.quote(l,"USD","RUB",day))))
        assertEquals(BigDecimal.ONE, CurrencyConversion.quote(l,"JPY","JPY",day))
        assertNull(CurrencyConversion.quote(l,"JPY","RUB",day))
        assertNull(CurrencyConversion.quote(l,"USD","EUR",day-1))
    }

    @Test fun roundsHalfEvenAcrossZeroAndThreeDecimalCurrencies() {
        assertEquals(2L, CurrencyConversion.convert(250,"USD","JPY",BigDecimal.ONE))
        assertEquals(4L, CurrencyConversion.convert(350,"USD","JPY",BigDecimal.ONE))
        assertEquals(333L, CurrencyConversion.convert(100,"USD","KWD",BigDecimal("0.333")))
        assertEquals(0L, CurrencyConversion.convert(0,"USD","JPY",BigDecimal.ONE))
    }

    @Test fun rejectsInvalidRateNegativeAndOverflow() {
        assertEquals(BigDecimal("0.25"), CurrencyConversion.manualRate("0,25"))
        listOf("0","-1","1e12","abc","1000000000001").forEach {
            assertTrue(runCatching { CurrencyConversion.manualRate(it) }.isFailure)
        }
        assertTrue(runCatching { CurrencyConversion.convert(-1,"USD","RUB",BigDecimal.ONE) }.isFailure)
        assertTrue(runCatching { CurrencyConversion.convert(Long.MAX_VALUE,"USD","RUB",BigDecimal("100")) }.isFailure)
    }
}
