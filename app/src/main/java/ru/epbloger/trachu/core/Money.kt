package ru.epbloger.trachu.core

import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

object Money {
    fun digits(currency: String): Int = Currency.getInstance(currency).defaultFractionDigits
        .also { require(it in 0..4) { "Неподдерживаемая валюта" } }
    fun parse(text: String, currency: String): Long {
        val clean = text.trim().replace(" ", "").replace("\u00a0", "").replace(",", ".")
        require(clean.length <= 48 && clean.matches(Regex("[+-]?[0-9]+(\\.[0-9]+)?"))) { "Введите корректную сумму" }
        return BigDecimal(clean).movePointRight(digits(currency)).longValueExact()
    }
    fun decimal(minor: Long, currency: String): BigDecimal = BigDecimal.valueOf(minor, digits(currency))
    fun input(minor: Long, currency: String): String = decimal(minor, currency).toPlainString()
    private fun formatter(currency: String) = NumberFormat.getCurrencyInstance(Locale("ru", "RU")).apply {
        this.currency = Currency.getInstance(currency)
        minimumFractionDigits = digits(currency)
        maximumFractionDigits = digits(currency)
    }
    fun format(minor: Long, currency: String): String =
        formatter(currency).format(decimal(minor, currency))

    /** A display-only currency subtotal may exceed the range of one account.
     * Keep the exact integer amount rather than crashing or clipping the display. */
    fun formatTotal(values: Iterable<Long>, currency: String): String {
        val total = values.fold(BigInteger.ZERO) { sum, value -> sum.add(BigInteger.valueOf(value)) }
        return formatter(currency).format(BigDecimal(total, digits(currency)))
    }
    fun convert(minor: Long, from: String, to: String, rate: String): Long {
        val r = BigDecimal(rate)
        require(r.signum() > 0 && r.scale() in -12..24 && r.precision() <= 40 && r <= BigDecimal("1000000000000")) { "Некорректный курс" }
        return decimal(minor, from).multiply(r).movePointRight(digits(to))
            .setScale(0, RoundingMode.HALF_EVEN).longValueExact()
    }
    fun share(total: Long, part: Long, whole: Long): Long {
        require(whole > 0 && part in 0..whole)
        return BigDecimal.valueOf(total).multiply(BigDecimal.valueOf(part))
            .divide(BigDecimal.valueOf(whole), 0, RoundingMode.HALF_EVEN).longValueExact()
    }
    /** Signed aggregates must not depend on iteration order. Individual account
     * movements still use addExact, and out-of-range final totals are rejected. */
    fun sum(values: Iterable<Long>): Long {
        var total = 0L
        var wide: BigInteger? = null
        for (value in values) {
            val previous = wide
            if (previous != null) wide = previous.add(BigInteger.valueOf(value))
            else try { total = Math.addExact(total, value) }
            catch (_: ArithmeticException) {
                wide = BigInteger.valueOf(total).add(BigInteger.valueOf(value))
            }
        }
        val result = wide ?: return total
        if (result.bitLength() > 63) throw ArithmeticException("Сумма слишком велика")
        return result.toLong()
    }
    fun add(a: Long, b: Long) = Math.addExact(a, b)
    fun sub(a: Long, b: Long) = Math.subtractExact(a, b)
}
