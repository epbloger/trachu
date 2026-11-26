package ru.epbloger.trachu.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ru.epbloger.trachu.core.*
import java.time.LocalDate
import java.time.YearMonth
import java.time.DayOfWeek
import java.time.temporal.TemporalAdjusters

@Composable fun Dashboard(ledger: Ledger, add: (Kind)->Unit, openEntry: (Entry)->Unit, accounts: ()->Unit, reports: ()->Unit, plans: ()->Unit, operations: ()->Unit, calendar: ()->Unit, monthStart: Int=1, consentContent:(@Composable ()->Unit)?=null) {
    val day=today()
    var periodType by rememberSaveable{mutableStateOf(PeriodType.MONTH)}
    var p by rememberSaveable(monthStart,stateSaver=PeriodSaver){mutableStateOf(Period.month(day,monthStart))}
    var showPeriod by rememberSaveable{mutableStateOf(false)}
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
                        Column(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(Graphite,Color(0xFF44356D)))).padding(20.dp),
                            verticalArrangement=Arrangement.spacedBy(12.dp)) {
                            Text("Общий баланс",color=Lavender,style=MaterialTheme.typography.labelLarge)
                            MoneyText(valuation.minor,ledger.baseCurrency,color=Color.White,large=true)
                            if(valuation.missingCurrencies.isNotEmpty()) Text("Не включены валюты: "+valuation.missingCurrencies.joinToString(),color=Lavender,style=MaterialTheme.typography.bodyMedium)
                            Text("Результат выбранного периода",color=Lavender,style=MaterialTheme.typography.bodyMedium)
                            MoneyText(totals.net,ledger.baseCurrency,color=Color.White)
                            BoxWithConstraints(Modifier.fillMaxWidth()) {
                                @Composable fun Expense(modifier:Modifier) {
                                    Button(onClick={add(Kind.EXPENSE)},modifier=modifier.heightIn(min=52.dp).testTag("add-expense"),colors=ButtonDefaults.buttonColors(containerColor=Lavender,contentColor=Graphite)) { Text("− Расход") }
                                }
                                @Composable fun Income(modifier:Modifier) {
                                    OutlinedButton(onClick={add(Kind.INCOME)},modifier=modifier.heightIn(min=52.dp).testTag("add-income"),colors=ButtonDefaults.outlinedButtonColors(contentColor=Color.White)) { Text("+ Доход") }
                                }
                                if(maxWidth<260.dp||LocalDensity.current.fontScale>=1.3f)Column(verticalArrangement=Arrangement.spacedBy(8.dp)){Expense(Modifier.fillMaxWidth());Income(Modifier.fillMaxWidth())}
                                else Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){Expense(Modifier.weight(1f));Income(Modifier.weight(1f))}
                            }
                        }
                    }
                }
                @Composable fun Summary() {
                    Panel {
                        SectionTitle("Движение денег",action="Отчёты",onAction=reports)
                        TextButton(onClick={showPeriod=!showPeriod}){Text("${periodType.label} · ${iso(p.from)} — ${iso(p.through)}")}
                        if(showPeriod)PeriodControls(periodType,{periodType=it},p,{p=it},monthStart)
                        BoxWithConstraints(Modifier.fillMaxWidth()) {
                            @Composable fun Total(label:String,value:Long,color:Color,modifier:Modifier) {
                                Column(modifier,verticalArrangement=Arrangement.spacedBy(4.dp)) {
                                    Text(label,color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodyMedium)
                                    MoneyText(value,ledger.baseCurrency,color=color)
                                }
                            }
                            if(maxWidth>=270.dp&&LocalDensity.current.fontScale<1.3f) {
                                Row(horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                                    Total("Доходы",totals.income,MaterialTheme.colorScheme.primary,Modifier.weight(1f))
                                    Total("Расходы",totals.expense,MaterialTheme.colorScheme.error,Modifier.weight(1f))
                                }
                            } else Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                                Total("Доходы",totals.income,MaterialTheme.colorScheme.primary,Modifier.fillMaxWidth())
                                HorizontalDivider()
                                Total("Расходы",totals.expense,MaterialTheme.colorScheme.error,Modifier.fillMaxWidth())
                            }
                        }
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
                    ledger.accounts.filterNot { it.archived }.sortedWith(compareBy<Account>{it.sortOrder}.thenBy{it.name}).take(3).forEach { a ->
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) { Text(a.name,style=MaterialTheme.typography.titleMedium); Text(a.type.label(),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant) }
                            MoneyText(Finance.balance(ledger,a.id),a.currency)
                        }
                    }
                    OutlinedButton(onClick={add(Kind.TRANSFER)},modifier=Modifier.fillMaxWidth(),enabled=ledger.accounts.count{!it.archived}>=2){Text("Перевод между счетами")}
                }
            }
            item {
                Panel {
                    SectionTitle("Последние операции",action="Все",onAction=operations)
                    if(recent.isEmpty()) Text("Здесь появятся ваши операции.",color=MaterialTheme.colorScheme.onSurfaceVariant)
                    recent.forEach { EntryRow(ledger,it){openEntry(it)} }
                }
            }
            item {
                @Composable fun Spending() { CategoryReport(ledger,p) }
                @Composable fun BudgetSummary() {
                    Panel {
                        SectionTitle("Бюджет",action="План",onAction=plans)
                        if(budget==null) { Text("Установите лимит на месяц, чтобы отслеживать остаток."); TextButton(onClick=plans){Text("Настроить бюджет")} }
                        else {
                            val result=runCatching{Finance.budget(ledger,budget,day).also{it.remaining}}.getOrNull()
                            Text(budget.name,style=MaterialTheme.typography.titleMedium)
                            if(result==null)Warning("Слишком большой накопленный лимит. Измените бюджет в плане.")
                            else {
                                MoneyText(result.remaining,ledger.baseCurrency,large=true,color=if(result.remaining<0)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                                Text("Осталось из "+Money.format(result.available,ledger.baseCurrency))
                                Progress(result.spent,result.available)
                                Text("Использовано ${result.percent}%")
                            }
                        }
                    }
                }
                if(wide) Row(horizontalArrangement=Arrangement.spacedBy(16.dp)) { Box(Modifier.weight(1f)){Spending()};Box(Modifier.weight(1f)){BudgetSummary()} }
                else Column(verticalArrangement=Arrangement.spacedBy(16.dp)){Spending();BudgetSummary()}
            }
            item {
                Panel {
                    SectionTitle("Предстоящие платежи",action="Календарь",onAction=calendar)
                    if(planned.isEmpty()) Text("Пока ничего не запланировано.",color=MaterialTheme.colorScheme.onSurfaceVariant)
                    planned.forEach { EntryRow(ledger,it){openEntry(it)} }
                }
            }
            if(consentContent!=null)item{Panel{consentContent()}}
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
            Column(Modifier.fillMaxWidth(),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Donut(positive.map{(id,v)->Color(ledger.categories.find{it.id==id}?.color ?: 0xFF6143CC) to v},"Диаграмма категорий")
                Text("Итого",style=MaterialTheme.typography.labelSmall)
                MoneyText(total,ledger.baseCurrency,large=true)
            }
            if(categories.any{it.second<0})Text("Диаграмма показывает положительные суммы. Отрицательные значения означают превышение возвратов над расходами категории.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            categories.forEach { (id,v)->
                val category=ledger.categories.find{it.id==id}
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    CategoryDot(category)
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        Text(ledger.categoryName(id),style=MaterialTheme.typography.titleMedium)
                        MoneyText(v,ledger.baseCurrency)
                        if(v>0&&positiveTotal>0) {
                            val share=(v.toDouble()/positiveTotal.toDouble()*100).coerceIn(0.0,100.0)
                            Text(String.format(java.util.Locale("ru"),"%.1f%% положительных сумм",share),style=MaterialTheme.typography.labelSmall)
                            Progress(v,positiveTotal,Color(category?.color ?: 0xFF6143CC))
                        }
                    }
                }
            }
        }
    }
}

enum class PeriodType(val label: String) { DAY("День"),WEEK("Неделя"),MONTH("Месяц"),YEAR("Год"),CUSTOM("Период") }
internal val PeriodSaver=Saver<Period,LongArray>(
    save={longArrayOf(it.from,it.through)},restore={Period(it[0],it[1])})
fun periodFor(type: PeriodType, day: Long=today(), monthStart:Int=1): Period {
    val d=LocalDate.ofEpochDay(day)
    return when(type) {
        PeriodType.DAY->Period(day,day)
        PeriodType.WEEK->Period(d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toEpochDay(),d.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY)).toEpochDay())
        PeriodType.MONTH,PeriodType.CUSTOM->Period.month(day,monthStart)
        PeriodType.YEAR->Period(d.withDayOfYear(1).toEpochDay(),d.withDayOfYear(d.lengthOfYear()).toEpochDay())
    }
}
@Composable fun PeriodControls(type: PeriodType, onType: (PeriodType)->Unit, period: Period, onPeriod: (Period)->Unit, monthStart:Int=1) {
    var from by rememberSaveable(period.from,period.through){mutableStateOf(iso(period.from))}
    var to by rememberSaveable(period.from,period.through){mutableStateOf(iso(period.through))}
    var error by rememberSaveable{mutableStateOf("")}
    Chips(PeriodType.entries,type,{it.label}) { onType(it); if(it!=PeriodType.CUSTOM)onPeriod(periodFor(it,monthStart=monthStart)) }
    if(type==PeriodType.CUSTOM) {
        DateField("С",from,{from=it})
        DateField("По",to,{to=it})
        Button(onClick={runCatching{Period(parseDay(from),parseDay(to))}.onSuccess{onPeriod(it);error=""}.onFailure{error="Проверьте даты"}}){Text("Применить")}
        if(error.isNotEmpty())Warning(error)
    } else {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
            TextButton(onClick={
                onPeriod(periodFor(type,period.from-1,monthStart))
            },modifier=Modifier.semantics{contentDescription="Предыдущий период"}){Text("‹")}
            Text("${iso(period.from)} — ${iso(period.through)}",style=MaterialTheme.typography.labelMedium,modifier=Modifier.weight(1f))
            TextButton(onClick={
                onPeriod(periodFor(type,period.through+1,monthStart))
            },modifier=Modifier.semantics{contentDescription="Следующий период"}){Text("›")}
        }
    }
}
@Composable fun Reports(ledger: Ledger, monthStart:Int=1, exportPdf: (Period,String)->Unit) {
    var type by rememberSaveable { mutableStateOf(PeriodType.MONTH) }
    var period by rememberSaveable(monthStart,stateSaver=PeriodSaver) { mutableStateOf(Period.month(startDay=monthStart)) }
    var income by rememberSaveable { mutableStateOf(false) }
    var selectedAccount by rememberSaveable{mutableStateOf("")}
    val reportLedger=remember(ledger,selectedAccount){
        if(selectedAccount.isEmpty())ledger else ledger.copy(accounts=ledger.accounts.map{it.copy(includeInStats=it.id==selectedAccount && it.includeInStats)})
    }
    val totals=remember(reportLedger,period){Finance.totals(reportLedger,period)}
    val length=period.through-period.from+1
    val previous=remember(reportLedger,period,type) {
        val prev=when(type) {
            PeriodType.MONTH->Period.month(period.from-1,monthStart)
            PeriodType.YEAR->periodFor(type,LocalDate.ofEpochDay(period.from).minusYears(1).toEpochDay())
            else->Period(period.from-length,period.from-1)
        }
        Finance.totals(reportLedger,prev)
    }
    val groups=remember(reportLedger,period){
        val included=reportLedger.accounts.filter{it.includeInStats}.map{it.id}.toSet()
        reportLedger.entries.filter { it.status==EntryStatus.POSTED && it.day in period && it.accountId in included }
            .groupBy { if(length<=31) iso(it.day) else YearMonth.from(LocalDate.ofEpochDay(it.day)).toString() }
            .toSortedMap().map { (label,entries) ->
                val p=Period(entries.minOf{it.day},entries.maxOf{it.day})
                label to Finance.totals(reportLedger,p)
            }.takeLast(60)
    }
    val forecastStart=today()
    val forecastEnd=forecastStart+29
    val forecast=remember(ledger,forecastStart) { runCatching {
        Finance.forecastEntries(ledger,forecastEnd)
    } }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide=maxWidth>=840.dp && LocalDensity.current.fontScale<1.5f
        LazyColumn(contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            item { SectionTitle("Аналитика",action="PDF"){exportPdf(period,selectedAccount)} }
            item { Panel {
                PeriodControls(type,{type=it},period,{period=it},monthStart)
                Choice("Счёт в отчёте",selectedAccount,listOf("")+ledger.accounts.filter{it.includeInStats}.map{it.id},{if(it.isEmpty())"Все счета отчётов" else ledger.accountName(it)}){selectedAccount=it}
                if(selectedAccount.isNotEmpty())Text("Фильтр применяется к отчётам и PDF. Прогноз показывает все счета.",style=MaterialTheme.typography.bodySmall)
            } }
            item {
                Panel {
                    Text("Финансовый результат",style=MaterialTheme.typography.titleMedium)
                    MoneyText(totals.net,ledger.baseCurrency,large=true,color=if(totals.net>=0)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                    Text("Доходы: "+Money.format(totals.income,ledger.baseCurrency))
                    Text("Расходы: "+Money.format(totals.expense,ledger.baseCurrency))
                    Text("Средние расходы в день: "+Money.format(totals.expense/length,ledger.baseCurrency))
                    val expenseEntries=reportLedger.entries.filter{it.status==EntryStatus.POSTED && it.day in period && it.kind in setOf(Kind.EXPENSE,Kind.REFUND) && reportLedger.accounts.any{a->a.id==it.accountId&&a.includeInStats}}
                    val essential=Money.sum(expenseEntries.filter{it.essential}.map{if(it.kind==Kind.REFUND)-it.baseMinor else it.baseMinor})
                    val discretionary=Money.sum(expenseEntries.filterNot{it.essential}.map{if(it.kind==Kind.REFUND)-it.baseMinor else it.baseMinor})
                    Text("Обязательные траты: "+Money.format(essential,ledger.baseCurrency))
                    Text("Прочие траты: "+Money.format(discretionary,ledger.baseCurrency))
                    val interest=Money.sub(totals.expense,Money.add(essential,discretionary))
                    if(interest!=0L)Text("Уплаченные проценты: "+Money.format(interest,ledger.baseCurrency))
                    HorizontalDivider()
                    Text("Предыдущий сопоставимый период",style=MaterialTheme.typography.titleMedium)
                    Text("Результат: "+Money.format(previous.net,ledger.baseCurrency))
                    Text("Изменение: "+Money.format(Money.sub(totals.net,previous.net),ledger.baseCurrency))
                }
            }
            item {
                @Composable fun Cats(){ Column(verticalArrangement=Arrangement.spacedBy(8.dp)) { Chips(listOf(false,true),income,{if(it)"Доходы" else "Расходы"}){income=it};CategoryReport(reportLedger,period,income) } }
                @Composable fun Tags(){ Panel {
                    SectionTitle("Расходы по тегам")
                    val tags=Finance.byTag(reportLedger,period).toList().sortedByDescending{it.second}
                    if(tags.isEmpty())Text("Добавьте теги к операциям.")
                    else tags.forEach { (tag,value)->Row(horizontalArrangement=Arrangement.SpaceBetween,modifier=Modifier.fillMaxWidth()){Text("#$tag",Modifier.weight(1f));MoneyText(value,ledger.baseCurrency)} }
                    if(tags.isNotEmpty())Text("Одна операция может входить в несколько тегов.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                } }
                if(wide)Row(horizontalArrangement=Arrangement.spacedBy(16.dp)){Box(Modifier.weight(1f)){Cats()};Box(Modifier.weight(1f)){Tags()}}
                else Column(verticalArrangement=Arrangement.spacedBy(16.dp)){Cats();Tags()}
            }
            item {
                Panel {
                    SectionTitle("Динамика",if(length<=31)"По дням · дни с операциями" else "По месяцам · до 60 последних месяцев с операциями")
                    val max=groups.maxOfOrNull{maxOf(it.second.income,it.second.expense,0)} ?: 0
                    if(groups.isEmpty())Text("Нет данных для графика.")
                    groups.forEach { (label,t)->
                        Text(label,style=MaterialTheme.typography.labelMedium)
                        Text("Доходы: "+Money.format(t.income,ledger.baseCurrency),style=MaterialTheme.typography.bodySmall);Progress(t.income,max)
                        Text("Расходы за вычетом возвратов: "+Money.format(t.expense,ledger.baseCurrency),style=MaterialTheme.typography.bodySmall)
                        if(t.expense>=0)Progress(t.expense,max,MaterialTheme.colorScheme.error)
                        else Text("Возвраты превышают расходы",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary)
                    }
                }
            }
            item {
                Panel {
                    SectionTitle("Прогноз на 30 дней","${dayLabel(forecastStart)} — ${dayLabel(forecastEnd)}")
                    val upcoming=forecast.getOrNull()
                    if(upcoming==null)Warning("Не удалось рассчитать расписания. Проверьте регулярные операции и валютные курсы.")
                    else {
                        val firstDay=minOf(forecastStart,upcoming.minOfOrNull{it.day} ?: forecastStart)
                        val expected=Finance.totals(ledger.copy(entries=upcoming.map{it.copy(status=EntryStatus.POSTED)}),Period(firstDay,forecastEnd))
                        val overdue=upcoming.count{it.day<forecastStart}
                        if(overdue>0)Text("Включены просроченные операции: $overdue",color=MaterialTheme.colorScheme.tertiary)
                        Text("Плановые доходы: "+Money.format(expected.income,ledger.baseCurrency))
                        Text("Плановые расходы: "+Money.format(expected.expense,ledger.baseCurrency))
                        Text("Ожидаемый финансовый результат",style=MaterialTheme.typography.labelLarge)
                        MoneyText(expected.net,ledger.baseCurrency,large=true,color=if(expected.net<0)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                        val projected=runCatching{Finance.projectedValuation(ledger,upcoming,forecastEnd,forecastStart)}.getOrNull()
                        if(projected!=null){
                            HorizontalDivider()
                            Text("Ожидаемый общий баланс",style=MaterialTheme.typography.labelLarge)
                            MoneyText(projected.minor,ledger.baseCurrency,large=true)
                            if(projected.missingCurrencies.isNotEmpty())Warning("Не включены валюты без курса: "+projected.missingCurrencies.joinToString())
                            Text("По текущим известным курсам, с учётом переводов и настроек счетов.",style=MaterialTheme.typography.bodySmall)
                        }else Warning("Не удалось рассчитать ожидаемый баланс.")
                        if(upcoming.isEmpty())Text("Нет запланированных операций на этот период.")
                        else Text("Запланированных операций: ${upcoming.size}")
                        Text("Это ожидаемая разница доходов и расходов по счетам, включённым в отчёты. Переводы не учитываются. Фактический баланс не изменяется.",style=MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
