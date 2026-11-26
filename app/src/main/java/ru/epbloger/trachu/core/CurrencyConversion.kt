package ru.epbloger.trachu.core

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/** A quotation is a number of target major units per one source major unit. */
object CurrencyConversion {
    fun quote(ledger: Ledger, from: String, to: String, day: Long): BigDecimal? {
        Money.digits(from)
        Money.digits(to)
        if (from == to) return BigDecimal.ONE
        val source = Finance.rate(ledger, from, day)?.let(::BigDecimal) ?: return null
        val target = Finance.rate(ledger, to, day)?.let(::BigDecimal) ?: return null
        return source.divide(target, MathContext(24, RoundingMode.HALF_EVEN)).stripTrailingZeros()
            .takeIf { it.signum() > 0 }
    }

    /** Apply the stored cross-rate as a ratio, rounding only the final minor units.
     * The rounded display quotation must never determine the ledger amount. */
    fun convertStored(ledger: Ledger, minor: Long, from: String, to: String, day: Long): Long {
        require(minor >= 0) { "Сумма не может быть отрицательной" }
        val source = Money.decimal(minor, from)
        val targetDigits = Money.digits(to)
        if (from == to) return minor
        val a = requireNotNull(Finance.rate(ledger, from, day)) { "Нет курса $from на выбранную дату" }.let(::BigDecimal)
        val b = requireNotNull(Finance.rate(ledger, to, day)) { "Нет курса $to на выбранную дату" }.let(::BigDecimal)
        require(a.signum() > 0 && b.signum() > 0) { "Некорректный курс" }
        return source.multiply(a).movePointRight(targetDigits)
            .divide(b, 0, RoundingMode.HALF_EVEN).longValueExact()
    }

    fun manualRate(text: String): BigDecimal {
        val clean = text.trim().replace(',', '.')
        require(clean.length <= 48 && clean.matches(Regex("[0-9]+(\\.[0-9]+)?"))) { "Введите корректный курс" }
        return BigDecimal(clean).stripTrailingZeros().also {
            require(it.signum() > 0 && it.scale() in -12..24 && it.precision() <= 40 &&
                it <= BigDecimal("1000000000000")) { "Некорректный курс" }
        }
    }

    fun convert(minor: Long, from: String, to: String, rate: BigDecimal): Long {
        require(minor >= 0) { "Сумма не может быть отрицательной" }
        return Money.convert(minor, from, to, rate.stripTrailingZeros().toPlainString())
    }
}
