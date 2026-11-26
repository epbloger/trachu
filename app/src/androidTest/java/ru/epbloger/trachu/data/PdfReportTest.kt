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
    private fun java.io.InputStream.readNBytesCompat(n:Int):ByteArray=ByteArray(n).also{java.io.DataInputStream(this).readFully(it)}
}
