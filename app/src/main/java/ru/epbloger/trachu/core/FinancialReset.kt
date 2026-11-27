package ru.epbloger.trachu.core

internal fun isFinancialResetConfirmed(text:String):Boolean =
    text.trim().equals("ПОДТВЕРЖДАЮ",ignoreCase=true)
