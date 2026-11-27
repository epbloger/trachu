package ru.epbloger.trachu.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlannerRateValidationTest {
    @Test fun paymentPreviewHandlesBlankNegativeOverpreciseAndOverflowingAmounts() {
        assertEquals(DebtPaymentPreview(1250L,750L),debtPaymentPreview("12.50","",true,"RUB",2000L))
        assertEquals(DebtPaymentPreview(1500L,750L),debtPaymentPreview("12.50","2.50",true,"RUB",2000L))
        assertEquals(DebtPaymentPreview(1250L,750L),debtPaymentPreview("12.50","invalid",false,"RUB",2000L))
        for (bad in listOf("","-1","abc","0.001","92233720368547758.08")) {
            assertEquals(DebtPaymentPreview(null,null),debtPaymentPreview(bad,"0",true,"RUB",2000L))
        }
        assertEquals(DebtPaymentPreview(null,750L),debtPaymentPreview("12.50","-1",true,"RUB",2000L))
        assertEquals(DebtPaymentPreview(null,0L),debtPaymentPreview("92233720368547758.07","0.01",true,"RUB",Long.MAX_VALUE))
        assertEquals(DebtPaymentPreview(2001L,null),debtPaymentPreview("20.01","0",true,"RUB",2000L))
    }

    @Test fun blankRateUsesTheSavedQuote() {
        assertNull(plannerRateOrNull("  ","Курс погашения"))
    }

    @Test fun commaDecimalIsNormalizedWithoutRounding() {
        assertEquals("91.234567",plannerRateOrNull("91,234567","Курс погашения"))
    }

    @Test fun malformedNonPositiveAndOversizedRatesHaveFieldSpecificErrors() {
        val invalid=listOf(
            "abc" to "введите корректный курс",
            "0" to "курс должен быть больше нуля",
            "-1" to "курс должен быть больше нуля",
            "1.1234567890123456789012345" to "слишком большой или содержит слишком много знаков",
            "1E+13" to "слишком большой или содержит слишком много знаков"
        )
        invalid.forEach{(value,expected)->
            val failure=runCatching{plannerRateOrNull(value,"Курс получения / выдачи")}.exceptionOrNull()
            assertTrue("$value should be rejected",failure is IllegalArgumentException)
            assertTrue(failure?.message.orEmpty().startsWith("Курс получения / выдачи:"))
            assertTrue(failure?.message.orEmpty().contains(expected))
        }
    }
}
