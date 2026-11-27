package ru.epbloger.trachu.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import ru.epbloger.trachu.core.*
import java.io.File
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class PdfReportTest {
    @Test fun refundAppearsInPurchaseAndReceivingAccountReportsWithoutDoubleCounting() {
        val day=today()
        val base=Ledger.initial().copy(accounts=listOf(Account("a","Карта"),Account("b","Наличные")))
        val purchase=Finance.makeEntry(base,Kind.EXPENSE,"a",1000,day,listOf("food" to 1000L))
        val purchased=Finance.putEntry(base,purchase)
        val refund=Finance.refund(purchased,purchase.id,300,day,accountId="b")
        val ledger=Finance.putEntry(purchased,refund)
        val period=Period(day,day)
        fun accountReport(id:String)=ledger.copy(accounts=ledger.accounts.map{it.copy(includeInStats=it.id==id)})
        val a=accountReport("a")
        val b=accountReport("b")
        assertEquals(setOf(purchase.id,refund.id),PdfReport.operationEntries(a,period).map{it.id}.toSet())
        assertEquals(listOf(refund.id),PdfReport.operationEntries(b,period).map{it.id})
        assertEquals(2,PdfReport.operationEntries(ledger,period).size)
        assertTrue(PdfReport.operationEntries(b,Period(day-1,day-1)).isEmpty())
        assertEquals(700L,Finance.totals(a,period).expense)
        assertEquals(0L,Finance.totals(b,period).expense)
        assertEquals(300L,Finance.balance(ledger,"b",day))
        val output=ByteArrayOutputStream()
        PdfReport.write(ledger,period,output,"b")
        assertTrue(output.size()>100)
    }

    @Test fun accountReportCanBeOpenedAndRendered() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val day=today()
        var ledger=Ledger.initial().copy(accounts=listOf(Account("a","Карта"),Account("b","Наличные")))
        ledger=Finance.putEntry(ledger,Finance.makeEntry(ledger,Kind.EXPENSE,"a",1234,day,listOf("food" to 1000L,"home" to 234L)))
        ledger=Finance.putEntry(ledger,Finance.makeEntry(ledger,Kind.TRANSFER,"a",500,day,targetId="b",targetMinor=500))
        val file=File.createTempFile("report-",".pdf",context.cacheDir)
        try {
            file.outputStream().use{PdfReport.write(ledger,Period.month(day),it,"a")}
            assertTrue(file.length()>100)
            assertEquals("%PDF-",file.inputStream().use{String(it.readNBytesCompat(5),Charsets.US_ASCII)})
            PdfRenderer(ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY)).use{renderer->
                assertTrue(renderer.pageCount>0)
                renderer.openPage(0).use{page->
                    val bitmap=Bitmap.createBitmap(page.width,page.height,Bitmap.Config.ARGB_8888)
                    try{page.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)}finally{bitmap.recycle()}
                }
            }
            assertTrue(runCatching{PdfReport.write(ledger,Period.month(day),ByteArrayOutputStream(),"unknown")}.isFailure)
        }finally{file.delete()}
    }

    @Test fun selectedAccountAndDateRangeFilterRenderedPdfContent() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val day=today()
        val earlier=day-40
        var ledger=Ledger.initial().copy(accounts=listOf(Account("a","Карта A"),Account("b","Карта B")))
        fun add(kind:Kind,account:String,minor:Long,date:Long,category:String){
            ledger=Finance.putEntry(ledger,Finance.makeEntry(ledger,kind,account,minor,date,listOf(category to minor)))
        }
        add(Kind.INCOME,"a",5000,day,"salary")
        add(Kind.EXPENSE,"a",1200,day,"food")
        add(Kind.INCOME,"b",9000,day,"salary")
        add(Kind.EXPENSE,"b",3000,day,"home")
        add(Kind.INCOME,"a",7000,earlier,"salary")
        add(Kind.EXPENSE,"a",4000,earlier,"food")

        val files=listOf(
            File.createTempFile("report-current-a-",".pdf",context.cacheDir),
            File.createTempFile("report-current-b-",".pdf",context.cacheDir),
            File.createTempFile("report-earlier-a-",".pdf",context.cacheDir)
        )
        val bitmaps=mutableListOf<Bitmap>()
        try{
            files[0].outputStream().use{PdfReport.write(ledger,Period(day,day),it,"a")}
            files[1].outputStream().use{PdfReport.write(ledger,Period(day,day),it,"b")}
            files[2].outputStream().use{PdfReport.write(ledger,Period(earlier,earlier),it,"a")}
            files.forEach{file->
                assertTrue(file.length()>100)
                PdfRenderer(ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY)).use{renderer->
                    assertTrue(renderer.pageCount>0)
                    renderer.openPage(0).use{page->
                        val bitmap=Bitmap.createBitmap(page.width,page.height,Bitmap.Config.ARGB_8888)
                        page.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bitmaps+=bitmap
                    }
                }
            }
            fun contentBelowAccount(bitmap:Bitmap)=Bitmap.createBitmap(bitmap,0,120,bitmap.width,bitmap.height-120)
            val currentA=contentBelowAccount(bitmaps[0])
            val currentB=contentBelowAccount(bitmaps[1])
            val earlierA=contentBelowAccount(bitmaps[2])
            try{
                assertFalse("Selected account must filter totals and operations",currentA.sameAs(currentB))
                assertFalse("Selected date range must filter totals and operations",currentA.sameAs(earlierA))
            }finally{currentA.recycle();currentB.recycle();earlierA.recycle()}
        }finally{
            bitmaps.forEach{it.recycle()}
            files.forEach{it.delete()}
        }
    }
    private fun java.io.InputStream.readNBytesCompat(n:Int):ByteArray=ByteArray(n).also{java.io.DataInputStream(this).readFully(it)}
}
