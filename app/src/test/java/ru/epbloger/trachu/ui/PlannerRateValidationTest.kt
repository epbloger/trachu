package ru.epbloger.trachu.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlannerRateValidationTest {
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
