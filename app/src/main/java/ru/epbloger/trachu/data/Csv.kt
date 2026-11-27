package ru.epbloger.trachu.data

import ru.epbloger.trachu.core.*
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.Reader
import java.io.StringReader
import java.time.LocalDate

object Csv {
    private fun inputRows(input: InputStream, maxBytes: Int): Sequence<List<String>> {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        val maxRows = MemoryBudget.available() / 2048
        val reader = InputStreamReader(LimitedInput(input, maxBytes, MemoryBudget.fileLimit(maxBytes)), decoder)
        return rows(reader).mapIndexed { index, row ->
            require(index.toLong() < maxRows) { "Недостаточно памяти для обработки такого количества строк" }
            row
        }
    }
    private val legacyHeader = listOf("type", "id", "date", "account", "operation", "amount", "currency", "note", "data")
    private val header = legacyHeader.dropLast(1) + "data_v2"
    private fun needsEscaping(s: String) =
        s.firstOrNull() in listOf('\t', '\r', '\n', '\'') ||
            s.trimStart().firstOrNull() in listOf('=', '+', '-', '@')
    private fun safe(s: String) = if (needsEscaping(s)) "'$s" else s
    private fun unsafe(s: String) = if (s.startsWith("'") && needsEscaping(s.substring(1))) s.substring(1) else s
    private fun line(fields: List<String>) = fields.joinToString(",") { "\""+it.replace("\"", "\"\"")+"\"" }+"\r\n"
    fun write(ledger: Ledger, output: OutputStream) {
        val account = ledger.accounts.associateBy { it.id }
        val entries = ledger.entries.associateBy { it.id }
        output.bufferedWriter(Charsets.UTF_8).let { w ->
            w.write("\uFEFF"); w.write(line(header))
            LedgerRecords.records(ledger).forEach { record ->
                val e = entries[record.id].takeIf { record.kind == "entry" }
                val a = e?.let { account[it.accountId] }
                w.write(line(listOf(record.kind, safe(record.id), e?.let { LocalDate.ofEpochDay(it.day).toString() }.orEmpty(),
                    safe(a?.name.orEmpty()), e?.kind?.name.orEmpty(),
                    if (e != null && a != null) Money.input(e.minor, a.currency) else "",
                    a?.currency.orEmpty(), safe(e?.note.orEmpty()), safe(record.payload))))
            }
            w.flush()
        }
    }
    fun parse(text: String): List<List<String>> = rows(StringReader(text)).toList()

    // Buffer only the header to detect its separator, then emit one complete
    // logical row at a time (quoted fields may contain line breaks).
    private fun rows(reader: Reader): Sequence<List<String>> = sequence {
        val source = reader.buffered()
        val prefix = StringBuilder()
        var headerQuoted = false
        var commas = 0
        var semicolons = 0
        var ch = source.read()
        if (ch == 0xFEFF) ch = source.read()
        while (ch >= 0) {
            val c = ch.toChar()
            require(prefix.length < 65_536) { "Слишком длинный заголовок CSV" }
            prefix.append(c)
            if (c == '"') headerQuoted = !headerQuoted
            else if (!headerQuoted) when (c) {
                '\r', '\n' -> break
                ',' -> commas++
                ';' -> semicolons++
            }
            ch = source.read()
        }
        val separator = if (semicolons > commas) ';' else ','
        val maxFields = maxOf(128, maxOf(commas, semicolons) + 1)
        var offset = 0
        fun nextChar(): Int = if (offset < prefix.length) prefix[offset++].code else source.read()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var closed = false
        fun finishField() {
            require(row.size < maxFields) { "Слишком много столбцов CSV" }
            row.add(field.toString()); field.setLength(0); closed = false
        }
        ch = nextChar()
        while (ch >= 0) {
            val c = ch.toChar()
            ch = nextChar()
            if (quoted) {
                if (c == '"') {
                    if (ch == '"'.code) { field.append('"'); ch = nextChar() }
                    else { quoted = false; closed = true }
                } else field.append(c)
            } else when (c) {
                '"' -> { require(field.isEmpty() && !closed) { "Некорректные кавычки CSV" }; quoted = true }
                separator -> finishField()
                '\r', '\n' -> {
                    if (c == '\r' && ch == '\n'.code) ch = nextChar()
                    finishField()
                    if (row.any { it.isNotEmpty() }) yield(row)
                    row = mutableListOf()
                }
                else -> { require(!closed) { "Некорректное поле CSV" }; field.append(c) }
            }
        }
        require(!quoted) { "Незакрытые кавычки CSV" }
        if (field.isNotEmpty() || row.isNotEmpty() || closed) {
            finishField()
            if (row.any { it.isNotEmpty() }) yield(row)
        }
    }
    /** Import the complete lossless Trachu CSV. Identical IDs are idempotent; conflicts are rejected. */
    fun read(input: InputStream): Ledger {
        val rows = inputRows(input, 100*1024*1024).iterator()
        val actualHeader = if (rows.hasNext()) rows.next() else emptyList()
        val legacy = actualHeader == legacyHeader
        require(legacy || actualHeader == header) { "Нужна таблица ТРАЧУ. или формат выписки" }
        fun unescape(value: String): String = if (!legacy) unsafe(value) else
            if (value.startsWith("'") && value.getOrNull(1) in listOf('=', '+', '-', '@', '\t', '\r')) value.substring(1) else value
        require(rows.hasNext())
        val records = rows.asSequence().map {
            require(it.size == header.size)
            // Undo Excel formula protection on raw payloads.
            val payload = unescape(it[8])
            val id = unescape(it[1])
            LedgerRecord(it[0], id, payload)
        }
        return LedgerRecords.decode(records)
    }
    /** Replace only a genuinely empty ledger; never discard goals, budgets, rates or custom categories. */
    fun importFull(existing: Ledger, incoming: Ledger): Ledger {
        val defaults=Ledger.initial().categories.sortedBy{it.id}
        val hasUserData=existing.baseCurrency!=Ledger.initial().baseCurrency || existing.accounts.isNotEmpty() || existing.entries.isNotEmpty() ||
            existing.budgets.isNotEmpty() || existing.debts.isNotEmpty() || existing.goals.isNotEmpty() ||
            existing.schedules.isNotEmpty() || existing.rates.isNotEmpty() || existing.receipts.isNotEmpty() ||
            existing.categories.sortedBy{it.id}!=defaults
        return if(hasUserData)merge(existing,incoming) else incoming.validated()
    }

    fun merge(existing: Ledger, incoming: Ledger): Ledger {
        require(existing.baseCurrency == incoming.baseCurrency) { "Основные валюты различаются" }
        fun <T> mergeList(kind: String, old: List<T>, new: List<T>,
                          equivalent: (T,T) -> Boolean = { a,b -> a==b }, id: (T) -> String): List<T> {
            val merged = old.associateBy(id).toMutableMap()
            new.forEach {
                val key = id(it)
                val previous=merged[key]
                require(previous == null || equivalent(previous,it)) { "Конфликт записи: $kind / $key" }
                if(previous==null)merged[key] = it
            }
            return merged.values.toList()
        }
        return Ledger(existing.baseCurrency,
            mergeList("account",existing.accounts,incoming.accounts) { it.id },
            mergeList("category",existing.categories,incoming.categories) { it.id },
            mergeList("entry",existing.entries,incoming.entries,
                equivalent={ a,b -> a.copy(recordedAtMillis=0L)==b.copy(recordedAtMillis=0L) }) { it.id },
            mergeList("budget",existing.budgets,incoming.budgets) { it.id },
            mergeList("debt",existing.debts,incoming.debts) { it.id },
            mergeList("goal",existing.goals,incoming.goals) { it.id },
            mergeList("schedule",existing.schedules,incoming.schedules) { it.id },
            mergeList("rate",existing.rates,incoming.rates) { "${it.currency}:${it.day}" },
            mergeList("receipt",existing.receipts,incoming.receipts) { it.id }
        ).validated()
    }
    /**
     * Generic statement CSV: date,amount,category,note; optional id.
     * Amounts signed in the selected account currency; each record gets a stable
     * content+occurrence ID unless the source provides its own ID.
     */
    fun importStatement(input: InputStream, ledger: Ledger, accountId: String): Ledger {
        val rows = inputRows(input, 20*1024*1024).iterator()
        val h = if (rows.hasNext()) rows.next().map { it.trim().lowercase() } else error("Таблица пуста")
        require(h.distinct().size == h.size && listOf("date","amount","category").all { it in h }) { "Столбцы: date,amount,category,note,id" }
        val account = ledger.accounts.first { it.id == accountId && !it.archived }
        val categories = ledger.categories.toMutableList()
        val entries = ledger.entries.associateBy { it.id }.toMutableMap()
        val categoryIndex = categories.associateBy { it.name.lowercase() to it.income }.toMutableMap()
        val seen = mutableMapOf<String,Int>()
        rows.asSequence().forEachIndexed { index, row ->
            try {
                require(row.size == h.size)
                fun col(name: String) = h.indexOf(name).let { if (it < 0) "" else row[it].trim() }
                val signed = Money.parse(col("amount"), account.currency)
                require(signed != 0L && signed != Long.MIN_VALUE)
                val day = LocalDate.parse(col("date")).toEpochDay()
                require(day<=today()){"Выписка не может содержать будущие операции"}
                val income = signed > 0
                val name = col("category").ifBlank { if (income) "Другие доходы" else "Другое" }
                val category = categoryIndex.getOrPut(name.lowercase() to income) {
                    Category(name=name,income=income).also { categories.add(it) }
                }
                val raw = listOf(accountId,col("date"),signed.toString(),name,col("note")).joinToString("\u001f")
                val count = seen.getOrDefault(raw,0); seen[raw] = count+1
                val identity = col("id").takeIf { it.isNotBlank() }?.let { "$accountId:id:$it" } ?: "$raw:$count"
                val id = java.util.UUID.nameUUIDFromBytes(identity.toByteArray(Charsets.UTF_8)).toString()
                val kind = if(income) Kind.INCOME else Kind.EXPENSE
                val minor = kotlin.math.abs(signed)
                val old = entries[id]
                if (old == null) {
                    entries[id] = Finance.makeEntry(ledger,kind,accountId,minor,day,
                        listOf(category.id to minor),col("note"),id=id)
                } else require(old.status==EntryStatus.POSTED && old.accountId == accountId && old.minor == minor && old.kind == kind && old.day == day &&
                    old.note == col("note") && old.splits.map { it.categoryId } == listOf(category.id)) { "Конфликт операции" }
            } catch (e: Exception) { throw IllegalArgumentException("Строка ${index+2}: ${e.message ?: "ошибка импорта"}",e) }
        }
        // One final validation, not one full ledger traversal per CSV row.
        // The caller commits the resulting snapshot in a single Room transaction.
        return ledger.copy(categories=categories,entries=entries.values.toList()).validated()
    }
}
