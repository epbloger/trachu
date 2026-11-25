package ru.epbloger.trachu.data

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import ru.epbloger.trachu.core.*
import java.io.OutputStream
import java.time.LocalDate

object PdfReport {
    fun write(ledger: Ledger, period: Period, output: OutputStream) {
        val doc = PdfDocument()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        var page: PdfDocument.Page? = null
        var pageNumber = 0
        var y = 0f
        fun text(canvas: Canvas, content: String, x: Float, top: Float, size: Float = 12f, bold: Boolean = false, color: Int = Color.rgb(28,48,44)) {
            paint.textSize=size; paint.color=color
            paint.typeface=Typeface.create("sans-serif",if(bold) Typeface.BOLD else Typeface.NORMAL)
            canvas.drawText(content,x,top,paint)
        }
        fun newPage() {
            page?.let { doc.finishPage(it) }
            pageNumber++
            page=doc.startPage(PdfDocument.PageInfo.Builder(595,842,pageNumber).create())
            val c=requireNotNull(page).canvas
            c.drawColor(Color.WHITE)
            text(c,"ТРАЧУ.",36f,44f,24f,true)
            text(c,"Финансовый отчёт • ${LocalDate.ofEpochDay(period.from)} — ${LocalDate.ofEpochDay(period.through)}",36f,68f,11f)
            text(c,"Moorkis Development  •  $pageNumber",36f,815f,10f)
            y=96f
        }
        fun row(value: String, bold: Boolean=false) {
            var remaining=value.replace('\n',' ')
            do {
                paint.textSize=11f
                paint.typeface=Typeface.create("sans-serif",if(bold) Typeface.BOLD else Typeface.NORMAL)
                val measured=paint.breakText(remaining,true,523f,null)
                val count=if(remaining.isEmpty())0 else measured.coerceAtLeast(1).coerceAtMost(remaining.length)
                val end=if(count<remaining.length && count>0) {
                    val space=remaining.lastIndexOf(' ',count-1)
                    if(space>0)space else count
                } else count
                if(y>785f)newPage()
                text(requireNotNull(page).canvas,remaining.substring(0,end),36f,y,11f,bold)
                y+=18f
                remaining=remaining.substring(end).trimStart()
            }while(remaining.isNotEmpty())
        }
        try {
            newPage()
            val totals=Finance.totals(ledger,period)
            row("Доходы: ${Money.format(totals.income,ledger.baseCurrency)}",true)
            row("Расходы: ${Money.format(totals.expense,ledger.baseCurrency)}",true)
            row("Результат: ${Money.format(totals.net,ledger.baseCurrency)}",true)
            y+=12
            row("По категориям",true)
            Finance.byCategory(ledger,period).toList().sortedByDescending { it.second }.forEach { (id,amount) ->
                row("${Finance.categoryLabel(ledger,id)}: ${Money.format(amount,ledger.baseCurrency)}")
            }
            y+=12
            row("Операции",true)
            ledger.entries.filter { it.status==EntryStatus.POSTED && it.day in period }.sortedByDescending { it.day }.forEach { e ->
                val a=ledger.accounts.first { it.id==e.accountId }
                val kind=when(e.kind) { Kind.EXPENSE->"Расход"; Kind.INCOME->"Доход"; Kind.TRANSFER->"Перевод"; Kind.REFUND->"Возврат"; Kind.DEBT_IN,Kind.DEBT_OUT->"Погашение долга"; Kind.LOAN_IN,Kind.LOAN_OUT->"Получение / выдача займа" }
                row("${LocalDate.ofEpochDay(e.day)}  $kind  ${Money.format(e.minor,a.currency)}  ${a.name}")
                if(e.note.isNotBlank()) row(e.note)
            }
            page?.let { doc.finishPage(it); page=null }
            doc.writeTo(output)
        } finally {
            page?.let { runCatching { doc.finishPage(it) } }
            doc.close()
        }
    }
}
