package ru.epbloger.trachu.core

import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

data class Period(val from: Long, val through: Long) {
    init { require(from <= through) }
    operator fun contains(day: Long) = day in from..through
    companion object {
        fun month(day: Long = today(), startDay: Int = 1): Period {
            require(startDay in 1..31)
            var m = YearMonth.from(LocalDate.ofEpochDay(day))
            fun start(month: YearMonth) = month.atDay(minOf(startDay,month.lengthOfMonth())).toEpochDay()
            if(day < start(m)) m=m.minusMonths(1)
            return Period(start(m),start(m.plusMonths(1))-1)
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
    private const val INTEREST_IN="@interest-income"
    private const val INTEREST_OUT="@interest-expense"
    fun categoryLabel(ledger:Ledger,id:String):String=when(id) {
        INTEREST_IN->"Полученные проценты"
        INTEREST_OUT->"Уплаченные проценты"
        else->ledger.categories.find{it.id==id}?.name ?: "Категория"
    }
    private fun interestBase(entry:Entry):Long=Money.share(entry.baseMinor,entry.minor-entry.principalMinor,entry.minor)
    private fun expenseBase(entry:Entry):Long=when(entry.kind){
        Kind.EXPENSE->entry.baseMinor
        Kind.REFUND->-entry.baseMinor
        Kind.DEBT_OUT->interestBase(entry)
        else->0L
    }
    /** Published rates may update their own rows, but never overwrite a user-entered rate. */
    fun mergePublishedRates(ledger:Ledger,base:String,published:List<FxRate>):Ledger {
        require(ledger.baseCurrency==base) { "Основная валюта была изменена" }
        val keyed=ledger.rates.associateBy{it.currency to it.day}.toMutableMap()
        published.forEach { rate ->
            require(rate.source=="CBR")
            val key=rate.currency to rate.day
            if(keyed[key]?.source!="manual")keyed[key]=rate
        }
        return ledger.copy(rates=keyed.values.toList())
    }
    fun rate(ledger: Ledger, currency: String, day: Long): String? =
        if (currency == ledger.baseCurrency) "1" else ledger.rates
            .filter { it.currency == currency && it.day <= day }.maxByOrNull { it.day }?.unitsOfBasePerUnit

    fun balance(ledger: Ledger, accountId: String, through: Long = today()): Long {
        val account = ledger.accounts.first { it.id == accountId }
        return ledger.entries.asSequence().filter {
            it.status == EntryStatus.POSTED && it.day <= through && (it.accountId == accountId || it.targetAccountId == accountId)
        }.sortedWith(compareBy<Entry> { it.day }.thenBy { it.timeMinutes }.thenBy { it.id }).fold(account.openingMinor) { total, e ->
            val delta = when {
                e.kind == Kind.TRANSFER && e.targetAccountId == accountId -> requireNotNull(e.targetMinor)
                e.accountId != accountId -> 0L
                e.kind in listOf(Kind.INCOME, Kind.REFUND, Kind.DEBT_IN, Kind.LOAN_IN) -> e.minor
                else -> -e.minor
            }
            Money.add(total, delta)
        }
    }
    fun valuation(ledger: Ledger, day: Long = today(), rateDay: Long = day): Valuation {
        val missing = mutableSetOf<String>()
        val total = Money.sum(ledger.accounts.filter { it.includeInTotal }.map { a ->
            val value = balance(ledger, a.id, day)
            val r = rate(ledger, a.currency, rateDay)
            if (r == null) { if (value != 0L) missing.add(a.currency); 0L }
            else Money.convert(value, a.currency, ledger.baseCurrency, r)
        })
        return Valuation(total, missing)
    }
    /** Project actual account movements, including transfers, at today's known rates.
     * This is a scenario, not a forecast of foreign exchange prices. */
    fun projectedValuation(ledger: Ledger, planned: List<Entry>, through: Long, rateDay: Long = today()): Valuation {
        require(planned.all { it.status == EntryStatus.PLANNED && it.day <= through })
        require(planned.map { it.id }.distinct().size == planned.size)
        val posted = ledger.entries.filter { it.status == EntryStatus.POSTED }
        val postedIds = posted.map { it.id }.toSet()
        require(planned.none { it.id in postedIds })
        return valuation(ledger.copy(entries=posted+planned.map{it.copy(status=EntryStatus.POSTED,autoPostPlanned=false)}),through,rateDay)
    }
    fun makeEntry(
        ledger: Ledger, kind: Kind, accountId: String, minor: Long, day: Long,
        categoryAmounts: List<Pair<String, Long>> = emptyList(), note: String = "",
        tags: List<String> = emptyList(), targetId: String? = null, targetMinor: Long? = null,
        status: EntryStatus = EntryStatus.POSTED, explicitRate: String? = null,
        essential: Boolean = false, timeMinutes: Int = 720, id: String = newId(),
        debtId: String? = null, principalMinor: Long = 0, receiptIds: List<String> = emptyList(),
        allowArchived: Boolean = false, autoPostPlanned: Boolean = false
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
            receiptIds = receiptIds, rate = r, autoPostPlanned = autoPostPlanned)
    }
    /** Remaining original-currency and reporting-currency amounts per purchase category. */
    fun refundableSplits(ledger: Ledger, originalId: String, excludingRefundId: String? = null): List<Split> {
        val original = ledger.entries.first { it.id == originalId && it.kind == Kind.EXPENSE && it.status == EntryStatus.POSTED }
        val existing = ledger.entries.filter { it.kind == Kind.REFUND && it.originalId == originalId && it.id != excludingRefundId }
            .flatMap { it.splits }.groupBy { it.categoryId }
        return original.splits.map { split ->
            val used = existing[split.categoryId].orEmpty()
            val usedMinor = Money.sum(used.map { it.minor })
            val usedBase = Money.sum(used.map { it.baseMinor })
            val remainingMinor = Money.sub(split.minor, usedMinor)
            val remainingBase = Money.sub(split.baseMinor, usedBase)
            Split(split.categoryId, remainingMinor, remainingBase)
        }.filter { it.minor > 0 }
    }
    fun refund(ledger: Ledger, originalId: String, amount: Long, day: Long, note: String = "", id: String = newId(),
               categoryAmounts: List<Pair<String, Long>>? = null): Entry {
        val original = ledger.entries.first { it.id == originalId && it.kind == Kind.EXPENSE && it.status == EntryStatus.POSTED }
        require(day >= original.day && amount > 0)
        val remaining = refundableSplits(ledger, originalId, id)
        require(amount <= Money.sum(remaining.map { it.minor })) { "Возврат превышает остаток покупки" }
        val requested = categoryAmounts?.also { amounts ->
            require(amounts.isNotEmpty() && amounts.map { it.first }.distinct().size == amounts.size &&
                amounts.all { (category,value) -> value > 0 && remaining.any { it.categoryId == category && value <= it.minor } } &&
                Money.sum(amounts.map { it.second }) == amount) { "Проверьте распределение возврата по категориям" }
        }?.toMap()
        var left = amount
        val splits = remaining.mapNotNull { split ->
            val take = if(requested == null) minOf(left, split.minor) else requested[split.categoryId] ?: 0L
            left = Money.sub(left, take)
            if (take == 0L) null else Split(split.categoryId, take,
                Money.share(split.baseMinor, take, split.minor))
        }
        require(left == 0L)
        return Entry(id, Kind.REFUND, original.accountId, amount, Money.sum(splits.map { it.baseMinor }),
            day, note = note, tags = original.tags, splits = splits, originalId = originalId, rate = original.rate,
            essential = original.essential)
    }
    fun totals(ledger: Ledger, period: Period): Totals {
        val excluded=ledger.accounts.filterNot { it.includeInStats }.map { it.id }.toSet()
        val posted = ledger.entries.filter { it.status == EntryStatus.POSTED && it.day in period && it.accountId !in excluded }
        val income = Money.sum(posted.map {
            when (it.kind) {
                Kind.INCOME -> it.baseMinor
                Kind.DEBT_IN -> interestBase(it)
                else -> 0L
            }
        })
        val expense = Money.sum(posted.map(::expenseBase))
        return Totals(income, expense)
    }
    fun byCategory(ledger: Ledger, period: Period, income: Boolean = false): Map<String, Long> {
        val kinds = if(income)setOf(Kind.INCOME,Kind.DEBT_IN) else setOf(Kind.EXPENSE,Kind.REFUND,Kind.DEBT_OUT)
        val excluded=ledger.accounts.filterNot { it.includeInStats }.map { it.id }.toSet()
        return ledger.entries.filter { it.status == EntryStatus.POSTED && it.day in period && it.kind in kinds && it.accountId !in excluded }
            .flatMap { e ->
                when(e.kind) {
                    Kind.DEBT_IN->listOf(INTEREST_IN to interestBase(e)).filter{it.second!=0L}
                    Kind.DEBT_OUT->listOf(INTEREST_OUT to interestBase(e)).filter{it.second!=0L}
                    else->e.splits.map { it.categoryId to if(e.kind==Kind.REFUND)-it.baseMinor else it.baseMinor }
                }
            }.groupBy({it.first},{it.second}).mapValues{Money.sum(it.value)}
    }
    fun byTag(ledger: Ledger, period: Period): Map<String, Long> =
        ledger.entries.filter { it.status == EntryStatus.POSTED && it.day in period && it.kind in setOf(Kind.EXPENSE, Kind.REFUND) && ledger.accounts.any { a -> a.id==it.accountId && a.includeInStats } }
            .flatMap { e -> e.tags.distinct().map { it to if (e.kind == Kind.REFUND) -e.baseMinor else e.baseMinor } }
            .groupBy({ it.first }, { it.second }).mapValues { Money.sum(it.value) }
    fun budget(ledger: Ledger, budget: Budget, day: Long): BudgetProgress {
        val target=YearMonth.from(LocalDate.ofEpochDay(day))
        val start=YearMonth.from(LocalDate.ofEpochDay(budget.startMonth))
        if(target<start)return BudgetProgress(0,0)
        val first=if(budget.rollover)start else target
        val included=ledger.accounts.filter{it.includeInStats}.map{it.id}.toSet()
        val ids=ledger.categories.filter{it.id==budget.categoryId || it.parentId==budget.categoryId}.map{it.id}.toSet()
        val spentByMonth=ledger.entries.asSequence().filter{
            it.status==EntryStatus.POSTED && it.accountId in included &&
                it.day in first.atDay(1).toEpochDay()..target.atEndOfMonth().toEpochDay()
        }.map { entry ->
            val spent=if(budget.categoryId==null)expenseBase(entry)
            else if(entry.kind in setOf(Kind.EXPENSE,Kind.REFUND)) {
                val value=Money.sum(entry.splits.filter{it.categoryId in ids}.map{it.baseMinor})
                if(entry.kind==Kind.REFUND)-value else value
            } else 0L
            YearMonth.from(LocalDate.ofEpochDay(entry.day)) to spent
        }.groupBy({it.first},{it.second}).mapValues{Money.sum(it.value)}
        val monthly=java.math.BigInteger.valueOf(budget.monthlyMinor)
        var carry=java.math.BigInteger.ZERO
        if(budget.rollover) {
            var cursor=start
            fun gap(from:YearMonth,to:YearMonth)=java.math.BigInteger.valueOf(java.time.temporal.ChronoUnit.MONTHS.between(from,to))
            spentByMonth.toSortedMap().filterKeys{it<target}.forEach { (month,spent)->
                carry=carry.add(monthly.multiply(gap(cursor,month))).add(monthly)
                    .subtract(java.math.BigInteger.valueOf(spent)).max(java.math.BigInteger.ZERO)
                cursor=month.plusMonths(1)
            }
            carry=carry.add(monthly.multiply(gap(cursor,target)))
        }
        val available=monthly.add(carry)
        // BigInteger.longValueExact() is only available from Android API 31.
        // Preserve exact overflow detection on every supported Android version.
        if(available.bitLength()>63)throw ArithmeticException("Лимит бюджета слишком большой")
        return BudgetProgress(available.toLong(),spentByMonth[target] ?: 0L)
    }
    fun debtRemaining(ledger: Ledger, debt: Debt): Long = Money.sub(debt.principalMinor,
        Money.sum(ledger.entries.filter { it.status == EntryStatus.POSTED && it.debtId == debt.id && it.kind in setOf(Kind.DEBT_IN, Kind.DEBT_OUT) }.map { it.principalMinor }))

    fun goalSaved(goal: Goal): Long = Money.add(goal.savedMinor, Money.sum(goal.adjustments.map { it.minor }))

    fun estimatedInterest(ledger: Ledger, debt: Debt, days: Int): Long {
        require(days in 1..366)
        return BigDecimal.valueOf(debtRemaining(ledger, debt)).multiply(BigDecimal(debt.annualPercent))
            .multiply(BigDecimal(days)).divide(BigDecimal(36500), 0, java.math.RoundingMode.HALF_EVEN).longValueExact()
    }
    /** Simple Actual/365 estimate using each historical outstanding principal.
     * Payments reduce principal from their posting date. Actual paid interest is
     * tracked separately; contract fees and compounding are not inferred. */
    fun accruedInterest(ledger: Ledger, debt: Debt, through: Long = today()): Long? {
        val start = debt.openedDay ?: return null
        require(through >= start)
        var cursor = start
        var principal = debt.principalMinor
        var principalDays = BigDecimal.ZERO
        val payments = ledger.entries.filter {
            it.debtId == debt.id && it.status == EntryStatus.POSTED &&
                it.kind in setOf(Kind.DEBT_IN, Kind.DEBT_OUT) && it.day in start..through
        }.sortedBy { it.day }
        payments.forEach { payment ->
            principalDays = principalDays.add(BigDecimal.valueOf(principal).multiply(BigDecimal.valueOf(payment.day - cursor)))
            principal = Money.sub(principal, payment.principalMinor)
            cursor = payment.day
        }
        principalDays = principalDays.add(BigDecimal.valueOf(principal).multiply(BigDecimal.valueOf(through - cursor)))
        return principalDays.multiply(BigDecimal(debt.annualPercent))
            .divide(BigDecimal(36500), 0, java.math.RoundingMode.HALF_EVEN).longValueExact()
    }
    fun paidInterest(ledger: Ledger, debt: Debt, through: Long = today()): Long =
        Money.sum(ledger.entries.filter {
            it.debtId == debt.id && it.status == EntryStatus.POSTED && it.day <= through &&
                it.kind in setOf(Kind.DEBT_IN, Kind.DEBT_OUT)
        }.map { Money.sub(it.minor, it.principalMinor) })
    fun nextDate(schedule: Schedule, day: Long): Long {
        val d = LocalDate.ofEpochDay(day)
        val next = when (schedule.frequency) {
            Frequency.DAILY -> d.plusDays(schedule.interval.toLong())
            Frequency.WEEKLY -> {
                if(schedule.weekdays.isEmpty()) d.plusWeeks(schedule.interval.toLong())
                else if(schedule.alternateWeekdays.isNotEmpty()) {
                    val anchor=LocalDate.ofEpochDay(schedule.anchorEpochDay).with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
                    (1L..14L).map { d.plusDays(it) }.first { candidate ->
                        val week=java.time.temporal.ChronoUnit.WEEKS.between(anchor,candidate.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY)))
                        val weekdays=if(Math.floorMod(week,2L)==0L)schedule.weekdays else schedule.alternateWeekdays
                        candidate.dayOfWeek.value in weekdays
                    }
                } else {
                    val weekStart=d.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
                    val later=schedule.weekdays.sorted().firstOrNull { it>d.dayOfWeek.value }
                    if(later!=null)weekStart.plusDays(later-1L)
                    else weekStart.plusWeeks(schedule.interval.toLong()).plusDays(schedule.weekdays.min()-1L)
                }
            }
            Frequency.MONTHLY -> {
                if(schedule.monthDays.isEmpty()) d.plusMonths(schedule.interval.toLong()).let { it.withDayOfMonth(minOf(schedule.anchorDay, it.lengthOfMonth())) }
                else {
                    val current=YearMonth.from(d)
                    fun days(m: YearMonth)=schedule.monthDays.map{minOf(it,m.lengthOfMonth())}.distinct().sorted()
                    val later=days(current).firstOrNull{it>d.dayOfMonth}
                    if(later!=null)current.atDay(later)
                    else current.plusMonths(schedule.interval.toLong()).let{it.atDay(days(it).first())}
                }
            }
            Frequency.YEARLY -> d.plusYears(schedule.interval.toLong()).let { it.withDayOfMonth(minOf(schedule.anchorDay, it.lengthOfMonth())) }
        }
        return next.toEpochDay()
    }
    fun isDue(entry: Entry, through: Long, throughMinutes: Int? = null): Boolean =
        entry.day < through || (entry.day == through && (throughMinutes == null || entry.timeMinutes <= throughMinutes))

    fun generate(ledger: Ledger, through: Long, forecast: Boolean = false, throughMinutes: Int? = null): Ledger {
        val activeAccounts = ledger.accounts.filterNot { it.archived }.map { it.id }.toSet()
        val newEntries = ledger.entries.map { entry ->
            if (forecast || entry.status != EntryStatus.PLANNED || !entry.autoPostPlanned || !isDue(entry,through,throughMinutes)) entry
            else if(entry.accountId in activeAccounts && (entry.targetAccountId == null || entry.targetAccountId in activeAccounts))
                entry.copy(status=EntryStatus.POSTED,autoPostPlanned=false)
            else entry.copy(autoPostPlanned=false)
        }.toMutableList()
        val ids = newEntries.map { it.id }.toHashSet()
        val updated = ledger.schedules.map { s ->
            if (!s.enabled) return@map s
            var day = s.nextDay
            var count = 0
            while ((day < through || day == through && (throughMinutes == null || s.template.timeMinutes <= throughMinutes)) && (s.endDay == null || day <= s.endDay)) {
                // A very old schedule must not permanently block all subsequent
                // work. Process bounded batches; nextDay resumes the next batch.
                if(++count > 5000) {
                    require(!forecast) { "Слишком много пропущенных повторений для прогноза" }
                    break
                }
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
        require(ledger.entries.none { it.id == id && it.kind in setOf(Kind.LOAN_IN,Kind.LOAN_OUT) }) { "Измените связанную запись долга" }
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
        require(c.name.isNotBlank() && !c.id.startsWith("@"))
        c.parentId?.let { p -> require(cc[p]?.parentId == null && cc[p]?.income == c.income && p != c.id && p in cc) }
    }
    require(rates.map { it.currency to it.day }.distinct().size == rates.size)
    rates.forEach { Money.digits(it.currency); require(it.currency != baseCurrency); Money.convert(0, it.currency, baseCurrency, it.unitsOfBasePerUnit) }
    fun checkEntry(e: Entry, template: Boolean = false) {
        val account = requireNotNull(aa[e.accountId])
        require(e.minor > 0 && e.baseMinor >= 0 && e.timeMinutes in 0..1439)
        require(!e.autoPostPlanned || !template && e.status == EntryStatus.PLANNED &&
            e.kind in setOf(Kind.EXPENSE,Kind.INCOME,Kind.TRANSFER))
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
            Kind.DEBT_IN, Kind.DEBT_OUT, Kind.LOAN_IN, Kind.LOAN_OUT -> {
                val debt = requireNotNull(dd[e.debtId])
                val opening = e.kind in setOf(Kind.LOAN_IN,Kind.LOAN_OUT)
                val expected = if(opening) {
                    if(debt.owedByMe) Kind.LOAN_IN else Kind.LOAN_OUT
                } else {
                    if(debt.owedByMe) Kind.DEBT_OUT else Kind.DEBT_IN
                }
                require(debt.accountId == e.accountId && e.kind == expected)
                require(e.principalMinor in 0..e.minor && e.splits.isEmpty() && e.originalId == null && e.targetAccountId == null && e.targetMinor == null)
                if(opening) require(e.principalMinor == debt.principalMinor && e.minor == debt.principalMinor && debt.openedDay == e.day)
                else require(debt.openedDay == null || e.day >= debt.openedDay)
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
        val percent=BigDecimal(it.annualPercent)
        require(it.person.isNotBlank() && it.accountId in aa && it.principalMinor > 0 && percent >= BigDecimal.ZERO && percent <= BigDecimal("1000") && percent.scale() in -3..6)
        val openings=entries.filter { e -> e.debtId == it.id && e.kind in setOf(Kind.LOAN_IN,Kind.LOAN_OUT) }
        require(openings.size == if(it.openedDay==null) 0 else 1)
        require(Finance.debtRemaining(this, it) >= 0) { "Погашение больше остатка долга" }
    }
    goals.forEach {
        Money.digits(it.currency)
        require(it.name.isNotBlank() && it.targetMinor > 0 && it.savedMinor >= 0 && it.adjustments.size <= 500)
        unique(it.adjustments.map { a -> a.id })
        var saved=it.savedMinor
        it.adjustments.forEach { a -> require(a.minor!=0L && a.note.length<=200); LocalDate.ofEpochDay(a.day); saved=Money.add(saved,a.minor); require(saved>=0) }
    }
    budgets.forEach {
        require(it.name.isNotBlank() && it.monthlyMinor>0 && (it.categoryId==null || cc[it.categoryId]?.income==false))
        LocalDate.ofEpochDay(it.startMonth)
        Finance.budget(this,it,today()).remaining
    }
    schedules.forEach { require(it.interval in 1..365 && it.anchorDay in 1..31 && it.template.kind in setOf(Kind.EXPENSE, Kind.INCOME, Kind.TRANSFER)); checkEntry(it.template, true); LocalDate.ofEpochDay(it.nextDay)
        require(it.weekdays.all { d->d in 1..7 } && it.weekdays.distinct().size==it.weekdays.size)
        require(it.alternateWeekdays.all { d->d in 1..7 } && it.alternateWeekdays.distinct().size==it.alternateWeekdays.size)
        require(it.alternateWeekdays.isEmpty() || it.interval==2 && it.frequency==Frequency.WEEKLY && it.weekdays.isNotEmpty())
        require(it.monthDays.all { d->d in 1..31 } && it.monthDays.distinct().size==it.monthDays.size)
        LocalDate.ofEpochDay(it.anchorEpochDay)
    }
    receipts.forEach { require(it.mime in setOf("image/jpeg", "image/png", "image/webp") && it.base64.length <= 8_000_000) }
    accounts.forEach { Finance.balance(this, it.id, Long.MAX_VALUE) }
    Finance.valuation(this)
    return this
}
