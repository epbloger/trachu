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
            val before=store.current()
            val rows=db.ledgerDao().read()
            for(word in listOf("","Да","Не подтверждаю","ПОДТВЕРЖДА")){
                assertTrue(runCatching{store.clearFinancialData(word)}.isFailure)
                assertEquals(before,store.current())
                assertEquals(rows,db.ledgerDao().read())
            }
            val empty=store.clearFinancialData("ПОДТВЕРЖДАЮ")
            assertEquals(Ledger.initial(),empty)
            val expectedPersisted=Ledger.initial().let{it.copy(categories=it.categories.sortedBy{c->c.id})}
            assertEquals(expectedPersisted,store.processDue(day+2))
            db.close()
            db=Room.databaseBuilder(context,TrachuDatabase::class.java,name).build()
            assertEquals(expectedPersisted,LedgerStore(db).current())
            assertTrue(db.ledgerDao().read().none{it.kind in setOf("entry","receipt","receipt_chunk","schedule","account","goal","debt","budget","rate")})
        } finally { db.close();context.deleteDatabase(name) }
    }
    @Test fun encryptedBackupRestoresEveryRecordAfterConfirmedResetAndReopen()=runBlocking {
        val name="backup-reset-${newId()}.db"
        val file=java.io.File.createTempFile("reset-test-", ".trachu", context.cacheDir)
        val password="test-backup-2026"
        var db=Room.databaseBuilder(context,TrachuDatabase::class.java,name).build()
        try {
            var store=LedgerStore(db)
            val day=today()
            val a=Account("a","Карта",openingMinor=100000)
            val usd=Account("usd","Доллары","USD",openingMinor=10000)
            val receipt=Receipt(id="receipt-test",mime="image/png",base64="AQID")
            val debt=Debt(id="debt",person="Кредитор",accountId=a.id,owedByMe=true,principalMinor=20000)
            val base=Ledger.initial().copy(accounts=listOf(a,usd),
                categories=Ledger.initial().categories+Category("custom","Своя категория"),
                receipts=listOf(receipt),debts=listOf(debt),
                goals=listOf(Goal(id="goal",name="Отпуск",targetMinor=50000,savedMinor=10000,
                    adjustments=listOf(GoalAdjustment(id="adjust",day=day,minor=5000,note="Пополнение")))),
                budgets=listOf(Budget(id="budget",name="Лимит",categoryId="custom",monthlyMinor=10000,startMonth=Period.month(day).from)),
                rates=listOf(FxRate("USD",day,"100")))
            val expense=Finance.makeEntry(base,Kind.EXPENSE,a.id,5000,day,
                listOf("custom" to 3000L,"food" to 2000L),note="Покупка",tags=listOf("тест"),
                id="expense",timeMinutes=600,receiptIds=listOf(receipt.id)).copy(recordedAtMillis=100L)
            val income=Finance.makeEntry(base,Kind.INCOME,a.id,20000,day,listOf("salary" to 20000L),
                id="income",timeMinutes=600).copy(recordedAtMillis=101L)
            val transfer=Finance.makeEntry(base,Kind.TRANSFER,a.id,10000,day,targetId=usd.id,targetMinor=100,id="transfer")
            val payment=Finance.makeEntry(base,Kind.DEBT_OUT,a.id,2500,day,debtId=debt.id,principalMinor=2000,id="payment")
            val planned=Finance.makeEntry(base,Kind.EXPENSE,a.id,300,day+2,listOf("food" to 300L),
                status=EntryStatus.PLANNED,id="planned",autoPostPlanned=true)
            val ledger=base.copy(entries=listOf(expense,income,transfer,payment,planned))
            val refund=Finance.refund(ledger,expense.id,1000,day,id="refund")
            val schedule=Schedule("schedule",expense.copy(id="template",receiptIds=emptyList(),recordedAtMillis=0L),
                day+3,Frequency.MONTHLY,autoPost=true)
            store.restore(ledger.copy(entries=ledger.entries+refund,schedules=listOf(schedule)))
            val original=store.current()
            val originalRows=db.ledgerDao().read()
            val balances=original.accounts.associate{it.id to Finance.balance(original,it.id)}
            val totals=Finance.totals(original,Period.month(day))
            val order=original.entries.sortedWith(EntryOrder.newestFirst).map{it.id}
            // Same encrypted staging implementation as the app's file export.
            Backup.writeStaged(original,password.toCharArray(),context.cacheDir){file.outputStream()}
            val bytes=file.readBytes()
            assertTrue(bytes.isNotEmpty())
            assertEquals(original,Backup.read(password.toCharArray(),bytes.inputStream()))
            // Authentication failure must leave an existing, nonempty ledger intact.
            val damaged=bytes.copyOf().apply{this[lastIndex]=(this[lastIndex].toInt() xor 1).toByte()}
            for((data,secret) in listOf(bytes to "wrong-password",damaged to password,bytes.copyOf(bytes.size/2) to password)){
                assertTrue(runCatching{store.restore(Backup.read(secret.toCharArray(),data.inputStream()))}.isFailure)
                assertEquals(originalRows,db.ledgerDao().read())
            }
            store.clearFinancialData("ПОДТВЕРЖДАЮ")
            db.close()
            db=Room.databaseBuilder(context,TrachuDatabase::class.java,name).build()
            store=LedgerStore(db)
            val empty=store.current()
            assertTrue(empty.accounts.isEmpty()&&empty.entries.isEmpty()&&empty.receipts.isEmpty()&&empty.schedules.isEmpty())
            assertTrue(empty.goals.isEmpty()&&empty.debts.isEmpty()&&empty.budgets.isEmpty()&&empty.rates.isEmpty())
            val emptyRows=db.ledgerDao().read()
            assertArrayEquals(bytes,file.readBytes())
            for((data,secret) in listOf(bytes to "wrong-password",damaged to password,bytes.copyOf(bytes.size/2) to password)){
                assertTrue(runCatching{store.restore(Backup.read(secret.toCharArray(),data.inputStream()))}.isFailure)
                assertEquals(emptyRows,db.ledgerDao().read())
            }
            file.inputStream().use{store.restore(Backup.read(password.toCharArray(),it))}
            assertEquals(original,store.current())
            assertEquals(originalRows,db.ledgerDao().read())
            // Repeated restore replaces the snapshot without creating duplicates.
            file.inputStream().use{store.restore(Backup.read(password.toCharArray(),it))}
            db.close()
            db=Room.databaseBuilder(context,TrachuDatabase::class.java,name).build()
            val restored=LedgerStore(db).current()
            assertEquals(original,restored)
            assertEquals(originalRows,db.ledgerDao().read())
            assertEquals(balances,restored.accounts.associate{it.id to Finance.balance(restored,it.id)})
            assertEquals(totals,Finance.totals(restored,Period.month(day)))
            assertEquals(order,restored.entries.sortedWith(EntryOrder.newestFirst).map{it.id})
            assertEquals(15000L,Finance.goalSaved(restored.goals.single()))
            assertEquals(18000L,Finance.debtRemaining(restored,restored.debts.single()))
            assertEquals(receipt,restored.receipts.single())
        } finally {db.close();context.deleteDatabase(name);file.delete()}
    }

    @Test fun individualDeletesPreserveEveryUnrelatedDatabaseRow()=runBlocking {
        val name="delete-isolation-${newId()}.db"
        var db=Room.databaseBuilder(context,TrachuDatabase::class.java,name).build()
        try {
            var store=LedgerStore(db)
            val day=today()
            val a=Account("a","Карта",openingMinor=10000)
            val b=Account("b","Наличные",openingMinor=20000)
            val unused=Account("unused","Резерв",openingMinor=30000)
            val shared=Receipt(id="shared",mime="image/png",base64="AQID")
            val only=Receipt(id="only",mime="image/png",base64="BAUG")
            val base=Ledger.initial().copy(accounts=listOf(a,b,unused),receipts=listOf(shared,only),
                categories=Ledger.initial().categories+Category("custom","Своя категория"),
                goals=listOf(Goal(id="goal",name="Цель",targetMinor=10000,savedMinor=500)),
                debts=listOf(Debt(id="debt",person="Долг",accountId=b.id,owedByMe=true,principalMinor=1000)),
                budgets=listOf(Budget(id="budget",name="Бюджет",monthlyMinor=1000,startMonth=Period.month(day).from)),
                rates=listOf(FxRate("USD",day,"100")))
            val first=Finance.makeEntry(base,Kind.EXPENSE,a.id,500,day,listOf("food" to 500L),
                note="Удаляемая запись",id="delete",receiptIds=listOf(shared.id,only.id))
            val other=Finance.makeEntry(base,Kind.EXPENSE,b.id,200,day,listOf("food" to 200L),
                note="Сохранить",id="keep",receiptIds=listOf(shared.id))
            val template=other.copy(id="template",receiptIds=emptyList())
            store.restore(base.copy(entries=listOf(first,other),schedules=listOf(Schedule("schedule",template,day+1,Frequency.DAILY))))
            val before=store.current()
            val rowsBefore=db.ledgerDao().read()
            val afterAccount=before.copy(accounts=before.accounts.filterNot{it.id==unused.id})
            store.change{Finance.deleteAccount(it,unused)}
            assertEquals(afterAccount,store.current())
            assertEquals(rowsBefore.filterNot{it.kind=="account"&&it.id==unused.id},db.ledgerDao().read())
            val rowsAfterAccount=db.ledgerDao().read()
            for(account in listOf(a,b)){
                assertTrue(runCatching{store.change{Finance.deleteAccount(it,account)}}.isFailure)
                assertEquals(afterAccount,store.current())
                assertEquals(rowsAfterAccount,db.ledgerDao().read())
            }
            val expected=afterAccount.copy(entries=afterAccount.entries.filterNot{it.id==first.id},
                receipts=afterAccount.receipts.filterNot{it.id==only.id})
            store.change{Finance.deleteEntry(it,first.id)}
            assertEquals(expected,store.current())
            assertEquals(10000L,Finance.balance(store.current(),a.id))
            assertEquals(19800L,Finance.balance(store.current(),b.id))
            val expectedRows=rowsAfterAccount.filterNot{
                (it.kind=="entry"&&it.id==first.id)||(it.kind=="receipt"&&it.id==only.id)||
                    (it.kind=="receipt_chunk"&&it.id.startsWith("${only.id}:"))
            }
            assertEquals(expectedRows,db.ledgerDao().read())
            // A stale second delete of the same account and repeated entry deletion
            // may not touch any additional row.
            assertTrue(runCatching{store.change{Finance.deleteAccount(it,unused)}}.isFailure)
            store.change{Finance.deleteEntry(it,first.id)}
            db.close()
            db=Room.databaseBuilder(context,TrachuDatabase::class.java,name).build()
            store=LedgerStore(db)
            assertEquals(expected,store.current())
            assertEquals(expectedRows,db.ledgerDao().read())
        } finally {db.close();context.deleteDatabase(name)}
    }

    @Test fun deletingLastEntryAndLastAccountKeepsIndependentFinancialHistory()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,TrachuDatabase::class.java).build()
        try {
            val store=LedgerStore(db)
            val day=today()
            val account=Account("last","Последний счёт",openingMinor=10000)
            val base=Ledger.initial().copy(accounts=listOf(account),
                categories=Ledger.initial().categories+Category("custom","Моя категория"),
                goals=listOf(Goal(name="Отпуск",targetMinor=20000,savedMinor=5000)),
                budgets=listOf(Budget(name="Лимит",monthlyMinor=1000,startMonth=Period.month(day).from)),
                rates=listOf(FxRate("USD",day,"100")))
            val entry=Finance.makeEntry(base,Kind.EXPENSE,account.id,100,day,listOf("custom" to 100L))
            store.restore(base.copy(entries=listOf(entry)))
            val before=store.current()
            store.change{Finance.deleteEntry(it,entry.id)}
            assertEquals(before.copy(entries=emptyList()),store.current())
            store.change{Finance.deleteAccount(it,account)}
            val expected=before.copy(entries=emptyList(),accounts=emptyList())
            assertEquals(expected,store.current())
            assertEquals(expected,store.processDue(day+1))
        } finally { db.close() }
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
