package ru.epbloger.trachu.data

import ru.epbloger.trachu.core.*
import java.io.InputStream
import java.io.OutputStream
import java.time.LocalDate

object Csv {
    private val header = listOf("type", "id", "date", "account", "operation", "amount", "currency", "note", "data")
    private fun safe(s: String) = if (s.firstOrNull() in listOf('=', '+', '-', '@', '\t', '\r')) "'$s" else s
    private fun line(fields: List<String>) = fields.joinToString(",") { "\""+it.replace("\"", "\"\"")+"\"" }+"\r\n"
    fun write(ledger: Ledger, output: OutputStream) {
        val account = ledger.accounts.associateBy { it.id }
        val entries = ledger.entries.associateBy { it.id }
        output.bufferedWriter(Charsets.UTF_8).let { w ->
            w.write("\uFEFF"); w.write(line(header))
            LedgerRecords.encode(ledger).forEach { record ->
                val e = entries[record.id].takeIf { record.kind == "entry" }
                val a = e?.let { account[it.accountId] }
                w.write(line(listOf(record.kind, record.id, e?.let { LocalDate.ofEpochDay(it.day).toString() }.orEmpty(),
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
        val separator = if (s.lineSequence().firstOrNull()?.let { it.count { c -> c == ';' } > it.count { c -> c == ',' } } == true) ';' else ','
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
        require(rows.firstOrNull() == header) { "Нужна таблица ТРАЧУ. или формат выписки" }
        val records = rows.drop(1).map {
            require(it.size == header.size)
            // Undo Excel formula protection on raw payloads.
            val payload = it[8].let { p -> if (p.startsWith("'") && p.getOrNull(1) in listOf('=', '+', '-', '@', '\t', '\r')) p.substring(1) else p }
            LedgerRecord(it[0], it[1], payload)
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
        var next = ledger
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
                val category = next.categories.find { it.name.equals(name,true) && it.income == income }
                    ?: Category(name=name,income=income).also { next = next.copy(categories=next.categories+it) }
                val raw = listOf(accountId,col("date"),signed.toString(),name,col("note")).joinToString("\u001f")
                val count = seen.getOrDefault(raw,0); seen[raw] = count+1
                val identity = col("id").takeIf { it.isNotBlank() }?.let { "$accountId:id:$it" } ?: "$raw:$count"
                val id = java.util.UUID.nameUUIDFromBytes(identity.toByteArray(Charsets.UTF_8)).toString()
                val e = Finance.makeEntry(next,if(income) Kind.INCOME else Kind.EXPENSE,accountId,kotlin.math.abs(signed),day,
                    listOf(category.id to kotlin.math.abs(signed)),col("note"),id=id)
                val old = next.entries.find { it.id == id }
                if (old == null) next = Finance.putEntry(next,e)
                else require(old.accountId == e.accountId && old.minor == e.minor && old.kind == e.kind && old.day == e.day &&
                    old.note == e.note && old.splits.map { it.categoryId } == e.splits.map { it.categoryId }) { "Конфликт операции" }
            } catch (e: Exception) { throw IllegalArgumentException("Строка ${index+2}: ${e.message ?: "ошибка импорта"}",e) }
        }
        return next.validated()
    }
}
