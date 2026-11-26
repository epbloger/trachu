package ru.epbloger.trachu.ui

import org.junit.Assert.*
import org.junit.Test
import ru.epbloger.trachu.core.*
import ru.epbloger.trachu.security.Settings

class OnboardingTest {
    @Test fun pristineDatabaseInEitherCategoryOrderNeedsSetup() {
        val initial = Ledger.initial()
        assertTrue(needsOnboarding(initial, Settings()))
        assertTrue(needsOnboarding(initial.copy(categories = initial.categories.sortedBy { it.id }), Settings()))
        assertFalse(needsOnboarding(initial, Settings(onboardingCompleted = true)))
        assertFalse(needsOnboarding(initial, Settings(credential = "protected")))
    }

    @Test fun existingFinancialDataAndCustomizedCategoriesAreNeverReinitialized() {
        val initial = Ledger.initial()
        listOf(initial.copy(accounts = listOf(Account(name = "Счёт"))),
            initial.copy(baseCurrency = "USD"),
            initial.copy(categories = initial.categories.dropLast(1)),
            initial.copy(goals = listOf(Goal(name = "Цель", targetMinor = 100))),
            initial.copy(rates = listOf(FxRate("USD", today(), "90")))).forEach { existing ->
            assertFalse(needsOnboarding(existing, Settings()))
            assertTrue(runCatching { firstAccountLedger(existing, "RUB", "Другой", AccountType.CARD, "0") }.isFailure)
        }
    }

    @Test fun firstAccountUsesChosenCurrencyAndExactOpeningWithoutIncome() {
        val initial = Ledger.initial()
        val result = firstAccountLedger(initial, "EUR", "  Карта  ", AccountType.CARD, "123,45")
        val account = result.accounts.single()
        assertEquals("EUR", result.baseCurrency)
        assertEquals("EUR", account.currency)
        assertEquals("Карта", account.name)
        assertEquals(AccountType.CARD, account.type)
        assertEquals(12345L, Finance.balance(result, account.id))
        assertTrue(result.entries.isEmpty())
        assertEquals(initial.categories, result.categories)
        assertFalse(needsOnboarding(result, Settings()))
        assertEquals(-7L, firstAccountLedger(initial, "JPY", "Кредит", AccountType.CREDIT, "-7").accounts.single().openingMinor)
        assertTrue(runCatching { firstAccountLedger(initial, "JPY", "Карта", AccountType.CARD, "1.5") }.isFailure)
        assertTrue(runCatching { firstAccountLedger(initial, "RUB", " ", AccountType.CARD, "0") }.isFailure)
    }
}
