package ru.epbloger.trachu.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.epbloger.trachu.AppViewModel
import ru.epbloger.trachu.core.*
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class PlannerTab(val label:String){BUDGET("Бюджеты"),GOAL("Цели"),DEBT("Долги"),RECURRING("Регулярные"),CALENDAR("Календарь")}
private enum class PlannerGroup(val label:String,val defaultTab:PlannerTab){
    BUDGET("Бюджеты",PlannerTab.BUDGET),
    PAYMENTS("Платежи",PlannerTab.CALENDAR),
    SAVINGS("Цели и долги",PlannerTab.GOAL)
}
private fun PlannerTab.group()=when(this){
    PlannerTab.BUDGET->PlannerGroup.BUDGET
    PlannerTab.CALENDAR,PlannerTab.RECURRING->PlannerGroup.PAYMENTS
    PlannerTab.GOAL,PlannerTab.DEBT->PlannerGroup.SAVINGS
}
@OptIn(ExperimentalLayoutApi::class)
@Composable private fun <T> PlannerChoices(options:List<T>,selected:T,label:(T)->String,tag:(T)->String,choose:(T)->Unit){
    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(0.dp)){
        options.forEach{option->
            FilterChip(selected=option==selected,onClick={choose(option)},label={Text(label(option))},modifier=Modifier.testTag(tag(option)))
        }
    }
}
@Composable fun Planner(ledger:Ledger,initial:PlannerTab=PlannerTab.BUDGET,editBudget:(Budget?)->Unit,editGoal:(Goal?)->Unit,editDebt:(Debt?)->Unit,payDebt:(Debt)->Unit,editSchedule:(Schedule?)->Unit,openEntry:(Entry)->Unit,addPlanned:(Long)->Unit,adjustGoal:(Goal,Boolean)->Unit) {
    var tab by rememberSaveable(initial){mutableStateOf(initial)}
    var monthKey by rememberSaveable{mutableStateOf(YearMonth.now().toString())}
    val month=YearMonth.parse(monthKey)
    var selectedDay by rememberSaveable{mutableStateOf(today())}
    var historyId by rememberSaveable{mutableStateOf<String?>(null)}
    val calendar=remember(ledger,month) {
        runCatching { Finance.generate(ledger,month.atEndOfMonth().toEpochDay(),true).entries }.getOrElse{ledger.entries}
            .filter{it.day in month.atDay(1).toEpochDay()..month.atEndOfMonth().toEpochDay()}
    }
    LazyColumn(modifier=Modifier.testTag("planner-list"),contentPadding=PaddingValues(PageInset),verticalArrangement=Arrangement.spacedBy(SectionGap)) {
        item{
            Text("Планирование",style=MaterialTheme.typography.headlineLarge)
            PlannerChoices(PlannerGroup.entries,tab.group(),{it.label},{"planner-group-${it.name.lowercase()}"}){group->
                if(group!=tab.group())tab=group.defaultTab
            }
            val subviews=when(tab.group()){
                PlannerGroup.BUDGET->emptyList()
                PlannerGroup.PAYMENTS->listOf(PlannerTab.CALENDAR,PlannerTab.RECURRING)
                PlannerGroup.SAVINGS->listOf(PlannerTab.GOAL,PlannerTab.DEBT)
            }
            if(subviews.isNotEmpty())PlannerChoices(subviews,tab,{it.label},{"planner-tab-${it.name.lowercase()}"}){tab=it}
        }
        when(tab){
            PlannerTab.BUDGET->{
                item{SectionTitle("Лимиты расходов",action="Добавить"){editBudget(null)}}
                if(ledger.budgets.isEmpty())item{EmptyState("Поставьте лимит","Общий месячный бюджет или лимит отдельной категории.","Создать бюджет"){editBudget(null)}}
                items(ledger.budgets,key={it.id}){b->
                    val r=runCatching{Finance.budget(ledger,b,today()).also{it.remaining}}.getOrNull()
                    Panel {
                        SectionTitle(b.name,b.categoryId?.let{ledger.categoryName(it)} ?: "Все расходы",action="Изменить"){editBudget(b)}
                        if(r==null)Warning("Слишком большой накопленный лимит. Измените сумму или перенос.")
                        else {
                        MoneyText(r.remaining,ledger.baseCurrency,large=true,color=if(r.remaining<0)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                        Text("Остаток · ${Money.format(r.available,ledger.baseCurrency)} доступно")
                        Progress(r.spent,r.available,if(r.level>=2)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                        Text("Факт: ${Money.format(r.spent,ledger.baseCurrency)} · ${r.percent}%")
                        Text(if(b.rollover)"Остаток переносится" else "Без переноса остатка",style=MaterialTheme.typography.bodySmall)
                        if(r.level>0)Warning(if(r.level==3)"Бюджет превышен" else if(r.level==2)"Лимит достигнут" else "Использовано 80% или больше")
                        }
                    }
                }
            }
            PlannerTab.GOAL->{
                item{SectionTitle("Накопительные цели",action="Добавить"){editGoal(null)}}
                if(ledger.goals.isEmpty())item{EmptyState("На что копим?","Укажите сумму цели и текущие накопления.","Создать цель"){editGoal(null)}}
                items(ledger.goals,key={it.id}){g->Panel {
                    SectionTitle(g.name,g.dueDay?.let{dayLabel(it)},action="Изменить"){editGoal(g)}
                    val saved=Finance.goalSaved(g)
                    MoneyText(saved,g.currency,large=true,color=MaterialTheme.colorScheme.primary)
                    Progress(saved,g.targetMinor)
                    Text("Цель: "+Money.format(g.targetMinor,g.currency))
                    Text(if(saved>=g.targetMinor)"Цель достигнута" else "Осталось: "+Money.format(g.targetMinor-saved,g.currency))
                    BoxWithConstraints(Modifier.fillMaxWidth()){
                        @Composable fun Fund(modifier:Modifier){Button(onClick={adjustGoal(g,false)},modifier=modifier.testTag("goal-fund:${g.id}"),colors=moneyActionColors(true)){Text("Пополнить")}}
                        @Composable fun Withdraw(modifier:Modifier){OutlinedButton(onClick={adjustGoal(g,true)},enabled=saved>0,modifier=modifier.testTag("goal-withdraw:${g.id}"),colors=ButtonDefaults.outlinedButtonColors(contentColor=MaterialTheme.colorScheme.error)){Text("Снять")}}
                        if(maxWidth<260.dp||LocalDensity.current.fontScale>=1.5f){
                            Column(verticalArrangement=Arrangement.spacedBy(4.dp)){Fund(Modifier.fillMaxWidth());Withdraw(Modifier.fillMaxWidth())}
                        }else Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){Fund(Modifier.weight(1f));Withdraw(Modifier.weight(1f))}
                    }
                    TextButton(onClick={historyId=g.id},modifier=Modifier.fillMaxWidth().testTag("goal-history:${g.id}")){Text("История пополнений и снятий")}
                }}
            }
            PlannerTab.DEBT->{
                item{SectionTitle("Долги и кредиты",action="Добавить"){editDebt(null)}}
                if(ledger.debts.isEmpty())item{EmptyState("Долгов нет","Учитывайте задолженности, проценты и погашения.","Добавить долг"){editDebt(null)}}
                items(ledger.debts,key={it.id}){d->
                    val a=ledger.accounts.first{it.id==d.accountId}
                    val left=Finance.debtRemaining(ledger,d)
                    val unpaidInterest=debtUnpaidInterestEstimate(ledger,d)
                    val outstanding=Finance.debtHasOutstandingAmount(ledger,d)
                    val canPay=outstanding&&ledger.accounts.any{!it.archived&&it.currency==a.currency}
                    Panel {
                        SectionTitle(d.person,if(d.owedByMe)"Я должен" else "Мне должны",action="Изменить"){editDebt(d)}
                        MoneyText(left,a.currency,large=true,color=if(d.owedByMe)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                        Text("Исходная сумма: "+Money.format(d.principalMinor,a.currency))
                        Progress(d.principalMinor-left,d.principalMinor)
                        if(plannerHasInterestRate(d)) {
                            Text("Годовая ставка: ${d.annualPercent}%")
                            runCatching{Finance.estimatedInterest(ledger,d,30)}.getOrNull()?.let{Text("Оценка процентов за 30 дней: "+Money.format(it,a.currency),style=MaterialTheme.typography.bodySmall)}
                            val accrued=runCatching{Finance.accruedInterest(ledger,d)}.getOrNull()
                            val paid=runCatching{Finance.paidInterest(ledger,d)}.getOrNull()
                            if(accrued!=null)Text("Начислено по оценке: "+Money.format(accrued,a.currency),style=MaterialTheme.typography.bodySmall)
                            if(paid!=null)Text("Фактически учтённые проценты: "+Money.format(paid,a.currency),style=MaterialTheme.typography.bodySmall)
                            if(unpaidInterest!=null&&unpaidInterest>0)Text("Осталось процентов по оценке: "+Money.format(unpaidInterest,a.currency),style=MaterialTheme.typography.bodySmall)
                            if(d.openedDay!=null&&unpaidInterest==null)Warning("Не удалось рассчитать проценты. Фактический платёж можно записать вручную.")
                            if(d.openedDay==null)Text("Дата начала не указана — фактические проценты можно записать вручную.",style=MaterialTheme.typography.bodySmall)
                            Text("Простая оценка Actual/365, без банковского графика и капитализации.",style=MaterialTheme.typography.bodySmall)
                        }
                        d.dueDay?.let{Text("Срок: "+dayLabel(it),color=if(it<today()&&outstanding)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)}
                        if(outstanding&&!canPay)Warning("Для погашения нужен активный счёт в валюте долга (${a.currency}).")
                        if(d.note.isNotBlank())Text(d.note)
                        Button(onClick={payDebt(d)},enabled=canPay,modifier=Modifier.fillMaxWidth().testTag("debt-pay:${d.id}"),colors=moneyActionColors(!d.owedByMe)){
                            Text(when {
                                left>0 -> if(d.owedByMe)"Погасить долг" else "Получить возврат"
                                else -> "Учесть проценты"
                            })
                        }
                    }
                }
            }
            PlannerTab.RECURRING->{
                item{SectionTitle("Регулярные операции",action="Добавить"){editSchedule(null)}}
                if(ledger.schedules.isEmpty())item{EmptyState("Автоматизируйте рутину","Зарплата, аренда, подписки и другие повторяющиеся операции.","Создать расписание"){editSchedule(null)}}
                items(ledger.schedules,key={it.id}){s->
                    val a=ledger.accounts.first{it.id==s.template.accountId}
                    Panel {
                        val state=when {
                            s.enabled -> "Следующая: "+dayLabel(s.nextDay)
                            s.endDay!=null && s.nextDay>s.endDay -> "Завершено · "+dayLabel(s.endDay)
                            else -> "Приостановлено"
                        }
                        SectionTitle(ledger.entryName(s.template),state,action="Изменить"){editSchedule(s)}
                        MoneyText(s.template.minor,a.currency,large=true)
                        Text("${s.frequency.label()} · интервал ${s.interval}")
                        Text(if(s.autoPost)"Автоматическое проведение" else "Подтверждение вручную")
                    }
                }
            }
            PlannerTab.CALENDAR->{
                item{
                    Panel {
                        SectionTitle("Платежи по датам",action="Добавить"){addPlanned(selectedDay)}
                        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween){
                            TextButton(modifier=Modifier.width(48.dp).testTag("calendar-prev"),onClick={val next=month.minusMonths(1);monthKey=next.toString();selectedDay=next.atDay(1).toEpochDay()}){Text("‹")}
                            Text(month.format(DateTimeFormatter.ofPattern("LLLL yyyy",Locale("ru"))),Modifier.weight(1f).testTag("calendar-month"),style=MaterialTheme.typography.titleMedium,textAlign=androidx.compose.ui.text.style.TextAlign.Center)
                            TextButton(modifier=Modifier.width(48.dp).testTag("calendar-next"),onClick={val next=month.plusMonths(1);monthKey=next.toString();selectedDay=next.atDay(1).toEpochDay()}){Text("›")}
                        }
                        BoxWithConstraints(Modifier.fillMaxWidth()){
                            val compact=maxWidth<290.dp || LocalDensity.current.fontScale>1.35f
                            val days=(1..month.lengthOfMonth()).map{month.atDay(it).toEpochDay()}
                            if(compact)Choice("День",selectedDay,days,{dayLabel(it)}){selectedDay=it}
                            else {
                                val blank=month.atDay(1).dayOfWeek.value-1
                                val cells=List<Long?>(blank){null}+days
                                Column(verticalArrangement=Arrangement.spacedBy(4.dp)){
                                    Row{listOf("Пн","Вт","Ср","Чт","Пт","Сб","Вс").forEach{Text(it,Modifier.weight(1f),style=MaterialTheme.typography.labelSmall,textAlign=androidx.compose.ui.text.style.TextAlign.Center)}}
                                    cells.chunked(7).forEach{week->
                                        Row(horizontalArrangement=Arrangement.spacedBy(2.dp)){
                                            (week+List(7-week.size){null}).forEach{d->
                                                val count=calendar.count{it.day==d}+ledger.debts.count{it.dueDay==d && Finance.debtHasOutstandingAmount(ledger,it)}+ledger.goals.count{it.dueDay==d}
                                                if(d==null)Spacer(Modifier.weight(1f))
                                                else Column(Modifier.weight(1f).background(if(d==selectedDay)MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,MaterialTheme.shapes.small).clickable{selectedDay=d}.padding(vertical=8.dp),horizontalAlignment=Alignment.CenterHorizontally){
                                                    Text(LocalDate.ofEpochDay(d).dayOfMonth.toString(),style=MaterialTheme.typography.bodyMedium)
                                                    Text(if(count>0)"• $count" else " ",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                item{
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                        TextButton(modifier=Modifier.width(48.dp).testTag("calendar-day-prev"),onClick={
                            val next=LocalDate.ofEpochDay(selectedDay).minusDays(1)
                            selectedDay=next.toEpochDay()
                            monthKey=YearMonth.from(next).toString()
                        }){Text("‹")}
                        Text(dayLabel(selectedDay),Modifier.weight(1f).testTag("calendar-selected-day"),style=MaterialTheme.typography.titleLarge,textAlign=androidx.compose.ui.text.style.TextAlign.Center)
                        TextButton(modifier=Modifier.width(48.dp).testTag("calendar-day-next"),onClick={
                            val next=LocalDate.ofEpochDay(selectedDay).plusDays(1)
                            selectedDay=next.toEpochDay()
                            monthKey=YearMonth.from(next).toString()
                        }){Text("›")}
                    }
                }
                val selected=calendar.filter{it.day==selectedDay}.sortedWith(EntryOrder.newestFirst)
                val obligations=ledger.debts.filter{it.dueDay==selectedDay&&Finance.debtHasOutstandingAmount(ledger,it)}
                val goals=ledger.goals.filter{it.dueDay==selectedDay}
                if(selected.isEmpty() && obligations.isEmpty() && goals.isEmpty())item{Text("Нет событий в этот день.")}
                items(selected,key={it.id}){e->Panel {
                    val real=ledger.entries.find{it.id==e.id}
                    if(real!=null)EntryRow(ledger,real){openEntry(real)}
                    else {
                        Text(ledger.entryName(e),style=MaterialTheme.typography.titleMedium)
                        MoneyText(e.minor,ledger.accounts.first{it.id==e.accountId}.currency)
                        Text("Прогноз из регулярного расписания",color=MaterialTheme.colorScheme.tertiary)
                    }
                }}
                items(obligations,key={"debt-"+it.id}){d->Panel{SectionTitle(d.person,"Срок погашения",action="Открыть"){editDebt(d)}}}
                items(goals,key={"goal-"+it.id}){g->Panel{SectionTitle(g.name,"Срок цели",action="Открыть"){editGoal(g)}}}
            }
        }
    }
    ledger.goals.find{it.id==historyId}?.let{g->
        AlertDialog(onDismissRequest={historyId=null},title={Text("История · "+g.name,maxLines=2,overflow=TextOverflow.Ellipsis)},
            text={
                LazyColumn(Modifier.fillMaxWidth().heightIn(max=360.dp).testTag("goal-history-list"),verticalArrangement=Arrangement.spacedBy(8.dp)){
                    item{Text("Начальные накопления: "+Money.format(g.savedMinor,g.currency))}
                    if(g.adjustments.isEmpty())item{Text("Пока нет пополнений и снятий.")}
                    items(g.adjustments.asReversed(),key={it.id}){a->
                        Column(Modifier.testTag("goal-history-entry:${a.id}"),verticalArrangement=Arrangement.spacedBy(2.dp)){
                            Text(if(a.minor>0)"Пополнение" else "Снятие",style=MaterialTheme.typography.labelMedium)
                            MoneyText(a.minor,g.currency)
                            Text(dayLabel(a.day),style=MaterialTheme.typography.bodySmall)
                            if(a.note.isNotBlank())Text(a.note)
                        }
                        HorizontalDivider()
                    }
                }
            },confirmButton={TextButton(onClick={historyId=null}){Text("Закрыть")}})
    }
}

@Composable fun BudgetEditor(ledger:Ledger,current:Budget?,vm:AppViewModel,busy:Boolean,close:()->Unit, draft:EntryDraft){
    val original = draft.state("originalBudget"){current}.value
    var name by draft.state("name"){current?.name ?: "Бюджет месяца"}
    var category by draft.state("category"){current?.categoryId.orEmpty()}
    var amount by draft.state("amount"){current?.let{Money.input(it.monthlyMinor,ledger.baseCurrency)}.orEmpty()}
    var start by draft.state("start"){iso(current?.startMonth ?: Period.month().from)}
    var carry by draft.state("carry"){current?.rollover ?: false}
    var error by draft.state("error"){""}
    var delete by draft.state("delete"){false}
    Form(if(original==null)"Новый бюджет" else "Редактировать бюджет",close,busy,validationError=error,onSave={
        runCatching{
            require(name.isNotBlank()){"Введите название бюджета"}
            val limit=plannerAmount(amount,ledger.baseCurrency,"Введите корректный месячный лимит")
            require(limit>0){"Месячный лимит должен быть больше нуля"}
            val b=Budget(original?.id ?: newId(),name.trim(),category.ifBlank{null},limit,plannerDate(start),carry)
            runCatching{Finance.budget(ledger,b,today()).remaining}.getOrElse{throw IllegalArgumentException("Слишком большой лимит или период бюджета")}
            vm.change({l->
                if(original!=null)require(l.budgets.find{it.id==original?.id}==original){"Бюджет изменился. Откройте его снова."}
                require(b.categoryId==null||l.categories.any{it.id==b.categoryId&&!it.income}){"Выберите категорию расходов"}
                l.copy(budgets=l.budgets.filterNot{it.id==b.id}+b)
            },close)
        }.onFailure{error=it.message ?: "Проверьте данные"}
    }){
        Field("Название",name,{name=it})
        Choice("Категория",category,listOf("")+ledger.categories.filter{!it.income&&!it.archived || it.id==category}.map{it.id},{if(it.isEmpty())"Общий бюджет" else ledger.categoryName(it)}){category=it}
        Field("Месячный лимит (${ledger.baseCurrency})",amount,{amount=it},true)
        DateField("Начало",start,{start=it},supporting="ГГГГ-ММ-ДД (учитывается месяц)")
        Toggle("Переносить остаток",carry,{carry=it})
        Text("Предупреждения при 80%, 100% и превышении лимита.")
        if(original!=null)TextButton(onClick={delete=true}){Text("Удалить бюджет",color=MaterialTheme.colorScheme.error)}
    }
    if(delete && original!=null)Confirm("Удалить бюджет?","Операции не будут удалены.",{delete=false},{delete=false;vm.change({l->
        require(l.budgets.find{it.id==original?.id}==original){"Бюджет изменился. Откройте его снова."}
        l.copy(budgets=l.budgets.filterNot{it.id==original?.id})
    },close)},busy)
}

@Composable fun GoalEditor(ledger:Ledger,current:Goal?,vm:AppViewModel,busy:Boolean,close:()->Unit, draft:EntryDraft){
    val original = draft.state("originalGoal"){current}.value
    var name by draft.state("name"){current?.name.orEmpty()}
    var currency by draft.state("currency"){current?.currency ?: ledger.baseCurrency}
    var target by draft.state("target"){current?.let{Money.input(it.targetMinor,it.currency)}.orEmpty()}
    var saved by draft.state("saved"){current?.let{Money.input(it.savedMinor,it.currency)} ?: "0"}
    var due by draft.state("due"){current?.dueDay?.let(::iso).orEmpty()}
    var error by draft.state("error"){""}
    var delete by draft.state("delete"){false}
    Form(if(original==null)"Новая цель" else "Редактировать цель",close,busy,validationError=error,onSave={
        runCatching{
            require(name.isNotBlank()){"Введите название цели"}
            val targetMinor=plannerAmount(target,currency,"Введите корректную целевую сумму")
            val savedMinor=plannerAmount(saved,currency,"Введите корректную сумму начальных накоплений")
            require(targetMinor>0){"Целевая сумма должна быть больше нуля"}
            require(savedMinor>=0){"Начальные накопления не могут быть отрицательными"}
            val dueDay=due.trim().takeIf{it.isNotEmpty()}?.let{plannerDate(it)}
            val g=Goal(original?.id ?: newId(),name.trim(),currency,targetMinor,savedMinor,dueDay,original?.adjustments.orEmpty())
            vm.change({l->
                if(original!=null){
                    require(l.goals.find{it.id==original.id}==original){"Цель изменилась. Откройте её снова."}
                    require(g.currency==original.currency||original.savedMinor==0L&&original.adjustments.isEmpty()){
                        "Нельзя менять валюту цели с накоплениями или их историей"
                    }
                }
                var balance=g.savedMinor
                g.adjustments.forEach{
                    balance=runCatching{Money.add(balance,it.minor)}.getOrElse{throw IllegalArgumentException("Слишком большая сумма накоплений")}
                    require(balance>=0){"Начальные накопления меньше уже снятой суммы"}
                }
                l.copy(goals=l.goals.filterNot{it.id==g.id}+g)
            },close)
        }.onFailure{error=it.message ?: "Проверьте данные"}
    }){
        Field("Название",name,{name=it})
        if(original==null||original.savedMinor==0L&&original.adjustments.isEmpty())CurrencyPicker("Валюта",currency){currency=it;error=""}
        else Text("Валюта: $currency. Её нельзя менять, пока есть накопления или их история.")
        Field("Целевая сумма",target,{target=it},true)
        Field("Начальные накопления",saved,{saved=it},true,supporting=if(original!=null)"Сумма на момент создания цели" else null)
        if(original!=null) {
            Text("Текущий прогресс: "+Money.format(Finance.goalSaved(original),original.currency),style=MaterialTheme.typography.titleMedium)
            if(original.adjustments.isNotEmpty())Text("Последние изменения",style=MaterialTheme.typography.titleSmall)
            original.adjustments.takeLast(10).asReversed().forEach { a ->
                Text(dayLabel(a.day)+" · "+Money.format(a.minor,original.currency)+" · "+a.note,style=MaterialTheme.typography.bodySmall)
            }
        }
        DateField("Срок (необязательно)",due,{due=it})
        Text("Накопления в цели не меняют баланс счетов.",style=MaterialTheme.typography.bodySmall)
        if(original!=null)TextButton(onClick={delete=true}){Text("Удалить цель",color=MaterialTheme.colorScheme.error)}
    }
    if(delete&&original!=null)Confirm("Удалить цель?","Счета и операции не изменятся.",{delete=false},{delete=false;vm.change({l->
        require(l.goals.find{it.id==original?.id}==original){"Цель изменилась. Откройте её снова."}
        l.copy(goals=l.goals.filterNot{it.id==original?.id})
    },close)},busy)
}

private fun plannerAmount(value:String,currency:String,message:String):Long =
    runCatching{Money.parse(value,currency)}.getOrElse{throw IllegalArgumentException(message)}
private fun plannerDate(value:String):Long =
    runCatching{parseDay(value)}.getOrElse{throw IllegalArgumentException("Введите дату в формате ГГГГ-ММ-ДД")}
internal fun plannerRateOrNull(value:String,label:String):String? {
    val clean=value.trim().replace(',','.')
    if(clean.isEmpty())return null
    val rate=runCatching{BigDecimal(clean)}.getOrElse{throw IllegalArgumentException(label+": введите корректный курс")}
    require(rate.signum()>0){label+": курс должен быть больше нуля"}
    require(rate.scale() in -12..24&&rate.precision()<=40&&rate<=BigDecimal("1000000000000")){
        label+": курс слишком большой или содержит слишком много знаков"
    }
    return rate.toPlainString()
}
private fun plannerHasInterestRate(debt:Debt):Boolean =
    runCatching{BigDecimal(debt.annualPercent).signum()>0}.getOrDefault(false)
private fun debtUnpaidInterestEstimate(ledger:Ledger,debt:Debt):Long? =
    runCatching{Finance.unpaidInterestEstimate(ledger,debt)}.getOrNull()

internal data class DebtPaymentPreview(val total:Long?,val remaining:Long?)
internal fun debtPaymentPreview(principal:String,interest:String,showInterest:Boolean,currency:String,available:Long):DebtPaymentPreview {
    val p=runCatching{Money.parse(principal,currency)}.getOrNull()
    val remaining=p?.takeIf{it in 0..available}?.let{Money.sub(available,it)}
    val i=if(showInterest&&interest.isNotBlank())runCatching{Money.parse(interest,currency)}.getOrNull() else 0L
    val total=if(p!=null&&p>=0&&i!=null&&i>=0)runCatching{Money.add(p,i)}.getOrNull() else null
    return DebtPaymentPreview(total,remaining)
}

@Composable fun GoalFundingEditor(goal:Goal,vm:AppViewModel,busy:Boolean,close:()->Unit,draft:EntryDraft,withdraw:Boolean){
    val currency by draft.state("fundCurrency"){goal.currency}
    var amount by draft.state("fundAmount"){""}
    var note by draft.state("fundNote"){""}
    var error by draft.state("fundError"){""}
    val saved=Finance.goalSaved(goal)
    val action=if(withdraw)"Снять" else "Пополнить"
    Form(if(withdraw)"Снять накопления" else "Пополнить цель",close,busy,saveLabel=action,onSave={
        runCatching{
            val minor=plannerAmount(amount,currency,"Введите корректную сумму")
            require(minor>0){"Сумма должна быть больше нуля"}
            require(!withdraw||minor<=saved){"Нельзя снять больше накопленной суммы"}
            val cleanNote=note.trim()
            require(cleanNote.length<=200){"Комментарий не должен быть длиннее 200 символов"}
            val adjustment=GoalAdjustment(minor=if(withdraw)-minor else minor,note=cleanNote)
            vm.change({l->
                val latest=requireNotNull(l.goals.find{it.id==goal.id}){"Цель удалена"}
                require(latest.currency==currency){"Валюта цели изменилась. Откройте форму снова."}
                require(latest.adjustments.size<500){"Достигнут лимит записей этой цели"}
                val balance=Finance.goalSaved(latest)
                require(!withdraw||minor<=balance){"Нельзя снять больше накопленной суммы"}
                runCatching{Money.add(balance,adjustment.minor)}.getOrElse{throw IllegalArgumentException("Слишком большая сумма накоплений")}
                l.copy(goals=l.goals.map{if(it.id==goal.id)latest.copy(adjustments=latest.adjustments+adjustment) else it})
            },close)
        }.onFailure{error=it.message ?: "Проверьте сумму"}
    },compact=true,validationError=error){
        Text(goal.name,style=MaterialTheme.typography.titleMedium)
        Text("Накоплено: "+Money.format(saved,goal.currency))
        Field("Сумма ($currency)",amount,{amount=it;error=""},true)
        Field("Комментарий (необязательно)",note,{note=it;error=""})
        Text("Баланс счетов не изменится.",style=MaterialTheme.typography.bodySmall)
    }
}

@Composable fun DebtEditor(ledger:Ledger,current:Debt?,vm:AppViewModel,busy:Boolean,close:()->Unit, draft:EntryDraft){
    val original = draft.state("originalDebt"){current}.value
    var person by draft.state("person"){current?.person.orEmpty()}
    var owed by draft.state("owed"){current?.owedByMe ?: true}
    var accountId by draft.state("accountId"){current?.accountId ?: ledger.accounts.firstOrNull{!it.archived}?.id.orEmpty()}
    val account=ledger.accounts.find{it.id==accountId}
    var amount by draft.state("amount"){current?.let{Money.input(it.principalMinor,ledger.accounts.first{a->a.id==it.accountId}.currency)}.orEmpty()}
    var interest by draft.state("interest"){current?.annualPercent ?: "0"}
    var due by draft.state("due"){current?.dueDay?.let(::iso).orEmpty()}
    var note by draft.state("note"){current?.note.orEmpty()}
    var error by draft.state("error"){""}
    var delete by draft.state("delete"){false}
    val paid=original!=null && ledger.entries.any{it.debtId==original?.id && it.kind in setOf(Kind.DEBT_IN,Kind.DEBT_OUT)}
    val hasOpeningEntry=original!=null && ledger.entries.any{it.debtId==original?.id && it.kind in setOf(Kind.LOAN_IN,Kind.LOAN_OUT)}
    var recordOpening by draft.state("recordOpening"){false}
    var opened by draft.state("opened"){iso(today())}
    var openingRate by draft.state("openingRate"){""}
    Form(if(original==null)"Новый долг" else "Редактировать долг",close,busy,validationError=error,onSave={
        runCatching{
            require(person.isNotBlank()){"Укажите, кому вы должны или кто должен вам"}
            require(person.trim().length<=160){"Имя не должно быть длиннее 160 символов"}
            require(note.length<=8000){"Комментарий не должен быть длиннее 8000 символов"}
            val a=requireNotNull(account){"Выберите счёт"}
            val percent=runCatching{BigDecimal(interest.trim().ifBlank{"0"}.replace(',','.'))}.getOrElse{throw IllegalArgumentException("Укажите корректную годовую ставку")}
            require(percent >= BigDecimal.ZERO && percent <= BigDecimal("1000")){"Годовая ставка должна быть от 0 до 1000%"}
            val normalized=percent.stripTrailingZeros()
            require(normalized.scale() in -3..6){"Укажите годовую ставку с точностью до 6 знаков после запятой"}
            val openedDay=original?.openedDay ?: if(original==null&&recordOpening)plannerDate(opened) else null
            require(openedDay==null || openedDay<=today()){"Дата займа не может быть в будущем"}
            val principal=plannerAmount(amount,a.currency,"Введите корректную сумму долга")
            require(principal>0){"Сумма долга должна быть больше нуля"}
            val openingRateValue=if(recordOpening&&a.currency!=ledger.baseCurrency)
                plannerRateOrNull(openingRate,"Курс получения / выдачи") else null
            val d=Debt(original?.id ?: newId(),person.trim(),a.id,owed,principal,normalized.toPlainString(),
                due.trim().takeIf{it.isNotEmpty()}?.let(::plannerDate),note,openedDay)
            vm.change({l->
                val latestAccount=requireNotNull(l.accounts.find{it.id==d.accountId}){"Выберите счёт"}
                require(latestAccount.currency==a.currency){"Валюта счёта изменилась. Откройте долг снова."}
                if(original!=null) return@change Finance.editDebt(l,original,d)
                require(!latestAccount.archived){"Выберите активный счёт"}
                val updated=l.copy(debts=l.debts+d)
                if(openedDay!=null) {
                    require(latestAccount.currency==l.baseCurrency||openingRateValue!=null||
                        Finance.rate(updated,latestAccount.currency,openedDay)!=null){
                        "Курс получения / выдачи: укажите курс или добавьте его в разделе «Валюты»"
                    }
                    val entry=runCatching{
                        Finance.makeEntry(updated,if(owed)Kind.LOAN_IN else Kind.LOAN_OUT,latestAccount.id,d.principalMinor,openedDay,
                            note=note,explicitRate=openingRateValue,debtId=d.id,principalMinor=d.principalMinor,
                            timeMinutes=EntryOrder.manualTime(openedDay))
                    }.getOrElse{throw IllegalArgumentException("Не удалось пересчитать сумму займа по выбранному курсу")}
                    Finance.putEntry(updated,entry)
                } else updated
            },close)
        }.onFailure{error=it.message ?: "Проверьте данные"}
    }){
        Field(if(owed)"Кому я должен" else "Кто должен мне",person,{person=it;error=""})
        if(!paid && original?.openedDay==null){
            Chips(listOf(true,false),owed,{if(it)"Я должен" else "Мне должны"}){owed=it}
            AccountChoice("Счёт займа",accountId,ledger.accounts.filter{!it.archived||it.id==accountId}){accountId=it}
        }else Text((if(owed)"Я должен" else "Мне должны")+" · "+ledger.accountName(accountId))
        if(original?.openedDay==null)Field("Сумма долга (${account?.currency ?: ledger.baseCurrency})",amount,{amount=it;error=""},true)
        else if(account!=null)Text("Основная сумма: "+Money.format(original?.principalMinor ?: 0L,account.currency))
        else Text("Счёт недоступен. Закройте форму и откройте долг снова.",color=MaterialTheme.colorScheme.error)
        Field("Годовая ставка, % (необязательно)",interest,{interest=it;error=""},true)
        DateField("Срок (необязательно)",due,{due=it})
        Field("Комментарий (необязательно)",note,{note=it},singleLine=false)
        if(original==null) {
            Toggle("Записать получение или выдачу денег",recordOpening,{recordOpening=it},"Выключите для существующей задолженности, уже учтённой в начальном остатке.")
            if(recordOpening) {
                DateField("Дата получения / выдачи",opened,{opened=it})
                if(account!=null && account.currency!=ledger.baseCurrency)Field("Курс ${ledger.baseCurrency} за 1 ${account.currency}",openingRate,{openingRate=it},true)
            }
        }
        Text("Основной долг не считается доходом или расходом; проценты учитываются при погашении.")
        if(original!=null&&!paid)TextButton(onClick={delete=true}){Text("Удалить долг",color=MaterialTheme.colorScheme.error)}
    }
    if(delete&&original!=null)Confirm("Удалить долг?",
        if(hasOpeningEntry)"Связанная запись получения или выдачи денег тоже будет удалена, и остаток счёта изменится."
        else "Запись задолженности будет удалена.",
        {delete=false},{delete=false;vm.change({l->
            require(l.debts.find{it.id==original?.id}==original){"Долг изменился. Откройте его снова."}
            Finance.deleteUnpaidDebt(l,requireNotNull(original?.id))
        },close)},busy)
}

@Composable fun DebtPaymentEditor(ledger:Ledger,debt:Debt,vm:AppViewModel,busy:Boolean,close:()->Unit,current:Entry?=null, draft:EntryDraft){
    val original = draft.state("originalPayment"){current}.value
    val originalDebt = draft.state("originalDebt"){debt}.value
    val a=ledger.accounts.first{it.id==debt.accountId}
    val paymentAccounts=ledger.accounts.filter{it.currency==a.currency&&(!it.archived||it.id==original?.accountId)}
        .sortedWith(compareBy<Account>{it.sortOrder}.thenBy{it.name})
    var paymentAccountId by draft.state("paymentAccountId"){
        original?.accountId ?: paymentAccounts.firstOrNull{it.id==debt.accountId}?.id
            ?: paymentAccounts.firstOrNull()?.id.orEmpty()
    }
    val remaining=Money.add(Finance.debtRemaining(ledger,debt),current?.principalMinor ?: 0)
    var principal by draft.state("principal"){Money.input(original?.principalMinor ?: remaining,a.currency)}
    var interest by draft.state("interest"){original?.let{Money.input(it.minor-it.principalMinor,a.currency)} ?: "0"}
    var showInterest by draft.state("showInterest"){plannerHasInterestRate(debt) || (original?.let{it.minor>it.principalMinor}==true)}
    var day by draft.state("day"){iso(original?.day ?: today())}
    var note by draft.state("note"){original?.note.orEmpty()}
    var rate by draft.state("rate"){original?.rate.orEmpty()}
    var advanced by draft.state("advanced"){original!=null}
    var error by draft.state("error"){""}
    val currency by draft.state("paymentCurrency"){a.currency}
    val preview=debtPaymentPreview(principal,interest,showInterest,currency,remaining)
    val total=preview.total
    Form(if(original!=null)"Изменить платёж" else if(debt.owedByMe)"Погасить долг" else "Получить возврат",close,busy,
        saveLabel="Записать",onSave={
        runCatching{
            val p=plannerAmount(principal,currency,"Введите корректную сумму основного долга")
            val i=if(showInterest&&interest.isNotBlank())plannerAmount(interest,currency,"Введите корректную сумму процентов") else 0L
            val date=plannerDate(day)
            require(p>=0){"Сумма погашения не может быть отрицательной"}
            require(i>=0){"Проценты не могут быть отрицательными"}
            val sum=runCatching{Money.add(p,i)}.getOrElse{throw IllegalArgumentException("Слишком большая сумма платежа")}
            require(sum>0){"Введите сумму платежа больше нуля"}
            require(p<=remaining){"Основной платёж больше остатка долга"}
            require(date<=today()){"Дата платежа не может быть в будущем"}
            require(debt.openedDay==null||date>=debt.openedDay){"Дата платежа не может быть раньше даты займа"}
            require(note.trim().length<=8000){"Комментарий не должен быть длиннее 8000 символов"}
            require(paymentAccounts.any{it.id==paymentAccountId}){"Выберите счёт платежа"}
            val explicitRate=if(currency!=ledger.baseCurrency)plannerRateOrNull(rate,"Курс погашения") else null
            vm.change({l->
                val latest=requireNotNull(l.debts.find{it.id==debt.id}){"Долг удалён"}
                require(latest==originalDebt){"Долг изменился. Откройте платёж снова."}
                if(original!=null)require(l.entries.find{it.id==original.id}==original){"Платёж изменился. Откройте его снова."}
                val account=requireNotNull(l.accounts.find{it.id==paymentAccountId}){"Выберите счёт платежа"}
                require(l.accounts.find{it.id==latest.accountId}?.currency==currency){"Валюта долга изменилась. Откройте платёж снова."}
                require(account.currency==currency){"Валюта счёта изменилась. Откройте платёж снова."}
                require(!account.archived||original?.accountId==account.id){"Выберите активный счёт"}
                val available=Money.add(Finance.debtRemaining(l,latest),original?.principalMinor ?: 0)
                require(p<=available){"Основной платёж больше остатка долга"}
                require(account.currency==l.baseCurrency||explicitRate!=null||Finance.rate(l,account.currency,date)!=null){
                    "Курс погашения: укажите курс или добавьте его в разделе «Валюты»"
                }
                val e=runCatching{
                    Finance.makeEntry(l,if(latest.owedByMe)Kind.DEBT_OUT else Kind.DEBT_IN,account.id,sum,date,note=note,
                        explicitRate=explicitRate,debtId=latest.id,principalMinor=p,id=original?.id ?: newId(),allowArchived=original?.accountId==account.id,
                        timeMinutes=EntryOrder.manualTime(date,original))
                }.getOrElse{throw IllegalArgumentException("Не удалось пересчитать платёж по выбранному курсу")}
                Finance.putEntry(l,e)
            },close)
        }.onFailure{error=it.message ?: "Проверьте данные"}
    },compact=true,validationError=error){
        Text(debt.person,style=MaterialTheme.typography.titleMedium)
        Text("Остаток долга: "+Money.format(remaining,currency))
        AccountChoice(if(debt.owedByMe)"Списать со счёта" else "Зачислить на счёт",paymentAccountId,paymentAccounts){paymentAccountId=it;error=""}
        if(paymentAccounts.isEmpty())Warning("Нет активных счетов в валюте долга ($currency).")
        else if(ledger.accounts.any{!it.archived&&it.currency!=currency})Text("Платёж в валюте долга ($currency). Для другой валюты сначала выполните перевод между счетами.",style=MaterialTheme.typography.bodySmall)
        Field("Основной долг",principal,{principal=it;error=""},true,supporting="Сумма, на которую уменьшится долг")
        if(showInterest){
            Field("Проценты",interest,{interest=it;error=""},true,supporting=if(debt.owedByMe)"Только фактически уплаченные проценты" else "Только фактически полученные проценты")
            TextButton(onClick={showInterest=false;interest="0";error=""}){Text("Без процентов")}
        }else TextButton(onClick={showInterest=true}){Text("Добавить проценты")}
        if(total!=null)Text((if(debt.owedByMe)"Всего списать: " else "Всего получить: ")+Money.format(total,currency),style=MaterialTheme.typography.titleMedium)
        val afterPayment=preview.remaining
        if(afterPayment!=null)Text("Останется: "+Money.format(afterPayment,currency))
        if(currency!=ledger.baseCurrency)Field("Курс ${ledger.baseCurrency} за 1 $currency",rate,{rate=it;error=""},true,supporting="Пустое поле использует сохранённый курс")
        TextButton(onClick={advanced=!advanced}){Text(if(advanced)"Свернуть детали" else "Дата и комментарий")}
        if(advanced){
            DateField("Дата",day,{day=it;error=""})
            Field("Комментарий",note,{note=it;error=""})
        }
        if(showInterest)Text(if(debt.owedByMe)"В расходах учитываются только проценты." else "В доходах учитываются только проценты.",style=MaterialTheme.typography.bodySmall)
    }
}
