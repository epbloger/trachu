package ru.epbloger.trachu.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId

/** Calls the production HTTPS client directly; this test does not use the injected UI test transport. */
@RunWith(AndroidJUnit4::class)
class CbrHttpsIntegrationTest {
    @Test fun fetchesOfficialRatesOverRealHttps() = runBlocking {
        val requestedDay = LocalDate.now(ZoneId.of("Europe/Moscow")).toEpochDay()
        var rates: List<ru.epbloger.trachu.core.FxRate>? = null
        var lastFailure: Exception? = null
        for (attempt in 0..2) {
            try {
                rates = withContext(Dispatchers.IO) {
                    ExchangeRates.fetch("RUB", requestedDay)
                }
                if (!rates.isNullOrEmpty()) break
            } catch (failure: Exception) {
                lastFailure = failure
                if (attempt < 2) delay(1_500)
            }
        }
        val actual = requireNotNull(rates?.takeIf { it.isNotEmpty() }) {
            "Live CBR HTTPS request failed: ${lastFailure?.javaClass?.simpleName ?: "empty feed"}"
        }
        for (code in listOf("USD", "EUR")) {
            val rate = requireNotNull(actual.singleOrNull { it.currency == code }) {
                "CBR response must contain $code"
            }
            assertEquals("CBR", rate.source)
            assertTrue(BigDecimal(rate.unitsOfBasePerUnit).signum() > 0)
            assertTrue("CBR date must not be in the future or stale", rate.day in (requestedDay - 10)..requestedDay)
        }
    }
}
