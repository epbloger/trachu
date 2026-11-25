package ru.epbloger.trachu.core

import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

data class Period(val from: Long, val through: Long) {
    init { require(from <= through) }
    operator fun contains(day: Long) = day in from..through
    companion object {
        fun month(day: Long = today()): Period {
            val m = YearMonth.from(LocalDate.ofEpochDay(day))
            return Period(m.atDay(1).toEpochDay(), m.atEndOfMonth().toEpochDay())
        }
    }
}
data class Totals(val income: Long, val expense: Long) {
    val net: Long get() = Money.sub(income, expense)
}
data class Valuation(val minor: Long, val missingCurrencies: Set<String>)
data class BudgetProgress(val available: Long, val spent: Long) {
    val remaining get() = Money.sub(available, spent)
    val percent get() = if (available <= 0) if (spent > 0) 100 else 0
        else BigDecimal.valueOf(spent.coerceAtLeast(0)).multiply(BigDecimal(100)).divide(BigDecimal.valueOf(available), 0, java.math.RoundingMode.DOWN).coerceAtMost(BigDecimal(Int.MAX_VALUE)).toInt()
    val level get() = when {
        spent > available -> 3
        available > 0 && spent == available -> 2
        available > 0 && percent >= 80 -> 1
        else -> 0
    }
}

object Finance {
    fun rate(ledger: Ledger, currency: String, day: Long): String? =
        if (currency == ledger.baseCurrency) "1" else ledger.rates
            .filter { it.currency == currency && it.day <= day }.maxByOrNull { it.day }?.unitsOfBasePerUnit

    fun balance(ledger: Ledger, accountId: String, through: Long = today()): Long {
        val account = ledger.accounts.first { it.id == accountId }
        return ledger.entries.filter { it.status == EntryStatus.POSTED && it.day <= through }.fold(account.openingMinor) { total, e ->
            val delta = when {
                e.kind == Kind.TRANSFER && e.targetAccountId == accountId -> requireNotNull(e.targetMinor)
                e.accountId != accountId -> 0L
                e.kind in listOf(Kind.INCOME, Kind.REFUND, Kind.DEBT_IN) -> e.minor
                else -> -e.minor
            }
            Money.add(total, delta)
        }
    }
    fun valuation(ledger: Ledger, day: Long = today()): Valuation {
        val missing = mutableSetOf<String>()
        val total = Money.sum(ledger.accounts.filter { it.includeInTotal }.map { a ->
            val value = balance(ledger, a.id, day)
            val r = rate(ledger, a.currency, day)
            if (r == null) { if (value != 0L) missing.add(a.currency); 0L }
            else Money.convert(value, a.currency, ledger.baseCurrency, r)
        })
        return Valuation(total, missing)
    }
    fun makeEntry(
        ledger: Ledger, kind: Kind, accountId: String, minor: Long, day: Long,
        categoryAmounts: List<Pair<String, Long>> = emptyList(), note: String = "",
        tags: List<String> = emptyList(), targetId: String? = null, targetMinor: Long? = null,
        status: EntryStatus = EntryStatus.POSTED, explicitRate: String? = null,
        essential: Boolean = false, timeMinutes: Int = 720, id: String = newId(),
        debtId: String? = null, principalMinor: Long = 0, receiptIds: List<String> = emptyList(),
        allowArchived: Boolean = false
    ): Entry {
        val account = ledger.accounts.first { it.id == accountId && (!it.archived || allowArchived) }
        require(kind != Kind.REFUND) { "Возврат должен ссылаться на покупку" }
        require(minor > 0)
        val r = if (account.currency == ledger.baseCurrency) "1" else explicitRate ?: rate(ledger, account.currency, day)
            ?: error("Укажите курс валюты")
        val base = Money.convert(minor, account.currency, ledger.baseCurrency, r)
        require(base >= 0)
        var allocatedMinor = 0L
        var allocatedBase = 0L
        val splits = categoryAmounts.map { (category, value) ->
            require(value > 0)
            allocatedMinor = Money.add(allocatedMinor, value)
            require(allocatedMinor <= minor)
            val cumulative = Money.share(base, allocatedMinor, minor)
            val split = Split(category, value, Money.sub(cumulative, allocatedBase))
            allocatedBase = cumulative
            split
        }
        return Entry(id, kind, accountId, minor, base, day, timeMinutes, note.trim(),
            tags.map { it.trim() }.filter { it.isNotBlank() }.distinct(), splits, status,
            targetId, targetMinor, debtId = debtId, principalMinor = principalMinor, essential = essential,
            receiptIds = receiptIds, rate = r)
    }
    fun refund(ledger: Ledger, originalId: String, amount: Long, day: Long, note: String = "", id: String = newId()): Entry {
        val original = ledger.entries.first { it.id == originalId && it.kind == Kind.EXPENSE && it.status == EntryStatus.POSTED }
        require(day >= original.day && amount > 0)
        val existing = ledger.entries.filter { it.kind == Kind.REFUND && it.originalId == originalId && it.id != id }
        val already = Money.sum(existing.map { it.minor })
        require(amount <= Money.sub(original.minor, already)) { "Возврат превышает остаток покупки" }
        var left = amount
        val splits = original.splits.mapNotNull { split ->
            val usedMinor = Money.sum(existing.flatMap { it.splits }.filter { it.categoryId == split.categoryId }.map { it.minor })
            val usedBase = Money.sum(existing.flatMap { it.splits }.filter { it.categoryId == split.categoryId }.map { it.baseMinor })
            val remainingMinor = Money.sub(split.minor, usedMinor)
            val remainingBase = Money.sub(split.baseMinor, usedBase)
            val take = minOf(left, remainingMinor)
            left -= take
            if (take == 0L) null else Split(split.categoryId, take,
                Money.share(remainingBase, take, remainingMinor))
        }
        require(left == 0L)
        return Entry(id, Kind.REFUND, original.accountId, amount, Money.sum(splits.map { it.baseMinor }),
            day, note = note, tags = original.tags, splits = splits, originalId = originalId, rate = original.rate,
            essential = original.essential)
    }
    fun totals(ledger: Ledger, period: Period): Totals {
        val posted = ledger.entries.filter { it.status == EntryStatus.POSTED && it.day in period }
        val income = Money.sum(posted.map {
            when (it.kind) {
                Kind.INCOME -> it.baseMinor
                Kind.DEBT_IN -> Money.share(it.baseMinor, it.minor - it.principalMinor, it.minor)
                else -> 0L
            }
        })
        val expense = Money.sum(posted.map {
            when (it.kind) {
                Kind.EXPENSE -> it.baseMinor
                Kind.REFUND -> -it.baseMinor
                Kind.DEBT_OUT -> Money.share(it.baseMinor, it.minor - it.principalMinor, it.minor)
                else -> 0L
            }
        })
        return Totals(income, expense)
    }
    fun byCategory(ledger: Ledger, period: Period, income: Boolean = false): Map<String, Long> {
        val kinds = if (income) setOf(Kind.INCOME) else setOf(Kind.EXPENSE, Kind.REFUND)
        return ledger.entries.filter { it.status == EntryStatus.POSTED && it.day in period && it.kind in kinds }
            .flatMap { e -> e.splits.map { it.categoryId to if (e.kind == Kind.REFUND) -it.baseMinor else it.baseMinor } }
            .groupBy({ it.first }, { it.second }).mapValues { Money.sum(it.value) }
    }
    fun byTag(ledger: Ledger, period: Period): Map<String, Long> =
        ledger.entries.filter { it.status == EntryStatus.POSTED && it.day in period && it.kind in setOf(Kind.EXPENSE, Kind.REFUND) }
            .flatMap { e -> e.tags.distinct().map { it to if (e.kind == Kind.REFUND) -e.baseMinor else e.baseMinor } }
            .groupBy({ it.first }, { it.second }).mapValues { Money.sum(it.value) }
    fun budget(ledger: Ledger, budget: Budget, day: Long): BudgetProgress {
        val target = YearMonth.from(LocalDate.ofEpochDay(day))
        var month = YearMonth.from(LocalDate.ofEpochDay(budget.startMonth))
        if (target < month) return BudgetProgress(0, 0)
        require(java.time.temporal.ChronoUnit.MONTHS.between(month, target) <= 1200)
        var carry = 0L
        fun spent(m: YearMonth): Long {
            val p = Period(m.atDay(1).toEpochDay(), m.atEndOfMonth().toEpochDay())
            if (budget.categoryId == null) return totals(ledger, p).expense
            val ids = ledger.categories.filter { it.id == budget.categoryId || it.parentId == budget.categoryId }.map { it.id }.toSet()
            return Money.sum(byCategory(ledger, p).filterKeys { it in ids }.values)
        }
        while (month < target) {
            if (budget.rollover) carry = maxOf(0, Money.sub(Money.add(budget.monthlyMinor, carry), spent(month)))
            month = month.plusMonths(1)
        }
        return BudgetProgress(Money.add(budget.monthlyMinor, carry), spent(target))
    }
    fun debtRemaining(ledger: Ledger, debt: Debt): Long = Money.sub(debt.principalMinor,
        Money.sum(ledger.entries.filter { it.status == EntryStatus.POSTED && it.debtId == debt.id }.map { it.principalMinor }))
    fun nextDate(schedule: Schedule, day: Long): Long {
        val d = LocalDate.ofEpochDay(day)
        val next = when (schedule.frequency) {
            Frequency.DAILY -> d.plusDays(schedule.interval.toLong())
            Frequency.WEEKLY -> d.plusWeeks(schedule.interval.toLong())
            Frequency.MONTHLY -> d.plusMonths(schedule.interval.toLong()).let { it.withDayOfMonth(minOf(schedule.anchorDay, it.lengthOfMonth())) }
            Frequency.YEARLY -> d.plusYears(schedule.interval.toLong()).let { it.withDayOfMonth(minOf(schedule.anchorDay, it.lengthOfMonth())) }
        }
        return next.toEpochDay()
    }
    fun generate(ledger: Ledger, through: Long, forecast: Boolean = false): Ledger {
        val newEntries = ledger.entries.toMutableList()
        val ids = newEntries.map { it.id }.toHashSet()
        val updated = ledger.schedules.map { s ->
            if (!s.enabled) return@map s
            var day = s.nextDay
            var count = 0
            while (day <= through && (s.endDay == null || day <= s.endDay)) {
                require(++count <= 5000) { "Слишком много повторений" }
                val id = UUID.nameUUIDFromBytes(("${s.id}:$day").toByteArray(Charsets.UTF_8)).toString()
                if (ids.add(id)) {
                    val t = s.template
                    val account = ledger.accounts.first { it.id == t.accountId }
                    val historical = rate(ledger, account.currency, day)
                    val currentRate = historical ?: t.rate
                    val occurrence = makeEntry(ledger, t.kind, t.accountId, t.minor, day,
                        t.splits.map { it.categoryId to it.minor }, t.note, t.tags, t.targetAccountId, t.targetMinor,
                        if (s.autoPost && !forecast && !account.archived && (t.targetAccountId == null || ledger.accounts.none { it.id == t.targetAccountId && it.archived })) EntryStatus.POSTED else EntryStatus.PLANNED,
                        currentRate, t.essential, t.timeMinutes, id, receiptIds = t.receiptIds, allowArchived = true)
                    newEntries.add(occurrence)
                }
                day = nextDate(s, day)
            }
            s.copy(nextDay = day, enabled = s.endDay == null || day <= s.endDay)
        }
        return ledger.copy(entries = newEntries, schedules = updated)
    }
    fun putEntry(ledger: Ledger, entry: Entry): Ledger {
        val original = ledger.entries.find { it.id == entry.id }
        require(original == null || ledger.entries.none { it.originalId == entry.id }) { "Сначала удалите связанные возвраты" }
        return ledger.copy(entries = ledger.entries.filterNot { it.id == entry.id } + entry).validated()
    }
    fun deleteEntry(ledger: Ledger, id: String): Ledger {
        require(ledger.entries.none { it.originalId == id }) { "Сначала удалите связанные возвраты" }
        return ledger.copy(entries = ledger.entries.filterNot { it.id == id }).validated()
    }
}

fun Ledger.validated(): Ledger {
    fun unique(ids: List<String>) = require(ids.size == ids.toSet().size && ids.none { it.isBlank() }) { "Повторяющиеся идентификаторы" }
    Money.digits(baseCurrency)
    unique(accounts.map { it.id }); unique(categories.map { it.id }); unique(entries.map { it.id })
    unique(budgets.map { it.id }); unique(debts.map { it.id }); unique(goals.map { it.id })
    unique(schedules.map { it.id }); unique(receipts.map { it.id })
    val aa = accounts.associateBy { it.id }; val cc = categories.associateBy { it.id }; val ee = entries.associateBy { it.id }
    val dd = debts.associateBy { it.id }; val rr = receipts.map { it.id }.toSet()
    accounts.forEach { require(it.name.isNotBlank()); Money.digits(it.currency) }
    categories.forEach { c ->
        require(c.name.isNotBlank())
        c.parentId?.let { p -> require(cc[p]?.parentId == null && cc[p]?.income == c.income && p != c.id && p in cc) }
    }
    require(rates.map { it.currency to it.day }.distinct().size == rates.size)
    rates.forEach { Money.digits(it.currency); require(it.currency != baseCurrency); Money.convert(0, it.currency, baseCurrency, it.unitsOfBasePerUnit) }
    fun checkEntry(e: Entry, template: Boolean = false) {
        val account = requireNotNull(aa[e.accountId])
        require(e.minor > 0 && e.baseMinor >= 0 && e.timeMinutes in 0..1439)
        LocalDate.ofEpochDay(e.day)
        if (e.kind != Kind.REFUND) require(e.baseMinor == Money.convert(e.minor, account.currency, baseCurrency, e.rate))
        if (account.currency == baseCurrency) require(BigDecimal(e.rate).compareTo(BigDecimal.ONE) == 0)
        require(e.receiptIds.all { it in rr })
        when (e.kind) {
            Kind.INCOME, Kind.EXPENSE, Kind.REFUND -> {
                require(e.targetAccountId == null && e.targetMinor == null && e.debtId == null && e.principalMinor == 0L)
                require(e.splits.isNotEmpty() && e.splits.map { it.categoryId }.distinct().size == e.splits.size)
                require(Money.sum(e.splits.map { it.minor }) == e.minor && Money.sum(e.splits.map { it.baseMinor }) == e.baseMinor)
                e.splits.forEach { require(it.minor > 0 && it.baseMinor >= 0 && cc[it.categoryId]?.income == (e.kind == Kind.INCOME)) }
                if (e.kind == Kind.REFUND) {
                    require(!template && e.status == EntryStatus.POSTED)
                    val o = requireNotNull(ee[e.originalId])
                    require(o.kind == Kind.EXPENSE && o.status == EntryStatus.POSTED && o.accountId == e.accountId && o.day <= e.day && o.rate == e.rate)
                    require(e.splits.all { s -> o.splits.any { it.categoryId == s.categoryId } })
                } else require(e.originalId == null)
            }
            Kind.TRANSFER -> {
                val target = requireNotNull(aa[e.targetAccountId])
                require(target.id != account.id && (e.targetMinor ?: 0L) > 0 && e.splits.isEmpty() && e.originalId == null && e.debtId == null && e.principalMinor == 0L)
                if (account.currency == target.currency) require(e.targetMinor == e.minor)
            }
            Kind.DEBT_IN, Kind.DEBT_OUT -> {
                val debt = requireNotNull(dd[e.debtId])
                require(debt.accountId == e.accountId && e.kind == if (debt.owedByMe) Kind.DEBT_OUT else Kind.DEBT_IN)
                require(e.principalMinor in 0..e.minor && e.splits.isEmpty() && e.originalId == null && e.targetAccountId == null && e.targetMinor == null)
                require(!template && e.status == EntryStatus.POSTED)
            }
        }
    }
    entries.forEach { checkEntry(it) }
    // Bound the absolute reporting volume so arbitrary period aggregation and
    // per-category arithmetic cannot overflow on a valid ledger.
    Money.sum(entries.map { it.baseMinor })
    require(entries.all { it.note.length <= 8_000 && it.tags.size <= 100 && it.tags.all { tag -> tag.length <= 100 } && it.splits.size <= 200 && it.receiptIds.size <= 4 })
    require(accounts.all { it.name.length <= 160 } && categories.all { it.name.length <= 160 && it.icon.length <= 24 })
    require(debts.all { it.note.length <= 8_000 && it.person.length <= 160 })
    entries.filter { it.kind == Kind.REFUND }.groupBy { it.originalId }.forEach { (id, refunds) ->
        val original = requireNotNull(ee[id])
        original.splits.forEach { s ->
            val all = refunds.flatMap { it.splits }.filter { it.categoryId == s.categoryId }
            val m = Money.sum(all.map { it.minor }); val b = Money.sum(all.map { it.baseMinor })
            require(m <= s.minor && b <= s.baseMinor)
            require(m != s.minor || b == s.baseMinor)
        }
    }
    debts.forEach {
        require(it.person.isNotBlank() && it.accountId in aa && it.principalMinor > 0 && BigDecimal(it.annualPercent).signum() >= 0)
        require(Finance.debtRemaining(this, it) >= 0) { "Погашение больше остатка долга" }
    }
    goals.forEach { Money.digits(it.currency); require(it.name.isNotBlank() && it.targetMinor > 0 && it.savedMinor >= 0) }
    budgets.forEach { require(it.name.isNotBlank() && it.monthlyMinor > 0 && (it.categoryId == null || cc[it.categoryId]?.income == false)); LocalDate.ofEpochDay(it.startMonth) }
    schedules.forEach { require(it.interval in 1..365 && it.anchorDay in 1..31 && it.template.kind !in setOf(Kind.REFUND, Kind.DEBT_IN, Kind.DEBT_OUT)); checkEntry(it.template, true); LocalDate.ofEpochDay(it.nextDay) }
    receipts.forEach { require(it.mime in setOf("image/jpeg", "image/png", "image/webp") && it.base64.length <= 8_000_000) }
    accounts.forEach { Finance.balance(this, it.id, Long.MAX_VALUE) }
    Finance.valuation(this)
    return this
}
