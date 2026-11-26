package ru.epbloger.trachu.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.luminance
import java.time.LocalTime
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ru.epbloger.trachu.core.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

val dateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM yyyy",Locale("ru"))
fun dayLabel(day: Long): String = LocalDate.ofEpochDay(day).format(dateFormatter)
fun iso(day: Long): String = LocalDate.ofEpochDay(day).toString()
fun parseDay(value: String): Long = LocalDate.parse(value.trim()).toEpochDay()
fun Kind.label(): String = when(this) {
    Kind.EXPENSE->"Расход"; Kind.INCOME->"Доход"; Kind.TRANSFER->"Перевод"; Kind.REFUND->"Возврат"
    Kind.DEBT_IN->"Возврат долга вам"; Kind.DEBT_OUT->"Погашение долга"
    Kind.LOAN_IN->"Получение займа"; Kind.LOAN_OUT->"Выдача займа"
}
fun AccountType.label(): String = when(this) {
    AccountType.CASH->"Наличные"; AccountType.CARD->"Банковская карта"; AccountType.SAVINGS->"Накопительный"
    AccountType.CREDIT->"Кредитный"; AccountType.OTHER->"Другой"
}
fun Frequency.label(): String = when(this) { Frequency.DAILY->"День"; Frequency.WEEKLY->"Неделя"; Frequency.MONTHLY->"Месяц"; Frequency.YEARLY->"Год" }
fun Ledger.accountName(id: String): String = accounts.find { it.id==id }?.name ?: "Счёт"
fun Ledger.categoryName(id: String): String = Finance.categoryLabel(this,id)
fun Ledger.entryName(e: Entry): String = when(e.kind) {
    Kind.TRANSFER->"${accountName(e.accountId)} → ${accountName(e.targetAccountId.orEmpty())}"
    Kind.REFUND->"Возврат · "+e.splits.joinToString(", ") { categoryName(it.categoryId) }
    Kind.DEBT_IN,Kind.DEBT_OUT,Kind.LOAN_IN,Kind.LOAN_OUT->e.kind.label()+" · "+(debts.find { it.id==e.debtId }?.person ?: "")
    else->e.splits.joinToString(", ") { categoryName(it.categoryId) }.ifBlank { e.kind.label() }
}

@Composable fun Panel(modifier: Modifier=Modifier, content: @Composable ColumnScope.()->Unit) {
    Card(modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface),
        shape=MaterialTheme.shapes.large) {
        Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp),content=content)
    }
}
@Composable fun SectionTitle(title: String, subtitle: String?=null, action: String?=null, onAction: ()->Unit={}) {
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title,style=MaterialTheme.typography.titleLarge)
            if(subtitle!=null) Text(subtitle,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if(action!=null) TextButton(onClick=onAction) { Text(action) }
    }
}
@Composable fun EmptyState(title: String, description: String, action: String?=null, onAction: ()->Unit={}) {
    Panel {
        Text(title,style=MaterialTheme.typography.titleLarge)
        Text(description,color=MaterialTheme.colorScheme.onSurfaceVariant)
        if(action!=null) Button(onClick=onAction) { Text(action) }
    }
}
@Composable fun Field(label: String, value: String, onChange: (String)->Unit, numeric: Boolean=false, modifier: Modifier=Modifier, singleLine: Boolean=true, supporting: String?=null, trailing:@Composable (() -> Unit)?=null) {
    val focusManager=LocalFocusManager.current
    OutlinedTextField(value,onChange,modifier.fillMaxWidth().testTag("field:$label"),label={ Text(label) },singleLine=singleLine,
        keyboardOptions=KeyboardOptions(keyboardType=if(numeric) KeyboardType.Decimal else KeyboardType.Text,imeAction=if(singleLine)ImeAction.Done else ImeAction.Default),
        keyboardActions=KeyboardActions(onDone={focusManager.clearFocus()}),
        supportingText=if(supporting==null) null else { { Text(supporting) } },trailingIcon=trailing)
}
@Composable fun DateField(label:String,value:String,onChange:(String)->Unit,supporting:String="ГГГГ-ММ-ДД") {
    val context=LocalContext.current
    val theme=if(MaterialTheme.colorScheme.surface.luminance()<0.5f)android.R.style.Theme_Material_Dialog_Alert else android.R.style.Theme_Material_Light_Dialog_Alert
    Field(label,value,onChange,supporting=supporting,trailing={
        IconButton(onClick={
            val date=runCatching{LocalDate.parse(value)}.getOrDefault(LocalDate.now())
            android.app.DatePickerDialog(context,theme,{_,year,month,day->
                onChange(LocalDate.of(year,month+1,day).toString())
            },date.year.coerceIn(1900,2200),date.monthValue-1,date.dayOfMonth.coerceAtMost(28).let{if(date.year in 1900..2200)date.dayOfMonth else it}).show()
        }){Icon(Icons.Outlined.DateRange,contentDescription="Выбрать дату: $label")}
    })
}
@Composable fun TimeField(label:String,value:String,onChange:(String)->Unit) {
    val context=LocalContext.current
    val theme=if(MaterialTheme.colorScheme.surface.luminance()<0.5f)android.R.style.Theme_Material_Dialog_Alert else android.R.style.Theme_Material_Light_Dialog_Alert
    Field(label,value,onChange,supporting="ЧЧ:ММ",trailing={
        IconButton(onClick={
            val time=runCatching{LocalTime.parse(value)}.getOrDefault(LocalTime.now())
            android.app.TimePickerDialog(context,theme,{_,h,m->onChange(String.format(Locale.ROOT,"%02d:%02d",h,m))},time.hour,time.minute,true).show()
        }){Icon(Icons.Outlined.AccessTime,contentDescription="Выбрать время")}
    })
}
@Composable fun <T> Choice(label: String, value: T?, options: List<T>, title: (T)->String, modifier: Modifier=Modifier, onChoose: (T)->Unit) {
    var open by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(4.dp)) {
        Text(label,style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            OutlinedButton(onClick={open=true},enabled=options.isNotEmpty(),modifier=Modifier.fillMaxWidth()) {
                Text(value?.let(title) ?: "Выбрать",modifier=Modifier.weight(1f),maxLines=2,overflow=TextOverflow.Ellipsis)
                Text("⌄")
            }
            DropdownMenu(expanded=open,onDismissRequest={open=false},modifier=Modifier.heightIn(max=360.dp)) {
                options.forEach { option -> DropdownMenuItem(text={Text(title(option))},onClick={open=false;onChoose(option)}) }
            }
        }
    }
}
@Composable fun CategoryChoice(label:String, value:String, options:List<Category>, ledger:Ledger, onChoose:(String)->Unit) {
    var open by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    fun title(c:Category)=c.parentId?.let{ledger.categoryName(it)+" / "+c.name} ?: c.name
    val current=options.find{it.id==value}
    Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
        Text(label,style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick={query="";open=true},enabled=options.isNotEmpty(),modifier=Modifier.fillMaxWidth().testTag("category-choice:$label")) {
            if(current!=null)CategoryDot(current,Modifier.padding(end=4.dp))
            Text(current?.let(::title) ?: "Выбрать категорию",Modifier.weight(1f))
            Text("⌄")
        }
        if(options.isEmpty())Text("Создайте категорию в разделе «Ещё».",style=MaterialTheme.typography.bodySmall)
    }
    if(open)Dialog(onDismissRequest={open=false},properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.widthIn(max=540.dp).fillMaxWidth(.94f).fillMaxHeight(.85f),shape=MaterialTheme.shapes.extraLarge) {
            Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text("Выберите категорию",style=MaterialTheme.typography.headlineSmall)
                Field("Поиск категории",query,{query=it})
                val matches=options.filter{title(it).contains(query.trim(),ignoreCase=true)}.sortedBy{title(it)}
                LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("category-list")) {
                    items(matches,key={it.id}) { category ->
                        Row(Modifier.fillMaxWidth().testTag("category-option:${category.id}").clickable{onChoose(category.id);open=false}.semantics{selected=category.id==value}.padding(vertical=8.dp),
                            verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                            CategoryDot(category)
                            Text(title(category),Modifier.weight(1f),style=MaterialTheme.typography.bodyLarge)
                            if(category.id==value)Text("✓",color=MaterialTheme.colorScheme.primary)
                        }
                    }
                    if(matches.isEmpty())item{Text("Категории не найдены.",Modifier.padding(8.dp))}
                }
                TextButton(onClick={open=false},modifier=Modifier.align(Alignment.End)){Text("Отмена")}
            }
        }
    }
}
@Composable fun MultiChoice(label:String, selected:Set<String>, options:List<String>, title:(String)->String,onChange:(Set<String>)->Unit){
    var open by remember { mutableStateOf(false) }
    Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
        Text(label,style=MaterialTheme.typography.labelLarge)
        Box {
            OutlinedButton(onClick={open=true},modifier=Modifier.fillMaxWidth()){Text(if(selected.isEmpty())"Все" else "Выбрано: ${selected.size}")}
            DropdownMenu(expanded=open,onDismissRequest={open=false},modifier=Modifier.heightIn(max=420.dp)){
                DropdownMenuItem(text={Text("Сбросить")},onClick={onChange(emptySet())})
                options.forEach { id->DropdownMenuItem(text={Text(title(id))},leadingIcon={Checkbox(checked=id in selected,onCheckedChange=null)},
                    onClick={onChange(if(id in selected)selected-id else selected+id)}) }
            }
        }
    }
}
@Composable fun <T> Chips(options: List<T>, selected: T, title: (T)->String, onChoose: (T)->Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        options.forEach { item -> FilterChip(selected=item==selected,onClick={onChoose(item)},label={Text(title(item))}) }
    }
}
@Composable fun Toggle(label: String, checked: Boolean, onChange: (Boolean)->Unit, detail: String?=null) {
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(label,style=MaterialTheme.typography.bodyLarge)
            if(detail!=null) Text(detail,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked,onCheckedChange=onChange,modifier=Modifier.testTag("toggle:$label").semantics{contentDescription=label})
    }
}
@Composable fun Form(title: String, onClose: ()->Unit, busy: Boolean, saveLabel: String="Сохранить", onSave: ()->Unit,
    content: @Composable ColumnScope.()->Unit) {
    Dialog(onDismissRequest={if(!busy)onClose()},properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.widthIn(max=620.dp).fillMaxWidth(.94f).fillMaxHeight(.88f),shape=MaterialTheme.shapes.extraLarge,
            color=MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(20.dp)) {
                Text(title,style=MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(12.dp))
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).testTag("form_content"),verticalArrangement=Arrangement.spacedBy(12.dp),content=content)
                Spacer(Modifier.height(12.dp))
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    @Composable fun Cancel(modifier:Modifier=Modifier) { TextButton(onClick=onClose,enabled=!busy,modifier=modifier) { Text("Отмена") } }
                    @Composable fun Save(modifier:Modifier=Modifier) { Button(onClick=onSave,enabled=!busy,modifier=modifier.testTag("form_save")) { if(busy) CircularProgressIndicator(Modifier.size(18.dp),strokeWidth=2.dp) else Text(saveLabel) } }
                    if(maxWidth<240.dp||LocalDensity.current.fontScale>=1.5f)Column(Modifier.fillMaxWidth()){Save(Modifier.fillMaxWidth());Cancel(Modifier.fillMaxWidth())}
                    else Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End,verticalAlignment=Alignment.CenterVertically){Cancel();Save()}
                }
            }
        }
    }
}
@Composable fun Warning(text: String) {
    Text(text,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.error)
}
@Composable fun MoneyText(minor: Long, currency: String, modifier: Modifier=Modifier, color: Color=MaterialTheme.colorScheme.onSurface, large: Boolean=false) {
    Text(Money.format(minor,currency),modifier=modifier,color=color,
        style=if(large) MaterialTheme.typography.displaySmall else MaterialTheme.typography.titleMedium,
        fontWeight=FontWeight.SemiBold)
}
@Composable fun Progress(amount: Long, total: Long, color: Color=MaterialTheme.colorScheme.primary) {
    val p=if(total<=0L) 0f else (amount.toDouble()/total.toDouble()).coerceIn(0.0,1.0).toFloat()
    val displayed by animateFloatAsState(p,label="budget progress")
    LinearProgressIndicator(progress={displayed},modifier=Modifier.fillMaxWidth().height(8.dp),color=color,trackColor=MaterialTheme.colorScheme.surfaceVariant)
}
@Composable fun Donut(segments: List<Pair<Color,Long>>, label: String, modifier: Modifier=Modifier) {
    val positive=segments.filter { it.second>0 }
    val sum=positive.sumOf { it.second.toDouble() }
    val emptyColor=MaterialTheme.colorScheme.surfaceVariant
    Canvas(modifier.size(142.dp).semantics { contentDescription=label }) {
        val stroke=Stroke(width=size.minDimension*.16f)
        val pad=stroke.width/2
        var start=-90f
        if(sum==0.0) drawArc(emptyColor,0f,360f,false,topLeft=androidx.compose.ui.geometry.Offset(pad,pad),size=androidx.compose.ui.geometry.Size(size.width-2*pad,size.height-2*pad),style=stroke)
        else positive.forEach { (color,value) ->
            val sweep=(360*value/sum).toFloat()
            drawArc(color,start,sweep,false,topLeft=androidx.compose.ui.geometry.Offset(pad,pad),size=androidx.compose.ui.geometry.Size(size.width-2*pad,size.height-2*pad),style=stroke)
            start+=sweep
        }
    }
}
@Composable fun CategoryDot(category: Category?, modifier: Modifier=Modifier) {
    val color=category?.let { Color(it.color) } ?: MaterialTheme.colorScheme.primary
    Box(modifier.size(42.dp).background(color.copy(alpha=.16f),CircleShape),contentAlignment=Alignment.Center) {
        Text(category?.icon ?: "•",color=color,style=MaterialTheme.typography.titleMedium)
    }
}
@Composable fun EntryRow(ledger: Ledger, entry: Entry, onClick: ()->Unit) {
    val a=ledger.accounts.first { it.id==entry.accountId }
    val category=ledger.categories.find { it.id==entry.splits.firstOrNull()?.categoryId }
    val incoming=entry.kind in listOf(Kind.INCOME,Kind.REFUND,Kind.DEBT_IN,Kind.LOAN_IN)
    val sign=when(entry.kind) { Kind.INCOME,Kind.REFUND,Kind.DEBT_IN,Kind.LOAN_IN->"+"; Kind.EXPENSE,Kind.DEBT_OUT,Kind.LOAN_OUT->"−"; Kind.TRANSFER->"" }
    val amount=sign+Money.format(entry.minor,a.currency)
    val amountColor=if(incoming) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    BoxWithConstraints(Modifier.fillMaxWidth().testTag("operation-row").clickable(onClick=onClick).padding(vertical=10.dp)) {
        val stack=maxWidth<320.dp || LocalDensity.current.fontScale>=1.3f || amount.length>15
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            CategoryDot(category)
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)) {
                Text(ledger.entryName(entry),style=MaterialTheme.typography.titleMedium,maxLines=2,overflow=TextOverflow.Ellipsis)
                Text(listOf(dayLabel(entry.day),a.name,entry.note.takeIf { it.isNotBlank() }).filterNotNull().joinToString(" · "),
                    style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=2,overflow=TextOverflow.Ellipsis)
                if(entry.status==EntryStatus.PLANNED)Text(if(entry.autoPostPlanned)"Запланировано · автопроведение" else "Запланировано",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.tertiary)
                if(stack)Text(amount,modifier=Modifier.testTag("operation-amount"),style=MaterialTheme.typography.titleMedium,color=amountColor)
            }
            if(!stack)Text(amount,modifier=Modifier.widthIn(max=156.dp).testTag("operation-amount"),style=MaterialTheme.typography.titleMedium,color=amountColor)
        }
    }
}
@Composable fun Confirm(title: String, detail: String, onClose: ()->Unit, onConfirm: ()->Unit, busy: Boolean=false) {
    AlertDialog(onDismissRequest=onClose,title={Text(title)},text={Text(detail)},
        confirmButton={TextButton(onClick=onConfirm,enabled=!busy){Text("Подтвердить")}},
        dismissButton={TextButton(onClick=onClose){Text("Отмена")}})
}
