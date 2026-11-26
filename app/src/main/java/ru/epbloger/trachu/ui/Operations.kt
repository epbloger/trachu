package ru.epbloger.trachu.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ru.epbloger.trachu.core.*

@Composable fun Operations(ledger: Ledger, open: (Entry)->Unit, monthStart:Int=1) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("Все") }
    var account by rememberSaveable { mutableStateOf("") }
    var categoryIds by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    val categories=categoryIds.toSet()
    var tagIds by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    val tags=tagIds.toSet()
    val availableTags=remember(ledger){ledger.entries.flatMap{it.tags}.distinct().sorted()}
    var type by rememberSaveable { mutableStateOf(PeriodType.MONTH) }
    var period by rememberSaveable(monthStart,stateSaver=PeriodSaver) { mutableStateOf(Period.month(startDay=monthStart)) }
    var allDates by rememberSaveable { mutableStateOf(true) }
    var sort by rememberSaveable { mutableStateOf("Новые") }
    var expanded by rememberSaveable { mutableStateOf(false) }
    val entries=remember(ledger,query,filter,account,categories,tags,period,allDates,sort) {
        val q=query.trim().lowercase()
        val selected=ledger.entries.filter { e->
            (allDates || e.day in period) &&
            (account.isEmpty() || e.accountId==account || e.targetAccountId==account) &&
            (categories.isEmpty() || e.splits.any{it.categoryId in categories || ledger.categories.find{c->c.id==it.categoryId}?.parentId in categories}) &&
            (tags.isEmpty() || e.tags.any{it in tags}) &&
            (filter=="Все" || filter=="План" && e.status==EntryStatus.PLANNED || filter==e.kind.label() && e.status==EntryStatus.POSTED) &&
            (q.isBlank() || listOf(e.note,ledger.entryName(e),ledger.accountName(e.accountId),e.tags.joinToString(" "),iso(e.day),Money.input(e.minor,ledger.accounts.first{it.id==e.accountId}.currency)).any{it.lowercase().contains(q)} ||
                q.replace(',','.')==Money.input(e.minor,ledger.accounts.first{it.id==e.accountId}.currency))
        }
        when(sort){ "Старые"->selected.sortedWith(compareBy<Entry>{it.day}.thenBy{it.timeMinutes});"Сумма"->selected.sortedByDescending{it.baseMinor};else->selected.sortedWith(compareByDescending<Entry>{it.day}.thenByDescending{it.timeMinutes}) }
    }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
      val wide=maxWidth>=760.dp && LocalDensity.current.fontScale<1.5f
      Row(Modifier.fillMaxSize()) {
       LazyColumn(modifier=Modifier.weight(1f).testTag("operations-list"),contentPadding=PaddingValues(start=20.dp,end=20.dp,top=20.dp,bottom=96.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
         item { SectionTitle("Операции") }
        item {
            Field("Поиск по сумме, заметке, тегу",query,{query=it})
             val primaryTypes=listOf("Все","Расход","Доход","Перевод")
             val otherTypes=listOf("Возврат","План","Погашение долга","Возврат долга вам","Получение займа","Выдача займа")
             var moreTypes by rememberSaveable{mutableStateOf(false)}
             Chips(primaryTypes,filter,{it}){filter=it}
             Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){
                 TextButton(onClick={moreTypes=true},modifier=Modifier.testTag("operation-more-types")){
                     Text(if(filter in otherTypes)"Тип: $filter ▾" else "Другие типы ▾")
                 }
                 if(filter in otherTypes)TextButton(onClick={filter="Все"}){Text("Сбросить")}
             }
             if(moreTypes)AlertDialog(onDismissRequest={moreTypes=false},title={Text("Тип операции")},text={
                 Column(Modifier.verticalScroll(rememberScrollState())){
                     otherTypes.forEach{kind->TextButton(onClick={filter=kind;moreTypes=false},modifier=Modifier.fillMaxWidth().testTag("operation-type:$kind")){Text(if(filter==kind)"✓ $kind" else kind)}}
                 }
             },confirmButton={TextButton(onClick={moreTypes=false}){Text("Закрыть")}})
            val activeFilters=(if(account.isNotEmpty())1 else 0)+(if(categories.isNotEmpty())1 else 0)+(if(tags.isNotEmpty())1 else 0)+(if(!allDates)1 else 0)+(if(sort!="Новые")1 else 0)
            TextButton(onClick={expanded=!expanded}){Text((if(expanded)"Скрыть фильтры" else "Фильтры и сортировка")+if(activeFilters>0)" ($activeFilters)" else "")}
        }
        if(expanded)item {
            Panel {
                 AccountChoice("Счёт",account,ledger.accounts,allLabel="Все счета"){account=it}
                MultiChoice("Категории",categories,ledger.categories.map{it.id},{ledger.categoryName(it)}){categoryIds=it.toList()}
                if(availableTags.isNotEmpty()||tags.isNotEmpty())MultiChoice("Теги (любой выбранный)",tags,availableTags,{it}){tagIds=it.toList()}
                Choice("Сортировка",sort,listOf("Новые","Старые","Сумма"),{it}){sort=it}
                Toggle("Все даты",allDates,{allDates=it})
                if(!allDates)PeriodControls(type,{type=it},period,{period=it},monthStart)
                TextButton(onClick={query="";filter="Все";account="";categoryIds=emptyList();tagIds=emptyList();type=PeriodType.MONTH;period=Period.month(startDay=monthStart);allDates=true;sort="Новые"}){Text("Сбросить все фильтры")}
            }
        }
        item { Text("Найдено: ${entries.size}",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant) }
        if(entries.isEmpty()) item { EmptyState("Нет операций","Добавьте расход или измените фильтры.") }
        items(entries,key={it.id}) { entry->Panel(Modifier.testTag("operation:${entry.id}")) { EntryRow(ledger,entry){if(wide)selectedId=entry.id else open(entry)} } }
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
