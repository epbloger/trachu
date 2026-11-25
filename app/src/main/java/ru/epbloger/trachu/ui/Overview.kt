package ru.epbloger.trachu.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import ru.epbloger.trachu.core.*
import java.time.LocalDate
import java.time.YearMonth
import java.time.DayOfWeek
import java.time.temporal.TemporalAdjusters

@Composable fun Dashboard(ledger: Ledger, add: (Kind)->Unit, openEntry: (Entry)->Unit, accounts: ()->Unit, reports: ()->Unit, plans: ()->Unit, operations: ()->Unit, calendar: ()->Unit) {
    val day=today()
    val p=Period.month(day)
    val totals=remember(ledger,p){Finance.totals(ledger,p)}
    val valuation=remember(ledger,day){Finance.valuation(ledger,day)}
    val recent=remember(ledger){ledger.entries.filter { it.status==EntryStatus.POSTED }.sortedWith(compareByDescending<Entry>{it.day}.thenByDescending{it.timeMinutes}).take(5)}
    val budget=ledger.budgets.firstOrNull { it.categoryId==null } ?: ledger.budgets.firstOrNull()
    val planned=remember(ledger){ledger.entries.filter { it.status==EntryStatus.PLANNED }.sortedBy { it.day }.take(3)}
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide=maxWidth>=840.dp && LocalDensity.current.fontScale<1.5f
        LazyColumn(contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            item {
                Text("Обзор",style=MaterialTheme.typography.headlineLarge)
                Text(LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("LLLL yyyy",java.util.Locale("ru"))).replaceFirstChar{it.uppercase()},
                    style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                @Composable fun Hero() {
                    Card(shape=MaterialTheme.shapes.extraLarge) {
                        Column(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(Deep,Color(0xFF245D4C)))).padding(24.dp),
                            verticalArrangement=Arrangement.spacedBy(12.dp)) {
                            Text("Общий баланс",color=Mint,style=MaterialTheme.typography.labelLarge)
                            MoneyText(valuation.minor,ledger.baseCurrency,color=Color.White,large=true)
                            if(valuation.missingCurrencies.isNotEmpty()) Text("Не включены валюты: "+valuation.missingCurrencies.joinToString(),color=Mint,style=MaterialTheme.typography.bodyMedium)
                            Text("Результат месяца",color=Mint,style=MaterialTheme.typography.bodyMedium)
                            MoneyText(totals.net,ledger.baseCurrency,color=Color.White)
                            Row(horizontalArrangement=Arrangement.spacedBy(8.dp),modifier=Modifier.fillMaxWidth()) {
                                Button(onClick={add(Kind.EXPENSE)},modifier=Modifier.weight(1f),colors=ButtonDefaults.buttonColors(containerColor=Mint,contentColor=Deep)) { Text("− Расход") }
                                OutlinedButton(onClick={add(Kind.INCOME)},modifier=Modifier.weight(1f),colors=ButtonDefaults.outlinedButtonColors(contentColor=Color.White)) { Text("+ Доход") }
                            }
                        }
                    }
                }
                @Composable fun Summary() {
                    Panel {
                        SectionTitle("Движение денег",action="Отчёты",onAction=reports)
                        Text("Доходы",color=MaterialTheme.colorScheme.onSurfaceVariant)
                        MoneyText(totals.income,ledger.baseCurrency,color=MaterialTheme.colorScheme.primary,large=true)
                        HorizontalDivider()
                        Text("Расходы",color=MaterialTheme.colorScheme.onSurfaceVariant)
                        MoneyText(totals.expense,ledger.baseCurrency,large=true)
                    }
                }
                if(wide) Row(horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                    Box(Modifier.weight(1f)){Hero()}; Box(Modifier.weight(1f)){Summary()}
                } else Column(verticalArrangement=Arrangement.spacedBy(16.dp)){Hero();Summary()}
            }
            if(ledger.accounts.isEmpty()) item { EmptyState("Начните со счёта","Добавьте наличные, карту или накопительный счёт. Начальный остаток не считается доходом.","Добавить счёт",accounts) }
            else item {
                Panel {
                    SectionTitle("Счета",action="Все",onAction=accounts)
                    ledger.accounts.filterNot { it.archived }.take(3).forEach { a ->
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) { Text(a.name,style=MaterialTheme.typography.titleMedium); Text(a.type.label(),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant) }
                            MoneyText(Finance.balance(ledger,a.id),a.currency)
                        }
                    }
                    OutlinedButton(onClick={add(Kind.TRANSFER)},modifier=Modifier.fillMaxWidth(),enabled=ledger.accounts.count{!it.archived}>=2){Text("Перевод между счетами")}
                }
            }
            item {
                @Composable fun Spending() { CategoryReport(ledger,p) }
                @Composable fun BudgetSummary() {
                    Panel {
                        SectionTitle("Бюджет",action="План",onAction=plans)
                        if(budget==null) { Text("Установите лимит на месяц, чтобы отслеживать остаток."); TextButton(onClick=plans){Text("Настроить бюджет")} }
                        else {
                            val result=Finance.budget(ledger,budget,day)
                            Text(budget.name,style=MaterialTheme.typography.titleMedium)
                            MoneyText(result.remaining,ledger.baseCurrency,large=true,color=if(result.remaining<0)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                            Text("Осталось из "+Money.format(result.available,ledger.baseCurrency))
                            Progress(result.spent,result.available)
                            Text("Использовано ${result.percent}%")
                        }
                    }
                }
                if(wide) Row(horizontalArrangement=Arrangement.spacedBy(16.dp)) { Box(Modifier.weight(1f)){Spending()};Box(Modifier.weight(1f)){BudgetSummary()} }
                else Column(verticalArrangement=Arrangement.spacedBy(16.dp)){Spending();BudgetSummary()}
            }
            item {
                Panel {
                    SectionTitle("Последние операции",action="Все",onAction=operations)
                    if(recent.isEmpty()) Text("Здесь появятся ваши операции.",color=MaterialTheme.colorScheme.onSurfaceVariant)
                    recent.forEach { EntryRow(ledger,it){openEntry(it)} }
                }
            }
            item {
                Panel {
                    SectionTitle("Предстоящие платежи",action="Календарь",onAction=calendar)
                    if(planned.isEmpty()) Text("Пока ничего не запланировано.",color=MaterialTheme.colorScheme.onSurfaceVariant)
                    planned.forEach { EntryRow(ledger,it){openEntry(it)} }
                }
            }
        }
    }
}

@Composable fun CategoryReport(ledger: Ledger, period: Period, income: Boolean=false) {
    val categories=remember(ledger,period,income){Finance.byCategory(ledger,period,income).toList().sortedByDescending{it.second}}
    val total=Money.sum(categories.map{it.second})
    val positive=categories.filter{it.second>0}
    val positiveTotal=Money.sum(positive.map{it.second})
    Panel {
        SectionTitle(if(income)"Структура доходов" else "Структура расходов")
        if(categories.isEmpty()) Text("Нет операций за выбранный период.",color=MaterialTheme.colorScheme.onSurfaceVariant)
        else {
            Box(Modifier.fillMaxWidth(),contentAlignment=Alignment.Center) {
                Donut(positive.map{(id,v)->Color(ledger.categories.find{it.id==id}?.color ?: 0xFF218C75) to v},"Диаграмма категорий")
                Column(horizontalAlignment=Alignment.CenterHorizontally,modifier=Modifier.widthIn(max=100.dp)) {
                    Text("Итого",style=MaterialTheme.typography.labelSmall)
                    Text(Money.format(total,ledger.baseCurrency),style=MaterialTheme.typography.labelMedium)
                }
            }
            categories.forEach { (id,v)->
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    CategoryDot(ledger.categories.find{it.id==id},Modifier)
                    Column(Modifier.weight(1f)) {
                        Text(ledger.categoryName(id),style=MaterialTheme.typography.titleMedium)
                        Progress(v,positiveTotal,Color(ledger.categories.find{it.id==id}?.color ?: 0xFF218C75))
                    }
                    MoneyText(v,ledger.baseCurrency)
                }
            }
        }
    }
}

enum class PeriodType(val label: String) { DAY("День"),WEEK("Неделя"),MONTH("Месяц"),YEAR("Год"),CUSTOM("Период") }
fun periodFor(type: PeriodType, day: Long=today()): Period {
    val d=LocalDate.ofEpochDay(day)
    return when(type) {
        PeriodType.DAY->Period(day,day)
        PeriodType.WEEK->Period(d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toEpochDay(),d.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY)).toEpochDay())
        PeriodType.MONTH,PeriodType.CUSTOM->Period.month(day)
        PeriodType.YEAR->Period(d.withDayOfYear(1).toEpochDay(),d.withDayOfYear(d.lengthOfYear()).toEpochDay())
    }
}
@Composable fun PeriodControls(type: PeriodType, onType: (PeriodType)->Unit, period: Period, onPeriod: (Period)->Unit) {
    var from by remember(period){mutableStateOf(iso(period.from))}
    var to by remember(period){mutableStateOf(iso(period.through))}
    var error by remember{mutableStateOf("")}
    Chips(PeriodType.entries,type,{it.label}) { onType(it); if(it!=PeriodType.CUSTOM)onPeriod(periodFor(it)) }
    if(type==PeriodType.CUSTOM) {
        Field("С",from,{from=it},supporting="ГГГГ-ММ-ДД")
        Field("По",to,{to=it},supporting="ГГГГ-ММ-ДД")
        Button(onClick={runCatching{Period(parseDay(from),parseDay(to))}.onSuccess{onPeriod(it);error=""}.onFailure{error="Проверьте даты"}}){Text("Применить")}
        if(error.isNotEmpty())Warning(error)
    } else {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
            TextButton(onClick={
                val d=LocalDate.ofEpochDay(period.from)
                val prev=when(type){PeriodType.DAY->d.minusDays(1);PeriodType.WEEK->d.minusWeeks(1);PeriodType.MONTH->d.minusMonths(1);PeriodType.YEAR->d.minusYears(1);else->d}
                onPeriod(periodFor(type,prev.toEpochDay()))
            }){Text("‹")}
            Text("${iso(period.from)} — ${iso(period.through)}",style=MaterialTheme.typography.labelMedium,modifier=Modifier.weight(1f))
            TextButton(onClick={
                val d=LocalDate.ofEpochDay(period.from)
                val next=when(type){PeriodType.DAY->d.plusDays(1);PeriodType.WEEK->d.plusWeeks(1);PeriodType.MONTH->d.plusMonths(1);PeriodType.YEAR->d.plusYears(1);else->d}
                onPeriod(periodFor(type,next.toEpochDay()))
            }){Text("›")}
        }
    }
}
@Composable fun Reports(ledger: Ledger, exportPdf: (Period)->Unit) {
    var type by remember { mutableStateOf(PeriodType.MONTH) }
    var period by remember { mutableStateOf(Period.month()) }
    var income by remember { mutableStateOf(false) }
    val totals=remember(ledger,period){Finance.totals(ledger,period)}
    val length=period.through-period.from+1
    val previous=remember(ledger,period,type) {
        val prev=when(type) {
            PeriodType.MONTH->Period.month(LocalDate.ofEpochDay(period.from).minusMonths(1).toEpochDay())
            PeriodType.YEAR->periodFor(type,LocalDate.ofEpochDay(period.from).minusYears(1).toEpochDay())
            else->Period(period.from-length,period.from-1)
        }
        Finance.totals(ledger,prev)
    }
    val groups=remember(ledger,period){
        ledger.entries.filter { it.status==EntryStatus.POSTED && it.day in period }
            .groupBy { if(length<=31) iso(it.day) else YearMonth.from(LocalDate.ofEpochDay(it.day)).toString() }
            .toSortedMap().map { (label,entries) ->
                val p=Period(entries.minOf{it.day},entries.maxOf{it.day})
                label to Finance.totals(ledger,p)
            }.takeLast(60)
    }
    val forecast=remember(ledger) { runCatching { Finance.generate(ledger,today()+30,true).entries.filter{it.status==EntryStatus.PLANNED && it.day in today()..today()+30} }.getOrDefault(emptyList()) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide=maxWidth>=840.dp && LocalDensity.current.fontScale<1.5f
        LazyColumn(contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            item { SectionTitle("Аналитика",action="PDF"){exportPdf(period)} }
            item { Panel { PeriodControls(type,{type=it},period,{period=it}) } }
            item {
                Panel {
                    Text("Финансовый результат",style=MaterialTheme.typography.titleMedium)
                    MoneyText(totals.net,ledger.baseCurrency,large=true,color=if(totals.net>=0)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                    Text("Доходы: "+Money.format(totals.income,ledger.baseCurrency))
                    Text("Расходы: "+Money.format(totals.expense,ledger.baseCurrency))
                    Text("Средние расходы в день: "+Money.format(totals.expense/length,ledger.baseCurrency))
                    HorizontalDivider()
                    Text("Предыдущий сопоставимый период",style=MaterialTheme.typography.titleMedium)
                    Text("Результат: "+Money.format(previous.net,ledger.baseCurrency))
                    Text("Изменение: "+Money.format(Money.sub(totals.net,previous.net),ledger.baseCurrency))
                }
            }
            item {
                @Composable fun Cats(){ Column(verticalArrangement=Arrangement.spacedBy(8.dp)) { Chips(listOf(false,true),income,{if(it)"Доходы" else "Расходы"}){income=it};CategoryReport(ledger,period,income) } }
                @Composable fun Tags(){ Panel {
                    SectionTitle("Расходы по тегам")
                    val tags=Finance.byTag(ledger,period).toList().sortedByDescending{it.second}
                    if(tags.isEmpty())Text("Добавьте теги к операциям.")
                    else tags.forEach { (tag,value)->Row(horizontalArrangement=Arrangement.SpaceBetween,modifier=Modifier.fillMaxWidth()){Text("#$tag",Modifier.weight(1f));MoneyText(value,ledger.baseCurrency)} }
                    if(tags.isNotEmpty())Text("Одна операция может входить в несколько тегов.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                } }
                if(wide)Row(horizontalArrangement=Arrangement.spacedBy(16.dp)){Box(Modifier.weight(1f)){Cats()};Box(Modifier.weight(1f)){Tags()}}
                else Column(verticalArrangement=Arrangement.spacedBy(16.dp)){Cats();Tags()}
            }
            item {
                Panel {
                    SectionTitle("Динамика",if(length<=31)"По дням" else "По месяцам")
                    val max=groups.maxOfOrNull{maxOf(it.second.income,it.second.expense,0)} ?: 0
                    if(groups.isEmpty())Text("Нет данных для графика.")
                    groups.forEach { (label,t)->
                        Text(label,style=MaterialTheme.typography.labelMedium)
                        Text("+ "+Money.format(t.income,ledger.baseCurrency),style=MaterialTheme.typography.bodySmall);Progress(t.income,max)
                        Text("− "+Money.format(t.expense,ledger.baseCurrency),style=MaterialTheme.typography.bodySmall);Progress(t.expense,max,MaterialTheme.colorScheme.error)
                    }
                }
            }
            item {
                Panel {
                    SectionTitle("Прогноз на 30 дней")
                    val expectedIncome=Money.sum(forecast.filter{it.kind==Kind.INCOME}.map{it.baseMinor})
                    val expectedExpense=Money.sum(forecast.filter{it.kind==Kind.EXPENSE}.map{it.baseMinor})
                    val net=Money.sub(expectedIncome,expectedExpense)
                    Text("Плановые доходы: "+Money.format(expectedIncome,ledger.baseCurrency))
                    Text("Плановые расходы: "+Money.format(expectedExpense,ledger.baseCurrency))
                    MoneyText(net,ledger.baseCurrency,large=true)
                    Text("Оценка по будущим записям и расписаниям. Переводы между счетами не входят в доходы и расходы.",style=MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
