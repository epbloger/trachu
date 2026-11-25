package ru.epbloger.trachu.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ru.epbloger.trachu.core.*

@Composable fun Operations(ledger: Ledger, add: (Kind)->Unit, open: (Entry)->Unit) {
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("Все") }
    var account by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(PeriodType.MONTH) }
    var period by remember { mutableStateOf(Period.month()) }
    var allDates by remember { mutableStateOf(true) }
    var sort by remember { mutableStateOf("Новые") }
    var expanded by remember { mutableStateOf(false) }
    val entries=remember(ledger,query,filter,account,category,period,allDates,sort) {
        val q=query.trim().lowercase()
        val selected=ledger.entries.filter { e->
            (allDates || e.day in period) &&
            (account.isEmpty() || e.accountId==account || e.targetAccountId==account) &&
            (category.isEmpty() || e.splits.any{it.categoryId==category || ledger.categories.find{c->c.id==it.categoryId}?.parentId==category}) &&
            (filter=="Все" || filter=="План" && e.status==EntryStatus.PLANNED || filter==e.kind.label() && e.status==EntryStatus.POSTED) &&
            (q.isBlank() || listOf(e.note,ledger.entryName(e),ledger.accountName(e.accountId),e.tags.joinToString(" "),iso(e.day),Money.input(e.minor,ledger.accounts.first{it.id==e.accountId}.currency)).any{it.lowercase().contains(q)})
        }
        when(sort){ "Старые"->selected.sortedWith(compareBy<Entry>{it.day}.thenBy{it.timeMinutes});"Сумма"->selected.sortedByDescending{it.baseMinor};else->selected.sortedWith(compareByDescending<Entry>{it.day}.thenByDescending{it.timeMinutes}) }
    }
    LazyColumn(contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        item { SectionTitle("Операции",action="Добавить"){add(Kind.EXPENSE)} }
        item {
            Field("Поиск по сумме, заметке, тегу",query,{query=it})
            Chips(listOf("Все","Расход","Доход","Перевод","Возврат","План"),filter,{it}){filter=it}
            TextButton(onClick={expanded=!expanded}){Text(if(expanded)"Скрыть фильтры" else "Фильтры и сортировка")}
        }
        if(expanded)item {
            Panel {
                Choice("Счёт",account,listOf("")+ledger.accounts.map{it.id},{if(it.isEmpty())"Все счета" else ledger.accountName(it)}){account=it}
                Choice("Категория",category,listOf("")+ledger.categories.map{it.id},{if(it.isEmpty())"Все категории" else ledger.categoryName(it)}){category=it}
                Choice("Сортировка",sort,listOf("Новые","Старые","Сумма"),{it}){sort=it}
                Toggle("Все даты",allDates,{allDates=it})
                if(!allDates)PeriodControls(type,{type=it},period,{period=it})
            }
        }
        item { Text("Найдено: ${entries.size}",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant) }
        if(entries.isEmpty()) item { EmptyState("Нет операций","Добавьте расход или измените фильтры.","Добавить",{add(Kind.EXPENSE)}) }
        items(entries,key={it.id}) { entry->Panel { EntryRow(ledger,entry){open(entry)} } }
    }
}
