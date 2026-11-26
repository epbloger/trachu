package ru.epbloger.trachu.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import ru.epbloger.trachu.AppViewModel
import ru.epbloger.trachu.BuildConfig
import ru.epbloger.trachu.core.*
import ru.epbloger.trachu.security.Settings
import ru.epbloger.trachu.widget.refreshWidgets

enum class MorePage(val label:String) { INDEX("Инструменты"), ACCOUNTS("Счета"), CATEGORIES("Категории"), CONVERTER("Валюты"), RATES("Курсы"), SETTINGS("Настройки"), DATA("Данные и копии"), TUTORIAL("Обучение"), ABOUT("Помощь") }

internal fun notificationAccessAllowed(apiLevel:Int,systemEnabled:Boolean,runtimePermissionGranted:Boolean):Boolean =
    systemEnabled && (apiLevel<33 || runtimePermissionGranted)

private fun currentNotificationAccess(context:Context):Boolean {
    val runtimeGranted=Build.VERSION.SDK_INT<33 ||
        ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED
    return notificationAccessAllowed(Build.VERSION.SDK_INT,NotificationManagerCompat.from(context).areNotificationsEnabled(),runtimeGranted)
}

@Composable fun Accounts(ledger:Ledger,edit:(Account?)->Unit,transfer:()->Unit,back:()->Unit,move:(String,Int)->Unit) {
    var showArchived by remember{mutableStateOf(false)}
    val accounts=ledger.accounts.filter{showArchived||!it.archived}.sortedWith(compareBy<Account>{it.sortOrder}.thenBy{it.name})
    LazyColumn(contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        item{SectionTitle("Счета",action="Назад",onAction=back)}
        item{Panel {
            val v=Finance.valuation(ledger)
            Text("Общий баланс");MoneyText(v.minor,ledger.baseCurrency,large=true)
            if(v.missingCurrencies.isNotEmpty())Warning("Нет курсов: "+v.missingCurrencies.joinToString())
            ledger.accounts.filter{it.includeInTotal}.groupBy{it.currency}.forEach{(currency,group)->
                Text("$currency: "+Money.format(Money.sum(group.map{Finance.balance(ledger,it.id)}),currency))
            }
            Button(onClick={edit(null)},modifier=Modifier.fillMaxWidth()){Text("Добавить счёт")}
            OutlinedButton(onClick=transfer,enabled=ledger.accounts.count{!it.archived}>=2,modifier=Modifier.fillMaxWidth()){Text("Перевести деньги")}
            Toggle("Показывать архив",showArchived,{showArchived=it})
        }}
        if(accounts.isEmpty())item{EmptyState("Счетов пока нет","Добавьте первый счёт, чтобы записывать операции.","Добавить"){edit(null)}}
        items(accounts,key={it.id}){a->Panel{
            SectionTitle(a.name,a.type.label()+" · "+a.currency,action="Изменить"){edit(a)}
            MoneyText(Finance.balance(ledger,a.id),a.currency,large=true)
            if(a.archived)Text("В архиве",color=MaterialTheme.colorScheme.tertiary)
            if(!a.includeInTotal)Text("Не включён в общий баланс",style=MaterialTheme.typography.bodyMedium)
            if(!a.includeInStats)Text("Не включён в отчёты",style=MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                TextButton(onClick={move(a.id,-1)},enabled=accounts.firstOrNull()?.id!=a.id){Text("↑ Выше")}
                TextButton(onClick={move(a.id,1)},enabled=accounts.lastOrNull()?.id!=a.id){Text("↓ Ниже")}
            }
        }}
    }
}
@Composable fun Categories(ledger:Ledger,edit:(Category?)->Unit,back:()->Unit){
    var income by remember{mutableStateOf(false)}
    var archived by remember{mutableStateOf(false)}
    val cats=ledger.categories.filter{it.income==income&&(archived||!it.archived)}
    LazyColumn(contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        item{SectionTitle("Категории",action="Назад",onAction=back)}
        item{Panel{
            Chips(listOf(false,true),income,{if(it)"Доходы" else "Расходы"}){income=it}
            Toggle("Показывать архив",archived,{archived=it})
            Button(onClick={edit(null)},modifier=Modifier.fillMaxWidth()){Text("Добавить категорию")}
        }}
        items(cats.sortedWith(compareBy<Category>{it.parentId?.let{p->ledger.categoryName(p)} ?: it.name}.thenBy{it.parentId!=null}),key={it.id}){c->Panel{
            Row(horizontalArrangement=Arrangement.spacedBy(12.dp)){
                CategoryDot(c)
                Column(Modifier.weight(1f)){
                    Text(c.parentId?.let{ledger.categoryName(it)+" / "}.orEmpty()+c.name,style=MaterialTheme.typography.titleMedium)
                    if(c.archived)Text("В архиве",color=MaterialTheme.colorScheme.tertiary)
                }
                TextButton(onClick={edit(c)}){Text("Изменить")}
            }
        }}
    }
}
@Composable fun Rates(ledger:Ledger,settings:Settings,vm:AppViewModel,busy:Boolean,back:()->Unit){
    var currency by remember{mutableStateOf(ledger.accounts.firstOrNull{it.currency!=ledger.baseCurrency}?.currency ?: allCurrencies.first{it!=ledger.baseCurrency})}
    var date by remember{mutableStateOf(iso(today()))}
    var value by remember{mutableStateOf("")}
    var error by remember{mutableStateOf("")}
    val relevant=ledger.rates.filter{it.currency==currency}.sortedByDescending{it.day}
    LazyColumn(contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        item{SectionTitle("Курсы валют",action="Конвертер",onAction=back)}
        item{Panel{
            Text("Основная валюта отчётов: ${ledger.baseCurrency}",style=MaterialTheme.typography.titleMedium)
            Toggle("Автоматические курсы ЦБ",settings.automaticRates,{if(!busy)vm.setAutomaticRates(it)},"Обновляются при наличии интернета")
        }}
        item{Panel{
            SectionTitle("Курс для финансового учёта")
            Choice("Валюта",currency.takeIf{it!=ledger.baseCurrency},allCurrencies.filter{it!=ledger.baseCurrency},{it}){currency=it;value=""}
            DateField("Дата курса",date,{date=it})
            Field("${ledger.baseCurrency} за 1 $currency",value,{value=it},true)
            Button(onClick={
                runCatching{
                    val d=runCatching{parseDay(date)}.getOrElse{throw IllegalArgumentException("Укажите дату курса")}
                    val r=value.trim().replace(',','.')
                    require(currency!=ledger.baseCurrency) { "Выберите другую валюту" }
                    require(d<=today()) { "Дата курса не может быть в будущем" }
                    runCatching{Money.convert(1,currency,ledger.baseCurrency,r)}.getOrElse{throw IllegalArgumentException("Введите положительный курс валюты")}
                    vm.change({l->l.copy(rates=l.rates.filterNot{it.currency==currency&&it.day==d}+FxRate(currency,d,r))})
                    error=""
                }.onFailure{error=it.message?.takeIf{m->m.any{c->c in 'А'..'я'||c=='ё'||c=='Ё'}} ?: "Проверьте дату и курс."}
            },enabled=!busy,modifier=Modifier.fillMaxWidth()){Text("Сохранить вручную")}
            OutlinedButton(onClick={runCatching{parseDay(date).also{require(it<=today())}}.onSuccess{vm.fetchRates(it);error=""}.onFailure{error="Проверьте дату"}},enabled=!busy,modifier=Modifier.fillMaxWidth()){Text("Получить официальные курсы ЦБ РФ")}
            Text("Ручные курсы не заменяются курсами ЦБ. Финансовые записи не передаются.",style=MaterialTheme.typography.bodySmall)
            if(error.isNotEmpty())Warning(error)
        }}
        item{Text("История $currency",style=MaterialTheme.typography.titleLarge)}
        if(relevant.isEmpty())item{Text("Сохранённых курсов пока нет.")}
        items(relevant,key={it.day}){r->Panel{Text(dayLabel(r.day),style=MaterialTheme.typography.titleMedium);Text("1 ${r.currency} = ${r.unitsOfBasePerUnit} ${ledger.baseCurrency}");Text(if(r.source=="CBR")"ЦБ РФ" else "Вручную",style=MaterialTheme.typography.bodySmall)}}
    }
}
private fun canChangeBaseCurrency(ledger:Ledger)=ledger.accounts.isEmpty()&&ledger.entries.isEmpty()&&ledger.budgets.isEmpty()&&ledger.schedules.isEmpty()&&ledger.debts.isEmpty()
@Composable fun PinField(label:String,value:String,change:(String)->Unit) {
    OutlinedTextField(value,{change(it.filter(Char::isDigit).take(12))},Modifier.fillMaxWidth(),label={Text(label)},singleLine=true,
        keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.NumberPassword),visualTransformation=PasswordVisualTransformation())
}
@Composable fun SettingsPage(ledger:Ledger,settings:Settings,vm:AppViewModel,busy:Boolean,biometricAvailable:Boolean,authenticate:((()->Unit)->Unit),back:()->Unit){
    var pinDialog by remember{mutableStateOf(false)}
    var removeDialog by remember{mutableStateOf(false)}
    var base by remember(ledger.baseCurrency){mutableStateOf(ledger.baseCurrency)}
    val context=LocalContext.current
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    var systemNotifications by remember{mutableStateOf(currentNotificationAccess(context))}
    DisposableEffect(lifecycle,context){
        val observer=LifecycleEventObserver{_,event->
            if(event==Lifecycle.Event.ON_RESUME)systemNotifications=currentNotificationAccess(context)
        }
        lifecycle.addObserver(observer)
        onDispose{lifecycle.removeObserver(observer)}
    }
    val notify=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){granted->
        systemNotifications=currentNotificationAccess(context)
        vm.notificationPermissionResult(granted)
    }
    fun openNotificationSettings(){
        context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE,context.packageName))
    }
    LazyColumn(contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        item{SectionTitle("Настройки",action="Назад",onAction=back)}
        item{Panel{
            SectionTitle("Оформление")
            Choice("Тема",settings.theme,listOf("system","light","dark"),{when(it){"light"->"Светлая";"dark"->"Тёмная";else->"Системная"}}){vm.perform{vm.preferences.theme(it)}}
        }}
        item{Panel{
            SectionTitle("Финансовый учёт")
            if(canChangeBaseCurrency(ledger)){
                CurrencyPicker("Основная валюта",base){base=it}
                Button(onClick={vm.change({l->require(canChangeBaseCurrency(l)){"Сначала завершите работу с существующими счетами и записями"};l.copy(baseCurrency=base,rates=emptyList())})},enabled=!busy&&base!=ledger.baseCurrency){Text("Изменить валюту")}
            }else Text("Основная валюта: ${ledger.baseCurrency}",style=MaterialTheme.typography.bodyLarge)
            Choice("Начало отчётного месяца",settings.monthStart,(1..31).toList(),{"$it число"}){vm.perform{vm.preferences.monthStart(it)}}
            Text("Настройка меняет месячный период в обзоре и аналитике. Бюджеты ведутся по календарным месяцам.",style=MaterialTheme.typography.bodySmall)
        }}
        item{Panel{
            SectionTitle("Защита приложения")
            Text(if(settings.protected)"PIN включён" else "PIN не установлен")
            Button(onClick={pinDialog=true},enabled=!busy,modifier=Modifier.fillMaxWidth()){Text(if(settings.protected)"Изменить PIN" else "Установить PIN")}
            if(settings.protected) {
                Toggle("Биометрия",settings.biometric,{value->
                    if(!value)vm.perform{vm.preferences.biometric(false)}
                    else authenticate { vm.perform{vm.preferences.biometric(true)} }
                },if(biometricAvailable)"Подтверждение отпечатком или другой сильной биометрией." else "Для включения настройте биометрию в Android.")
                Choice("Автоблокировка",settings.lockSeconds,listOf(0,15,30,60,300),{if(it==0)"Сразу" else "$it сек."}){vm.perform{vm.preferences.timeout(it)}}
                OutlinedButton(onClick={vm.lock()},modifier=Modifier.fillMaxWidth()){Text("Заблокировать сейчас")}
                TextButton(onClick={removeDialog=true}){Text("Отключить PIN",color=MaterialTheme.colorScheme.error)}
            }
            Text("PIN ограничивает доступ к интерфейсу. База защищена изоляцией Android; переносимая резервная копия шифруется отдельно паролем.",style=MaterialTheme.typography.bodySmall)
        }}
        item{Panel{
            SectionTitle("Уведомления")
            Toggle("Напоминания",settings.notificationsEnabled!=false&&systemNotifications,{enabled->
                if(!busy){
                    vm.perform{
                        vm.preferences.notificationsEnabled(enabled)
                        if(enabled){
                            ru.epbloger.trachu.worker.FinanceWorker.refresh(vm.getApplication())
                            if(Build.VERSION.SDK_INT>=33&&ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
                                notify.launch(Manifest.permission.POST_NOTIFICATIONS)
                            else if(!systemNotifications)openNotificationSettings()
                        }else ru.epbloger.trachu.worker.FinanceWorker.cancelReminders(vm.getApplication())
                    }
                }
            },"Платежи, долги и лимиты бюджета")
            if(!systemNotifications)Text("Уведомления запрещены в Android.",style=MaterialTheme.typography.bodySmall)
            TextButton(onClick=::openNotificationSettings){Text("Разрешения Android")}
        }}
        item{Panel{
            SectionTitle("Виджет")
            Toggle("Показывать баланс на виджете",settings.showWidgetBalance,{v->vm.perform{
                vm.preferences.widget(v);ru.epbloger.trachu.widget.refreshWidgets(vm.getApplication())
            }},"При включённом PIN сумма всегда скрыта.")
        }}
        if(!BuildConfig.DEBUG && BuildConfig.YANDEX_BANNER_ID.isNotEmpty())item{Panel{
            SectionTitle("Реклама и конфиденциальность")
            AdConsentPanel(settings.adsConsent){value->vm.perform{vm.preferences.adsConsent(value)}}
        }}
    }
    if(pinDialog||removeDialog){
        var old by remember{mutableStateOf("")}
        var pin by remember{mutableStateOf("")}
        var repeat by remember{mutableStateOf("")}
        var error by remember{mutableStateOf("")}
        Form(if(removeDialog)"Отключение PIN" else "Настройка PIN",{pinDialog=false;removeDialog=false},busy,onSave={
            if(!removeDialog && (pin.length<4||pin!=repeat))error="Введите совпадающий PIN от 4 до 12 цифр"
            else {
                vm.changePin(if(removeDialog)"" else pin,old) { pinDialog=false;removeDialog=false }
            }
        }){
            if(settings.protected)PinField("Текущий PIN",old,{old=it})
            if(!removeDialog){PinField("Новый PIN",pin,{pin=it});PinField("Повторите PIN",repeat,{repeat=it})}
            if(error.isNotEmpty())Warning(error)
        }
    }
}

@Composable fun AdConsentPanel(current:Boolean?=null,onChoose:(Boolean)->Unit) {
    val uri=androidx.compose.ui.platform.LocalUriHandler.current
    Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
        Text("Поддержать приложение рекламой",style=MaterialTheme.typography.titleSmall)
        Text("При согласии Яндекс может обрабатывать технические данные и использовать их для рекламы. Финансовые записи не передаются. Можно отказаться без потери функций.",style=MaterialTheme.typography.bodySmall)
        TextButton(onClick={runCatching{uri.openUri("https://epbloger.github.io/trachu/privacy.html")}}){Text("Политика конфиденциальности")}
        if(current==null)Column(Modifier.fillMaxWidth()){
            Button(onClick={onChoose(true)},modifier=Modifier.fillMaxWidth()){Text("Согласен")}
            OutlinedButton(onClick={onChoose(false)},modifier=Modifier.fillMaxWidth()){Text("Отказаться")}
        }else Toggle("Разрешить рекламу",current,onChoose,"Отказ прекращает новые рекламные запросы.")
    }
}
data class FileRequest(val mode:String,val save:Boolean,val password:String="",val account:String="",val period:Period=Period.month())
@Composable fun DataPage(ledger:Ledger,launch:(FileRequest)->Unit,busy:Boolean,back:()->Unit,openReports:()->Unit) {
    var backup by remember{mutableStateOf<String?>(null)}
    var importStatement by remember{mutableStateOf(false)}
    var account by remember{mutableStateOf(ledger.accounts.firstOrNull{!it.archived}?.id.orEmpty())}
    var importError by remember{mutableStateOf("")}
    var csvConfirm by remember{mutableStateOf(false)}
    LazyColumn(contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        item{SectionTitle("Данные и копии",action="Назад",onAction=back)}
        item{Panel{
            SectionTitle("Зашифрованная копия")
            Text("Сохраните все счета, операции, настройки финансов, категории, курсы и чеки в одном файле. Пароль нужен для восстановления на любом устройстве.")
            Button(onClick={backup="save"},enabled=!busy,modifier=Modifier.fillMaxWidth()){Text("Создать резервную копию")}
            OutlinedButton(onClick={backup="restore"},enabled=!busy,modifier=Modifier.fillMaxWidth()){Text("Восстановить из копии")}
        }}
        item{Panel{
            SectionTitle("CSV и Excel")
            Text("CSV в UTF-8 открывается в Excel и других таблицах. Файл ТРАЧУ. содержит операции и связанные записи для переноса; он не зашифрован.")
            Button(onClick={launch(FileRequest("csv",true))},enabled=!busy,modifier=Modifier.fillMaxWidth()){Text("Экспорт CSV")}
            OutlinedButton(onClick={csvConfirm=true},enabled=!busy,modifier=Modifier.fillMaxWidth()){Text("Импорт CSV ТРАЧУ.")}
            OutlinedButton(onClick={importStatement=true},enabled=!busy&&ledger.accounts.any{!it.archived},modifier=Modifier.fillMaxWidth()){Text("Импорт CSV выписки")}
            Text("Выписка: date,amount,category,note,id (id необязателен). Дата YYYY-MM-DD; отрицательные суммы — расходы, положительные — доходы.",style=MaterialTheme.typography.bodySmall)
        }}
        item{Panel{
            SectionTitle("PDF-отчёт")
            Button(onClick={launch(FileRequest("pdf",true,period=Period.month()))},enabled=!busy,modifier=Modifier.fillMaxWidth()){Text("PDF за календарный месяц")}
            TextButton(onClick=openReports,enabled=!busy,modifier=Modifier.fillMaxWidth()){Text("Другой период или счёт")}
        }}
    }
    if(backup!=null){
        var password by remember{mutableStateOf("")}
        var repeat by remember{mutableStateOf("")}
        var error by remember{mutableStateOf("")}
        val save=backup=="save"
        Form(if(save)"Пароль резервной копии" else "Восстановление",{backup=null},busy,if(save)"Выбрать место" else "Выбрать файл",onSave={
            if(password.length<8 || save&&password!=repeat)error="Пароль должен содержать минимум 8 символов"+(if(save)" и совпадать с подтверждением" else "")
            else {launch(FileRequest("backup",save,password));backup=null}
        }){
            if(!save)Warning("Восстановление полностью заменит текущие финансовые данные после проверки пароля и целостности файла.")
            OutlinedTextField(password,{password=it},Modifier.fillMaxWidth(),label={Text("Пароль")},visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),singleLine=true)
            if(save)OutlinedTextField(repeat,{repeat=it},Modifier.fillMaxWidth(),label={Text("Повторите пароль")},visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),singleLine=true)
            Text("Пароль нигде не хранится. Восстановить забытый пароль невозможно.")
            if(error.isNotEmpty())Warning(error)
        }
    }
    if(csvConfirm)Confirm("Импортировать таблицу?","Совпадающие записи не дублируются. При несовместимых идентификаторах импорт будет отклонён.",{csvConfirm=false},{csvConfirm=false;launch(FileRequest("csv",false))},busy)
    if(importStatement)Form("Импорт выписки",{importStatement=false;importError=""},busy,"Выбрать файл",validationError=importError,onSave={
        if(ledger.accounts.any{it.id==account&&!it.archived}){launch(FileRequest("statement",false,account=account));importStatement=false;importError=""}
        else importError="Выберите счёт для импорта выписки"
    }){
        AccountChoice("Счёт",account,ledger.accounts.filter{!it.archived}){account=it;importError=""}
        Text("Суммы должны быть в валюте этого счёта. Импорт повторно не добавит те же строки. Для операций в иностранной валюте заранее сохраните исторические курсы.")
    }
}
@Composable fun AboutPage(back:()->Unit,startTutorial:()->Unit) {
    var document by remember{mutableStateOf<String?>(null)}
    val context=androidx.compose.ui.platform.LocalContext.current
    val text=remember(document){document?.let{runCatching{context.assets.open("legal/$it.txt").bufferedReader().use{r->r.readText()}}.getOrElse{"Документ недоступен"}}}
    LazyColumn(contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        item{SectionTitle("Помощь",action="Назад",onAction=back)}
        item{Panel{
            SectionTitle("Как пользоваться ТРАЧУ.")
            Button(onClick=startTutorial,modifier=Modifier.fillMaxWidth().testTag("help-tutorial")){Text("Повторить обучение")}
        }}
        item{Panel{
            Text("ТРАЧУ.",style=MaterialTheme.typography.displaySmall,color=MaterialTheme.colorScheme.primary)
            Text("Личные финансы под контролем")
            Text("Moorkis Development · ${BuildConfig.VERSION_NAME}")
            Text("© 2026 Moorkis Development. Все права защищены.")
            Text("https://github.com/epbloger/trachu")
            Text("helpmoorkis@yandex.ru")
        }}
        item{Panel{
            SectionTitle("Документы")
            listOf("privacy" to "Политика конфиденциальности","terms" to "Условия использования","developer" to "О разработчике").forEach{(key,label)->
                TextButton(onClick={document=if(document==key)null else key},modifier=Modifier.fillMaxWidth()){Text(label)}
                if(document==key&&text!=null)Text(text,style=MaterialTheme.typography.bodyMedium)
            }
        }}
    }
}
