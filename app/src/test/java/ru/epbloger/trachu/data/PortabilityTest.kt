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
    @Test fun cbrNominalAndCrossRate() {
        val xml = """<ValCurs Date="26.11.2026"><Valute><CharCode>JPY</CharCode><Nominal>100</Nominal><Value>50,00</Value></Valute><Valute><CharCode>USD</CharCode><Nominal>1</Nominal><Value>100,00</Value></Valute></ValCurs>"""
        val rate = ExchangeRates.parse(xml.toByteArray(),"USD").first { it.currency=="JPY" }
        assertEquals("0.005",rate.unitsOfBasePerUnit)
    }
}
