package ru.epbloger.trachu.ui

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.epbloger.trachu.AppViewModel
import ru.epbloger.trachu.core.*
import ru.epbloger.trachu.security.Settings
import java.time.LocalDate
import java.time.LocalTime
import java.util.Currency

private val currencies = Currency.getAvailableCurrencies().map { it.currencyCode }.filter { runCatching { Money.digits(it) }.isSuccess }.sorted()
private val commonCurrencies = listOf("RUB","USD","EUR","CNY","KZT","BYN","TRY","JPY","GBP","AED")
val allCurrencies = (commonCurrencies+currencies).distinct()

@Composable fun AccountEditor(ledger: Ledger, current: Account?, busy: Boolean, vm: AppViewModel, close: ()->Unit) {
    var name by remember { mutableStateOf(current?.name.orEmpty()) }
    var type by remember { mutableStateOf(current?.type ?: AccountType.CASH) }
    var currency by remember { mutableStateOf(current?.currency ?: ledger.baseCurrency) }
    var opening by remember { mutableStateOf(current?.let { Money.input(it.openingMinor,it.currency) } ?: "0") }
    var included by remember { mutableStateOf(current?.includeInTotal ?: true) }
    var statistics by remember { mutableStateOf(current?.includeInStats ?: true) }
    var archived by remember { mutableStateOf(current?.archived ?: false) }
    var error by remember { mutableStateOf("") }
    var delete by remember { mutableStateOf(false) }
    val used=current!=null && (ledger.entries.any{it.accountId==current.id || it.targetAccountId==current.id} || ledger.debts.any{it.accountId==current.id} || ledger.schedules.any{it.template.accountId==current.id || it.template.targetAccountId==current.id})
    Form(if(current==null)"Новый счёт" else "Настройки счёта",close,busy,onSave={
        runCatching {
            require(name.isNotBlank()){"Введите название"}
            val a=Account(current?.id ?: newId(),name.trim(),currency,type,Money.parse(opening,currency),archived,included,statistics,current?.sortOrder ?: ((ledger.accounts.maxOfOrNull{it.sortOrder} ?: -1)+1))
            vm.change({ l->
                require(l.accounts.none{it.id!=a.id && it.name.equals(a.name,true)}){"Счёт с таким названием уже существует"}
                if(current!=null && currency!=current.currency) require(l.entries.none{it.accountId==current.id || it.targetAccountId==current.id} && l.debts.none{it.accountId==current.id} && l.schedules.none{it.template.accountId==current.id || it.template.targetAccountId==current.id})
                if(a.archived && current?.archived!=true) {
                    require(l.schedules.none{it.enabled && (it.template.accountId==a.id || it.template.targetAccountId==a.id)}){"Сначала приостановите регулярные операции этого счёта"}
                    require(l.entries.none{it.status==EntryStatus.PLANNED && (it.accountId==a.id || it.targetAccountId==a.id)}){"Сначала обработайте запланированные операции"}
                    require(l.debts.none{it.accountId==a.id && Finance.debtRemaining(l,it)>0}){"Сначала погасите задолженность на этом счёте"}
                }
                l.copy(accounts=l.accounts.filterNot{it.id==a.id}+a)
            },close)
        }.onFailure{error=it.message ?: "Проверьте данные"}
    }) {
        Field("Название",name,{name=it})
        Choice("Тип",type,AccountType.entries,{it.label()}){type=it}
        if(used)Text("Валюта: $currency. Её нельзя менять после появления операций.")
        else Choice("Валюта",currency,allCurrencies,{it}){currency=it;opening="0"}
        Field("Начальный остаток",opening,{opening=it},true,supporting="Не входит в доходы или расходы. Для кредитного счёта можно указать отрицательное значение.")
        Toggle("Включать в общий баланс",included,{included=it})
        Toggle("Включать в отчёты и бюджеты",statistics,{statistics=it},"Исключение не изменяет операции и остаток счёта.")
        if(current!=null)Toggle("Архивировать",archived,{archived=it},"История и остаток сохраняются. Архивный счёт скрыт в формах новых операций.")
        if(current!=null&&!used)TextButton(onClick={delete=true}){Text("Удалить счёт",color=MaterialTheme.colorScheme.error)}
        if(error.isNotEmpty())Warning(error)
    }
    if(delete&&current!=null)Confirm("Удалить счёт?","Начальный остаток будет удалён.",{delete=false},{vm.change({l->l.copy(accounts=l.accounts.filterNot{it.id==current.id})},close)},busy)
}

private val categoryColors=listOf(0xFF218C75L,0xFF4677BDL,0xFF9469B6L,0xFFBF646CL,0xFFB98B40L,0xFF657984L,0xFF54864DL,0xFFB25C32L)
private val categoryIcons=listOf("•","🛒","🚇","🏠","✚","★","₽","☕","🎁","✈","📚","🎮","👕","🐾","💼","💳","🚗","💡","🍽","🏋")
@Composable fun CategoryEditor(ledger: Ledger, current: Category?, busy: Boolean, vm: AppViewModel, close: ()->Unit) {
    var name by remember { mutableStateOf(current?.name.orEmpty()) }
    var income by remember { mutableStateOf(current?.income ?: false) }
    var parent by remember { mutableStateOf(current?.parentId.orEmpty()) }
    var icon by remember { mutableStateOf(current?.icon ?: "•") }
    var color by remember { mutableStateOf(current?.color ?: categoryColors.first()) }
    var archived by remember { mutableStateOf(current?.archived ?: false) }
    var error by remember { mutableStateOf("") }
    var delete by remember { mutableStateOf(false) }
    val used=current!=null && (ledger.entries.any{e->e.splits.any{it.categoryId==current.id}} || ledger.categories.any{it.parentId==current.id} || ledger.budgets.any{it.categoryId==current.id} || ledger.schedules.any{it.template.splits.any{sp->sp.categoryId==current.id}})
    val parents=listOf("")+ledger.categories.filter{it.id!=current?.id && it.income==income && it.parentId==null && !it.archived}.map{it.id}
    Form(if(current==null)"Новая категория" else "Изменить категорию",close,busy,onSave={
        runCatching {
            require(name.isNotBlank()){"Введите название"}
            val c=Category(current?.id ?: newId(),name.trim(),income,parent.ifBlank{null},color,icon,archived)
            vm.change({ l->l.copy(categories=l.categories.filterNot{it.id==c.id}+c) },close)
        }.onFailure{error=it.message ?: "Проверьте данные"}
    }) {
        Field("Название",name,{name=it})
        if(!used)Chips(listOf(false,true),income,{if(it)"Доход" else "Расход"}){income=it;parent=""}
        else Text(if(income)"Категория доходов" else "Категория расходов")
        if(current==null || ledger.categories.none{it.parentId==current.id})
            Choice("Родительская категория",parent,parents,{if(it.isEmpty())"Нет (основная)" else ledger.categoryName(it)}){parent=it}
        Choice("Значок",icon,categoryIcons,{it}){icon=it}
        Text("Цвет",style=MaterialTheme.typography.labelLarge)
        categoryColors.chunked(4).forEach { row->
            Row(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                row.forEach { c->
                    FilterChip(selected=color==c,onClick={color=c},label={Text("●",color=Color(c))},modifier=Modifier.weight(1f))
                }
            }
        }
        if(current!=null)Toggle("Архивировать",archived,{archived=it},"Старые операции сохранят категорию.")
        if(current!=null&&!used)TextButton(onClick={delete=true}){Text("Удалить категорию",color=MaterialTheme.colorScheme.error)}
        if(error.isNotEmpty())Warning(error)
    }
    if(delete&&current!=null)Confirm("Удалить категорию?","Категория не используется в операциях.",{delete=false},{vm.change({l->l.copy(categories=l.categories.filterNot{it.id==current.id})},close)},busy)
}

private data class SplitDraft(val category: String,val amount: String)

/** In-memory form state retained across a temporary lock, never written to saved-state bundles. */
class EntryDraft {
    private val states=mutableMapOf<String,MutableState<*>>()
    @Suppress("UNCHECKED_CAST")
    fun <T> state(key:String,initial:()->T):MutableState<T> =
        states.getOrPut(key){mutableStateOf(initial())} as MutableState<T>
    fun attach(receipt:Receipt) {
        val ids=state("receiptIds"){emptyList<String>()}
        if(ids.value.size>=4)return
        val pending=state("pendingReceipts"){emptyList<Receipt>()}
        pending.value=pending.value+receipt
        ids.value=ids.value+receipt.id
    }
}
@Composable fun EntryEditor(ledger: Ledger, settings: Settings, vm: AppViewModel, busy: Boolean, initialKind: Kind, current: Entry?=null, schedule: Schedule?=null, recurring: Boolean=false, close: ()->Unit, forcePlanned: Boolean=false, draft: EntryDraft, chooseReceipt: ()->Unit, initialDay:Long?=null) {
    // Keep the snapshot from opening the draft, even if the ledger emits a newer entry.
    val original by draft.state("originalEntry") { schedule?.template ?: current }
    val originalSchedule by draft.state("originalSchedule"){schedule}
    val active=ledger.accounts.filter{!it.archived || it.id==original?.accountId || it.id==original?.targetAccountId}.sortedWith(compareBy<Account>{it.sortOrder}.thenBy{it.name})
    val defaultAccount=active.find{it.id==original?.accountId} ?: active.find{it.id==settings.lastAccount} ?: active.firstOrNull()
    var accountId by draft.state("accountId") { defaultAccount?.id.orEmpty() }
    var kind by draft.state("kind") { original?.kind ?: initialKind }
    var amount by draft.state("amount") { original?.let{Money.input(it.minor,ledger.accounts.first{a->a.id==it.accountId}.currency)}.orEmpty() }
    var day by draft.state("day") { iso(schedule?.nextDay ?: original?.day ?: initialDay ?: today()) }
    var time by draft.state("time") { original?.timeMinutes?.let{"%02d:%02d".format(it/60,it%60)} ?: LocalTime.now().withSecond(0).withNano(0).toString() }
    var note by draft.state("note") { original?.note.orEmpty() }
    var tags by draft.state("tags") { original?.tags?.joinToString(", ").orEmpty() }
    var essential by draft.state("essential") { original?.essential ?: false }
    var planned by draft.state("planned") { original?.status==EntryStatus.PLANNED || recurring || forcePlanned }
    var autoPostPlanned by draft.state("autoPostPlanned") { original?.autoPostPlanned ?: false }
    var targetId by draft.state("targetId") { original?.targetAccountId.orEmpty() }
    var targetAmount by draft.state("targetAmount") { original?.let{e->e.targetMinor?.let{m->Money.input(m,ledger.accounts.first{a->a.id==e.targetAccountId}.currency)}}.orEmpty() }
    var explicitRate by draft.state("explicitRate") { original?.rate.orEmpty() }
    var splitMode by draft.state("splitMode") { (original?.splits?.size ?: 0)>1 }
    val initialCategory=ledger.categories.firstOrNull{it.income==(kind==Kind.INCOME) && !it.archived}?.id.orEmpty()
    var splitDrafts by draft.state("splitDrafts") { original?.splits?.map{SplitDraft(it.categoryId,Money.input(it.minor,defaultAccount?.currency ?: ledger.baseCurrency))}
        ?.takeIf{it.isNotEmpty()} ?: listOf(SplitDraft(initialCategory,"")) }
    var frequency by draft.state("frequency") { schedule?.frequency ?: Frequency.MONTHLY }
    var interval by draft.state("interval") { schedule?.interval?.toString() ?: "1" }
    var weekdays by draft.state("weekdays") { schedule?.weekdays.orEmpty().toSet() }
    var alternate by draft.state("alternate") { schedule?.alternateWeekdays.orEmpty().toSet() }
    var useAlternate by draft.state("useAlternate") { schedule?.alternateWeekdays?.isNotEmpty()==true }
    var monthDays by draft.state("monthDays") { schedule?.monthDays?.joinToString(",").orEmpty() }
    var endDay by draft.state("endDay") { schedule?.endDay?.let(::iso).orEmpty() }
    var autoPost by draft.state("autoPost") { schedule?.autoPost ?: false }
    var enabled by draft.state("enabled") { schedule?.enabled ?: true }
    var pendingReceipts by draft.state("pendingReceipts") { emptyList<Receipt>() }
    var receiptIds by draft.state("receiptIds") { original?.receiptIds ?: emptyList() }
    var error by draft.state("error") { "" }
    var advanced by draft.state("advanced") { original!=null || recurring || forcePlanned }
    var deleteSchedule by draft.state("deleteSchedule") { false }
    val account=ledger.accounts.find{it.id==accountId}
    val target=ledger.accounts.find{it.id==targetId}
    val cats=ledger.categories.filter{it.income==(kind==Kind.INCOME) && (!it.archived || splitDrafts.any{d->d.category==it.id})}
    val currentRate=account?.let{a->runCatching{Finance.rate(ledger,a.currency,parseDay(day))}.getOrNull()}
    val rateRequired=account!=null && account.currency!=ledger.baseCurrency
    Form(if(recurring)"Регулярная операция" else if(current==null)"Новая операция" else "Редактировать операцию",close,busy,onSave={
        runCatching {
            val a=requireNotNull(account){"Сначала создайте счёт"}
            val minor=Money.parse(amount,a.currency);require(minor>0){"Сумма должна быть положительной"}
            val date=parseDay(day)
            val t=LocalTime.parse(time)
            val split=if(kind==Kind.TRANSFER)emptyList() else if(splitMode)splitDrafts.map{it.category to Money.parse(it.amount,a.currency)} else listOf(splitDrafts.first().category to minor)
            if(kind!=Kind.TRANSFER)require(Money.sum(split.map{it.second})==minor){"Сумма распределения должна совпадать с суммой операции"}
            val toMinor=if(kind!=Kind.TRANSFER)null else {
                val dest=requireNotNull(target){"Выберите счёт получателя"}
                if(dest.currency==a.currency)minor else Money.parse(targetAmount,dest.currency)
            }
            val id=original?.id ?: newId()
            val plan=recurring || planned || date>today()
            val rate=explicitRate.trim().replace(',','.').ifBlank{null}
            val receipts=pendingReceipts.filter{it.id in receiptIds}
            vm.change({ old->
                if(!recurring&&original!=null)require(old.entries.find{it.id==original?.id}==original) {
                    "Операция изменилась. Закройте редактор и откройте её снова."
                }
                val prepared=old.copy(receipts=old.receipts+receipts)
                val e=Finance.makeEntry(prepared,kind,a.id,minor,date,split,note,tags.split(',').map{it.trim()},
                    targetId=targetId.takeIf{kind==Kind.TRANSFER},targetMinor=toMinor,
                    status=if(plan)EntryStatus.PLANNED else EntryStatus.POSTED,explicitRate=rate,essential=essential,
                    timeMinutes=t.hour*60+t.minute,id=id,receiptIds=receiptIds,allowArchived=original!=null,autoPostPlanned=plan&&!recurring&&autoPostPlanned)
                if(recurring) {
                    if(originalSchedule!=null)require(old.schedules.find{it.id==originalSchedule?.id}==originalSchedule){"Расписание изменилось. Откройте его снова."}
                    val s=Schedule(schedule?.id ?: newId(),e,date,frequency,interval.toInt(),
                        endDay.trim().takeIf{it.isNotEmpty()}?.let(::parseDay),autoPost,enabled,
                        if(schedule!=null && schedule.nextDay==date)schedule.anchorDay else LocalDate.ofEpochDay(date).dayOfMonth,
                        if(frequency==Frequency.WEEKLY)weekdays.sorted() else emptyList(),
                        if(frequency==Frequency.WEEKLY&&useAlternate&&interval.toInt()==2)alternate.sorted() else emptyList(),
                        if(frequency==Frequency.MONTHLY)monthDays.split(',').map{it.trim()}.filter{it.isNotEmpty()}.map{it.toInt()}.distinct().sorted() else emptyList(),
                        if(schedule!=null&&schedule.nextDay==date)schedule.anchorEpochDay else date)
                    require(s.endDay==null || s.endDay>=s.nextDay){"Дата окончания раньше начала"}
                    prepared.copy(schedules=prepared.schedules.filterNot{it.id==s.id}+s)
                } else Finance.putEntry(prepared,e)
            }) { vm.rememberAccount(a.id); close() }
        }.onFailure{error=it.message ?: "Проверьте данные"}
    }) {
        if(active.isEmpty())Warning("Создайте хотя бы один счёт в разделе «Ещё».")
        Chips(listOf(Kind.EXPENSE,Kind.INCOME,Kind.TRANSFER),kind,{it.label()}){ k->
            if(k!=kind){kind=k;splitDrafts=listOf(SplitDraft(ledger.categories.firstOrNull{it.income==(k==Kind.INCOME)&&!it.archived}?.id.orEmpty(),""));splitMode=false}
        }
        Choice("Счёт",accountId,active.map{it.id},{ledger.accountName(it)}){id->if(id!=accountId){accountId=id;explicitRate="";if(targetId==id)targetId=""}}
        Field("Сумма (${account?.currency ?: ledger.baseCurrency})",amount,{amount=it;error=""},true)
        if(error.isNotEmpty())Warning(error)
        if(kind==Kind.TRANSFER) {
            Choice("На счёт",targetId,active.filter{it.id!=accountId}.map{it.id},{ledger.accountName(it)}){targetId=it}
            if(account!=null && target!=null && account.currency!=target.currency)Field("Получено (${target.currency})",targetAmount,{targetAmount=it},true)
        } else {
            val currency=account?.currency ?: ledger.baseCurrency
            val totalMinor=runCatching{Money.parse(amount,currency)}.getOrNull()
            val allocated=runCatching{Money.sum(splitDrafts.map{if(it.amount.isBlank())0L else Money.parse(it.amount,currency).also{value->require(value>=0)}})}.getOrNull()
            val remainder=if(totalMinor!=null&&allocated!=null)runCatching{Money.sub(totalMinor,allocated)}.getOrNull() else null
            Toggle("Разделить по категориям",splitMode,{value->
                splitMode=value
                splitDrafts=if(value)listOf(splitDrafts.first().copy(amount=amount)) else listOf(splitDrafts.first().copy(amount=""))
            })
            if(splitMode) {
                if(remainder==null)Text("Введите корректные суммы для расчёта остатка.")
                else if(remainder<0)Warning("Распределено больше суммы операции")
                else Text("Осталось распределить: "+Money.format(remainder,currency),style=MaterialTheme.typography.titleMedium)
            }
            splitDrafts.forEachIndexed { i,draft->
                CategoryChoice(if(splitMode)"Категория ${i+1}" else "Категория",draft.category,cats.filter{c->c.id==draft.category || (!c.archived && splitDrafts.none{it.category==c.id})},ledger){id->
                    splitDrafts=splitDrafts.mapIndexed{j,d->if(j==i)d.copy(category=id) else d}}
                if(splitMode) {
                    Field("Часть суммы ${i+1}",draft.amount,{v->splitDrafts=splitDrafts.mapIndexed{j,d->if(i==j)d.copy(amount=v) else d}},true)
                    if(remainder!=null&&remainder>0&&draft.amount.isBlank())TextButton(onClick={splitDrafts=splitDrafts.mapIndexed{j,d->if(i==j)d.copy(amount=Money.input(remainder,currency)) else d}}){Text("Заполнить остатком")}
                    if(splitDrafts.size>1)TextButton(onClick={splitDrafts=splitDrafts.filterIndexed{j,_->j!=i}}){Text("Удалить часть")}
                }
            }
            if(splitMode)OutlinedButton(onClick={splitDrafts=splitDrafts+SplitDraft(cats.firstOrNull{c->!c.archived&&splitDrafts.none{it.category==c.id}}?.id.orEmpty(),"")},enabled=cats.any{c->!c.archived&&splitDrafts.none{it.category==c.id}}){Text("Добавить категорию")}
        }
        if(!recurring && !forcePlanned) TextButton(onClick={advanced=!advanced}) { Text(if(advanced)"Свернуть детали" else "Дата, комментарий, теги и чек") }
        if(advanced) {
            DateField("Дата",day,{day=it})
            TimeField("Время",time,{time=it})
        }
        if(rateRequired)Field("Курс: ${ledger.baseCurrency} за 1 ${account?.currency}",explicitRate,{explicitRate=it},true,
            supporting=currentRate?.let{"Сохранённый курс: $it. Пустое поле использует его."} ?: "Укажите курс или загрузите его в настройках.")
        if(advanced) {
            Field("Комментарий",note,{note=it},singleLine=false)
            Field("Теги",tags,{tags=it},supporting="Через запятую")
            if(kind==Kind.EXPENSE)Toggle("Обязательная трата",essential,{essential=it})
            if(!recurring)Toggle("Запланировать",planned,{planned=it},"До проведения операция не меняет фактический баланс.")
            if(!recurring&&(planned||runCatching{parseDay(day)>today()}.getOrDefault(false)))Toggle("Проводить автоматически",autoPostPlanned,{autoPostPlanned=it},"В назначенную дату и время, когда Android выполнит фоновую задачу. Для архивных счетов автоматическое проведение недоступно.")
        }
        if(recurring) {
            Choice("Повторять",frequency,Frequency.entries,{it.label()}){frequency=it}
            Field("Интервал",interval,{interval=it},true,supporting="Каждые N дней, недель, месяцев или лет")
            if(frequency==Frequency.WEEKLY){
                Text("Дни недели (пусто — день первой операции)",style=MaterialTheme.typography.labelLarge)
                WeekdayChoices(weekdays){weekdays=it}
                if(interval.trim()=="2"&&weekdays.isNotEmpty()){
                    Toggle("Разные дни в двух неделях",useAlternate,{useAlternate=it})
                    if(useAlternate)WeekdayChoices(alternate){alternate=it}
                }
            }
            if(frequency==Frequency.MONTHLY)Field("Дни месяца",monthDays,{monthDays=it},supporting="1–31 через запятую; пусто — день первой операции. 31 означает последний день короткого месяца.")
            DateField("Окончание (необязательно)",endDay,{endDay=it})
            Toggle("Проводить автоматически",autoPost,{autoPost=it},"Иначе появится запланированная операция для подтверждения.")
            Toggle("Активно",enabled,{enabled=it})
            if(schedule!=null)TextButton(onClick={deleteSchedule=true}){Text("Удалить расписание",color=MaterialTheme.colorScheme.error)}
        } else if(advanced) {
            receiptIds.forEach { id->
                val r=(pendingReceipts+ledger.receipts).find{it.id==id}
                if(r!=null)ReceiptPreview(r)
                TextButton(onClick={receiptIds=receiptIds.filterNot{it==id}}){Text("Убрать фотографию")}
            }
            OutlinedButton(onClick=chooseReceipt,enabled=!busy && receiptIds.size<4){Text("Прикрепить фото чека")}
        }
    }
    if(deleteSchedule&&schedule!=null)Confirm("Удалить расписание?","Уже созданные операции останутся.",{deleteSchedule=false},{vm.change({l->l.copy(schedules=l.schedules.filterNot{it.id==schedule.id})},close)},busy)
}

@Composable private fun WeekdayChoices(selected:Set<Int>,onChange:(Set<Int>)->Unit) {
    val labels=listOf("Пн","Вт","Ср","Чт","Пт","Сб","Вс")
    (1..7).toList().chunked(4).forEach { days ->
        Row(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
            days.forEach { day -> FilterChip(selected=day in selected,onClick={onChange(if(day in selected)selected-day else selected+day)},label={Text(labels[day-1])},modifier=Modifier.weight(1f)) }
        }
    }
}

@Composable fun ReceiptPreview(receipt: Receipt) {
    val image by produceState<ImageBitmap?>(null,receipt.id) {
        value=withContext(Dispatchers.IO) {
            runCatching {
                val bytes=Base64.decode(receipt.base64,Base64.DEFAULT)
                val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
                BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
                var sample=1
                while(maxOf(bounds.outWidth,bounds.outHeight)/sample>1400)sample*=2
                BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply{inSampleSize=sample})?.asImageBitmap()
            }.getOrNull()
        }
    }
    if(image!=null)Image(requireNotNull(image),"Фотография чека",Modifier.fillMaxWidth().heightIn(max=240.dp),contentScale=ContentScale.Fit)
    else Text("Фотография чека",style=MaterialTheme.typography.bodySmall)
}

@Composable fun EntryDetails(ledger: Ledger, entry: Entry, vm: AppViewModel,busy:Boolean,close:()->Unit,edit:()->Unit,refund:()->Unit) {
    var confirmDelete by remember { mutableStateOf(false) }
    val account=ledger.accounts.first{it.id==entry.accountId}
    val refunds=ledger.entries.filter{it.originalId==entry.id}
    val remaining=Money.sub(entry.minor,Money.sum(refunds.map{it.minor}))
    Form(ledger.entryName(entry),close,busy,saveLabel="Закрыть",onSave=close) {
        MoneyText(entry.minor,account.currency,large=true)
        Text(entry.kind.label()+" · "+dayLabel(entry.day))
        Text("Счёт: "+account.name)
        if(entry.targetAccountId!=null)Text("Получатель: "+ledger.accountName(entry.targetAccountId))
        if(entry.targetAccountId!=null && entry.targetMinor!=null)Text("Получено: "+Money.format(entry.targetMinor,ledger.accounts.first{it.id==entry.targetAccountId}.currency))
        if(account.currency!=ledger.baseCurrency)Text("Курс: ${entry.rate}; в отчёте: "+Money.format(entry.baseMinor,ledger.baseCurrency))
        if(entry.note.isNotBlank())Text(entry.note)
        if(entry.tags.isNotEmpty())Text(entry.tags.joinToString("  "){ "#$it" })
        entry.splits.forEach{Text(ledger.categoryName(it.categoryId)+": "+Money.format(it.minor,account.currency))}
        entry.receiptIds.forEach{id->ledger.receipts.find{it.id==id}?.let{ReceiptPreview(it)}}
        if(entry.status==EntryStatus.PLANNED) {
            Text("Операция ещё не проведена.",color=MaterialTheme.colorScheme.tertiary)
            if(entry.autoPostPlanned)Text("Включено автоматическое проведение.")
            Button(onClick={vm.change({l->
                val latest=l.entries.find{it.id==entry.id}
                require(latest==entry){"Операция изменилась. Откройте её снова."}
                Finance.putEntry(l,entry.copy(status=EntryStatus.POSTED,autoPostPlanned=false))
            },close)},enabled=!busy && entry.day<=today(),modifier=Modifier.fillMaxWidth()){Text("Подтвердить операцию")}
            if(entry.day>today())Text("Подтвердить можно в дату операции. Для досрочного проведения сначала измените дату.")
        }
        if(entry.kind !in setOf(Kind.LOAN_IN,Kind.LOAN_OUT) && refunds.isEmpty())
            OutlinedButton(onClick=edit,modifier=Modifier.fillMaxWidth()){Text("Редактировать")}
        if(entry.kind==Kind.EXPENSE && entry.status==EntryStatus.POSTED && remaining>0) {
            Text("Доступно к возврату: "+Money.format(remaining,account.currency))
            OutlinedButton(onClick=refund,modifier=Modifier.fillMaxWidth()){Text("Оформить возврат")}
        }
        if(refunds.isNotEmpty()) {
            Text("Связанные возвраты",style=MaterialTheme.typography.titleMedium)
            refunds.forEach{Text(dayLabel(it.day)+" · "+Money.format(it.minor,account.currency))}
        }
        OutlinedButton(onClick={confirmDelete=true},enabled=refunds.isEmpty()&&!busy&&entry.kind !in setOf(Kind.LOAN_IN,Kind.LOAN_OUT),modifier=Modifier.fillMaxWidth()){Text("Удалить операцию",color=MaterialTheme.colorScheme.error)}
    }
    if(confirmDelete)Confirm("Удалить операцию?","Баланс и отчёты будут пересчитаны.",{confirmDelete=false},{vm.change({l->Finance.deleteEntry(l,entry.id)},close)},busy)
}

@Composable fun RefundEditor(ledger: Ledger, entry: Entry, vm: AppViewModel,busy:Boolean,close:()->Unit,current:Entry?=null) {
    val account=ledger.accounts.first{it.id==entry.accountId}
    val left=Money.sub(entry.minor,Money.sum(ledger.entries.filter{it.originalId==entry.id&&it.id!=current?.id}.map{it.minor}))
    var amount by remember { mutableStateOf(Money.input(current?.minor ?: left,account.currency)) }
    var day by remember { mutableStateOf(iso(current?.day ?: today())) }
    var note by remember { mutableStateOf(current?.note.orEmpty()) }
    var error by remember { mutableStateOf("") }
    val refundable=remember(ledger,entry.id,current?.id){Finance.refundableSplits(ledger,entry.id,current?.id)}
    var byCategory by remember { mutableStateOf(current!=null && entry.splits.size>1) }
    var parts by remember { mutableStateOf(refundable.associate{split->split.categoryId to (current?.splits?.find{it.categoryId==split.categoryId}?.let{Money.input(it.minor,account.currency)} ?: "")}) }
    val categoryAmounts=runCatching{parts.mapNotNull{(id,text)->if(text.isBlank())null else (id to Money.parse(text,account.currency)).takeIf{it.second!=0L}}}
    val categoryTotal=categoryAmounts.mapCatching{Money.sum(it.map{part->part.second})}.getOrNull()
    Form("Возврат покупки",close,busy,onSave={
        runCatching {
            val selected=if(byCategory)categoryAmounts.getOrThrow() else null
            val minor=if(selected!=null)Money.sum(selected.map{it.second}) else Money.parse(amount,account.currency)
            val date=parseDay(day)
            require(date<=today()){"Дата возврата не может быть в будущем"}
            require(minor>0){"Введите положительную сумму возврата"}
            if(selected!=null)require(selected.all{(id,value)->value>0 && refundable.any{it.categoryId==id && value<=it.minor}}){"Проверьте доступные суммы по категориям"}
            vm.change({l->
                require(l.entries.find{it.id==entry.id}==entry){"Покупка изменилась. Откройте её снова."}
                if(current!=null)require(l.entries.find{it.id==current.id}==current){"Возврат изменился. Откройте его снова."}
                Finance.putEntry(l,Finance.refund(l,entry.id,minor,date,note,current?.id ?: newId(),categoryAmounts=selected))
            },close)
        }.onFailure{error=it.message ?: "Проверьте сумму"}
    }) {
        Text(ledger.entryName(entry));Text("Максимум: "+Money.format(left,account.currency))
        if(entry.splits.size>1)Toggle("Выбрать категории возврата",byCategory,{byCategory=it},"Укажите, какие части покупки были возвращены.")
        if(byCategory) {
            refundable.forEach { split->
                Field(ledger.categoryName(split.categoryId),parts[split.categoryId].orEmpty(),{parts=parts+(split.categoryId to it)},true,supporting="Доступно: "+Money.format(split.minor,account.currency))
            }
            if(categoryTotal==null)Warning("Проверьте суммы по категориям")
            else Text("Всего к возврату: "+Money.format(categoryTotal,account.currency),style=MaterialTheme.typography.titleMedium)
        } else Field("Сумма возврата",amount,{amount=it},true)
        DateField("Дата",day,{day=it})
        Field("Комментарий",note,{note=it})
        Text("Возврат уменьшает расходы в дату получения денег и использует исторический курс покупки. Без ручного выбора сумма распределяется автоматически по категориям исходной покупки.")
        if(error.isNotEmpty())Warning(error)
    }
}
