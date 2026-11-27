package ru.epbloger.trachu.core

import org.junit.Assert.*
import org.junit.Test

class FinancialResetTest {
    @Test fun confirmationRequiresEntireWord() {
        listOf("","Да","ПОДТВЕРЖДА","ПОДТВЕРЖДАЮ!","не подтверждаю","Подтверждаю и удаляю").forEach {
            assertFalse(isFinancialResetConfirmed(it))
        }
        assertTrue(isFinancialResetConfirmed("ПОДТВЕРЖДАЮ"))
        assertTrue(isFinancialResetConfirmed("Подтверждаю"))
        assertTrue(isFinancialResetConfirmed(" подтверждаю "))
    }
}
