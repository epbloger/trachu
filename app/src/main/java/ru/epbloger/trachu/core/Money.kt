package ru.epbloger.trachu.core

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

object Money {
    fun digits(currency: String): Int = Currency.getInstance(currency).defaultFractionDigits
        .also { require(it in 0..4) { "Неподдерживаемая валюта" } }
    fun parse(text: String, currency: String): Long {
        val clean = text.trim().replace(" ", "").replace("\u00a0", "").replace(",", ".")
        require(clean.matches(Regex("[+-]?[0-9]+(\\.[0-9]+)?"))) { "Введите корректную сумму" }
        return BigDecimal(clean).movePointRight(digits(currency)).longValueExact()
    }
    fun decimal(minor: Long, currency: String): BigDecimal = BigDecimal.valueOf(minor, digits(currency))
    fun input(minor: Long, currency: String): String = decimal(minor, currency).toPlainString()
    fun format(minor: Long, currency: String): String =
        NumberFormat.getCurrencyInstance(Locale("ru", "RU")).apply {
            this.currency = Currency.getInstance(currency)
            minimumFractionDigits = digits(currency)
            maximumFractionDigits = digits(currency)
        }.format(decimal(minor, currency))
    fun convert(minor: Long, from: String, to: String, rate: String): Long {
        val r = BigDecimal(rate)
        require(r.signum() > 0 && r.scale() <= 24 && r.precision() <= 40) { "Некорректный курс" }
        return decimal(minor, from).multiply(r).movePointRight(digits(to))
            .setScale(0, RoundingMode.HALF_EVEN).longValueExact()
    }
    fun share(total: Long, part: Long, whole: Long): Long {
        require(whole > 0 && part in 0..whole)
        return BigDecimal.valueOf(total).multiply(BigDecimal.valueOf(part))
            .divide(BigDecimal.valueOf(whole), 0, RoundingMode.HALF_EVEN).longValueExact()
    }
    fun sum(values: Iterable<Long>): Long = values.fold(0L, Math::addExact)
    fun add(a: Long, b: Long) = Math.addExact(a, b)
    fun sub(a: Long, b: Long) = Math.subtractExact(a, b)
}
