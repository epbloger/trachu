package ru.epbloger.trachu.ui

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
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

@Composable fun AccountEditor(ledger: Ledger, current: Account?, busy: Boolean, vm: AppViewModel, close: ()->Unit, draft:EntryDraft) {
    var name by draft.state("name"){current?.name.orEmpty()}
    var type by draft.state("type"){current?.type ?: AccountType.CASH}
    var currency by draft.state("currency"){current?.currency ?: ledger.baseCurrency}
    var opening by draft.state("opening"){current?.let { Money.input(it.openingMinor,it.currency) } ?: "0"}
    var included by draft.state("included"){current?.includeInTotal ?: true}
    var statistics by draft.state("statistics"){current?.includeInStats ?: true}
    var archived by draft.state("archived"){current?.archived ?: false}
    var error by draft.state("error"){""}
    var delete by draft.state("delete"){false}
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
        else CurrencyPicker("Валюта",currency){if(it!=currency){currency=it;opening="0"}}
        Field("Начальный остаток",opening,{opening=it},true,supporting="Не входит в доходы или расходы. Для кредитного счёта можно указать отрицательное значение.")
        Toggle("Включать в общий баланс",included,{included=it})
        Toggle("Включать в отчёты и бюджеты",statistics,{statistics=it},"Исключение не изменяет операции и остаток счёта.")
        if(current!=null)Toggle("Архивировать",archived,{archived=it},"История и остаток сохраняются. Архивный счёт скрыт в формах новых операций.")
        if(current!=null&&!used)TextButton(onClick={delete=true}){Text("Удалить счёт",color=MaterialTheme.colorScheme.error)}
        if(error.isNotEmpty())Warning(error)
    }
    if(delete&&current!=null)Confirm("Удалить счёт?","Начальный остаток будет удалён.",{delete=false},{vm.change({l->l.copy(accounts=l.accounts.filterNot{it.id==current.id})},close)},busy)
}

// UI default for newly created categories only. The serialized model retains its
// historical default for old backups that omit the color field.
private const val newCategoryColor=0xFF6143CCL
private val categoryColors=listOf(newCategoryColor,0xFF218C75L,0xFF4677BDL,0xFF9469B6L,0xFFBF646CL,0xFFB98B40L,0xFF657984L,0xFF54864DL,0xFFB25C32L)
private val categoryIcons=listOf("•","🛒","🚇","🏠","✚","★","₽","☕","🎁","✈","📚","🎮","👕","🐾","💼","💳","🚗","💡","🍽","🏋")
@Composable fun CategoryEditor(ledger: Ledger, current: Category?, busy: Boolean, vm: AppViewModel, close: ()->Unit, draft:EntryDraft) {
    var name by draft.state("name"){current?.name.orEmpty()}
    var income by draft.state("income"){current?.income ?: false}
    var parent by draft.state("parent"){current?.parentId.orEmpty()}
    var icon by draft.state("icon"){current?.icon ?: "•"}
    var color by draft.state("color"){current?.color ?: newCategoryColor}
    var archived by draft.state("archived"){current?.archived ?: false}
    var error by draft.state("error"){""}
    var delete by draft.state("delete"){false}
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
        categoryColors.chunked(3).forEach { row->
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

/** In-memory form state retained across Activity recreation and a temporary lock,
 * never written to saved-state bundles. */
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
internal fun canRepeatEntry(ledger:Ledger,entry:Entry):Boolean =
    entry.status==EntryStatus.POSTED && entry.kind in setOf(Kind.EXPENSE,Kind.INCOME,Kind.TRANSFER) &&
        ledger.accounts.any{it.id==entry.accountId&&!it.archived} &&
        (entry.kind!=Kind.TRANSFER || ledger.accounts.any{it.id==entry.targetAccountId&&!it.archived}) &&
        entry.splits.all{sp->ledger.categories.any{it.id==sp.categoryId&&!it.archived}}

/** A new draft, never an automatic posting or a mutation of the source entry. */
internal fun repeatEntryDraft(ledger:Ledger,entry:Entry):EntryDraft {
    require(canRepeatEntry(ledger,entry))
    val currency=ledger.accounts.first{it.id==entry.accountId}.currency
    return EntryDraft().apply {
        state("accountId"){entry.accountId}
        state("kind"){entry.kind}
        state("amount"){Money.input(entry.minor,currency)}
        state("note"){entry.note}
        state("tags"){entry.tags.joinToString(", ")}
        state("essential"){entry.essential}
        state("advanced"){true}
        state("targetId"){entry.targetAccountId.orEmpty()}
        state("targetAmount"){entry.targetMinor?.let{Money.input(it,ledger.accounts.first{a->a.id==entry.targetAccountId}.currency)}.orEmpty()}
        if(entry.splits.isNotEmpty()) {
            state("splitMode"){entry.splits.size>1}
            state("splitDrafts"){entry.splits.map{SplitDraft(it.categoryId,Money.input(it.minor,currency))}}
        }
        // New date/time, rate lookup, ID and no receipts or scheduled auto-posting.
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
    var day by draft.state("day") { iso(originalSchedule?.nextDay ?: original?.day ?: initialDay ?: today()) }
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
    var frequency by draft.state("frequency") { originalSchedule?.frequency ?: Frequency.MONTHLY }
    var interval by draft.state("interval") { originalSchedule?.interval?.toString() ?: "1" }
    var weekdays by draft.state("weekdays") { originalSchedule?.weekdays.orEmpty().toSet() }
    var alternate by draft.state("alternate") { originalSchedule?.alternateWeekdays.orEmpty().toSet() }
    var useAlternate by draft.state("useAlternate") { originalSchedule?.alternateWeekdays?.isNotEmpty()==true }
    var monthDays by draft.state("monthDays") { originalSchedule?.monthDays?.joinToString(",").orEmpty() }
    var endDay by draft.state("endDay") { originalSchedule?.endDay?.let(::iso).orEmpty() }
    var autoPost by draft.state("autoPost") { originalSchedule?.autoPost ?: false }
    var enabled by draft.state("enabled") { originalSchedule?.enabled ?: true }
    var pendingReceipts by draft.state("pendingReceipts") { emptyList<Receipt>() }
    var receiptIds by draft.state("receiptIds") { original?.receiptIds ?: emptyList() }
    var error by draft.state("error") { "" }
    var advanced by draft.state("advanced") { original!=null || recurring || forcePlanned }
    var deleteSchedule by draft.state("deleteSchedule") { false }
    var categoryCreationIndex by draft.state("categoryCreationIndex") { -1 }
    var categoryCreationName by draft.state("categoryCreationName") { "" }
    var conversionOpen by draft.state("conversionOpen") { false }
    var conversionCurrency by draft.state("conversionCurrency") { "" }
    var conversionAmount by draft.state("conversionAmount") { "" }
    var conversionManual by draft.state("conversionManual") { false }
    var conversionRate by draft.state("conversionRate") { "" }
    var pendingAccountId by draft.state("conversionAccountId") { "" }
    val account=ledger.accounts.find{it.id==accountId}
    val target=ledger.accounts.find{it.id==targetId}
    val cats=ledger.categories.filter{it.income==(kind==Kind.INCOME) && (!it.archived || splitDrafts.any{d->d.category==it.id})}
    val currentRate=account?.let{a->runCatching{Finance.rate(ledger,a.currency,parseDay(day))}.getOrNull()}
    val rateRequired=account!=null && account.currency!=ledger.baseCurrency
    fun openConversion(from:String, value:String="", nextAccountId:String="") {
        conversionCurrency=from
        conversionAmount=value
        conversionManual=false
        conversionRate=""
        pendingAccountId=nextAccountId
        conversionOpen=true
    }
    fun cancelConversion(){
        conversionOpen=false
        pendingAccountId=""
    }
    fun selectAccount(id:String) {
        val changedCurrency=account?.currency!=active.first{it.id==id}.currency
        if(changedCurrency){
            amount=""
            splitDrafts=splitDrafts.map{it.copy(amount="")}
            explicitRate=""
            targetAmount=""
        }
        accountId=id
        error=""
        if(targetId==id){targetId="";targetAmount=""}
    }
    Form(if(recurring)"Регулярная операция" else if(current==null)"Новая операция" else "Редактировать операцию",close,busy,spacing=6,compact=true,onSave={
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
                    val previous=originalSchedule
                    val s=Schedule(previous?.id ?: newId(),e,date,frequency,interval.toInt(),
                        endDay.trim().takeIf{it.isNotEmpty()}?.let(::parseDay),autoPost,enabled,
                        if(previous!=null && previous.nextDay==date)previous.anchorDay else LocalDate.ofEpochDay(date).dayOfMonth,
                        if(frequency==Frequency.WEEKLY)weekdays.sorted() else emptyList(),
                        if(frequency==Frequency.WEEKLY&&useAlternate&&interval.toInt()==2)alternate.sorted() else emptyList(),
                        if(frequency==Frequency.MONTHLY)monthDays.split(',').map{it.trim()}.filter{it.isNotEmpty()}.map{it.toInt()}.distinct().sorted() else emptyList(),
                        if(previous!=null&&previous.nextDay==date)previous.anchorEpochDay else date)
                    require(!s.enabled || s.endDay==null || s.endDay>=s.nextDay){"Дата окончания раньше следующего повтора. Измените дату или отключите расписание."}
                    prepared.copy(schedules=prepared.schedules.filterNot{it.id==s.id}+s)
                } else Finance.putEntry(prepared,e)
            }) { vm.rememberAccount(a.id); close() }
        }.onFailure{error=it.message ?: "Проверьте данные"}
    }) {
        if(active.isEmpty())Warning("Создайте хотя бы один счёт в разделе «Счета».")
        Chips(listOf(Kind.EXPENSE,Kind.INCOME,Kind.TRANSFER),kind,{it.label()}){ k->
            if(k!=kind){kind=k;splitDrafts=listOf(SplitDraft(ledger.categories.firstOrNull{it.income==(k==Kind.INCOME)&&!it.archived}?.id.orEmpty(),""));splitMode=false}
        }
        Choice("Счёт",accountId,active.map{it.id},{id->active.find{it.id==id}?.let{"${it.name} · ${it.currency}"} ?: "Выбрать счёт"},compact=true){id->
            if(id!=accountId){
                val previousCurrency=account?.currency
                val nextCurrency=active.first{it.id==id}.currency
                if(previousCurrency!=null&&previousCurrency!=nextCurrency&&amount.isNotBlank())
                    openConversion(previousCurrency,amount,id)
                else selectAccount(id)
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            @Composable fun AmountInput(modifier:Modifier=Modifier) {
                Field("Сумма (${account?.currency ?: ledger.baseCurrency})",amount,{amount=it;error=""},true,modifier=modifier)
            }
            @Composable fun ConvertButton() {
                if(account!=null)TextButton(onClick={
                    openConversion(if(account.currency=="USD")"EUR" else "USD")
                },enabled=!busy,modifier=Modifier.testTag("entry-convert")) {Text("Другая валюта")}
            }
            if(maxWidth>=300.dp&&LocalDensity.current.fontScale<=1.2f) {
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)){
                    AmountInput(Modifier.weight(1f));ConvertButton()
                }
            } else Column { AmountInput();ConvertButton() }
        }
        if(error.isNotEmpty())Warning(error)
        DateField("Дата",day,{day=it},supporting=null)
        if(!recurring)Chips(listOf(today(),today()-1),runCatching{parseDay(day)}.getOrDefault(Long.MIN_VALUE),{if(it==today())"Сегодня" else "Вчера"}){day=iso(it)}
        if(!recurring&&!planned&&runCatching{parseDay(day)>today()}.getOrDefault(false))
            Text("Будущая операция будет запланирована.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.tertiary)
        if(kind==Kind.TRANSFER) {
            Choice("На счёт",targetId,active.filter{it.id!=accountId}.map{it.id},{id->active.find{it.id==id}?.let{"${it.name} · ${it.currency}"} ?: "Выбрать счёт"},compact=true){id->
                if(target?.currency!=active.first{it.id==id}.currency)targetAmount=""
                targetId=id
            }
            if(account!=null && target!=null && account.currency!=target.currency)Field("Получено (${target.currency})",targetAmount,{targetAmount=it},true)
        } else {
            val currency=account?.currency ?: ledger.baseCurrency
            val totalMinor=runCatching{Money.parse(amount,currency)}.getOrNull()
            val allocated=runCatching{Money.sum(splitDrafts.map{if(it.amount.isBlank())0L else Money.parse(it.amount,currency).also{value->require(value>=0)}})}.getOrNull()
            val remainder=if(totalMinor!=null&&allocated!=null)runCatching{Money.sub(totalMinor,allocated)}.getOrNull() else null
            if(splitMode) {
                if(remainder==null)Text("Введите корректные суммы для расчёта остатка.")
                else if(remainder<0)Warning("Распределено больше суммы операции")
                else Text("Осталось распределить: "+Money.format(remainder,currency),style=MaterialTheme.typography.titleMedium)
            }
            splitDrafts.forEachIndexed { i,draft->
                CategoryChoice(if(splitMode)"Категория ${i+1}" else "Категория",draft.category,cats.filter{c->c.id==draft.category || (!c.archived && splitDrafts.none{it.category==c.id})},ledger,
                    onCreate=if(busy)null else ({categoryCreationIndex=i;categoryCreationName=""}),quick=!splitMode){id->
                    splitDrafts=splitDrafts.mapIndexed{j,d->if(j==i)d.copy(category=id) else d}}
                if(splitMode) {
                    Field("Часть суммы ${i+1}",draft.amount,{v->splitDrafts=splitDrafts.mapIndexed{j,d->if(i==j)d.copy(amount=v) else d}},true)
                    if(remainder!=null&&remainder>0&&draft.amount.isBlank())TextButton(onClick={splitDrafts=splitDrafts.mapIndexed{j,d->if(i==j)d.copy(amount=Money.input(remainder,currency)) else d}}){Text("Заполнить остатком")}
                    if(splitDrafts.size>1)TextButton(onClick={splitDrafts=splitDrafts.filterIndexed{j,_->j!=i}}){Text("Удалить часть")}
                }
            }
            if(splitMode)OutlinedButton(onClick={splitDrafts=splitDrafts+SplitDraft(cats.firstOrNull{c->!c.archived&&splitDrafts.none{it.category==c.id}}?.id.orEmpty(),"")},enabled=splitDrafts.none{it.category.isBlank()}){Text("Добавить часть")}
            Toggle("Разделить по категориям",splitMode,{value->
                splitMode=value
                splitDrafts=if(value)listOf(splitDrafts.first().copy(amount=amount)) else listOf(splitDrafts.first().copy(amount=""))
            })
        }
        if(!recurring && !forcePlanned) TextButton(onClick={advanced=!advanced}) { Text(if(advanced)"Свернуть детали" else "Комментарий, теги и чек") }
        if(advanced) {
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
            if(originalSchedule!=null)TextButton(onClick={deleteSchedule=true}){Text("Удалить расписание",color=MaterialTheme.colorScheme.error)}
        } else if(advanced) {
            receiptIds.forEach { id->
                val r=(pendingReceipts+ledger.receipts).find{it.id==id}
                if(r!=null)ReceiptPreview(r)
                TextButton(onClick={receiptIds=receiptIds.filterNot{it==id}}){Text("Убрать фотографию")}
            }
            OutlinedButton(onClick=chooseReceipt,enabled=!busy && receiptIds.size<4){Text("Прикрепить фото чека")}
        }
    }
    if(categoryCreationIndex in splitDrafts.indices && kind!=Kind.TRANSFER) {
        val index=categoryCreationIndex
        val income=kind==Kind.INCOME
        val name=categoryCreationName.trim()
        val duplicate=ledger.categories.any{it.income==income && it.parentId==null && it.name.equals(name,true)}
        AlertDialog(
            onDismissRequest={if(!busy)categoryCreationIndex=-1},
            title={Text(if(income)"Новая категория доходов" else "Новая категория расходов")},
            text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
                Field("Название категории",categoryCreationName,{categoryCreationName=it})
                if(duplicate)Warning("Категория с таким названием уже существует.")
                else Text("Категория появится в списке и будет выбрана в этой операции.")
            }},
            confirmButton={Button(onClick={
                val category=Category(name=name,income=income,color=newCategoryColor)
                vm.change({old->
                    require(old.categories.none{it.income==income&&it.parentId==null&&it.name.equals(name,true)}){"Категория с таким названием уже существует"}
                    old.copy(categories=old.categories+category)
                }){
                    splitDrafts=splitDrafts.mapIndexed{j,d->if(j==index)d.copy(category=category.id) else d}
                    categoryCreationIndex=-1
                    categoryCreationName=""
                }
            },enabled=!busy&&name.isNotBlank()&&name.length<=160&&!duplicate,modifier=Modifier.testTag("category-create-confirm")){Text("Создать и выбрать")}},
            dismissButton={TextButton(onClick={categoryCreationIndex=-1},enabled=!busy){Text("Отмена")}}
        )
    }
    if(conversionOpen&&account!=null) {
        val pendingAccount=active.find{it.id==pendingAccountId}
        val destination=pendingAccount?.currency ?: account.currency
        val quote=runCatching{CurrencyConversion.quote(ledger,conversionCurrency,destination,parseDay(day))}.getOrNull()
        val result=runCatching{
            val source=Money.parse(conversionAmount,conversionCurrency)
            require(source>0){"Введите положительную сумму"}
            val converted=when {
                conversionCurrency==destination->source
                conversionManual->CurrencyConversion.convert(source,conversionCurrency,destination,CurrencyConversion.manualRate(conversionRate))
                else->CurrencyConversion.convertStored(ledger,source,conversionCurrency,destination,parseDay(day))
            }
            converted.also{
                require(it>0){"После округления сумма равна нулю"}
            }
        }
        AlertDialog(
            onDismissRequest={cancelConversion()},
            title={Text("Пересчитать в $destination")},
            text={Column(Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){
                if(pendingAccountId.isNotBlank())Text("Исходная валюта: $conversionCurrency")
                else CurrencyPicker("Исходная валюта",conversionCurrency){conversionCurrency=it;conversionRate=""}
                Field("Исходная сумма ($conversionCurrency)",conversionAmount,{conversionAmount=it},true)
                if(conversionCurrency!=destination){
                    Toggle("Ручной курс",conversionManual,{conversionManual=it})
                    if(conversionManual)Field("$destination за 1 $conversionCurrency",conversionRate,{conversionRate=it},true)
                    else if(quote!=null)Text("1 $conversionCurrency ≈ ${quote.toPlainString()} $destination · $day")
                    else Warning("Нет сохранённого курса на эту дату.")
                }
                result.onSuccess{MoneyText(it,destination,modifier=Modifier.testTag("entry-convert-result"),large=true)}
                if(conversionAmount.isNotBlank())result.onFailure{Warning(it.message ?: "Проверьте сумму и курс")}
                Text("В операцию будет внесена сумма в валюте счёта. Курс конвертации не изменяет сохранённые курсы.",style=MaterialTheme.typography.bodySmall)
                if(splitMode)Text("После пересчёта проверьте распределение по категориям.",style=MaterialTheme.typography.bodySmall)
                if(pendingAccount!=null)TextButton(onClick={selectAccount(pendingAccount.id);cancelConversion()}){
                    Text("Сменить счёт и ввести сумму заново")
                }
            }},
            confirmButton={Button(onClick={
                result.getOrNull()?.let{minor->
                    if(pendingAccount!=null)selectAccount(pendingAccount.id)
                    amount=Money.input(minor,destination)
                    if(splitMode&&splitDrafts.size==1)splitDrafts=listOf(splitDrafts.first().copy(amount=amount))
                    cancelConversion()
                    error=""
                }
            },enabled=result.isSuccess&&(pendingAccountId.isBlank()||pendingAccount!=null),modifier=Modifier.testTag("entry-convert-apply")){Text("Применить сумму")}},
            dismissButton={TextButton(onClick={cancelConversion()},modifier=Modifier.testTag("entry-convert-cancel")){Text("Отмена")}}
        )
    }
    val previous=originalSchedule
    if(deleteSchedule&&previous!=null)Confirm("Удалить расписание?","Уже созданные операции останутся.",{deleteSchedule=false},{vm.change({l->
        require(l.schedules.find{it.id==previous.id}==previous){"Расписание изменилось. Откройте его снова."}
        l.copy(schedules=l.schedules.filterNot{it.id==previous.id})
    },close)},busy)
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

@Composable fun EntryDetails(ledger: Ledger, entry: Entry, vm: AppViewModel,busy:Boolean,close:()->Unit,edit:()->Unit,refund:()->Unit,repeat:()->Unit) {
    var deletionSnapshot by remember { mutableStateOf<Entry?>(null) }
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
        if(canRepeatEntry(ledger,entry))OutlinedButton(onClick=repeat,enabled=!busy,modifier=Modifier.fillMaxWidth()){Text("Повторить операцию")}
        if(entry.kind==Kind.EXPENSE && entry.status==EntryStatus.POSTED && remaining>0) {
            Text("Доступно к возврату: "+Money.format(remaining,account.currency))
            OutlinedButton(onClick=refund,modifier=Modifier.fillMaxWidth()){Text("Оформить возврат")}
        }
        if(refunds.isNotEmpty()) {
            Text("Связанные возвраты",style=MaterialTheme.typography.titleMedium)
            refunds.forEach{Text(dayLabel(it.day)+" · "+Money.format(it.minor,account.currency))}
        }
        OutlinedButton(onClick={deletionSnapshot=entry},enabled=refunds.isEmpty()&&!busy&&entry.kind !in setOf(Kind.LOAN_IN,Kind.LOAN_OUT),modifier=Modifier.fillMaxWidth()){Text("Удалить операцию",color=MaterialTheme.colorScheme.error)}
    }
    deletionSnapshot?.let{snapshot->Confirm("Удалить операцию?","Баланс и отчёты будут пересчитаны.",{deletionSnapshot=null},{vm.change({l->
        require(l.entries.find{it.id==snapshot.id}==snapshot){"Операция изменилась. Откройте её снова."}
        Finance.deleteEntry(l,snapshot.id)
    },close)},busy)}
}

@Composable fun RefundEditor(ledger: Ledger, entry: Entry, vm: AppViewModel,busy:Boolean,close:()->Unit,current:Entry?=null, draft:EntryDraft) {
    val account=ledger.accounts.first{it.id==entry.accountId}
    val left=Money.sub(entry.minor,Money.sum(ledger.entries.filter{it.originalId==entry.id&&it.id!=current?.id}.map{it.minor}))
    var amount by draft.state("amount"){Money.input(current?.minor ?: left,account.currency)}
    var day by draft.state("day"){iso(current?.day ?: today())}
    var note by draft.state("note"){current?.note.orEmpty()}
    var error by draft.state("error"){""}
    val refundable=remember(ledger,entry.id,current?.id){Finance.refundableSplits(ledger,entry.id,current?.id)}
    var byCategory by draft.state("byCategory"){current!=null && entry.splits.size>1}
    var parts by draft.state("parts"){refundable.associate{split->split.categoryId to (current?.splits?.find{it.categoryId==split.categoryId}?.let{Money.input(it.minor,account.currency)} ?: "")}}
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
