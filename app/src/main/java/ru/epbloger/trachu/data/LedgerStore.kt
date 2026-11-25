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
data class LedgerRecord(val kind: String, val id: String, val payload: String)

@Dao
interface LedgerDao {
    @Query("SELECT * FROM ledger_records ORDER BY kind, id")
    suspend fun read(): List<LedgerRecord>
    @Query("SELECT * FROM ledger_records ORDER BY kind, id")
    fun observe(): Flow<List<LedgerRecord>>
    @Upsert suspend fun put(records: List<LedgerRecord>)
    @Delete suspend fun delete(records: List<LedgerRecord>)
}

@Database(entities = [LedgerRecord::class], version = 1, exportSchema = true)
abstract class TrachuDatabase : RoomDatabase() {
    abstract fun ledgerDao(): LedgerDao
    companion object {
        @Volatile private var instance: TrachuDatabase? = null
        fun get(context: Context): TrachuDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, TrachuDatabase::class.java, "trachu.db")
                .build().also { instance = it }
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
    fun encode(ledger: Ledger): List<LedgerRecord> = buildList {
        add(LedgerRecord("meta", "base", ledger.baseCurrency))
        fun <T> addAll(kind: String, values: List<T>, id: (T) -> String, encode: (T) -> String) {
            values.forEach { add(LedgerRecord(kind, id(it), encode(it))) }
        }
        addAll("account", ledger.accounts, { it.id }, { json.encodeToString(it) })
        addAll("category", ledger.categories, { it.id }, { json.encodeToString(it) })
        addAll("entry", ledger.entries, { it.id }, { json.encodeToString(it) })
        addAll("budget", ledger.budgets, { it.id }, { json.encodeToString(it) })
        addAll("debt", ledger.debts, { it.id }, { json.encodeToString(it) })
        addAll("goal", ledger.goals, { it.id }, { json.encodeToString(it) })
        addAll("schedule", ledger.schedules, { it.id }, { json.encodeToString(it) })
        addAll("rate", ledger.rates, { "${it.currency}:${it.day}" }, { json.encodeToString(it) })
        ledger.receipts.forEach { receipt ->
            add(LedgerRecord("receipt", receipt.id, receipt.mime))
            receipt.base64.chunked(48_000).forEachIndexed { index, chunk ->
                add(LedgerRecord("receipt_chunk", "${receipt.id}:${index.toString().padStart(6,'0')}", chunk))
            }
        }
    }
    fun decode(rows: List<LedgerRecord>): Ledger {
        if (rows.isEmpty()) return Ledger.initial()
        require(rows.map { it.kind to it.id }.distinct().size == rows.size)
        require(rows.all { it.kind in setOf("meta","account","category","entry","budget","debt","goal","schedule","rate","receipt","receipt_chunk") })
        val grouped = rows.groupBy { it.kind }
        val base = grouped["meta"]?.single()?.also { require(it.id == "base") }?.payload
            ?: error("Отсутствует основная валюта")
        fun group(kind: String) = grouped[kind].orEmpty()
        val chunks = group("receipt_chunk").groupBy { it.id.substringBeforeLast(':') }
        val receipts = group("receipt").map { r ->
            val parts = chunks[r.id].orEmpty().sortedBy { it.id }
            require(parts.isNotEmpty() && parts.indices.all { i -> parts[i].id == "${r.id}:${i.toString().padStart(6,'0')}" })
            Receipt(r.id, r.payload, parts.joinToString("") { it.payload })
        }
        require(chunks.keys == receipts.map { it.id }.toSet())
        return Ledger(
            base,
            group("account").map { json.decodeFromString<Account>(it.payload).also { a -> require(a.id == it.id) } },
            group("category").map { json.decodeFromString<Category>(it.payload).also { a -> require(a.id == it.id) } },
            group("entry").map { json.decodeFromString<Entry>(it.payload).also { a -> require(a.id == it.id) } },
            group("budget").map { json.decodeFromString<Budget>(it.payload).also { a -> require(a.id == it.id) } },
            group("debt").map { json.decodeFromString<Debt>(it.payload).also { a -> require(a.id == it.id) } },
            group("goal").map { json.decodeFromString<Goal>(it.payload).also { a -> require(a.id == it.id) } },
            group("schedule").map { json.decodeFromString<Schedule>(it.payload).also { a -> require(a.id == it.id) } },
            group("rate").map { json.decodeFromString<FxRate>(it.payload).also { a -> require("${a.currency}:${a.day}" == it.id) } },
            receipts
        ).validated()
    }
}

class LedgerStore(private val db: TrachuDatabase) {
    private val dao get() = db.ledgerDao()
    val ledger: Flow<Ledger> = dao.observe().map { LedgerRecords.decode(it) }.flowOn(Dispatchers.Default)
    suspend fun current(): Ledger = db.withTransaction { LedgerRecords.decode(dao.read()) }
    suspend fun change(transform: (Ledger) -> Ledger): Ledger = db.withTransaction {
        val before = dao.read()
        val transformed = transform(LedgerRecords.decode(before))
        val usedReceipts = (transformed.entries.flatMap { it.receiptIds } + transformed.schedules.flatMap { it.template.receiptIds }).toSet()
        val next = transformed.copy(receipts = transformed.receipts.filter { it.id in usedReceipts }).validated()
        val after = LedgerRecords.encode(next)
        val old = before.associateBy { it.kind to it.id }
        val updated = after.associateBy { it.kind to it.id }
        val removed = before.filter { (it.kind to it.id) !in updated }
        val changed = after.filter { old[it.kind to it.id] != it }
        if (removed.isNotEmpty()) dao.delete(removed)
        if (changed.isNotEmpty()) dao.put(changed)
        next
    }
    suspend fun restore(ledger: Ledger) = change { ledger.validated() }
    suspend fun processDue(through: Long = today()) = change { Finance.generate(it, through) }
    companion object { fun get(context: Context) = LedgerStore(TrachuDatabase.get(context)) }
}
