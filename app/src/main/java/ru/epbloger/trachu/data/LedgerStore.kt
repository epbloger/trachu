package ru.epbloger.trachu.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ru.epbloger.trachu.core.*

@Entity(tableName = "ledger_records", primaryKeys = ["kind", "id"])
data class LedgerRecord(val kind: String, val id: String, val payload: String,
    @ColumnInfo(defaultValue = "1") val formatVersion: Int = 1)

// Prevent a single imported string/record from exceeding CursorWindow capacity.
// Receipt bodies are already chunked separately; ordinary records stay far smaller.
private fun LedgerRecord.checkedSize(): LedgerRecord = apply {
    require(id.length <= 4096 && payload.length <= 512 * 1024 &&
        id.toByteArray(Charsets.UTF_8).size.toLong() + payload.toByteArray(Charsets.UTF_8).size <= 512 * 1024) {
        "Отдельная финансовая запись слишком большая"
    }
}

data class RecordStats(val bytes: Long, @ColumnInfo(name = "row_count") val rows: Long) {
    // Includes decoded strings, receipt builders and per-object/list overhead.
    fun estimatedMemory(): Long = bytes * 4 + rows * 1024
}

@Dao
interface LedgerDao {
    @Query("SELECT * FROM ledger_records ORDER BY kind, id")
    suspend fun read(): List<LedgerRecord>
    @Query("SELECT COALESCE(SUM(length(CAST(payload AS BLOB)) + length(CAST(id AS BLOB)) + length(CAST(kind AS BLOB))), 0) AS bytes, COUNT(*) AS row_count FROM ledger_records")
    suspend fun stats(): RecordStats
    @Query("SELECT * FROM ledger_records WHERE kind = :kind AND id > :after ORDER BY id LIMIT :limit")
    suspend fun page(kind: String, after: String, limit: Int): List<LedgerRecord>
    @Query("SELECT * FROM ledger_records WHERE kind = 'receipt_chunk' AND id >= :prefix AND id < :end AND length(CAST(id AS BLOB)) = length(CAST(:prefix AS BLOB)) + 6 AND id > :after ORDER BY id LIMIT :limit")
    suspend fun receiptPage(prefix: String, end: String, after: String, limit: Int): List<LedgerRecord>
    @Query("DELETE FROM ledger_records WHERE kind = 'receipt_chunk' AND id >= :prefix AND id < :end AND length(CAST(id AS BLOB)) = length(CAST(:prefix AS BLOB)) + 6")
    suspend fun deleteReceiptChunks(prefix: String, end: String)
    @Query("DELETE FROM ledger_records")
    suspend fun clear()
    @Upsert suspend fun put(records: List<LedgerRecord>)
    @Delete suspend fun delete(records: List<LedgerRecord>)
}

@Database(entities = [LedgerRecord::class], version = 2, exportSchema = true)
abstract class TrachuDatabase : RoomDatabase() {
    abstract fun ledgerDao(): LedgerDao
    companion object {
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1,2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE ledger_records ADD COLUMN formatVersion INTEGER NOT NULL DEFAULT 1")
            }
        }
        @Volatile private var instance: TrachuDatabase? = null
        fun get(context: Context): TrachuDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, TrachuDatabase::class.java, "trachu.db")
                .addMigrations(MIGRATION_1_2).build().also { instance = it }
        }
    }
}

/**
 * Records use stable IDs and individually versioned JSON payloads. Receipt bytes are
 * partitioned to keep any SQLite cursor row well below Android CursorWindow limits.
 * Future database upgrades must register migrations; destructive fallback is forbidden.
 */
object LedgerRecords {
    val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
    private const val chunkSize = 24_000
    internal fun receiptRows(receipt: Receipt): Sequence<LedgerRecord> = sequence {
        yield(LedgerRecord("receipt", receipt.id, receipt.mime))
        for (start in receipt.base64.indices step chunkSize) {
            val index = start / chunkSize
            yield(LedgerRecord("receipt_chunk", "${receipt.id}:${index.toString().padStart(6,'0')}",
                receipt.base64.substring(start, minOf(start + chunkSize, receipt.base64.length))))
        }
    }
    fun records(ledger: Ledger): Sequence<LedgerRecord> = sequence {
        yield(LedgerRecord("meta", "base", ledger.baseCurrency))
        ledger.accounts.forEach { yield(LedgerRecord("account", it.id, json.encodeToString(it))) }
        ledger.categories.forEach { yield(LedgerRecord("category", it.id, json.encodeToString(it))) }
        ledger.entries.forEach { yield(LedgerRecord("entry", it.id, json.encodeToString(it))) }
        ledger.budgets.forEach { yield(LedgerRecord("budget", it.id, json.encodeToString(it))) }
        ledger.debts.forEach { yield(LedgerRecord("debt", it.id, json.encodeToString(it))) }
        ledger.goals.forEach { yield(LedgerRecord("goal", it.id, json.encodeToString(it))) }
        ledger.schedules.forEach { yield(LedgerRecord("schedule", it.id, json.encodeToString(it))) }
        ledger.rates.forEach { yield(LedgerRecord("rate", "${it.currency}:${it.day}", json.encodeToString(it))) }
        ledger.receipts.forEach { yieldAll(receiptRows(it)) }
    }
    fun encode(ledger: Ledger): List<LedgerRecord> = records(ledger).toList()

    internal fun stats(ledger: Ledger): RecordStats {
        var bytes = 0L
        var rows = 0L
        records(ledger).forEach {
            it.checkedSize()
            bytes += it.kind.toByteArray(Charsets.UTF_8).size.toLong() +
                it.id.toByteArray(Charsets.UTF_8).size + it.payload.toByteArray(Charsets.UTF_8).size
            rows++
        }
        return RecordStats(bytes, rows)
    }

    internal class ReceiptBuilder(private val row: LedgerRecord) {
        private val data = StringBuilder()
        private var count = 0
        fun append(part: LedgerRecord) {
            require(part.formatVersion == 1) { "Неподдерживаемая версия записи" }
            require(part.kind == "receipt_chunk" &&
                part.id == "${row.id}:${count.toString().padStart(6,'0')}" &&
                part.payload.length <= 8_000_000 - data.length) { "Повреждённые данные чека" }
            data.append(part.payload)
            count++
        }
        fun build(): Receipt {
            require(count > 0) { "Отсутствуют данные чека" }
            return Receipt(row.id, row.payload, data.toString())
        }
    }

    internal class Decoder {
        private var base: String? = null
        private val accounts = mutableListOf<Account>()
        private val categories = mutableListOf<Category>()
        private val entries = mutableListOf<Entry>()
        private val budgets = mutableListOf<Budget>()
        private val debts = mutableListOf<Debt>()
        private val goals = mutableListOf<Goal>()
        private val schedules = mutableListOf<Schedule>()
        private val rates = mutableListOf<FxRate>()
        val receiptMetadata = mutableListOf<LedgerRecord>()
        fun add(row: LedgerRecord) {
            require(row.formatVersion == 1) { "Неподдерживаемая версия записи" }
            fun <T> checked(value: T, id: (T) -> String): T = value.also { require(id(it) == row.id) }
            when (row.kind) {
                "meta" -> { require(base == null && row.id == "base"); base = row.payload }
                "account" -> accounts.add(checked(json.decodeFromString<Account>(row.payload)) { it.id })
                "category" -> categories.add(checked(json.decodeFromString<Category>(row.payload)) { it.id })
                "entry" -> entries.add(checked(json.decodeFromString<Entry>(row.payload)) { it.id })
                "budget" -> budgets.add(checked(json.decodeFromString<Budget>(row.payload)) { it.id })
                "debt" -> debts.add(checked(json.decodeFromString<Debt>(row.payload)) { it.id })
                "goal" -> goals.add(checked(json.decodeFromString<Goal>(row.payload)) { it.id })
                "schedule" -> schedules.add(checked(json.decodeFromString<Schedule>(row.payload)) { it.id })
                "rate" -> rates.add(checked(json.decodeFromString<FxRate>(row.payload)) { "${it.currency}:${it.day}" })
                "receipt" -> receiptMetadata.add(row)
                else -> error("Неподдерживаемый тип записи")
            }
        }
        fun build(receipts: List<Receipt>): Ledger = Ledger(
            base ?: error("Отсутствует основная валюта"), accounts, categories, entries,
            budgets, debts, goals, schedules, rates, receipts
        ).validated()
    }

    fun decode(rows: List<LedgerRecord>): Ledger = decode(rows.asSequence())
    internal fun decode(rows: Sequence<LedgerRecord>): Ledger {
        val seen = mutableSetOf<Pair<String, String>>()
        val decoder = Decoder()
        val chunks = mutableMapOf<String, MutableList<LedgerRecord>>()
        rows.forEach {
            require(seen.add(it.kind to it.id))
            if (it.kind == "receipt_chunk") chunks.getOrPut(it.id.substringBeforeLast(':')) { mutableListOf() }.add(it)
            else decoder.add(it)
        }
        if (seen.isEmpty()) return Ledger.initial()
        val receipts = decoder.receiptMetadata.map { row ->
            val builder = ReceiptBuilder(row)
            chunks.remove(row.id).orEmpty().sortedBy { it.id }.forEach(builder::append)
            builder.build()
        }
        require(chunks.isEmpty())
        return decoder.build(receipts)
    }
}

class LedgerStore(private val db: TrachuDatabase) {
    private val dao get() = db.ledgerDao()
    private val pageSize = 32
    val ledger: Flow<Ledger> = db.invalidationTracker.createFlow("ledger_records")
        .map { current() }.flowOn(Dispatchers.Default)
    suspend fun current(): Ledger = db.withTransaction { readSnapshot(dao.stats()) }

    private suspend fun readSnapshot(stats: RecordStats): Ledger {
        if (stats.rows == 0L) return Ledger.initial()
        require(stats.estimatedMemory() <= MemoryBudget.available()) {
            "Недостаточно памяти для открытия финансовых данных"
        }
        val decoder = LedgerRecords.Decoder()
        var count = 0L
        for (kind in listOf("account","budget","category","debt","entry","goal","meta","rate","receipt","schedule")) {
            var after = ""
            while (true) {
                val rows = dao.page(kind, after, pageSize)
                if (rows.isEmpty()) break
                rows.forEach(decoder::add)
                count += rows.size
                after = rows.last().id
            }
        }
        val receipts = decoder.receiptMetadata.map { row ->
            val builder = LedgerRecords.ReceiptBuilder(row)
            val prefix = "${row.id}:"
            val end = "${row.id};"
            var after = prefix
            while (true) {
                val parts = dao.receiptPage(prefix, end, after, pageSize)
                if (parts.isEmpty()) break
                parts.forEach(builder::append)
                count += parts.size
                after = parts.last().id
            }
            builder.build()
        }
        require(count == stats.rows) { "Повреждённые финансовые данные" }
        return decoder.build(receipts)
    }

    private fun prune(transformed: Ledger): Ledger {
        val usedReceipts = (transformed.entries.flatMap { it.receiptIds } + transformed.schedules.flatMap { it.template.receiptIds }).toSet()
        return transformed.copy(receipts = transformed.receipts.filter { it.id in usedReceipts }).validated()
    }

    // Avoid committing a growing database that cannot be loaded alongside the
    // current UI snapshot on this heap. Smaller replacements remain possible.
    private suspend fun checkGrowth(before: RecordStats) {
        val after = dao.stats().estimatedMemory()
        require(after <= before.estimatedMemory() || after <= MemoryBudget.storageLimit()) {
            "Недостаточно памяти устройства для хранения дополнительных данных"
        }
    }

    private suspend fun <T> sync(kind: String, before: List<T>, after: List<T>, id: (T) -> String, encode: (T) -> String) {
        val old = before.associateBy(id)
        val nextIds = after.mapTo(HashSet(), id)
        before.asSequence().filter { id(it) !in nextIds }
            .map { LedgerRecord(kind, id(it), "") }.chunked(pageSize).forEach { dao.delete(it) }
        after.asSequence().filter { old[id(it)] != it }
            .map { LedgerRecord(kind, id(it), encode(it)).checkedSize() }.chunked(pageSize).forEach { dao.put(it) }
    }

    private suspend fun syncReceipts(before: List<Receipt>, after: List<Receipt>) {
        val old = before.associateBy { it.id }
        val next = after.associateBy { it.id }
        before.filter { next[it.id] != it }.forEach {
            dao.deleteReceiptChunks("${it.id}:", "${it.id};")
            if (it.id !in next) dao.delete(listOf(LedgerRecord("receipt", it.id, "")))
        }
        after.filter { old[it.id] != it }.forEach {
            LedgerRecords.receiptRows(it).map { row->row.checkedSize() }.chunked(pageSize).forEach { rows -> dao.put(rows) }
        }
    }

    suspend fun change(transform: (Ledger) -> Ledger): Ledger = db.withTransaction {
        val stats = dao.stats()
        val before = readSnapshot(stats)
        val next = EntryOrder.stampNew(before, prune(transform(before)))
        if (stats.rows == 0L) {
            LedgerRecords.records(next).map{it.checkedSize()}.chunked(pageSize).forEach { dao.put(it) }
        } else {
            val json = LedgerRecords.json
            if (before.baseCurrency != next.baseCurrency) dao.put(listOf(LedgerRecord("meta", "base", next.baseCurrency)))
            sync("account", before.accounts, next.accounts, { it.id }, { json.encodeToString(it) })
            sync("category", before.categories, next.categories, { it.id }, { json.encodeToString(it) })
            sync("entry", before.entries, next.entries, { it.id }, { json.encodeToString(it) })
            sync("budget", before.budgets, next.budgets, { it.id }, { json.encodeToString(it) })
            sync("debt", before.debts, next.debts, { it.id }, { json.encodeToString(it) })
            sync("goal", before.goals, next.goals, { it.id }, { json.encodeToString(it) })
            sync("schedule", before.schedules, next.schedules, { it.id }, { json.encodeToString(it) })
            sync("rate", before.rates, next.rates, { "${it.currency}:${it.day}" }, { json.encodeToString(it) })
            syncReceipts(before.receipts, next.receipts)
        }
        checkGrowth(stats)
        next
    }
    suspend fun restore(ledger: Ledger): Ledger = db.withTransaction {
        val next = prune(ledger.validated())
        val stats = dao.stats()
        dao.clear()
        LedgerRecords.records(next).map{it.checkedSize()}.chunked(pageSize).forEach { dao.put(it) }
        checkGrowth(stats)
        next
    }
    suspend fun processDue(through: Long = today()): Ledger {
        val now = java.time.LocalTime.now()
        return change { Finance.generate(it, through, throughMinutes = if(through==today()) now.hour*60+now.minute else null) }
    }
    companion object { fun get(context: Context) = LedgerStore(TrachuDatabase.get(context)) }
}
