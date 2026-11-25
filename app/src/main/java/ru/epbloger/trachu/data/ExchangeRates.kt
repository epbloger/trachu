package ru.epbloger.trachu.data

import ru.epbloger.trachu.core.FxRate
import ru.epbloger.trachu.core.Money
import java.math.BigDecimal
import java.math.MathContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.InputSource

object ExchangeRates {
    private val dateFormat = DateTimeFormatter.ofPattern("dd.MM.uuuu")
    fun fetch(base: String, day: Long): List<FxRate> {
        Money.digits(base)
        val date = LocalDate.ofEpochDay(day).format(DateTimeFormatter.ofPattern("dd/MM/uuuu"))
        val url = URL("https://www.cbr.ru/scripts/XML_daily.asp?date_req=$date")
        val connection = url.openConnection() as HttpsURLConnection
        connection.connectTimeout = 12_000; connection.readTimeout = 12_000
        connection.instanceFollowRedirects = false
        connection.requestMethod = "GET"
        try {
            require(connection.responseCode == 200) { "ЦБ РФ не вернул курсы" }
            return connection.inputStream.use { parse(it.readBounded(1024*1024), base) }
        } finally { connection.disconnect() }
    }
    fun parse(bytes: ByteArray, base: String): List<FxRate> {
        val factory = DocumentBuilderFactory.newInstance()
        // The published feed is ASCII-compatible Windows-1251 or UTF-8.
        // Reject declarations before parsing, including UTF-16/32 NUL bytes.
        require(bytes.none { it == 0.toByte() })
        val ascii = bytes.toString(Charsets.ISO_8859_1)
        require(!ascii.contains("<!DOCTYPE", ignoreCase=true) && !ascii.contains("<!ENTITY", ignoreCase=true))
        factory.isExpandEntityReferences = false
        runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        val builder = factory.newDocumentBuilder()
        builder.setEntityResolver { _, _ -> InputSource(java.io.StringReader("")) }
        val root = builder.parse(bytes.inputStream()).documentElement
        require(root.tagName == "ValCurs")
        val day = LocalDate.parse(root.getAttribute("Date"),dateFormat).toEpochDay()
        val rub = mutableMapOf("RUB" to BigDecimal.ONE)
        val nodes = root.getElementsByTagName("Valute")
        for (i in 0 until nodes.length) {
            val v = nodes.item(i) as Element
            fun field(name: String) = v.getElementsByTagName(name).item(0).textContent.trim()
            val code = field("CharCode")
            if (runCatching { Money.digits(code) }.isFailure) continue
            val nominal = BigDecimal(field("Nominal"))
            val value = BigDecimal(field("Value").replace(',','.'))
            require(nominal.signum() > 0 && value.signum() > 0)
            rub[code] = value.divide(nominal,MathContext.DECIMAL128)
        }
        val pivot = rub[base] ?: error("ЦБ РФ не публикует курс $base")
        return rub.filterKeys { it != base }.map { (code,value) ->
            FxRate(code,day,value.divide(pivot,MathContext.DECIMAL128).setScale(18,java.math.RoundingMode.HALF_EVEN).stripTrailingZeros().toPlainString(),"CBR")
        }
    }
}
