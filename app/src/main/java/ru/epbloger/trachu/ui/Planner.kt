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
    val calendar=remember(ledger,month) {
        runCatching { Finance.generate(ledger,month.atEndOfMonth().toEpochDay(),true).entries }.getOrElse{ledger.entries}
            .filter{it.day in month.atDay(1).toEpochDay()..month.atEndOfMonth().toEpochDay()}
    }
    LazyColumn(modifier=Modifier.testTag("planner-list"),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
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
                        @Composable fun Fund(modifier:Modifier){Button(onClick={adjustGoal(g,false)},modifier=modifier.testTag("goal-fund:${g.id}")){Text("Пополнить")}}
                        @Composable fun Withdraw(modifier:Modifier){OutlinedButton(onClick={adjustGoal(g,true)},enabled=saved>0,modifier=modifier.testTag("goal-withdraw:${g.id}")){Text("Снять")}}
                        if(maxWidth<260.dp||LocalDensity.current.fontScale>=1.5f){
                            Column(verticalArrangement=Arrangement.spacedBy(4.dp)){Fund(Modifier.fillMaxWidth());Withdraw(Modifier.fillMaxWidth())}
                        }else Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){Fund(Modifier.weight(1f));Withdraw(Modifier.weight(1f))}
                    }
                }}
            }
            PlannerTab.DEBT->{
                item{SectionTitle("Долги и кредиты",action="Добавить"){editDebt(null)}}
                if(ledger.debts.isEmpty())item{EmptyState("Долгов нет","Учитывайте задолженности, проценты и погашения.","Добавить долг"){editDebt(null)}}
                items(ledger.debts,key={it.id}){d->
                    val a=ledger.accounts.first{it.id==d.accountId}
                    val left=Finance.debtRemaining(ledger,d)
                    Panel {
                        SectionTitle(d.person,if(d.owedByMe)"Я должен" else "Мне должны",action="Изменить"){editDebt(d)}
                        MoneyText(left,a.currency,large=true,color=if(d.owedByMe)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                        Text("Исходная сумма: "+Money.format(d.principalMinor,a.currency))
                        Progress(d.principalMinor-left,d.principalMinor)
                        if(d.annualPercent!="0") {
                            Text("Годовая ставка: ${d.annualPercent}%")
                            runCatching{Finance.estimatedInterest(ledger,d,30)}.getOrNull()?.let{Text("Оценка процентов за 30 дней: "+Money.format(it,a.currency),style=MaterialTheme.typography.bodySmall)}
                            val accrued=runCatching{Finance.accruedInterest(ledger,d)}.getOrNull()
                            val paid=runCatching{Finance.paidInterest(ledger,d)}.getOrNull()
                            if(accrued!=null)Text("Начислено по оценке: "+Money.format(accrued,a.currency),style=MaterialTheme.typography.bodySmall)
                            if(paid!=null)Text("Фактически учтённые проценты: "+Money.format(paid,a.currency),style=MaterialTheme.typography.bodySmall)
                            Text("Простая оценка Actual/365, без банковского графика и капитализации.",style=MaterialTheme.typography.bodySmall)
                        }
                        d.dueDay?.let{Text("Срок: "+dayLabel(it),color=if(it<today()&&left>0)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)}
                        if(d.note.isNotBlank())Text(d.note)
                        Button(onClick={payDebt(d)},enabled=left>0,modifier=Modifier.fillMaxWidth().testTag("debt-pay:${d.id}")){Text(if(d.owedByMe)"Погасить долг" else "Получить возврат")}
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
                            TextButton(onClick={val next=month.minusMonths(1);monthKey=next.toString();selectedDay=next.atDay(1).toEpochDay()}){Text("‹")}
                            Text(month.format(DateTimeFormatter.ofPattern("LLLL yyyy",Locale("ru"))),Modifier.weight(1f),style=MaterialTheme.typography.titleMedium)
                            TextButton(modifier=Modifier.testTag("calendar-next"),onClick={val next=month.plusMonths(1);monthKey=next.toString();selectedDay=next.atDay(1).toEpochDay()}){Text("›")}
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
                                                val count=calendar.count{it.day==d}+ledger.debts.count{it.dueDay==d && Finance.debtRemaining(ledger,it)>0}+ledger.goals.count{it.dueDay==d}
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
                item{Text(dayLabel(selectedDay),style=MaterialTheme.typography.titleLarge)}
                val selected=calendar.filter{it.day==selectedDay}.sortedBy{it.timeMinutes}
                val obligations=ledger.debts.filter{it.dueDay==selectedDay&&Finance.debtRemaining(ledger,it)>0}
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
}

@Composable fun BudgetEditor(ledger:Ledger,current:Budget?,vm:AppViewModel,busy:Boolean,close:()->Unit, draft:EntryDraft){
    var name by draft.state("name"){current?.name ?: "Бюджет месяца"}
    var category by draft.state("category"){current?.categoryId.orEmpty()}
    var amount by draft.state("amount"){current?.let{Money.input(it.monthlyMinor,ledger.baseCurrency)}.orEmpty()}
    var start by draft.state("start"){iso(current?.startMonth ?: Period.month().from)}
    var carry by draft.state("carry"){current?.rollover ?: false}
    var error by draft.state("error"){""}
    var delete by draft.state("delete"){false}
    Form(if(current==null)"Новый бюджет" else "Редактировать бюджет",close,busy,validationError=error,onSave={
        runCatching{
            require(name.isNotBlank()){"Введите название бюджета"}
            val limit=plannerAmount(amount,ledger.baseCurrency,"Введите корректный месячный лимит")
            require(limit>0){"Месячный лимит должен быть больше нуля"}
            val b=Budget(current?.id ?: newId(),name.trim(),category.ifBlank{null},limit,plannerDate(start),carry)
            runCatching{Finance.budget(ledger,b,today()).remaining}.getOrElse{throw IllegalArgumentException("Слишком большой лимит или период бюджета")}
            vm.change({l->
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
        if(current!=null)TextButton(onClick={delete=true}){Text("Удалить бюджет",color=MaterialTheme.colorScheme.error)}
    }
    if(delete && current!=null)Confirm("Удалить бюджет?","Операции не будут удалены.",{delete=false},{vm.change({l->l.copy(budgets=l.budgets.filterNot{it.id==current.id})},close)},busy)
}

@Composable fun GoalEditor(ledger:Ledger,current:Goal?,vm:AppViewModel,busy:Boolean,close:()->Unit, draft:EntryDraft){
    val original by draft.state("originalGoal"){current}
    var name by draft.state("name"){current?.name.orEmpty()}
    var currency by draft.state("currency"){current?.currency ?: ledger.baseCurrency}
    var target by draft.state("target"){current?.let{Money.input(it.targetMinor,it.currency)}.orEmpty()}
    var saved by draft.state("saved"){current?.let{Money.input(it.savedMinor,it.currency)} ?: "0"}
    var due by draft.state("due"){current?.dueDay?.let(::iso).orEmpty()}
    var error by draft.state("error"){""}
    var delete by draft.state("delete"){false}
    Form(if(current==null)"Новая цель" else "Редактировать цель",close,busy,validationError=error,onSave={
        runCatching{
            require(name.isNotBlank()){"Введите название цели"}
            val targetMinor=plannerAmount(target,currency,"Введите корректную целевую сумму")
            val savedMinor=plannerAmount(saved,currency,"Введите корректную сумму начальных накоплений")
            require(targetMinor>0){"Целевая сумма должна быть больше нуля"}
            require(savedMinor>=0){"Начальные накопления не могут быть отрицательными"}
            val dueDay=due.trim().takeIf{it.isNotEmpty()}?.let{plannerDate(it)}
            val g=Goal(original?.id ?: newId(),name.trim(),currency,targetMinor,savedMinor,dueDay,original?.adjustments.orEmpty())
            vm.change({l->
                if(original!=null)require(l.goals.find{it.id==original?.id}==original){"Цель изменилась. Откройте её снова."}
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
        if(current==null||current.adjustments.isEmpty())Choice("Валюта",currency,allCurrencies,{it}){currency=it}
        else Text("Валюта: $currency")
        Field("Целевая сумма",target,{target=it},true)
        Field("Начальные накопления",saved,{saved=it},true,supporting=if(current!=null)"Сумма на момент создания цели" else null)
        if(current!=null) {
            Text("Текущий прогресс: "+Money.format(Finance.goalSaved(current),current.currency),style=MaterialTheme.typography.titleMedium)
            if(current.adjustments.isNotEmpty())Text("Последние изменения",style=MaterialTheme.typography.titleSmall)
            current.adjustments.takeLast(10).asReversed().forEach { a ->
                Text(dayLabel(a.day)+" · "+Money.format(a.minor,current.currency)+" · "+a.note,style=MaterialTheme.typography.bodySmall)
            }
        }
        DateField("Срок (необязательно)",due,{due=it})
        Text("Накопления в цели не меняют баланс счетов.",style=MaterialTheme.typography.bodySmall)
        if(current!=null)TextButton(onClick={delete=true}){Text("Удалить цель",color=MaterialTheme.colorScheme.error)}
    }
    if(delete&&current!=null)Confirm("Удалить цель?","Счета и операции не изменятся.",{delete=false},{vm.change({l->l.copy(goals=l.goals.filterNot{it.id==current.id})},close)},busy)
}

private fun plannerAmount(value:String,currency:String,message:String):Long =
    runCatching{Money.parse(value,currency)}.getOrElse{throw IllegalArgumentException(message)}
private fun plannerDate(value:String):Long =
    runCatching{parseDay(value)}.getOrElse{throw IllegalArgumentException("Введите дату в формате ГГГГ-ММ-ДД")}

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
    val paid=current!=null && ledger.entries.any{it.debtId==current.id && it.kind in setOf(Kind.DEBT_IN,Kind.DEBT_OUT)}
    var recordOpening by draft.state("recordOpening"){false}
    var opened by draft.state("opened"){iso(today())}
    var openingRate by draft.state("openingRate"){""}
    Form(if(current==null)"Новый долг" else "Редактировать долг",close,busy,validationError=error,onSave={
        runCatching{
            require(person.isNotBlank()){"Укажите, кому вы должны или кто должен вам"}
            require(person.trim().length<=160){"Имя не должно быть длиннее 160 символов"}
            require(note.length<=8000){"Комментарий не должен быть длиннее 8000 символов"}
            val a=requireNotNull(account){"Выберите счёт"}
            val percent=runCatching{BigDecimal(interest.trim().ifBlank{"0"}.replace(',','.'))}.getOrElse{throw IllegalArgumentException("Укажите корректную годовую ставку")}
            require(percent >= BigDecimal.ZERO && percent <= BigDecimal("1000")){"Годовая ставка должна быть от 0 до 1000%"}
            val normalized=percent.stripTrailingZeros()
            require(normalized.scale() in -3..6){"Укажите годовую ставку с точностью до 6 знаков после запятой"}
            val openedDay=current?.openedDay ?: if(current==null&&recordOpening)plannerDate(opened) else null
            require(openedDay==null || openedDay<=today()){"Дата займа не может быть в будущем"}
            val principal=plannerAmount(amount,a.currency,"Введите корректную сумму долга")
            require(principal>0){"Сумма долга должна быть больше нуля"}
            val d=Debt(current?.id ?: newId(),person.trim(),a.id,owed,principal,normalized.toPlainString(),
                due.trim().takeIf{it.isNotEmpty()}?.let(::plannerDate),note,openedDay)
            vm.change({l->
                require(l.accounts.any{it.id==d.accountId}){"Выберите счёт"}
                val updated=l.copy(debts=l.debts.filterNot{it.id==d.id}+d)
                require(Finance.debtRemaining(updated,d)>=0){"Сумма долга меньше уже погашенной суммы"}
                if(current==null && openedDay!=null) {
                    val entry=Finance.makeEntry(updated,if(owed)Kind.LOAN_IN else Kind.LOAN_OUT,a.id,d.principalMinor,openedDay,
                        note=note,explicitRate=openingRate.trim().replace(',','.').ifBlank{null},debtId=d.id,principalMinor=d.principalMinor)
                    Finance.putEntry(updated,entry)
                } else updated
            },close)
        }.onFailure{error=it.message ?: "Проверьте данные"}
    }){
        Field(if(owed)"Кому я должен" else "Кто должен мне",person,{person=it;error=""})
        if(!paid && current?.openedDay==null){
            Chips(listOf(true,false),owed,{if(it)"Я должен" else "Мне должны"}){owed=it}
            AccountChoice("Счёт погашения",accountId,ledger.accounts.filter{!it.archived||it.id==accountId}){accountId=it}
        }else Text((if(owed)"Я должен" else "Мне должны")+" · "+ledger.accountName(accountId))
        if(current?.openedDay==null)Field("Сумма долга (${account?.currency ?: ledger.baseCurrency})",amount,{amount=it;error=""},true)
        else Text("Основная сумма: "+Money.format(current.principalMinor,requireNotNull(account).currency))
        Field("Годовая ставка, % (необязательно)",interest,{interest=it;error=""},true)
        DateField("Срок (необязательно)",due,{due=it})
        Field("Комментарий (необязательно)",note,{note=it},singleLine=false)
        if(current==null) {
            Toggle("Записать получение или выдачу денег",recordOpening,{recordOpening=it},"Выключите для существующей задолженности, уже учтённой в начальном остатке.")
            if(recordOpening) {
                DateField("Дата получения / выдачи",opened,{opened=it})
                if(account!=null && account.currency!=ledger.baseCurrency)Field("Курс ${ledger.baseCurrency} за 1 ${account.currency}",openingRate,{openingRate=it},true)
            }
        }
        Text("Основной долг не считается доходом или расходом; проценты учитываются при погашении.")
        if(current!=null&&!paid)TextButton(onClick={delete=true}){Text("Удалить долг",color=MaterialTheme.colorScheme.error)}
    }
    if(delete&&current!=null)Confirm("Удалить долг?","Запись задолженности будет удалена.",{delete=false},{vm.change({l->l.copy(debts=l.debts.filterNot{it.id==current.id},entries=l.entries.filterNot{it.debtId==current.id})},close)},busy)
}

@Composable fun DebtPaymentEditor(ledger:Ledger,debt:Debt,vm:AppViewModel,busy:Boolean,close:()->Unit,current:Entry?=null, draft:EntryDraft){
    val original by draft.state("originalPayment"){current}
    val originalDebt by draft.state("originalDebt"){debt}
    val a=ledger.accounts.first{it.id==debt.accountId}
    val remaining=Money.add(Finance.debtRemaining(ledger,debt),current?.principalMinor ?: 0)
    var principal by draft.state("principal"){Money.input(original?.principalMinor ?: remaining,a.currency)}
    var interest by draft.state("interest"){original?.let{Money.input(it.minor-it.principalMinor,a.currency)} ?: "0"}
    var showInterest by draft.state("showInterest"){debt.annualPercent!="0" || (original?.let{it.minor>it.principalMinor}==true)}
    var day by draft.state("day"){iso(original?.day ?: today())}
    var note by draft.state("note"){original?.note.orEmpty()}
    var rate by draft.state("rate"){original?.rate.orEmpty()}
    var advanced by draft.state("advanced"){original!=null}
    var error by draft.state("error"){""}
    val currency by draft.state("paymentCurrency"){a.currency}
    val total=runCatching{
        val p=Money.parse(principal,currency)
        val i=if(showInterest&&interest.isNotBlank())Money.parse(interest,currency) else 0L
        require(p>=0&&i>=0)
        Money.add(p,i)
    }.getOrNull()
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
            vm.change({l->
                val latest=requireNotNull(l.debts.find{it.id==debt.id}){"Долг удалён"}
                require(latest==originalDebt){"Долг изменился. Откройте платёж снова."}
                if(original!=null)require(l.entries.find{it.id==original?.id}==original){"Платёж изменился. Откройте его снова."}
                val account=requireNotNull(l.accounts.find{it.id==latest.accountId}){"Счёт удалён"}
                require(account.currency==currency){"Валюта счёта изменилась. Откройте платёж снова."}
                require(!account.archived||original!=null){"Сначала восстановите счёт из архива"}
                val available=Money.add(Finance.debtRemaining(l,latest),original?.principalMinor ?: 0)
                require(p<=available){"Основной платёж больше остатка долга"}
                val e=Finance.makeEntry(l,if(latest.owedByMe)Kind.DEBT_OUT else Kind.DEBT_IN,account.id,sum,date,note=note,
                    explicitRate=rate.trim().replace(',','.').ifBlank{null},debtId=latest.id,principalMinor=p,id=original?.id ?: newId(),allowArchived=original!=null)
                Finance.putEntry(l,e)
            },close)
        }.onFailure{error=it.message ?: "Проверьте данные"}
    },compact=true,validationError=error){
        Text(debt.person,style=MaterialTheme.typography.titleMedium)
        Text("Остаток долга: "+Money.format(remaining,currency))
        Text(accountChoiceLabel(a),style=MaterialTheme.typography.bodySmall)
        Field("Основной долг",principal,{principal=it;error=""},true,supporting="Сумма, на которую уменьшится долг")
        if(showInterest){
            Field("Проценты",interest,{interest=it;error=""},true,supporting=if(debt.owedByMe)"Только фактически уплаченные проценты" else "Только фактически полученные проценты")
            TextButton(onClick={showInterest=false;interest="0";error=""}){Text("Без процентов")}
        }else TextButton(onClick={showInterest=true}){Text("Добавить проценты")}
        if(total!=null)Text((if(debt.owedByMe)"Всего списать: " else "Всего получить: ")+Money.format(total,currency),style=MaterialTheme.typography.titleMedium)
        if(currency!=ledger.baseCurrency)Field("Курс ${ledger.baseCurrency} за 1 $currency",rate,{rate=it;error=""},true,supporting="Пустое поле использует сохранённый курс")
        TextButton(onClick={advanced=!advanced}){Text(if(advanced)"Свернуть детали" else "Дата и комментарий")}
        if(advanced){
            DateField("Дата",day,{day=it;error=""})
            Field("Комментарий",note,{note=it;error=""})
        }
        if(showInterest)Text(if(debt.owedByMe)"В расходах учитываются только проценты." else "В доходах учитываются только проценты.",style=MaterialTheme.typography.bodySmall)
    }
}
