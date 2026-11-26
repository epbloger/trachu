package ru.epbloger.trachu.data

import org.junit.Assert.*
import org.junit.Test
import ru.epbloger.trachu.core.*
import java.io.ByteArrayOutputStream

class PortabilityTest {
    private val a = Account("a","Карта")
    private val original = Ledger.initial().copy(accounts=listOf(a)).let {
        Finance.putEntry(it,Finance.makeEntry(it,Kind.EXPENSE,"a",12345,today(),listOf("food" to 12345),
            note="Заметка, \"кавычки\"\nСтрока",id="e"))
    }
    @Test fun backupRestoresAndRejectsTampering() {
        val out = ByteArrayOutputStream()
        Backup.write(original,"password123".toCharArray(),out)
        val bytes = out.toByteArray()
        assertEquals(original,Backup.read("password123".toCharArray(),bytes.inputStream()))
        assertTrue(runCatching { Backup.read("incorrect".toCharArray(),bytes.inputStream()) }.isFailure)
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        assertTrue(runCatching { Backup.read("password123".toCharArray(),bytes.inputStream()) }.isFailure)
    }
    @Test fun csvRoundTripAndIdempotence() {
        val out = ByteArrayOutputStream()
        Csv.write(original,out)
        val imported = Csv.read(out.toByteArray().inputStream())
        assertEquals(original.copy(categories=original.categories.sortedBy { it.id }), imported.copy(categories=imported.categories.sortedBy { it.id }))
        assertEquals(1,Csv.merge(original,imported).entries.size)
    }
    @Test fun statementRepeatedImportDoesNotDuplicate() {
        val source = "date,amount,category,note\n2026-01-01,-12.34,Продукты,\"Чек, 1\"\n"
        val first = Csv.importStatement(source.byteInputStream(),original,"a")
        val second = Csv.importStatement(source.byteInputStream(),first,"a")
        assertEquals(first,second)
        assertEquals(2,first.entries.size)
    }
    @Test fun spreadsheetEscapingRoundTripsLeadingApostrophes() {
        val account=Account("''id","  =SUM(1,1)")
        val initial=Ledger.initial().copy(accounts=listOf(account))
        val state=Finance.putEntry(initial,Finance.makeEntry(initial,Kind.EXPENSE,account.id,123,today(),listOf("food" to 123L)))
        val bytes=ByteArrayOutputStream().also{Csv.write(state,it)}.toByteArray()
        assertEquals(state,Csv.read(bytes.inputStream()))
        val text=bytes.toString(Charsets.UTF_8)
        assertTrue(text.contains("'${account.name}"))
        assertTrue(text.contains("data_v2"))
        // Old exported files still read with the historical escaping format.
        val legacy=ByteArrayOutputStream().also{Csv.write(original,it)}.toString("UTF-8").replace("data_v2","data")
        assertEquals(original,Csv.read(legacy.byteInputStream()))
    }
    @Test fun spreadsheetSeparatorsInsideQuotedHeadersDoNotChangeDialect() {
        assertEquals(listOf(listOf("a;b;c","d"),listOf("x","y")),Csv.parse("\"a;b;c\",d\nx,y\n"))
    }
    @Test fun invalidBackupWriteWipesPasswordBeforeReturning() {
        val password="short".toCharArray()
        assertTrue(runCatching{Backup.write(original,password,ByteArrayOutputStream())}.isFailure)
        assertTrue(password.all{it=='\u0000'})
    }
    @Test fun oldOperationPayloadUsesSafeManualConfirmationDefault() {
        val encoded=LedgerRecords.json.encodeToString(Entry.serializer(),original.entries.single())
        val legacy=encoded.replace(",\"autoPostPlanned\":false","")
        assertFalse(LedgerRecords.json.decodeFromString(Entry.serializer(),legacy).autoPostPlanned)
    }
    @Test fun cbrNominalAndCrossRate() {
        val xml = """<ValCurs Date="26.11.2026"><Valute><CharCode>JPY</CharCode><Nominal>100</Nominal><Value>50,00</Value></Valute><Valute><CharCode>USD</CharCode><Nominal>1</Nominal><Value>100,00</Value></Valute></ValCurs>"""
        val rate = ExchangeRates.parse(xml.toByteArray(),"USD").first { it.currency=="JPY" }
        assertEquals("0.005",rate.unitsOfBasePerUnit)
    }
    @Test fun malformedUtf8AndFutureStatementAreRejected() {
        val invalid="date,amount,category,note\n2026-01-01,-1,Продукты,".toByteArray()+byteArrayOf(0xc3.toByte(),0x28)
        assertTrue(runCatching{Csv.importStatement(invalid.inputStream(),original,"a")}.isFailure)
        val future="date,amount,category\n${java.time.LocalDate.now().plusDays(1)},-1,Продукты\n"
        assertTrue(runCatching{Csv.importStatement(future.byteInputStream(),original,"a")}.isFailure)
    }
}
