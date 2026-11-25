package ru.epbloger.trachu.ui

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
    var archived by remember { mutableStateOf(current?.archived ?: false) }
    var error by remember { mutableStateOf("") }
    val used=current!=null && (ledger.entries.any{it.accountId==current.id || it.targetAccountId==current.id} || ledger.debts.any{it.accountId==current.id} || ledger.schedules.any{it.template.accountId==current.id || it.template.targetAccountId==current.id})
    Form(if(current==null)"Новый счёт" else "Настройки счёта",close,busy,onSave={
        runCatching {
            require(name.isNotBlank()){"Введите название"}
            val a=Account(current?.id ?: newId(),name.trim(),currency,type,Money.parse(opening,currency),archived,included)
            vm.change({ l->
                require(l.accounts.none{it.id!=a.id && it.name.equals(a.name,true)}){"Счёт с таким названием уже существует"}
                if(current!=null && currency!=current.currency) require(l.entries.none{it.accountId==current.id || it.targetAccountId==current.id} && l.debts.none{it.accountId==current.id} && l.schedules.none{it.template.accountId==current.id || it.template.targetAccountId==current.id})
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
        if(current!=null)Toggle("Архивировать",archived,{archived=it},"История и остаток сохраняются. Архивный счёт скрыт в формах новых операций.")
        if(error.isNotEmpty())Warning(error)
    }
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
        if(error.isNotEmpty())Warning(error)
    }
}

private data class SplitDraft(val category: String,val amount: String)

@Composable fun EntryEditor(ledger: Ledger, settings: Settings, vm: AppViewModel, busy: Boolean, initialKind: Kind, current: Entry?=null, schedule: Schedule?=null, recurring: Boolean=false, close: ()->Unit) {
    val original=schedule?.template ?: current
    val active=ledger.accounts.filter{!it.archived || it.id==original?.accountId || it.id==original?.targetAccountId}
    val defaultAccount=active.find{it.id==original?.accountId} ?: active.find{it.id==settings.lastAccount} ?: active.firstOrNull()
    var accountId by remember { mutableStateOf(defaultAccount?.id.orEmpty()) }
    var kind by remember { mutableStateOf(original?.kind ?: initialKind) }
    var amount by remember { mutableStateOf(original?.let{Money.input(it.minor,ledger.accounts.first{a->a.id==it.accountId}.currency)}.orEmpty()) }
    var day by remember { mutableStateOf(iso(schedule?.nextDay ?: original?.day ?: today())) }
    var time by remember { mutableStateOf(original?.timeMinutes?.let{"%02d:%02d".format(it/60,it%60)} ?: LocalTime.now().withSecond(0).withNano(0).toString()) }
    var note by remember { mutableStateOf(original?.note.orEmpty()) }
    var tags by remember { mutableStateOf(original?.tags?.joinToString(", ").orEmpty()) }
    var essential by remember { mutableStateOf(original?.essential ?: false) }
    var planned by remember { mutableStateOf(original?.status==EntryStatus.PLANNED || recurring) }
    var targetId by remember { mutableStateOf(original?.targetAccountId.orEmpty()) }
    var targetAmount by remember { mutableStateOf(original?.let{e->e.targetMinor?.let{m->Money.input(m,ledger.accounts.first{a->a.id==e.targetAccountId}.currency)}}.orEmpty()) }
    var explicitRate by remember { mutableStateOf(original?.rate.orEmpty()) }
    var splitMode by remember { mutableStateOf((original?.splits?.size ?: 0)>1) }
    val initialCategory=ledger.categories.firstOrNull{it.income==(kind==Kind.INCOME) && !it.archived}?.id.orEmpty()
    var splitDrafts by remember { mutableStateOf(original?.splits?.map{SplitDraft(it.categoryId,Money.input(it.minor,defaultAccount?.currency ?: ledger.baseCurrency))}
        ?.takeIf{it.isNotEmpty()} ?: listOf(SplitDraft(initialCategory,""))) }
    var frequency by remember { mutableStateOf(schedule?.frequency ?: Frequency.MONTHLY) }
    var interval by remember { mutableStateOf(schedule?.interval?.toString() ?: "1") }
    var endDay by remember { mutableStateOf(schedule?.endDay?.let(::iso).orEmpty()) }
    var autoPost by remember { mutableStateOf(schedule?.autoPost ?: false) }
    var enabled by remember { mutableStateOf(schedule?.enabled ?: true) }
    var pendingReceipts by remember { mutableStateOf(emptyList<Receipt>()) }
    var receiptIds by remember { mutableStateOf(original?.receiptIds ?: emptyList()) }
    val launcher=rememberLauncherForActivityResult(ActivityResultContracts.GetContent()){uri->if(uri!=null)vm.readReceipt(uri){r->pendingReceipts=pendingReceipts+r;receiptIds=receiptIds+r.id}}
    var error by remember { mutableStateOf("") }
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
                val prepared=old.copy(receipts=old.receipts+receipts)
                val e=Finance.makeEntry(prepared,kind,a.id,minor,date,split,note,tags.split(',').map{it.trim()},
                    targetId=targetId.takeIf{kind==Kind.TRANSFER},targetMinor=toMinor,
                    status=if(plan)EntryStatus.PLANNED else EntryStatus.POSTED,explicitRate=rate,essential=essential,
                    timeMinutes=t.hour*60+t.minute,id=id,receiptIds=receiptIds)
                if(recurring) {
                    val s=Schedule(schedule?.id ?: newId(),e,date,frequency,interval.toInt(),
                        endDay.trim().takeIf{it.isNotEmpty()}?.let(::parseDay),autoPost,enabled,
                        if(schedule!=null && schedule.nextDay==date)schedule.anchorDay else LocalDate.ofEpochDay(date).dayOfMonth)
                    require(s.endDay==null || s.endDay>=s.nextDay){"Дата окончания раньше начала"}
                    prepared.copy(schedules=prepared.schedules.filterNot{it.id==s.id}+s)
                } else Finance.putEntry(prepared,e)
            },close)
        }.onFailure{error=it.message ?: "Проверьте данные"}
    }) {
        if(active.isEmpty())Warning("Создайте хотя бы один счёт в разделе «Ещё».")
        Chips(listOf(Kind.EXPENSE,Kind.INCOME,Kind.TRANSFER),kind,{it.label()}){ k->
            kind=k;splitDrafts=listOf(SplitDraft(ledger.categories.firstOrNull{it.income==(k==Kind.INCOME)&&!it.archived}?.id.orEmpty(),""));splitMode=false
        }
        Choice("Счёт",accountId,active.map{it.id},{ledger.accountName(it)}){id->if(id!=accountId){accountId=id;explicitRate="";if(targetId==id)targetId=""}}
        Field("Сумма (${account?.currency ?: ledger.baseCurrency})",amount,{amount=it},true)
        if(kind==Kind.TRANSFER) {
            Choice("На счёт",targetId,active.filter{it.id!=accountId}.map{it.id},{ledger.accountName(it)}){targetId=it}
            if(account!=null && target!=null && account.currency!=target.currency)Field("Получено (${target.currency})",targetAmount,{targetAmount=it},true)
        } else {
            Toggle("Разделить по категориям",splitMode,{value->splitMode=value;if(!value)splitDrafts=listOf(splitDrafts.first())})
            splitDrafts.forEachIndexed { i,draft->
                Choice("Категория ${if(splitMode)i+1 else ""}",draft.category,cats.map{it.id},{id->
                    val c=ledger.categories.firstOrNull{it.id==id};c?.let{(it.parentId?.let{p->ledger.categoryName(p)+" / "}.orEmpty())+it.name} ?: "Выбрать"
                }){id->splitDrafts=splitDrafts.mapIndexed{j,d->if(j==i)d.copy(category=id) else d}}
                if(splitMode) {
                    Field("Часть суммы",draft.amount,{v->splitDrafts=splitDrafts.mapIndexed{j,d->if(i==j)d.copy(amount=v) else d}},true)
                    if(splitDrafts.size>1)TextButton(onClick={splitDrafts=splitDrafts.filterIndexed{j,_->j!=i}}){Text("Удалить часть")}
                }
            }
            if(splitMode)OutlinedButton(onClick={splitDrafts=splitDrafts+SplitDraft(cats.firstOrNull{c->splitDrafts.none{it.category==c.id}}?.id.orEmpty(),"")},enabled=splitDrafts.size<cats.size){Text("Добавить категорию")}
        }
        Field("Дата",day,{day=it},supporting="ГГГГ-ММ-ДД")
        Field("Время",time,{time=it},supporting="ЧЧ:ММ")
        if(rateRequired)Field("Курс: ${ledger.baseCurrency} за 1 ${account?.currency}",explicitRate,{explicitRate=it},true,
            supporting=currentRate?.let{"Сохранённый курс: $it. Пустое поле использует его."} ?: "Укажите курс или загрузите его в настройках.")
        Field("Комментарий",note,{note=it},singleLine=false)
        Field("Теги",tags,{tags=it},supporting="Через запятую")
        if(kind==Kind.EXPENSE)Toggle("Обязательная трата",essential,{essential=it})
        if(!recurring)Toggle("Запланировать",planned,{planned=it},"Будущие операции не меняют фактический баланс до подтверждения.")
        if(recurring) {
            Choice("Повторять",frequency,Frequency.entries,{it.label()}){frequency=it}
            Field("Интервал",interval,{interval=it},true,supporting="Каждые N дней, недель, месяцев или лет")
            Field("Окончание (необязательно)",endDay,{endDay=it},supporting="ГГГГ-ММ-ДД")
            Toggle("Проводить автоматически",autoPost,{autoPost=it},"Иначе появится запланированная операция для подтверждения.")
            Toggle("Активно",enabled,{enabled=it})
        } else {
            receiptIds.forEach { id->
                val r=(pendingReceipts+ledger.receipts).find{it.id==id}
                if(r!=null)ReceiptPreview(r)
                TextButton(onClick={receiptIds=receiptIds.filterNot{it==id}}){Text("Убрать фотографию")}
            }
            OutlinedButton(onClick={launcher.launch("image/*")},enabled=!busy && receiptIds.size<4){Text("Прикрепить фото чека")}
        }
        if(error.isNotEmpty())Warning(error)
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
            Button(onClick={vm.change({l->Finance.putEntry(l,entry.copy(status=EntryStatus.POSTED,day=minOf(entry.day,today())))},close)},enabled=!busy,modifier=Modifier.fillMaxWidth()){Text("Подтвердить операцию")}
        }
        if(entry.kind in setOf(Kind.EXPENSE,Kind.INCOME,Kind.TRANSFER) && refunds.isEmpty())
            OutlinedButton(onClick=edit,modifier=Modifier.fillMaxWidth()){Text("Редактировать")}
        if(entry.kind==Kind.EXPENSE && entry.status==EntryStatus.POSTED && remaining>0) {
            Text("Доступно к возврату: "+Money.format(remaining,account.currency))
            OutlinedButton(onClick=refund,modifier=Modifier.fillMaxWidth()){Text("Оформить возврат")}
        }
        if(refunds.isNotEmpty()) {
            Text("Связанные возвраты",style=MaterialTheme.typography.titleMedium)
            refunds.forEach{Text(dayLabel(it.day)+" · "+Money.format(it.minor,account.currency))}
        }
        OutlinedButton(onClick={confirmDelete=true},enabled=refunds.isEmpty()&&!busy,modifier=Modifier.fillMaxWidth()){Text("Удалить операцию",color=MaterialTheme.colorScheme.error)}
    }
    if(confirmDelete)Confirm("Удалить операцию?","Баланс и отчёты будут пересчитаны.",{confirmDelete=false},{vm.change({l->Finance.deleteEntry(l,entry.id)},close)},busy)
}

@Composable fun RefundEditor(ledger: Ledger, entry: Entry, vm: AppViewModel,busy:Boolean,close:()->Unit) {
    val account=ledger.accounts.first{it.id==entry.accountId}
    val left=Money.sub(entry.minor,Money.sum(ledger.entries.filter{it.originalId==entry.id}.map{it.minor}))
    var amount by remember { mutableStateOf(Money.input(left,account.currency)) }
    var day by remember { mutableStateOf(iso(today())) }
    var note by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    Form("Возврат покупки",close,busy,onSave={
        runCatching {
            val minor=Money.parse(amount,account.currency);val date=parseDay(day)
            require(date<=today()){"Дата возврата не может быть в будущем"}
            vm.change({l->Finance.putEntry(l,Finance.refund(l,entry.id,minor,date,note))},close)
        }.onFailure{error=it.message ?: "Проверьте сумму"}
    }) {
        Text(ledger.entryName(entry));Text("Максимум: "+Money.format(left,account.currency))
        Field("Сумма возврата",amount,{amount=it},true)
        Field("Дата",day,{day=it},supporting="ГГГГ-ММ-ДД")
        Field("Комментарий",note,{note=it})
        Text("Возврат уменьшает расходы в дату получения денег и использует исторический курс покупки. Части распределяются по категориям исходной покупки.")
        if(error.isNotEmpty())Warning(error)
    }
}
