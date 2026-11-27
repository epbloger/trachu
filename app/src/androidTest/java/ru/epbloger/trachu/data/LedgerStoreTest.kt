package ru.epbloger.trachu.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import ru.epbloger.trachu.core.*

@RunWith(AndroidJUnit4::class)
class LedgerStoreTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    @Test fun sameMinuteNewestFirstSurvivesDatabaseReopenAndEdits()=runBlocking {
        val name="entry-order-${newId()}.db"
        val account=Account("a","Карта",openingMinor=10000)
        val day=today()
        var db=Room.databaseBuilder(context,TrachuDatabase::class.java,name).build()
        try {
            var store=LedgerStore(db)
            store.restore(Ledger.initial().copy(accounts=listOf(account)))
            val first=Finance.makeEntry(store.current(),Kind.EXPENSE,"a",100,day,listOf("food" to 100L),id="z-first",timeMinutes=600)
            val second=Finance.makeEntry(store.current(),Kind.EXPENSE,"a",200,day,listOf("food" to 200L),id="a-second",timeMinutes=600)
            store.change{Finance.putEntry(it,first)}
            store.change{Finance.putEntry(it,second)}
            val before=store.current()
            assertEquals(listOf(second.id,first.id),before.entries.sortedWith(EntryOrder.newestFirst).map{it.id})
            val savedFirst=before.entries.single{it.id==first.id}
            assertTrue(savedFirst.recordedAtMillis>0)
            store.change{Finance.putEntry(it,first.copy(note="Уточнение"))}
            db.close()
            db=Room.databaseBuilder(context,TrachuDatabase::class.java,name).build()
            store=LedgerStore(db)
            val reopened=store.current()
            assertEquals(listOf(second.id,first.id),reopened.entries.sortedWith(EntryOrder.newestFirst).map{it.id})
            assertEquals(savedFirst.recordedAtMillis,reopened.entries.single{it.id==first.id}.recordedAtMillis)
            assertEquals(9700L,Finance.balance(reopened,"a"))
            store.restore(reopened)
            assertEquals(reopened,store.current())
        } finally { db.close();context.deleteDatabase(name) }
    }
    @Test fun financialResetClearsRecordsAndRemainsEmptyAfterReopen()=runBlocking {
        val name="reset-${newId()}.db"
        var db=Room.databaseBuilder(context,TrachuDatabase::class.java,name).build()
        try {
            val account=Account("a","Личные данные",openingMinor=10000)
            val day=today()
            val receipt=Receipt(mime="image/png",base64="AQID")
            val base=Ledger.initial().copy(accounts=listOf(account),categories=Ledger.initial().categories+Category("custom","Своя категория"),receipts=listOf(receipt))
            val entry=Finance.makeEntry(base,Kind.EXPENSE,"a",100,day,listOf("food" to 100L),receiptIds=listOf(receipt.id))
            val schedule=Schedule("s",entry.copy(id="template",receiptIds=emptyList()),day+1,Frequency.DAILY)
            val initial=base.copy(entries=listOf(entry),schedules=listOf(schedule),
                goals=listOf(Goal(name="Цель",targetMinor=5000)),
                debts=listOf(Debt(person="Долг",accountId="a",owedByMe=true,principalMinor=100)),
                budgets=listOf(Budget(name="Бюджет",monthlyMinor=1000,startMonth=Period.month(day).from)),
                rates=listOf(FxRate("USD",day,"100")))
            val store=LedgerStore(db)
            store.restore(initial)
            assertEquals(1,store.current().receipts.size)
            val empty=store.clearFinancialData()
            assertEquals(Ledger.initial(),empty)
            val expectedPersisted=Ledger.initial().let{it.copy(categories=it.categories.sortedBy{c->c.id})}
            assertEquals(expectedPersisted,store.processDue(day+2))
            db.close()
            db=Room.databaseBuilder(context,TrachuDatabase::class.java,name).build()
            assertEquals(expectedPersisted,LedgerStore(db).current())
            assertTrue(db.ledgerDao().read().none{it.kind in setOf("entry","receipt","receipt_chunk","schedule","account","goal","debt","budget","rate")})
        } finally { db.close();context.deleteDatabase(name) }
    }
    @Test fun transactionRollbackAndRestore()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,TrachuDatabase::class.java).build()
        try {
            val store=LedgerStore(db)
            val a=Account("a","Наличные",openingMinor=10000)
            val b=Account("b","Карта")
            store.change{it.copy(accounts=listOf(a,b))}
            val e=Finance.makeEntry(store.current(),Kind.TRANSFER,a.id,2500,today(),targetId=b.id,targetMinor=2500)
            store.change{Finance.putEntry(it,e)}
            val before=store.current()
            assertTrue(runCatching{store.change{it.copy(accounts=listOf(a))}}.isFailure)
            assertEquals(before,store.current())
            assertEquals(7500L,Finance.balance(before,a.id))
            assertEquals(2500L,Finance.balance(before,b.id))
            store.restore(before)
            store.restore(before)
            assertEquals(1,store.current().entries.size)
            val oversized=before.copy(accounts=before.accounts.map{if(it.id==a.id)it.copy(name="Ж".repeat(300_000)) else it})
            assertTrue(runCatching{store.restore(oversized)}.isFailure)
            assertTrue(runCatching{store.change{oversized}}.isFailure)
            assertEquals(before,store.current())
        } finally {db.close()}
    }
    @Test fun migrationPreservesLegacyRowsAndReopen()=runBlocking {
        val name="migration-${newId()}.db"
        val file=context.getDatabasePath(name)
        file.parentFile?.mkdirs()
        val account=Account("legacy","Старый счёт",openingMinor=43210)
        val state=Ledger.initial().copy(accounts=listOf(account))
        SQLiteDatabase.openOrCreateDatabase(file,null).use { old ->
            old.execSQL("CREATE TABLE ledger_records (kind TEXT NOT NULL, id TEXT NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(kind,id))")
            LedgerRecords.encode(state).forEach{row->old.execSQL("INSERT INTO ledger_records(kind,id,payload) VALUES(?,?,?)",arrayOf(row.kind,row.id,row.payload))}
            old.version=1
        }
        fun open()=Room.databaseBuilder(context,TrachuDatabase::class.java,name).addMigrations(TrachuDatabase.MIGRATION_1_2).build()
        val db=open()
        try {
            val restored=LedgerStore(db).current()
            assertEquals(43210L,Finance.balance(restored,account.id))
            assertEquals(2,db.openHelper.writableDatabase.version)
            assertTrue(db.ledgerDao().read().all{it.formatVersion==1})
        } finally {db.close()}
        val reopened=open()
        try { assertEquals(43210L,Finance.balance(LedgerStore(reopened).current(),account.id)) }
        finally {reopened.close();context.deleteDatabase(name)}
    }
    @Test fun concurrentDueProcessingAndCorruptImportAreAtomic()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,TrachuDatabase::class.java).build()
        try {
            val store=LedgerStore(db)
            val state=Ledger.initial().copy(accounts=listOf(Account("a","Карта",openingMinor=10000)))
            val template=Finance.makeEntry(state,Kind.EXPENSE,"a",500,today()-2,listOf("food" to 500L),status=EntryStatus.PLANNED)
            store.restore(state.copy(schedules=listOf(Schedule("s",template,today()-2,Frequency.DAILY,autoPost=true))))
            kotlinx.coroutines.coroutineScope {
                repeat(3){launch{store.processDue(today()-1)}}
            }
            val before=store.current()
            assertEquals(2,before.entries.size)
            assertEquals(9000L,Finance.balance(before,"a"))
            assertTrue(runCatching{store.restore(before.copy(accounts=emptyList()))}.isFailure)
            assertEquals(before,store.current())
            val bad="date,amount,category\n2026-01-01,-2,Продукты\nbad,-1,Другое\n"
            assertTrue(runCatching{store.change{Csv.importStatement(bad.byteInputStream(),it,"a")}}.isFailure)
            assertEquals(before,store.current())
        }finally{db.close()}
    }
    @Test fun pagedReceiptsKeepPrefixIdsSeparateAndRemoveObsoleteChunks()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,TrachuDatabase::class.java).build()
        try {
            val store=LedgerStore(db)
            val initial=Ledger.initial().copy(accounts=listOf(Account("a","Карта")))
            val first=Receipt("r","image/png","AAAA".repeat(220_000))
            val second=Receipt("r:000000","image/png","BBBB".repeat(7_000))
            val entry=Finance.makeEntry(initial,Kind.EXPENSE,"a",500,today(),listOf("food" to 500L),
                receiptIds=listOf(first.id,second.id))
            val ledger=initial.copy(entries=listOf(entry),receipts=listOf(first,second))
            store.restore(ledger)
            assertEquals(ledger.receipts.associateBy { it.id },store.current().receipts.associateBy { it.id })
            val shorter=first.copy(base64="CCCC")
            store.change { it.copy(receipts=listOf(shorter,second)) }
            assertEquals(listOf(shorter,second),store.current().receipts)
            store.change { it.copy(entries=it.entries.map { e -> e.copy(receiptIds=listOf(second.id)) }) }
            assertEquals(listOf(second),store.current().receipts)
            val before=store.current()
            db.ledgerDao().put(listOf(LedgerRecord("receipt_chunk","orphan:000000","AAAA")))
            assertTrue(runCatching { store.current() }.isFailure)
            // A valid replacement can recover a corrupt previous snapshot atomically.
            store.restore(before)
            assertEquals(before,store.current())
        } finally { db.close() }
    }
    @Test fun legacyEncryptedArchiveWithMultiMegabyteReceiptRestoresAtomically()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,TrachuDatabase::class.java).build()
        try {
            val store=LedgerStore(db)
            val account=Account("legacy-archive","Архив",openingMinor=10000)
            val receipt=Receipt("old-check","image/png","AAAA".repeat(750_000))
            val state=Ledger.initial().copy(accounts=listOf(account),receipts=listOf(receipt))
            val entry=Finance.makeEntry(state,Kind.EXPENSE,account.id,3210,today(),listOf("food" to 3210L),receiptIds=listOf(receipt.id))
            val expected=state.copy(entries=listOf(entry))
            // Generate a legacy TRACHU01 envelope with the platform JCA provider,
            // independently of Backup.write and its current streaming implementation.
            val header="TRACHU01".toByteArray(Charsets.US_ASCII)+ByteArray(28){it.toByte()}
            val spec=javax.crypto.spec.PBEKeySpec("legacy-password".toCharArray(),header.copyOfRange(8,24),310_000,256)
            val key=try{javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded}finally{spec.clearPassword()}
            val cipher=javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            try{cipher.init(javax.crypto.Cipher.ENCRYPT_MODE,javax.crypto.spec.SecretKeySpec(key,"AES"),javax.crypto.spec.GCMParameterSpec(128,header.copyOfRange(24,36)))}finally{key.fill(0)}
            cipher.updateAAD(header)
            val bytes=header+cipher.doFinal(("{\"version\":1,\"ledger\":"+LedgerRecords.json.encodeToString(Ledger.serializer(),expected)+"}").toByteArray(Charsets.UTF_8))
            store.restore(Backup.read("legacy-password".toCharArray(),bytes.inputStream()))
            val before=store.current()
            assertEquals(expected.receipts,before.receipts)
            assertEquals(expected.entries,before.entries)
            assertEquals(6790L,Finance.balance(before,account.id))
            bytes[bytes.lastIndex]=(bytes.last().toInt() xor 1).toByte()
            assertTrue(runCatching{store.restore(Backup.read("legacy-password".toCharArray(),bytes.inputStream()))}.isFailure)
            assertEquals(before,store.current())
        }finally{db.close()}
    }
}
