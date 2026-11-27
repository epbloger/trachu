package ru.epbloger.trachu.core

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalTime

class EntryOrderTest {
    private val day=20000L
    private fun entry(id:String, date:Long=day, time:Int=600, order:Long=0L) =
        Entry(id=id,kind=Kind.EXPENSE,accountId="a",minor=100,baseMinor=100,day=date,timeMinutes=time,recordedAtMillis=order)

    @Test fun dateAndTimeTakePriorityThenNewRecordOrderNotRandomId() {
        val legacy=entry("zz-legacy")
        val first=entry("z-first")
        val second=entry("a-second")
        val previous=Ledger(entries=listOf(legacy))
        val firstSaved=EntryOrder.stampNew(previous,previous.copy(entries=listOf(legacy,first)),100L)
        val next=EntryOrder.stampNew(firstSaved,firstSaved.copy(entries=listOf(legacy,firstSaved.entries.last(),second)),100L)
        val yesterday=entry("yesterday",day-1,1439,999)
        val later=entry("later",day,601,1)
        assertEquals(listOf("later","a-second","z-first","zz-legacy","yesterday"),
            (next.entries+listOf(yesterday,later)).shuffled().sortedWith(EntryOrder.newestFirst).map { it.id })
        val edited=EntryOrder.stampNew(next,next.copy(entries=next.entries.map{if(it.id==first.id)first.copy(note="Changed")else it}),50L)
        assertEquals(next.entries.map{it.recordedAtMillis},edited.entries.map{it.recordedAtMillis})
        assertEquals(0L,edited.entries.first().recordedAtMillis)
    }

    @Test fun newManualPaymentsUseCurrentTimeAndEditsKeepTime() {
        val now=LocalTime.of(17,45)
        assertEquals(1065,EntryOrder.manualTime(day,currentDay=day,now=now))
        assertEquals(720,EntryOrder.manualTime(day-1,currentDay=day,now=now))
        assertEquals(600,EntryOrder.manualTime(day,entry("previous"),day,now))
    }
}
