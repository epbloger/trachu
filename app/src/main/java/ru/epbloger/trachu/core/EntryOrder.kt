package ru.epbloger.trachu.core

import java.time.LocalTime

/** Chronological display order is separate from financial calculation order. */
object EntryOrder {
    val newestFirst: Comparator<Entry> = compareByDescending<Entry> { it.day }
        .thenByDescending { it.timeMinutes }
        .thenByDescending { it.recordedAtMillis }
        .thenByDescending { it.id }

    /** Called within the serialized database transaction, never on read or restore.
     * Old records keep their original metadata; edits don't become new records.
     * A monotonic tie breaker handles multiple saves in the same millisecond. */
    fun stampNew(before: Ledger, after: Ledger, nowMillis: Long = System.currentTimeMillis()): Ledger {
        val old=before.entries.associateBy { it.id }
        var last=maxOf(before.entries.maxOfOrNull { it.recordedAtMillis } ?: 0L,
            after.entries.maxOfOrNull { it.recordedAtMillis } ?: 0L,0L)
        val entries=after.entries.map { entry ->
            val previous=old[entry.id]
            if(previous!=null) {
                if(entry.recordedAtMillis==previous.recordedAtMillis) entry
                else entry.copy(recordedAtMillis=previous.recordedAtMillis)
            } else if(entry.recordedAtMillis>0L) entry
            else {
                last=maxOf(nowMillis.coerceAtLeast(1L),if(last==Long.MAX_VALUE)last else last+1L)
                entry.copy(recordedAtMillis=last)
            }
        }
        return after.copy(entries=entries)
    }

    /** Today's refund/loan/payment without an explicit time uses the current
     * minute, not noon. An edited record retains its original time. */
    fun manualTime(day: Long, previous: Entry? = null, currentDay: Long = today(),
                   now: LocalTime = LocalTime.now()): Int =
        previous?.timeMinutes ?: if(day==currentDay)now.hour*60+now.minute else 720
}
