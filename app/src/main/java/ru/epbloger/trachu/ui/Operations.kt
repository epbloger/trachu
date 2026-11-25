package ru.epbloger.trachu.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ru.epbloger.trachu.core.*

@Composable fun Operations(ledger: Ledger, add: (Kind)->Unit, open: (Entry)->Unit, monthStart:Int=1) {
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("Все") }
    var account by remember { mutableStateOf("") }
    var categories by remember { mutableStateOf(emptySet<String>()) }
    var type by remember { mutableStateOf(PeriodType.MONTH) }
    var period by remember(monthStart) { mutableStateOf(Period.month(startDay=monthStart)) }
    var allDates by remember { mutableStateOf(true) }
    var sort by remember { mutableStateOf("Новые") }
    var expanded by remember { mutableStateOf(false) }
    val entries=remember(ledger,query,filter,account,categories,period,allDates,sort) {
        val q=query.trim().lowercase()
        val selected=ledger.entries.filter { e->
            (allDates || e.day in period) &&
            (account.isEmpty() || e.accountId==account || e.targetAccountId==account) &&
            (categories.isEmpty() || e.splits.any{it.categoryId in categories || ledger.categories.find{c->c.id==it.categoryId}?.parentId in categories}) &&
            (filter=="Все" || filter=="План" && e.status==EntryStatus.PLANNED || filter==e.kind.label() && e.status==EntryStatus.POSTED) &&
            (q.isBlank() || listOf(e.note,ledger.entryName(e),ledger.accountName(e.accountId),e.tags.joinToString(" "),iso(e.day),Money.input(e.minor,ledger.accounts.first{it.id==e.accountId}.currency)).any{it.lowercase().contains(q)})
        }
        when(sort){ "Старые"->selected.sortedWith(compareBy<Entry>{it.day}.thenBy{it.timeMinutes});"Сумма"->selected.sortedByDescending{it.baseMinor};else->selected.sortedWith(compareByDescending<Entry>{it.day}.thenByDescending{it.timeMinutes}) }
    }
    var selectedId by remember { mutableStateOf<String?>(null) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
      val wide=maxWidth>=760.dp && LocalDensity.current.fontScale<1.5f
      Row(Modifier.fillMaxSize()) {
       LazyColumn(modifier=Modifier.weight(1f),contentPadding=PaddingValues(start=20.dp,end=20.dp,top=20.dp,bottom=96.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        item { SectionTitle("Операции",action="Добавить"){add(Kind.EXPENSE)} }
        item {
            Field("Поиск по сумме, заметке, тегу",query,{query=it})
            Chips(listOf("Все","Расход","Доход","Перевод","Возврат","План"),filter,{it}){filter=it}
            TextButton(onClick={expanded=!expanded}){Text(if(expanded)"Скрыть фильтры" else "Фильтры и сортировка")}
        }
        if(expanded)item {
            Panel {
                Choice("Счёт",account,listOf("")+ledger.accounts.map{it.id},{if(it.isEmpty())"Все счета" else ledger.accountName(it)}){account=it}
                MultiChoice("Категории",categories,ledger.categories.map{it.id},{ledger.categoryName(it)}){categories=it}
                Choice("Сортировка",sort,listOf("Новые","Старые","Сумма"),{it}){sort=it}
                Toggle("Все даты",allDates,{allDates=it})
                if(!allDates)PeriodControls(type,{type=it},period,{period=it},monthStart)
            }
        }
        item { Text("Найдено: ${entries.size}",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant) }
        if(entries.isEmpty()) item { EmptyState("Нет операций","Добавьте расход или измените фильтры.","Добавить",{add(Kind.EXPENSE)}) }
        items(entries,key={it.id}) { entry->Panel { EntryRow(ledger,entry){if(wide)selectedId=entry.id else open(entry)} } }
       }
       if(wide) {
        val selected=entries.find{it.id==selectedId} ?: entries.firstOrNull()
        Column(Modifier.weight(.9f).verticalScroll(rememberScrollState()).padding(top=20.dp,end=20.dp,bottom=96.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
          if(selected==null)EmptyState("Операция","Выберите запись в списке.")
          else Panel {
            SectionTitle(ledger.entryName(selected))
            MoneyText(selected.minor,ledger.accounts.first{it.id==selected.accountId}.currency,large=true)
            Text(dayLabel(selected.day)+" · "+ledger.accountName(selected.accountId))
            selected.splits.forEach{Text(ledger.categoryName(it.categoryId)+": "+Money.format(it.minor,ledger.accounts.first{a->a.id==selected.accountId}.currency))}
            if(selected.note.isNotBlank())Text(selected.note)
            if(selected.tags.isNotEmpty())Text(selected.tags.joinToString("  "){ "#$it" })
            selected.receiptIds.forEach{id->ledger.receipts.find{it.id==id}?.let{ReceiptPreview(it)}}
            Button(onClick={open(selected)},modifier=Modifier.fillMaxWidth()){Text("Подробности и действия")}
          }
        }
       }
      }
    }
}
