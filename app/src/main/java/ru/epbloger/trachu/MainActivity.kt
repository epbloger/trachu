package ru.epbloger.trachu

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import ru.epbloger.trachu.core.*
import ru.epbloger.trachu.ads.OverviewAdBanner
import ru.epbloger.trachu.ads.updateAdsConsent
import ru.epbloger.trachu.security.Settings
import ru.epbloger.trachu.ui.*

class MainActivity : FragmentActivity() {
    private val vm:AppViewModel by viewModels()
    private var biometricInProgress=false
    // Register unconditionally in the same order, before STARTED, even when
    // the ledger is loading or the app is locked.
    private val receiptPicker=registerForActivityResult(ActivityResultContracts.GetContent()){uri->
        val request=vm.receiptRequest
        vm.receiptRequest=null
        if(uri!=null){
            if(request==null)vm.notice("Выбор фотографии отменён после перезапуска приложения. Прикрепите чек снова.")
            else if(vm.editor?.draft===request)vm.queuePickedResult(PickedResult.Photo(uri,request))
        }
    }
    private val savePicker=registerForActivityResult(ActivityResultContracts.StartActivityForResult()){result->
        val request=vm.fileRequest?.takeIf{it.save}
        if(request!=null)vm.fileRequest=null
        val uri=result.data?.data
        if(result.resultCode==android.app.Activity.RESULT_OK && uri!=null){
            when{
                request==null->vm.notice("Выбор места сохранения отменён после перезапуска приложения. Сохраните файл снова.")
                else->vm.queuePickedResult(PickedResult.File(uri,request))
            }
        }
    }
    private val loadPicker=registerForActivityResult(ActivityResultContracts.OpenDocument()){uri->
        val request=vm.fileRequest?.takeIf{!it.save}
        if(request!=null)vm.fileRequest=null
        if(uri!=null){
            when{
                request==null->vm.notice("Выбор файла отменён после перезапуска приложения. Выберите файл снова.")
                else->vm.queuePickedResult(PickedResult.File(uri,request))
            }
        }
    }
    private fun chooseReceipt(){
        if(vm.busy.value||vm.pickerPending())return
        vm.receiptRequest=vm.editor?.draft ?: return
        runCatching{receiptPicker.launch("image/*")}.onFailure{
            vm.receiptRequest=null
            vm.notice("Не удалось открыть выбор фотографии.")
        }
    }
    private fun launchFile(request:FileRequest){
        if(vm.busy.value||vm.pickerPending())return
        vm.fileRequest=request
        runCatching{
            if(request.save){
                val mime=when(request.mode){"backup"->"application/octet-stream";"pdf"->"application/pdf";else->"text/csv"}
                val suffix=when(request.mode){"backup"->"trachu";"pdf"->"pdf";else->"csv"}
                savePicker.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(mime)
                    .putExtra(Intent.EXTRA_TITLE,"trachu-${java.time.LocalDate.now()}.$suffix"))
            }else loadPicker.launch(if(request.mode=="backup")arrayOf("*/*") else arrayOf("text/*","application/csv","application/vnd.ms-excel","application/octet-stream"))
        }.onFailure{
            vm.fileRequest=null
            vm.notice("Не удалось открыть выбор файла.")
        }
    }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        if(savedInstanceState==null)handleIntent(intent)
        else if(!vm.activityCreated && (savedInstanceState.getBoolean("editor_open") ||
            savedInstanceState.getBoolean("picker_pending"))){
            vm.notice("Android перезапустил приложение. Несохранённый черновик или выбор файла отменён. Повторите действие.")
        }
        vm.activityCreated=true
        // A picker may return while the app is locked. Wait until the ledger
        // and protection settings have loaded and the user has unlocked.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED){
                combine(vm.settings,vm.ledger,vm.unlocked,vm.busy,vm.pickedResult){s,l,u,b,p->
                    p!=null && s!=null && l!=null && (!s.protected||u) && !b
                }.collect{ready->if(ready)vm.deliverPickedResult()}
            }
        }
        setContent {
            val settings by vm.settings.collectAsStateWithLifecycle()
            val ledger by vm.ledger.collectAsStateWithLifecycle()
            val message by vm.message.collectAsStateWithLifecycle()
            val busy by vm.busy.collectAsStateWithLifecycle()
            val loadingError by vm.loadingError.collectAsStateWithLifecycle()
            val unlocked by vm.unlocked.collectAsStateWithLifecycle()
            SideEffect {
                if(settings==null || settings?.protected == true) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
            TrachuTheme(settings?.theme ?: "system") {
                Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background) {
                    val s=settings
                    val l=ledger
                    if(s==null||l==null)Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),contentAlignment=Alignment.Center){
                        val error=loadingError
                        if(error==null)CircularProgressIndicator()
                        else Column(Modifier.padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(16.dp)){
                            Text(error,style=MaterialTheme.typography.bodyLarge)
                            Button(onClick=vm::retryLoad){Text("Повторить")}
                        }
                    }
                    else AppShell(l,s,vm,busy,biometricAvailable(),s.protected&&!unlocked,{done->authenticate(done)},::launchFile,::chooseReceipt)
                    if(message!=null && (settings?.protected!=true || unlocked || message=="Неверный PIN" || message?.startsWith("Слишком много попыток")==true))AlertDialog(onDismissRequest=vm::clearMessage,title={Text("ТРАЧУ.")},text={Text(message.orEmpty())},
                        confirmButton={TextButton(onClick=vm::clearMessage){Text("OK")}})
                }
            }
        }
    }
    override fun onSaveInstanceState(outState:Bundle){
        super.onSaveInstanceState(outState)
        // Store only presence flags, never financial fields, receipt images or passwords.
        outState.putBoolean("editor_open",vm.editor!=null)
        outState.putBoolean("picker_pending",vm.pickerPending())
    }
    override fun onNewIntent(intent:Intent) { super.onNewIntent(intent);setIntent(intent);handleIntent(intent) }
    private fun handleIntent(intent:Intent?) {
        val kind=runCatching{Kind.valueOf(intent?.getStringExtra("quick").orEmpty())}.getOrNull()
        if(kind==Kind.EXPENSE||kind==Kind.INCOME)vm.quick(kind)
    }
    override fun onStop() {
        super.onStop()
        if(!isChangingConfigurations&&!biometricInProgress) {
            vm.stoppedAt=SystemClock.elapsedRealtime()
            if(vm.settings.value?.lockSeconds==0)vm.lock()
        }
    }
    override fun onStart() {
        val s=vm.settings.value
        val last=vm.stoppedAt
        if(last!=null && (s==null || (s.protected && SystemClock.elapsedRealtime()-last>=s.lockSeconds*1000L)))vm.lock()
        vm.stoppedAt=null
        super.onStart()
    }
    private fun biometricAvailable()=BiometricManager.from(this).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)==BiometricManager.BIOMETRIC_SUCCESS
    private fun authenticate(done:()->Unit) {
        if(!biometricAvailable()) {
            vm.perform{error("Настройте сильную биометрию в системных настройках Android.")}
            return
        }
        if(biometricInProgress)return
        biometricInProgress=true
        val prompt=BiometricPrompt(this,ContextCompat.getMainExecutor(this),object:BiometricPrompt.AuthenticationCallback(){
            override fun onAuthenticationSucceeded(result:BiometricPrompt.AuthenticationResult){biometricInProgress=false;done()}
            override fun onAuthenticationError(errorCode:Int,errString:CharSequence){biometricInProgress=false}
        })
        prompt.authenticate(BiometricPrompt.PromptInfo.Builder().setTitle("ТРАЧУ.").setSubtitle("Подтвердите доступ")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG).setNegativeButtonText("Отмена").build())
    }
}

private enum class Tab(val label:String,val icon:ImageVector) {
    HOME("Обзор",Icons.Outlined.Home), OPERATIONS("Операции",Icons.Outlined.AccountBalanceWallet),
    PLAN("План",Icons.Outlined.CalendarMonth), REPORTS("Отчёты",Icons.Outlined.BarChart), MORE("Ещё",Icons.Outlined.MoreHoriz)
}
internal class Editor(val type:String,val id:String?=null,val kind:Kind=Kind.EXPENSE,val initialDay:Long?=null,val draft:EntryDraft=EntryDraft())

@Composable private fun AppShell(ledger:Ledger,settings:Settings,vm:AppViewModel,busy:Boolean,biometricAvailable:Boolean,locked:Boolean,authenticate:((()->Unit)->Unit),file:(FileRequest)->Unit,chooseReceipt:()->Unit){
    var tab by rememberSaveable{mutableStateOf(Tab.HOME)}
    var more by rememberSaveable{mutableStateOf(MorePage.INDEX)}
    var plan by rememberSaveable{mutableStateOf(PlannerTab.BUDGET)}
    var editor by vm.editorState
    val quick by vm.quick.collectAsStateWithLifecycle()
    LaunchedEffect(settings.adsConsent){updateAdsConsent(settings.adsConsent==true)}
    fun openMore(page:MorePage){tab=Tab.MORE;more=page}
    fun add(kind:Kind,planned:Boolean=false,initialDay:Long?=null){
        if(ledger.accounts.none{!it.archived}){openMore(MorePage.ACCOUNTS);editor=Editor("account")}
        else editor=Editor(if(planned)"planned" else "entry",kind=kind,initialDay=initialDay)
    }
    fun schedule(s:Schedule?){
        if(ledger.accounts.none{!it.archived}){openMore(MorePage.ACCOUNTS);editor=Editor("account")}
        else editor=Editor("schedule",s?.id)
    }
    LaunchedEffect(quick,locked){if(quick!=null&&!locked){add(requireNotNull(quick));vm.quick(null)}}
    if(locked){
        LockScreen(settings,vm,busy,biometricAvailable,authenticate)
        return
    }
    fun choose(t:Tab){tab=t;if(t==Tab.MORE)more=MorePage.INDEX}
    BoxWithConstraints(Modifier.fillMaxSize()){
        // Keep every navigation destination reachable on short landscape phones.
        val rail=maxWidth>=680.dp && maxHeight>=460.dp
        val allowBanner=maxHeight>=460.dp && LocalDensity.current.fontScale<1.5f
        val largeFont=LocalDensity.current.fontScale>=1.5f
        Row(Modifier.fillMaxSize()){
            if(rail)NavigationRail(modifier=Modifier.fillMaxHeight().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Start+WindowInsetsSides.Top+WindowInsetsSides.Bottom)),
                header={Text("Т.",style=MaterialTheme.typography.headlineSmall,color=MaterialTheme.colorScheme.primary,modifier=Modifier.padding(12.dp))}){
                Tab.entries.forEach{t->NavigationRailItem(selected=t==tab,onClick={choose(t)},modifier=Modifier.testTag("nav-${t.name.lowercase()}"),icon={Icon(t.icon,contentDescription=t.label)},label={Text(t.label)},alwaysShowLabel=!largeFont)}
            }
            Scaffold(modifier=Modifier.weight(1f),containerColor=MaterialTheme.colorScheme.background,
                contentWindowInsets=WindowInsets.safeDrawing,
                bottomBar={if(!rail)NavigationBar{
                    Tab.entries.forEach{t->NavigationBarItem(selected=t==tab,onClick={choose(t)},modifier=Modifier.testTag("nav-${t.name.lowercase()}"),icon={Icon(t.icon,contentDescription=t.label)},label={Text(t.label,maxLines=1)},alwaysShowLabel=!largeFont)}
                }},
                floatingActionButton={if(tab==Tab.OPERATIONS)FloatingActionButton(onClick={add(Kind.EXPENSE)}){Text("+",style=MaterialTheme.typography.headlineSmall)}}){padding->
                Column(Modifier.fillMaxSize().padding(padding)){
                    if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
                    Box(Modifier.weight(1f)){
                        when(tab){
                            Tab.HOME->Dashboard(ledger,{add(it)},{editor=Editor("detail",it.id)},{openMore(MorePage.ACCOUNTS)},{tab=Tab.REPORTS},{tab=Tab.PLAN;plan=PlannerTab.BUDGET},{tab=Tab.OPERATIONS},{tab=Tab.PLAN;plan=PlannerTab.CALENDAR},settings.monthStart,
                                consentContent=if(!BuildConfig.DEBUG && BuildConfig.YANDEX_BANNER_ID.isNotEmpty() && settings.adsConsent==null)
                                    {{AdConsentPanel { accepted->vm.perform{vm.preferences.adsConsent(accepted)} }}} else null)
                            Tab.OPERATIONS->Operations(ledger,{add(it)},{editor=Editor("detail",it.id)},settings.monthStart)
                            Tab.PLAN->Planner(ledger,plan,{editor=Editor("budget",it?.id)},{editor=Editor("goal",it?.id)},
                                {if(ledger.accounts.isEmpty()){openMore(MorePage.ACCOUNTS);editor=Editor("account")}else editor=Editor("debt",it?.id)},
                                {editor=Editor("payment",it.id)},::schedule,{editor=Editor("detail",it.id)},{day->add(Kind.EXPENSE,true,day)})
                            Tab.REPORTS->Reports(ledger,settings.monthStart){period,account->file(FileRequest("pdf",true,period=period,account=account))}
                            Tab.MORE->when(more){
                                MorePage.INDEX->MoreIndex{more=it}
                                MorePage.ACCOUNTS->Accounts(ledger,{editor=Editor("account",it?.id)},{add(Kind.TRANSFER)},{more=MorePage.INDEX}){id,delta->
                                    vm.change({ l->
                                        val sorted=l.accounts.sortedWith(compareBy<Account>{it.sortOrder}.thenBy{it.name}).toMutableList()
                                        val i=sorted.indexOfFirst{it.id==id};val j=i+delta
                                        if(i>=0&&j in sorted.indices)java.util.Collections.swap(sorted,i,j)
                                        l.copy(accounts=sorted.mapIndexed{index,a->a.copy(sortOrder=index)})
                                    })
                                }
                                MorePage.CATEGORIES->Categories(ledger,{editor=Editor("category",it?.id)},{more=MorePage.INDEX})
                                MorePage.RATES->Rates(ledger,settings,vm,busy){more=MorePage.INDEX}
                                MorePage.SETTINGS->SettingsPage(settings,vm,busy,biometricAvailable,authenticate){more=MorePage.INDEX}
                                MorePage.DATA->DataPage(ledger,file,busy){more=MorePage.INDEX}
                                MorePage.ABOUT->AboutPage{more=MorePage.INDEX}
                            }
                        }
                    }
                    // Only the overview may show an ad. Editors, details, quick entry,
                    // lock screen and financial actions remove it from composition.
                    if(tab==Tab.HOME && editor==null && quick==null && !busy && allowBanner) {
                        OverviewAdBanner(settings.adsConsent)
                    }
                }
            }
        }
    }
    androidx.activity.compose.BackHandler(enabled=editor==null&&(tab!=Tab.HOME||more!=MorePage.INDEX)){
        if(tab==Tab.MORE&&more!=MorePage.INDEX)more=MorePage.INDEX else tab=Tab.HOME
    }
    val current=editor
    if(current!=null){
        val close:()->Unit={if(vm.editor===current)vm.editor=null}
        val entryDraft=current.draft
        key(current){
            when(current.type){
                "account"->AccountEditor(ledger,ledger.accounts.find{it.id==current.id},busy,vm,close,entryDraft)
                "category"->CategoryEditor(ledger,ledger.categories.find{it.id==current.id},busy,vm,close,entryDraft)
                "entry","planned"->EntryEditor(ledger,settings,vm,busy,current.kind,current=ledger.entries.find{it.id==current.id},close=close,forcePlanned=current.type=="planned",draft=entryDraft,chooseReceipt=chooseReceipt,initialDay=current.initialDay)
                "schedule"->EntryEditor(ledger,settings,vm,busy,Kind.EXPENSE,schedule=ledger.schedules.find{it.id==current.id},recurring=true,close=close,draft=entryDraft,chooseReceipt=chooseReceipt)
                "detail"->ledger.entries.find{it.id==current.id}?.let{e->EntryDetails(ledger,e,vm,busy,close,{
                    editor=when(e.kind){
                        Kind.REFUND->Editor("editRefund",e.id)
                        Kind.DEBT_IN,Kind.DEBT_OUT->Editor("editPayment",e.id)
                        else->Editor("entry",e.id,e.kind)
                    }
                },{editor=Editor("refund",e.id)})}
                "refund"->ledger.entries.find{it.id==current.id}?.let{RefundEditor(ledger,it,vm,busy,close,draft=entryDraft)}
                "editRefund"->ledger.entries.find{it.id==current.id}?.let{r->ledger.entries.find{it.id==r.originalId}?.let{o->RefundEditor(ledger,o,vm,busy,close,r,entryDraft)}}
                "editPayment"->ledger.entries.find{it.id==current.id}?.let{p->ledger.debts.find{it.id==p.debtId}?.let{d->DebtPaymentEditor(ledger,d,vm,busy,close,p,entryDraft)}}
                "budget"->BudgetEditor(ledger,ledger.budgets.find{it.id==current.id},vm,busy,close,entryDraft)
                "goal"->GoalEditor(ledger,ledger.goals.find{it.id==current.id},vm,busy,close,entryDraft)
                "debt"->DebtEditor(ledger,ledger.debts.find{it.id==current.id},vm,busy,close,entryDraft)
                "payment"->ledger.debts.find{it.id==current.id}?.let{DebtPaymentEditor(ledger,it,vm,busy,close,draft=entryDraft)}
            }
        }
    }
}
@Composable private fun LockScreen(settings:Settings,vm:AppViewModel,busy:Boolean,biometricAvailable:Boolean,authenticate:((()->Unit)->Unit)){
    var pin by remember{mutableStateOf("")}
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),contentAlignment=Alignment.Center){
        Column(Modifier.widthIn(max=440.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(18.dp)){
            Text("ТРАЧУ.",style=MaterialTheme.typography.displaySmall,color=MaterialTheme.colorScheme.primary)
            Text("Ваши финансы защищены",style=MaterialTheme.typography.headlineSmall)
            PinField("PIN-код",pin,{pin=it})
            Button(onClick={vm.unlockPin(pin);pin=""},enabled=!busy&&pin.length>=4,modifier=Modifier.fillMaxWidth()){Text("Разблокировать")}
            if(settings.biometric&&biometricAvailable)OutlinedButton(onClick={authenticate(vm::unlockBiometric)},enabled=!busy,modifier=Modifier.fillMaxWidth()){Text("Войти с биометрией")}
            if(settings.lockedUntil>System.currentTimeMillis())Warning("Слишком много попыток. Подождите 30 секунд.")
        }
    }
}
