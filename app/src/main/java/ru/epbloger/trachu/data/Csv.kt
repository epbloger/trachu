package ru.epbloger.trachu.data

import ru.epbloger.trachu.core.*
import java.io.InputStream
import java.io.OutputStream
import java.time.LocalDate

object Csv {
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
            LedgerRecords.encode(ledger).forEach { record ->
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
    fun parse(text: String): List<List<String>> {
        val result = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var closed = false
        var i = 0
        val s = text.removePrefix("\uFEFF")
        // Count delimiters in the header outside quotes, not in quoted fields.
        var headerQuoted = false
        var commas = 0
        var semicolons = 0
        for (c in s) {
            if (c == '"') headerQuoted = !headerQuoted
            else if (!headerQuoted) when (c) {
                '\r', '\n' -> break
                ',' -> commas++
                ';' -> semicolons++
            }
        }
        val separator = if (semicolons > commas) ';' else ','
        fun finishField() { row.add(field.toString()); field.setLength(0); closed = false }
        fun finishRow() { finishField(); if (row.any { it.isNotEmpty() }) result.add(row); row = mutableListOf() }
        while (i < s.length) {
            val c = s[i++]
            if (quoted) {
                if (c == '"') {
                    if (i < s.length && s[i] == '"') { field.append('"'); i++ }
                    else { quoted = false; closed = true }
                } else field.append(c)
            } else when (c) {
                '"' -> { require(field.isEmpty() && !closed) { "Некорректные кавычки CSV" }; quoted = true }
                separator -> finishField()
                '\r', '\n' -> { if (c == '\r' && i < s.length && s[i] == '\n') i++; finishRow() }
                else -> { require(!closed) { "Некорректное поле CSV" }; field.append(c) }
            }
        }
        require(!quoted) { "Незакрытые кавычки CSV" }
        if (field.isNotEmpty() || row.isNotEmpty() || closed) finishRow()
        return result
    }
    /** Import the complete lossless Trachu CSV. Identical IDs are idempotent; conflicts are rejected. */
    fun read(input: InputStream): Ledger {
        val rows = parse(input.readBounded(100*1024*1024).toString(Charsets.UTF_8))
        val legacy = rows.firstOrNull() == legacyHeader
        require(legacy || rows.firstOrNull() == header) { "Нужна таблица ТРАЧУ. или формат выписки" }
        fun unescape(value: String): String = if (!legacy) unsafe(value) else
            if (value.startsWith("'") && value.getOrNull(1) in listOf('=', '+', '-', '@', '\t', '\r')) value.substring(1) else value
        val records = rows.drop(1).map {
            require(it.size == header.size)
            // Undo Excel formula protection on raw payloads.
            val payload = unescape(it[8])
            val id = unescape(it[1])
            LedgerRecord(it[0], id, payload)
        }
        require(records.isNotEmpty())
        return LedgerRecords.decode(records)
    }
    fun merge(existing: Ledger, incoming: Ledger): Ledger {
        require(existing.baseCurrency == incoming.baseCurrency) { "Основные валюты различаются" }
        val merged = LedgerRecords.encode(existing).associateBy { it.kind to it.id }.toMutableMap()
        LedgerRecords.encode(incoming).forEach {
            val key = it.kind to it.id
            require(merged[key] == null || merged[key] == it) { "Конфликт записи: ${it.kind} / ${it.id}" }
            merged[key] = it
        }
        return LedgerRecords.decode(merged.values.toList())
    }
    /**
     * Generic statement CSV: date,amount,category,note; optional id.
     * Amounts signed in the selected account currency; each record gets a stable
     * content+occurrence ID unless the source provides its own ID.
     */
    fun importStatement(input: InputStream, ledger: Ledger, accountId: String): Ledger {
        val rows = parse(input.readBounded(20*1024*1024).toString(Charsets.UTF_8))
        val h = rows.firstOrNull()?.map { it.trim().lowercase() } ?: error("Таблица пуста")
        require(h.distinct().size == h.size && listOf("date","amount","category").all { it in h }) { "Столбцы: date,amount,category,note,id" }
        val account = ledger.accounts.first { it.id == accountId && !it.archived }
        val categories = ledger.categories.toMutableList()
        val entries = ledger.entries.associateBy { it.id }.toMutableMap()
        val categoryIndex = categories.associateBy { it.name.lowercase() to it.income }.toMutableMap()
        val seen = mutableMapOf<String,Int>()
        rows.drop(1).forEachIndexed { index, row ->
            try {
                require(row.size == h.size)
                fun col(name: String) = h.indexOf(name).let { if (it < 0) "" else row[it].trim() }
                val signed = Money.parse(col("amount"), account.currency)
                require(signed != 0L && signed != Long.MIN_VALUE)
                val day = LocalDate.parse(col("date")).toEpochDay()
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
                } else require(old.accountId == accountId && old.minor == minor && old.kind == kind && old.day == day &&
                    old.note == col("note") && old.splits.map { it.categoryId } == listOf(category.id)) { "Конфликт операции" }
            } catch (e: Exception) { throw IllegalArgumentException("Строка ${index+2}: ${e.message ?: "ошибка импорта"}",e) }
        }
        // One final validation, not one full ledger traversal per CSV row.
        // The caller commits the resulting snapshot in a single Room transaction.
        return ledger.copy(categories=categories,entries=entries.values.toList()).validated()
    }
}
