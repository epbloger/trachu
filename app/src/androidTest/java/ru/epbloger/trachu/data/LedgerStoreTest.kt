package ru.epbloger.trachu.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import ru.epbloger.trachu.core.*

@RunWith(AndroidJUnit4::class)
class LedgerStoreTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
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
}
