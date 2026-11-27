package ru.epbloger.trachu.data

import org.junit.Assert.*
import org.junit.Test
import ru.epbloger.trachu.core.*
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.jsonObject

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
    @Test fun backupRetainsLegacyEnvelopeAndAuthenticatesEntireFragmentedInput() {
        val password = "password123"
        fun cipher(mode: Int, header: ByteArray): Cipher {
            val spec = PBEKeySpec(password.toCharArray(),header.copyOfRange(8,24),310_000,256)
            val key = try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
                finally { spec.clearPassword() }
            return Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(mode,SecretKeySpec(key,"AES"),GCMParameterSpec(128,header.copyOfRange(24,36)))
                updateAAD(header)
            }.also { key.fill(0) }
        }
        val receipt = Receipt("receipt","image/png","AAAA".repeat(200_000))
        val ledger = original.copy(receipts=listOf(receipt),entries=original.entries.map { it.copy(receiptIds=listOf(receipt.id)) })
        val header = "TRACHU01".toByteArray(Charsets.US_ASCII) + ByteArray(28) { it.toByte() }
        val json = "{\"version\":1,\"ledger\":" + LedgerRecords.json.encodeToString(Ledger.serializer(),ledger) + "}"
        val legacy = header + cipher(Cipher.ENCRYPT_MODE,header).doFinal(json.toByteArray(Charsets.UTF_8))
        fun fragmented(bytes: ByteArray) = object : ByteArrayInputStream(bytes) {
            override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b,off,minOf(len,113))
        }
        val readPassword = password.toCharArray()
        assertEquals(ledger,Backup.read(readPassword,fragmented(legacy)))
        assertTrue(readPassword.all { it == '\u0000' })
        val output = ByteArrayOutputStream()
        val writePassword = password.toCharArray()
        Backup.write(ledger,writePassword,output)
        assertTrue(writePassword.all { it == '\u0000' })
        val bytes = output.toByteArray()
        assertArrayEquals("TRACHU01".toByteArray(Charsets.US_ASCII),bytes.copyOfRange(0,8))
        val clear = cipher(Cipher.DECRYPT_MODE,bytes.copyOfRange(0,36))
            .doFinal(bytes,36,bytes.size-36).toString(Charsets.UTF_8)
        val oldEnvelope = LedgerRecords.json.parseToJsonElement(clear).jsonObject
        assertEquals(ledger,LedgerRecords.json.decodeFromString(Ledger.serializer(),oldEnvelope.getValue("ledger").toString()))
        for (broken in listOf(bytes.copyOf(bytes.size-1),bytes+byteArrayOf(0),bytes.copyOf(36))) {
            val failedPassword = password.toCharArray()
            assertTrue(runCatching { Backup.read(failedPassword,fragmented(broken)) }.isFailure)
            assertTrue(failedPassword.all { it == '\u0000' })
        }
    }
    @Test fun admissionLimitsRejectBeforeReturningOversizedBlocks() {
        val limited = LimitedInput(ByteArray(17).inputStream(),32,16)
        val buffer = ByteArray(16)
        assertEquals(16,limited.read(buffer))
        val error = runCatching { limited.read(buffer) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException && error.message.orEmpty().contains("памяти"))
        val absolute = LimitedInput(ByteArray(17).inputStream(),16,32)
        assertEquals(16,absolute.read(buffer))
        assertTrue(runCatching { absolute.read() }.isFailure)
    }
    @Test fun operationOrderSurvivesExportsAndLegacyCsvMerge() {
        val account=Account("a","Карта")
        val base=Ledger.initial().copy(accounts=listOf(account))
        val first=Finance.makeEntry(base,Kind.EXPENSE,"a",100,today(),listOf("food" to 100L),id="z-first").copy(recordedAtMillis=100L)
        val second=Finance.makeEntry(base,Kind.EXPENSE,"a",200,today(),listOf("food" to 200L),id="a-second").copy(recordedAtMillis=101L)
        val ledger=base.copy(entries=listOf(first,second)).validated()
        val bytes=java.io.ByteArrayOutputStream()
        Csv.write(ledger,bytes)
        val imported=Csv.read(bytes.toByteArray().inputStream())
        assertEquals(ledger.entries.associateBy{it.id},imported.entries.associateBy{it.id})
        val legacy=ledger.copy(entries=ledger.entries.map{it.copy(recordedAtMillis=0L)})
        assertEquals(ledger,Csv.merge(ledger,legacy))
        assertTrue(runCatching{Csv.merge(ledger,legacy.copy(entries=listOf(first.copy(note="Другие данные"),second)))}.isFailure)
        val backup=java.io.ByteArrayOutputStream()
        Backup.write(ledger,"password123".toCharArray(),backup)
        assertEquals(ledger,Backup.read("password123".toCharArray(),backup.toByteArray().inputStream()))
    }
    @Test fun csvRoundTripAndIdempotence() {
        val out = ByteArrayOutputStream()
        Csv.write(original,out)
        val imported = Csv.read(out.toByteArray().inputStream())
        assertEquals(original.copy(categories=original.categories.sortedBy { it.id }), imported.copy(categories=imported.categories.sortedBy { it.id }))
        assertEquals(1,Csv.merge(original,imported).entries.size)
    }
    @Test fun fullCsvImportMergesWhenOnlyGoalsOrOtherPlanningDataExist() {
        val goal=Goal(name="Резерв",currency="RUB",targetMinor=250000,savedMinor=10000)
        val existing=Ledger.initial().copy(goals=listOf(goal))
        val importedAccount=Account("imported","Основной счёт")
        val incoming=Ledger.initial().copy(accounts=listOf(importedAccount))
        val merged=Csv.importFull(existing,incoming)
        assertEquals(goal,merged.goals.single())
        assertEquals(importedAccount,merged.accounts.single())
    }
    @Test fun fullCsvImportReplacesOnlyPristineLedger() {
        val incoming=Ledger.initial().copy(baseCurrency="USD",accounts=listOf(Account("usd","Основной",currency="USD")))
        assertEquals(incoming,Csv.importFull(Ledger.initial(),incoming))
    }

    @Test fun fullCsvImportCannotOverwriteExplicitBaseCurrency() {
        val existing=Ledger.initial().copy(baseCurrency="USD")
        val incoming=Ledger.initial().copy(accounts=listOf(Account("rub","Наличные")))
        assertTrue(runCatching { Csv.importFull(existing,incoming) }.isFailure)
        assertEquals(existing,Csv.importFull(existing,existing))
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
    @Test fun csvReadsFragmentedMultilineRowsAndRejectsTrailingMalformedData() {
        val receipt = Receipt("r","image/png","AAAA".repeat(30_000))
        val ledger = original.copy(receipts=listOf(receipt),entries=original.entries.map { it.copy(receiptIds=listOf("r")) })
        val bytes = ByteArrayOutputStream().also { Csv.write(ledger,it) }.toByteArray()
        fun fragmented(data: ByteArray) = object : ByteArrayInputStream(data) {
            override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b,off,minOf(len,7))
        }
        assertEquals(ledger,Csv.read(fragmented(bytes)))
        assertTrue(runCatching { Csv.read(fragmented(bytes+byteArrayOf(0xc3.toByte()))) }.isFailure)
        assertEquals(listOf(listOf("a","b"),listOf("x\ny","\"z\"")),Csv.parse("\uFEFFa;b\r\n\"x\ny\";\"\"\"z\"\"\""))
    }
    @Test fun invalidBackupWriteWipesPasswordBeforeReturning() {
        val password="short".toCharArray()
        assertTrue(runCatching{Backup.write(original,password,ByteArrayOutputStream())}.isFailure)
        assertTrue(password.all{it=='\u0000'})
    }
    @Test fun stagedBackupDoesNotOpenDestinationOnGenerationFailure() {
        val dir=java.nio.file.Files.createTempDirectory("trachu-backup-test").toFile()
        try {
            val target=java.io.File(dir,"existing.trachu").apply{writeText("previous backup")}
            val password="short".toCharArray()
            var opened=false
            assertTrue(runCatching{Backup.writeStaged(original,password,dir){opened=true;target.outputStream()}}.isFailure)
            assertFalse(opened)
            assertEquals("previous backup",target.readText())
            assertTrue(password.all{it=='\u0000'})
            assertEquals(listOf("existing.trachu"),dir.list()!!.toList())
            Backup.writeStaged(original,"password123".toCharArray(),dir){target.outputStream()}
            assertEquals(original,target.inputStream().use{Backup.read("password123".toCharArray(),it)})
            assertEquals(listOf("existing.trachu"),dir.list()!!.toList())
        } finally {dir.deleteRecursively()}
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
