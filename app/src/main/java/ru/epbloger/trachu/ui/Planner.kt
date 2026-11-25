package ru.epbloger.trachu.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import ru.epbloger.trachu.AppViewModel
import ru.epbloger.trachu.core.*
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class PlannerTab(val label:String){BUDGET("Бюджеты"),GOAL("Цели"),DEBT("Долги"),RECURRING("Регулярные"),CALENDAR("Календарь")}
@Composable fun Planner(ledger:Ledger,initial:PlannerTab=PlannerTab.BUDGET,editBudget:(Budget?)->Unit,editGoal:(Goal?)->Unit,editDebt:(Debt?)->Unit,payDebt:(Debt)->Unit,editSchedule:(Schedule?)->Unit,openEntry:(Entry)->Unit,addPlanned:()->Unit) {
    var tab by remember(initial){mutableStateOf(initial)}
    var month by remember{mutableStateOf(YearMonth.now())}
    var selectedDay by remember{mutableStateOf(today())}
    val calendar=remember(ledger,month) {
        runCatching { Finance.generate(ledger,month.atEndOfMonth().toEpochDay(),true).entries }.getOrElse{ledger.entries}
            .filter{it.day in month.atDay(1).toEpochDay()..month.atEndOfMonth().toEpochDay()}
    }
    LazyColumn(contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item{Text("Планирование",style=MaterialTheme.typography.headlineLarge);Chips(PlannerTab.entries,tab,{it.label}){tab=it}}
        when(tab){
            PlannerTab.BUDGET->{
                item{SectionTitle("Бюджеты",action="Добавить"){editBudget(null)}}
                if(ledger.budgets.isEmpty())item{EmptyState("Поставьте лимит","Общий месячный бюджет или лимит отдельной категории.","Создать бюджет"){editBudget(null)}}
                items(ledger.budgets,key={it.id}){b->
                    val r=Finance.budget(ledger,b,today())
                    Panel {
                        SectionTitle(b.name,b.categoryId?.let{ledger.categoryName(it)} ?: "Все расходы",action="Изменить"){editBudget(b)}
                        MoneyText(r.remaining,ledger.baseCurrency,large=true,color=if(r.remaining<0)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                        Text("Остаток · ${Money.format(r.available,ledger.baseCurrency)} доступно")
                        Progress(r.spent,r.available,if(r.level>=2)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                        Text("Факт: ${Money.format(r.spent,ledger.baseCurrency)} · ${r.percent}%")
                        Text(if(b.rollover)"Остаток переносится" else "Без переноса остатка",style=MaterialTheme.typography.bodySmall)
                        if(r.level>0)Warning(if(r.level==3)"Бюджет превышен" else if(r.level==2)"Лимит достигнут" else "Использовано 80% или больше")
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
                            Text("Оценка процентов за 30 дней: "+Money.format(Finance.estimatedInterest(ledger,d,30),a.currency),style=MaterialTheme.typography.bodySmall)
                        }
                        d.dueDay?.let{Text("Срок: "+dayLabel(it),color=if(it<today()&&left>0)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)}
                        if(d.note.isNotBlank())Text(d.note)
                        Button(onClick={payDebt(d)},enabled=left>0,modifier=Modifier.fillMaxWidth()){Text("Записать погашение")}
                    }
                }
            }
            PlannerTab.RECURRING->{
                item{SectionTitle("Регулярные операции",action="Добавить"){editSchedule(null)}}
                if(ledger.schedules.isEmpty())item{EmptyState("Автоматизируйте рутину","Зарплата, аренда, подписки и другие повторяющиеся операции.","Создать расписание"){editSchedule(null)}}
                items(ledger.schedules,key={it.id}){s->
                    val a=ledger.accounts.first{it.id==s.template.accountId}
                    Panel {
                        SectionTitle(ledger.entryName(s.template),if(s.enabled)"Следующая: "+dayLabel(s.nextDay) else "Приостановлено",action="Изменить"){editSchedule(s)}
                        MoneyText(s.template.minor,a.currency,large=true)
                        Text("${s.frequency.label()} · интервал ${s.interval}")
                        Text(if(s.autoPost)"Автоматическое проведение" else "Подтверждение вручную")
                    }
                }
            }
            PlannerTab.CALENDAR->{
                item{
                    Panel {
                        SectionTitle("Календарь",action="Добавить"){addPlanned()}
                        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween){
                            TextButton(onClick={month=month.minusMonths(1);selectedDay=month.atDay(1).toEpochDay()}){Text("‹")}
                            Text(month.format(DateTimeFormatter.ofPattern("LLLL yyyy",Locale("ru"))),Modifier.weight(1f),style=MaterialTheme.typography.titleMedium)
                            TextButton(onClick={month=month.plusMonths(1);selectedDay=month.atDay(1).toEpochDay()}){Text("›")}
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

@Composable fun BudgetEditor(ledger:Ledger,current:Budget?,vm:AppViewModel,busy:Boolean,close:()->Unit){
    var name by remember{mutableStateOf(current?.name ?: "Бюджет месяца")}
    var category by remember{mutableStateOf(current?.categoryId.orEmpty())}
    var amount by remember{mutableStateOf(current?.let{Money.input(it.monthlyMinor,ledger.baseCurrency)}.orEmpty())}
    var start by remember{mutableStateOf(iso(current?.startMonth ?: Period.month().from))}
    var carry by remember{mutableStateOf(current?.rollover ?: false)}
    var error by remember{mutableStateOf("")}
    var delete by remember{mutableStateOf(false)}
    Form(if(current==null)"Новый бюджет" else "Редактировать бюджет",close,busy,onSave={
        runCatching{
            val b=Budget(current?.id ?: newId(),name.trim(),category.ifBlank{null},Money.parse(amount,ledger.baseCurrency),parseDay(start),carry)
            require(b.monthlyMinor>0&&b.name.isNotBlank())
            vm.change({l->l.copy(budgets=l.budgets.filterNot{it.id==b.id}+b)},close)
        }.onFailure{error=it.message ?: "Проверьте данные"}
    }){
        Field("Название",name,{name=it})
        Choice("Категория",category,listOf("")+ledger.categories.filter{!it.income&&!it.archived || it.id==category}.map{it.id},{if(it.isEmpty())"Общий бюджет" else ledger.categoryName(it)}){category=it}
        Field("Месячный лимит (${ledger.baseCurrency})",amount,{amount=it},true)
        Field("Начало",start,{start=it},supporting="ГГГГ-ММ-ДД (учитывается месяц)")
        Toggle("Переносить остаток",carry,{carry=it})
        Text("Предупреждения при 80%, 100% и превышении лимита.")
        if(current!=null)TextButton(onClick={delete=true}){Text("Удалить бюджет",color=MaterialTheme.colorScheme.error)}
        if(error.isNotEmpty())Warning(error)
    }
    if(delete && current!=null)Confirm("Удалить бюджет?","Операции не будут удалены.",{delete=false},{vm.change({l->l.copy(budgets=l.budgets.filterNot{it.id==current.id})},close)},busy)
}

@Composable fun GoalEditor(ledger:Ledger,current:Goal?,vm:AppViewModel,busy:Boolean,close:()->Unit){
    var name by remember{mutableStateOf(current?.name.orEmpty())}
    var currency by remember{mutableStateOf(current?.currency ?: ledger.baseCurrency)}
    var target by remember{mutableStateOf(current?.let{Money.input(it.targetMinor,it.currency)}.orEmpty())}
    var saved by remember{mutableStateOf(current?.let{Money.input(it.savedMinor,it.currency)} ?: "0")}
    var due by remember{mutableStateOf(current?.dueDay?.let(::iso).orEmpty())}
    var error by remember{mutableStateOf("")}
    var delete by remember{mutableStateOf(false)}
    var adjustment by remember{mutableStateOf("")}
    var adjustmentNote by remember{mutableStateOf("")}
    var withdraw by remember{mutableStateOf(false)}
    Form(if(current==null)"Новая цель" else "Редактировать цель",close,busy,onSave={
        runCatching{
            val g=Goal(current?.id ?: newId(),name.trim(),currency,Money.parse(target,currency),Money.parse(saved,currency),due.trim().takeIf{it.isNotEmpty()}?.let(::parseDay),current?.adjustments.orEmpty())
            require(g.name.isNotBlank()&&g.targetMinor>0&&g.savedMinor>=0)
            vm.change({l->l.copy(goals=l.goals.filterNot{it.id==g.id}+g)},close)
        }.onFailure{error=it.message ?: "Проверьте данные"}
    }){
        Field("Название",name,{name=it})
        if(current==null||current.adjustments.isEmpty())Choice("Валюта",currency,allCurrencies,{it}){currency=it}
        else Text("Валюта: $currency")
        Field("Целевая сумма",target,{target=it},true)
        Field("Начальные накопления",saved,{saved=it},true)
        if(current!=null) {
            Text("Текущий прогресс: "+Money.format(Finance.goalSaved(current),current.currency),style=MaterialTheme.typography.titleMedium)
            Chips(listOf(false,true),withdraw,{if(it)"Снять" else "Пополнить"}){withdraw=it}
            Field("Сумма изменения",adjustment,{adjustment=it},true)
            Field("Комментарий к изменению",adjustmentNote,{adjustmentNote=it})
            OutlinedButton(onClick={
                runCatching {
                    val m=Money.parse(adjustment,current.currency);require(m>0)
                    val a=GoalAdjustment(minor=if(withdraw)-m else m,note=adjustmentNote)
                    vm.change({l->l.copy(goals=l.goals.map{g->if(g.id==current.id)g.copy(adjustments=g.adjustments+a) else g})})
                    adjustment="";adjustmentNote=""
                }.onFailure{error=it.message ?: "Проверьте сумму"}
            },enabled=!busy,modifier=Modifier.fillMaxWidth()){Text("Записать изменение")}
            current.adjustments.takeLast(10).asReversed().forEach { a ->
                Text(dayLabel(a.day)+" · "+Money.format(a.minor,current.currency)+" · "+a.note,style=MaterialTheme.typography.bodySmall)
            }
        }
        Field("Срок (необязательно)",due,{due=it},supporting="ГГГГ-ММ-ДД")
        Text("Цель отражает отложенные деньги, а не создаёт новую операцию. Переведите деньги на накопительный счёт отдельно, если нужно.")
        if(current!=null)TextButton(onClick={delete=true}){Text("Удалить цель",color=MaterialTheme.colorScheme.error)}
        if(error.isNotEmpty())Warning(error)
    }
    if(delete&&current!=null)Confirm("Удалить цель?","Счета и операции не изменятся.",{delete=false},{vm.change({l->l.copy(goals=l.goals.filterNot{it.id==current.id})},close)},busy)
}

@Composable fun DebtEditor(ledger:Ledger,current:Debt?,vm:AppViewModel,busy:Boolean,close:()->Unit){
    var person by remember{mutableStateOf(current?.person.orEmpty())}
    var owed by remember{mutableStateOf(current?.owedByMe ?: true)}
    var accountId by remember{mutableStateOf(current?.accountId ?: ledger.accounts.firstOrNull{!it.archived}?.id.orEmpty())}
    val account=ledger.accounts.find{it.id==accountId}
    var amount by remember{mutableStateOf(current?.let{Money.input(it.principalMinor,ledger.accounts.first{a->a.id==it.accountId}.currency)}.orEmpty())}
    var interest by remember{mutableStateOf(current?.annualPercent ?: "0")}
    var due by remember{mutableStateOf(current?.dueDay?.let(::iso).orEmpty())}
    var note by remember{mutableStateOf(current?.note.orEmpty())}
    var error by remember{mutableStateOf("")}
    var delete by remember{mutableStateOf(false)}
    val paid=current!=null && ledger.entries.any{it.debtId==current.id && it.kind in setOf(Kind.DEBT_IN,Kind.DEBT_OUT)}
    var recordOpening by remember{mutableStateOf(false)}
    var opened by remember{mutableStateOf(iso(today()))}
    var openingRate by remember{mutableStateOf("")}
    Form(if(current==null)"Новый долг" else "Редактировать долг",close,busy,onSave={
        runCatching{
            val a=requireNotNull(account){"Выберите счёт"}
            val percent=BigDecimal(interest.replace(',','.'))
            require(percent >= BigDecimal.ZERO && percent <= BigDecimal("1000") && percent.scale() in -3..6){"Некорректная ставка"}
            val openedDay=current?.openedDay ?: if(current==null&&recordOpening)parseDay(opened) else null
            require(openedDay==null || openedDay<=today()){"Дата займа не может быть в будущем"}
            val d=Debt(current?.id ?: newId(),person.trim(),a.id,owed,Money.parse(amount,a.currency),percent.stripTrailingZeros().toPlainString(),
                due.trim().takeIf{it.isNotEmpty()}?.let(::parseDay),note,openedDay)
            require(d.principalMinor>0&&d.person.isNotBlank())
            vm.change({l->
                val updated=l.copy(debts=l.debts.filterNot{it.id==d.id}+d)
                if(current==null && openedDay!=null) {
                    val entry=Finance.makeEntry(updated,if(owed)Kind.LOAN_IN else Kind.LOAN_OUT,a.id,d.principalMinor,openedDay,
                        note=note,explicitRate=openingRate.trim().replace(',','.').ifBlank{null},debtId=d.id,principalMinor=d.principalMinor)
                    Finance.putEntry(updated,entry)
                } else updated
            },close)
        }.onFailure{error=it.message ?: "Проверьте данные"}
    }){
        Field("Кредитор / должник",person,{person=it})
        if(!paid && current?.openedDay==null){
            Chips(listOf(true,false),owed,{if(it)"Я должен" else "Мне должны"}){owed=it}
            Choice("Счёт погашения",accountId,ledger.accounts.filter{!it.archived||it.id==accountId}.map{it.id},{ledger.accountName(it)}){accountId=it}
        }else Text((if(owed)"Я должен" else "Мне должны")+" · "+ledger.accountName(accountId))
        if(current?.openedDay==null)Field("Исходный основной долг (${account?.currency ?: ledger.baseCurrency})",amount,{amount=it},true)
        else Text("Основная сумма: "+Money.format(current.principalMinor,requireNotNull(account).currency))
        Field("Годовая ставка, %",interest,{interest=it},true)
        Field("Срок (необязательно)",due,{due=it},supporting="ГГГГ-ММ-ДД")
        Field("Заметка",note,{note=it},singleLine=false)
        if(current==null) {
            Toggle("Записать получение или выдачу денег",recordOpening,{recordOpening=it},"Выключите для существующей задолженности, уже учтённой в начальном остатке.")
            if(recordOpening) {
                Field("Дата получения / выдачи",opened,{opened=it},supporting="ГГГГ-ММ-ДД")
                if(account!=null && account.currency!=ledger.baseCurrency)Field("Курс ${ledger.baseCurrency} за 1 ${account.currency}",openingRate,{openingRate=it},true)
            }
        }
        Text("Основной долг не считается доходом или расходом; проценты учитываются при погашении.")
        if(current!=null&&!paid)TextButton(onClick={delete=true}){Text("Удалить долг",color=MaterialTheme.colorScheme.error)}
        if(error.isNotEmpty())Warning(error)
    }
    if(delete&&current!=null)Confirm("Удалить долг?","Запись задолженности будет удалена.",{delete=false},{vm.change({l->l.copy(debts=l.debts.filterNot{it.id==current.id},entries=l.entries.filterNot{it.debtId==current.id})},close)},busy)
}

@Composable fun DebtPaymentEditor(ledger:Ledger,debt:Debt,vm:AppViewModel,busy:Boolean,close:()->Unit,current:Entry?=null){
    val a=ledger.accounts.first{it.id==debt.accountId}
    val remaining=Money.add(Finance.debtRemaining(ledger,debt),current?.principalMinor ?: 0)
    var principal by remember{mutableStateOf(Money.input(current?.principalMinor ?: remaining,a.currency))}
    var interest by remember{mutableStateOf(current?.let{Money.input(it.minor-it.principalMinor,a.currency)} ?: "0")}
    var day by remember{mutableStateOf(iso(current?.day ?: today()))}
    var note by remember{mutableStateOf(current?.note.orEmpty())}
    var rate by remember{mutableStateOf(current?.rate.orEmpty())}
    var error by remember{mutableStateOf("")}
    Form("Погашение долга",close,busy,onSave={
        runCatching{
            val p=Money.parse(principal,a.currency);val i=Money.parse(interest,a.currency);val date=parseDay(day)
            require(p>=0&&i>=0&&Money.add(p,i)>0&&p<=remaining&&date<=today()){"Проверьте сумму и дату"}
            vm.change({l->
                val e=Finance.makeEntry(l,if(debt.owedByMe)Kind.DEBT_OUT else Kind.DEBT_IN,a.id,Money.add(p,i),date,note=note,
                    explicitRate=rate.trim().replace(',','.').ifBlank{null},debtId=debt.id,principalMinor=p,id=current?.id ?: newId(),allowArchived=current!=null)
                Finance.putEntry(l,e)
            },close)
        }.onFailure{error=it.message ?: "Проверьте данные"}
    }){
        Text(debt.person);Text("Остаток: "+Money.format(remaining,a.currency))
        Field("Основной долг",principal,{principal=it},true)
        Field("Фактически уплаченные проценты",interest,{interest=it},true)
        Field("Дата",day,{day=it},supporting="ГГГГ-ММ-ДД")
        if(a.currency!=ledger.baseCurrency)Field("Курс ${ledger.baseCurrency} за 1 ${a.currency}",rate,{rate=it},true,supporting="Пустое поле использует сохранённый курс")
        Field("Комментарий",note,{note=it})
        Text("Основная сумма уменьшает долг, проценты отражаются в доходах или расходах. Оба компонента меняют остаток счёта.")
        if(error.isNotEmpty())Warning(error)
    }
}
