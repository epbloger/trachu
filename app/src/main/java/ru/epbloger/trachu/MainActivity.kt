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
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
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
                    p!=null && s!=null && (l!=null||p.isBackupRestore()) && (!s.protected||u) && !b
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
            // Only the tutorial step is saveable; no financial fields or passwords.
            var tutorialStep by rememberSaveable{mutableStateOf<TutorialStep?>(null)}
            SideEffect {
                if(settings==null || settings?.protected == true) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
            TrachuTheme(settings?.theme ?: "system") {
                Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background) {
                    val s=settings
                    val l=ledger
                    if(s!=null && s.protected && !unlocked && l==null && loadingError!=null) {
                        LockScreen(s,vm,busy,biometricAvailable()){done->authenticate(done)}
                    } else if(s==null||l==null)Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),contentAlignment=Alignment.Center){
                        val error=loadingError
                        if(error==null)CircularProgressIndicator()
                        else RecoveryScreen(error,canRestore=s!=null&&l==null,busy=busy,retry=vm::retryLoad,file=::launchFile)
                    }
                    else if(!s.protected && needsOnboarding(l,s)) OnboardingScreen(l,s,vm,busy,::launchFile){start->
                        tutorialStep=if(start)TutorialStep.EXPENSE else null
                    }
                    else AppShell(l,s,vm,busy,biometricAvailable(),s.protected&&!unlocked,{done->authenticate(done)},::launchFile,::chooseReceipt,tutorialStep){tutorialStep=it}
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

@Composable private fun RecoveryScreen(error:String,canRestore:Boolean,busy:Boolean,retry:()->Unit,file:(FileRequest)->Unit){
    var restoring by remember{mutableStateOf(false)}
    Column(Modifier.widthIn(max=480.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(16.dp)){
        Text(error,style=MaterialTheme.typography.bodyLarge)
        Button(onClick=retry,enabled=!busy){Text("Повторить")}
        if(canRestore) {
            Text("Если повторное открытие не помогает, можно восстановить сохранённую зашифрованную копию. Текущая база не будет удалена до проверки файла.")
            OutlinedButton(onClick={restoring=true},enabled=!busy){Text("Восстановить из копии")}
        }
    }
    if(restoring&&canRestore){
        var password by remember{mutableStateOf("")}
        Form("Восстановление",{restoring=false},busy,"Выбрать файл",onSave={
            if(password.length>=8){file(FileRequest("backup",false,password));restoring=false}
        }){
            Warning("После проверки пароля и целостности копия полностью заменит финансовую историю. Неверный файл не изменит текущие записи.")
            OutlinedTextField(password,{password=it},Modifier.fillMaxWidth(),label={Text("Пароль резервной копии")},
                visualTransformation=androidx.compose.ui.text.input.PasswordVisualTransformation(),
                keyboardOptions=androidx.compose.foundation.text.KeyboardOptions(keyboardType=androidx.compose.ui.text.input.KeyboardType.Password),singleLine=true)
            if(password.length<8)Text("Пароль содержит не менее 8 символов.",style=MaterialTheme.typography.bodySmall)
        }
    }
}

private enum class Tab(val label:String,val icon:ImageVector) {
    HOME("Обзор",Icons.Outlined.Home), OPERATIONS("Операции",Icons.Outlined.AccountBalanceWallet),
    PLAN("План",Icons.Outlined.CalendarMonth), REPORTS("Аналитика",Icons.Outlined.BarChart), MORE("Инструменты",Icons.Outlined.MoreHoriz)
}
internal class Editor(val type:String,val id:String?=null,val kind:Kind=Kind.EXPENSE,val initialDay:Long?=null,val draft:EntryDraft=EntryDraft())

@Composable private fun AppShell(ledger:Ledger,settings:Settings,vm:AppViewModel,busy:Boolean,biometricAvailable:Boolean,locked:Boolean,authenticate:((()->Unit)->Unit),file:(FileRequest)->Unit,chooseReceipt:()->Unit,tutorialStep:TutorialStep?,onTutorialStep:(TutorialStep?)->Unit){
    var tab by rememberSaveable{mutableStateOf(Tab.HOME)}
    var more by rememberSaveable{mutableStateOf(MorePage.INDEX)}
    var returnTab by rememberSaveable{mutableStateOf(Tab.HOME)}
    var plan by rememberSaveable{mutableStateOf(PlannerTab.BUDGET)}
    var editor by vm.editorState
    var waitingForTutorialEditor by rememberSaveable{mutableStateOf(false)}
    val quick by vm.quick.collectAsStateWithLifecycle()
    LaunchedEffect(settings.adsConsent){updateAdsConsent(settings.adsConsent==true)}
    fun startTutorial(){tab=Tab.HOME;more=MorePage.INDEX;waitingForTutorialEditor=false;onTutorialStep(TutorialStep.EXPENSE)}
    fun openMore(page:MorePage){
        if(page==MorePage.TUTORIAL)startTutorial()
        else {if(tab!=Tab.MORE)returnTab=tab;tab=Tab.MORE;more=page}
    }
    fun backFromMore(){
        if(more==MorePage.RATES)more=MorePage.CONVERTER
        else {tab=returnTab;more=MorePage.INDEX}
    }
    fun add(kind:Kind,planned:Boolean=false,initialDay:Long?=null){
        if(ledger.accounts.none{!it.archived}){openMore(MorePage.ACCOUNTS);editor=Editor("account")}
        else editor=Editor(if(planned)"planned" else "entry",kind=kind,initialDay=initialDay)
    }
    fun schedule(s:Schedule?){
        if(ledger.accounts.none{!it.archived}){openMore(MorePage.ACCOUNTS);editor=Editor("account")}
        else editor=Editor("schedule",s?.id)
    }
    LaunchedEffect(quick,locked,busy){if(quick!=null&&!locked&&!busy){
        onTutorialStep(null);waitingForTutorialEditor=false
        if(editor==null&&!vm.pickerPending()){
            if(tab==Tab.MORE&&more==MorePage.TUTORIAL){tab=Tab.HOME;more=MorePage.INDEX}
            add(requireNotNull(quick))
        }
        else vm.notice("Сначала завершите текущую операцию. Черновик сохранён.")
        vm.quick(null)
    }}
    if(locked){
        // Keep only the non-sensitive walkthrough state and existing in-memory
        // draft; neither the underlying financial UI nor its spotlight is composed.
        LockScreen(settings,vm,busy,biometricAvailable,authenticate)
        return
    }
    fun choose(t:Tab){tab=t;if(t==Tab.MORE)more=MorePage.INDEX}
    val drawerState=rememberDrawerState(DrawerValue.Closed)
    val scope=rememberCoroutineScope()
    val focus=LocalFocusManager.current
    var anchor by remember(tutorialStep){mutableStateOf<Rect?>(null)}
    var advanced by remember(tutorialStep){mutableStateOf(false)}
    fun target(step:TutorialStep)=Modifier.tutorialAnchor(
        tutorialStep==step&&!waitingForTutorialEditor,probeKey=step.name.lowercase()
    ){anchor=it}
    fun stopTutorial(){onTutorialStep(null);waitingForTutorialEditor=false;scope.launch{drawerState.close()}}
    LaunchedEffect(tab,more){if(tab==Tab.MORE&&more==MorePage.TUTORIAL)startTutorial()}
    LaunchedEffect(tutorialStep){if(tutorialStep!=null)focus.clearFocus()}
    LaunchedEffect(editor,waitingForTutorialEditor,tutorialStep){
        if(waitingForTutorialEditor && editor==null){tab=Tab.HOME;more=MorePage.INDEX;waitingForTutorialEditor=false}
    }
    val guideNeedsDrawer=tutorialStep==TutorialStep.CATEGORIES||tutorialStep==TutorialStep.CONVERTER
    LaunchedEffect(tutorialStep,busy,editor){
        if(guideNeedsDrawer&&!busy&&editor==null)drawerState.open()
    }
    fun advanceTutorial(){
        val step=tutorialStep ?: return
        if(advanced||busy||editor!=null)return
        advanced=true
        when(step){
            TutorialStep.EXPENSE, TutorialStep.INCOME->{
                waitingForTutorialEditor=true
                add(if(step==TutorialStep.EXPENSE)Kind.EXPENSE else Kind.INCOME)
            }
            TutorialStep.OPERATIONS->choose(Tab.OPERATIONS)
            TutorialStep.PLAN->choose(Tab.PLAN)
            TutorialStep.REPORTS->choose(Tab.REPORTS)
            TutorialStep.MENU->Unit // The CATEGORIES step opens the real drawer, also after rotation.
            TutorialStep.CATEGORIES->{openMore(MorePage.CATEGORIES);scope.launch{drawerState.close()}}
            TutorialStep.MENU_CONVERTER->Unit
            TutorialStep.CONVERTER->{openMore(MorePage.CONVERTER);scope.launch{drawerState.close()}}
            TutorialStep.DONE->Unit
        }
        onTutorialStep(step.next)
    }
    val drawerOpen=drawerState.currentValue==DrawerValue.Open&&drawerState.targetValue==DrawerValue.Open
    val drawerClosed=drawerState.currentValue==DrawerValue.Closed&&drawerState.targetValue==DrawerValue.Closed
    val spotlight=tutorialStep!=null&&editor==null&&!busy&&!waitingForTutorialEditor&&quick==null&&
        (if(guideNeedsDrawer)drawerOpen else drawerClosed)
    val mainTabs=Tab.entries.filter{it!=Tab.MORE}
    val drawerPages=listOf(MorePage.ACCOUNTS,MorePage.CATEGORIES,MorePage.CONVERTER,
        MorePage.DATA,MorePage.SETTINGS,MorePage.ABOUT)
    LaunchedEffect(editor){if(editor!=null && drawerState.targetValue!=DrawerValue.Closed)drawerState.close()}
    Box(Modifier.fillMaxSize()){
    // Keep real layouts composed for the spotlight. Hide their accessibility actions
    // while the guide owns input; its target and Next provide the same action.
    Box(Modifier.fillMaxSize().then(if(spotlight)Modifier.clearAndSetSemantics{} else Modifier)){
    ModalNavigationDrawer(
        drawerState=drawerState,
        gesturesEnabled=editor==null && !busy && tutorialStep==null,
        drawerContent={
            ModalDrawerSheet {
                val visible=drawerState.currentValue==DrawerValue.Open || drawerState.targetValue==DrawerValue.Open
                Column(Modifier.then(if(visible)Modifier else Modifier.clearAndSetSemantics{}).fillMaxHeight().verticalScroll(rememberScrollState()).padding(horizontal=12.dp),
                    verticalArrangement=Arrangement.spacedBy(2.dp)){
                    Text("ТРАЧУ.",style=MaterialTheme.typography.headlineLarge,color=MaterialTheme.colorScheme.primary,modifier=Modifier.padding(16.dp))
                    Text("Личные финансы",style=MaterialTheme.typography.bodyMedium,modifier=Modifier.padding(horizontal=16.dp))
                    Spacer(Modifier.height(8.dp))
                    drawerPages.forEach{page->
                        if(page==MorePage.ACCOUNTS || page==MorePage.DATA){
                            if(page==MorePage.DATA)HorizontalDivider(Modifier.padding(vertical=8.dp))
                            Text(if(page==MorePage.ACCOUNTS)"Управление" else "Приложение",style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(horizontal=16.dp,vertical=4.dp))
                        }
                        NavigationDrawerItem(label={Text(page.label)},selected=tab==Tab.MORE&&(more==page||page==MorePage.CONVERTER&&more==MorePage.RATES),
                            onClick={openMore(page);scope.launch{drawerState.close()}},modifier=Modifier.testTag("drawer-${page.name.lowercase()}").then(when(page){MorePage.CATEGORIES->target(TutorialStep.CATEGORIES);MorePage.CONVERTER->target(TutorialStep.CONVERTER);else->Modifier}))
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    ){
    BoxWithConstraints(Modifier.fillMaxSize()){
        // Keep every navigation destination reachable on short landscape phones.
        val rail=maxWidth>=680.dp && maxHeight>=460.dp
        val allowBanner=maxHeight>=460.dp && LocalDensity.current.fontScale<1.5f
        val largeFont=LocalDensity.current.fontScale>=1.5f
        Row(Modifier.fillMaxSize()){
            if(rail)NavigationRail(modifier=Modifier.fillMaxHeight().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Start+WindowInsetsSides.Top+WindowInsetsSides.Bottom)),
                header={Text("Т.",style=MaterialTheme.typography.headlineSmall,color=MaterialTheme.colorScheme.primary,modifier=Modifier.padding(12.dp))}){
                mainTabs.forEach{t->NavigationRailItem(selected=t==tab,onClick={choose(t)},modifier=Modifier.testTag("nav-${t.name.lowercase()}").then(when(t){Tab.OPERATIONS->target(TutorialStep.OPERATIONS);Tab.PLAN->target(TutorialStep.PLAN);Tab.REPORTS->target(TutorialStep.REPORTS);else->Modifier}),icon={Icon(t.icon,contentDescription=t.label)},label={Text(t.label)},alwaysShowLabel=!largeFont)}
            }
            Scaffold(modifier=Modifier.weight(1f),containerColor=MaterialTheme.colorScheme.background,
                contentWindowInsets=WindowInsets.safeDrawing,
                topBar={
                    Surface(color=MaterialTheme.colorScheme.background){
                        Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top+WindowInsetsSides.Horizontal))
                            .padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically){
                            IconButton(onClick={scope.launch{drawerState.open()}},enabled=editor==null&&!busy,modifier=Modifier.testTag("nav-more").then(target(if(tutorialStep==TutorialStep.MENU_CONVERTER)TutorialStep.MENU_CONVERTER else TutorialStep.MENU))){
                                Icon(Icons.Outlined.Menu,contentDescription="Открыть меню")
                            }
                            Text("ТРАЧУ.",style=MaterialTheme.typography.titleLarge,color=MaterialTheme.colorScheme.primary)
                        }
                    }
                },
                bottomBar={if(!rail)NavigationBar{
                    mainTabs.forEach{t->NavigationBarItem(selected=t==tab,onClick={choose(t)},modifier=Modifier.testTag("nav-${t.name.lowercase()}").then(when(t){Tab.OPERATIONS->target(TutorialStep.OPERATIONS);Tab.PLAN->target(TutorialStep.PLAN);Tab.REPORTS->target(TutorialStep.REPORTS);else->Modifier}),icon={Icon(t.icon,contentDescription=t.label)},label={Text(t.label,maxLines=1)},alwaysShowLabel=!largeFont)}
                }},
                floatingActionButton={if(tab==Tab.OPERATIONS)ExtendedFloatingActionButton(onClick={add(Kind.EXPENSE)},modifier=Modifier.testTag("operation-add")){Text("+ Операция")}}){padding->
                Column(Modifier.fillMaxSize().padding(padding)){
                    if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
                    Box(Modifier.weight(1f)){
                        when(tab){
                            Tab.HOME->key(tutorialStep==TutorialStep.EXPENSE||tutorialStep==TutorialStep.INCOME){Dashboard(ledger,{add(it)},{editor=Editor("detail",it.id)},{openMore(MorePage.ACCOUNTS)},{tab=Tab.REPORTS},{tab=Tab.PLAN;plan=PlannerTab.BUDGET},{tab=Tab.OPERATIONS},{tab=Tab.PLAN;plan=PlannerTab.CALENDAR},settings.monthStart,
                                consentContent=if(!BuildConfig.DEBUG && BuildConfig.YANDEX_BANNER_ID.isNotEmpty() && settings.adsConsent==null)
                                    {{AdConsentPanel { accepted->vm.perform{vm.preferences.adsConsent(accepted)} }}} else null,
                                tutorialTarget={id->target(if(id=="expense")TutorialStep.EXPENSE else TutorialStep.INCOME)})}
                            Tab.OPERATIONS->Operations(ledger,{editor=Editor("detail",it.id)},settings.monthStart)
                            Tab.PLAN->Planner(ledger,plan,{editor=Editor("budget",it?.id)},{editor=Editor("goal",it?.id)},
                                {if(ledger.accounts.isEmpty()){openMore(MorePage.ACCOUNTS);editor=Editor("account")}else editor=Editor("debt",it?.id)},
                                {editor=Editor("payment",it.id)},::schedule,{editor=Editor("detail",it.id)},{day->add(Kind.EXPENSE,true,day)},
                                adjustGoal={g,withdraw->editor=Editor(if(withdraw)"goalWithdraw" else "goalFund",g.id)})
                            Tab.REPORTS->Reports(ledger,settings.monthStart){period,account->file(FileRequest("pdf",true,period=period,account=account))}
                            Tab.MORE->when(more){
                                MorePage.INDEX->LaunchedEffect(Unit){backFromMore()}
                                MorePage.ACCOUNTS->Accounts(ledger,{editor=Editor("account",it?.id)},{add(Kind.TRANSFER)},::backFromMore){id,delta->
                                    vm.change({ l->
                                        val sorted=l.accounts.sortedWith(compareBy<Account>{it.sortOrder}.thenBy{it.name}).toMutableList()
                                        val i=sorted.indexOfFirst{it.id==id};val j=i+delta
                                        if(i>=0&&j in sorted.indices)java.util.Collections.swap(sorted,i,j)
                                        l.copy(accounts=sorted.mapIndexed{index,a->a.copy(sortOrder=index)})
                                    })
                                }
                                MorePage.CATEGORIES->Categories(ledger,{editor=Editor("category",it?.id)},::backFromMore)
                                MorePage.CONVERTER->CurrencyConverter(ledger,vm,busy,::backFromMore,{more=MorePage.RATES})
                                MorePage.RATES->Rates(ledger,settings,vm,busy,::backFromMore)
                                MorePage.SETTINGS->SettingsPage(ledger,settings,vm,busy,biometricAvailable,authenticate,::backFromMore,file)
                                MorePage.DATA->DataPage(ledger,file,busy,::backFromMore){choose(Tab.REPORTS)}
                                MorePage.ABOUT->AboutPage(::backFromMore,::startTutorial)
                                MorePage.TUTORIAL->LaunchedEffect(Unit){startTutorial()}
                            }
                        }
                    }
                    // Only the overview may show an ad. Editors, details, quick entry,
                    // lock screen and financial actions remove it from composition.
                    if(tab==Tab.HOME && editor==null && quick==null && !busy && allowBanner && tutorialStep==null &&
                        drawerState.currentValue==DrawerValue.Closed && drawerState.targetValue==DrawerValue.Closed) {
                        OverviewAdBanner(settings.adsConsent)
                    }
                }
            }
        }
    }
    }
    }
    if(spotlight)TutorialSpotlight(requireNotNull(tutorialStep),anchor,::advanceTutorial,::stopTutorial)
    }
    androidx.activity.compose.BackHandler(enabled=editor==null&&drawerState.currentValue==DrawerValue.Closed&&drawerState.targetValue==DrawerValue.Closed&&tab!=Tab.HOME){
        if(tab==Tab.MORE)backFromMore() else tab=Tab.HOME
    }
    androidx.activity.compose.BackHandler(enabled=editor==null&&(drawerState.currentValue==DrawerValue.Open||drawerState.targetValue==DrawerValue.Open)){
        scope.launch{drawerState.close()}
    }
    androidx.activity.compose.BackHandler(enabled=editor==null&&tutorialStep!=null){stopTutorial()}
    val current=editor
    if(current!=null){
        val close:()->Unit={if(vm.editor===current)vm.editor=null}
        val entryDraft=current.draft
        key(current){
            when(current.type){
                "account"->AccountEditor(ledger,ledger.accounts.find{it.id==current.id},busy,vm,close,entryDraft)
                "category"->CategoryEditor(ledger,ledger.categories.find{it.id==current.id},busy,vm,close,entryDraft)
                "entry","planned"->CompositionLocalProvider(LocalGuidedEditorHint provides (waitingForTutorialEditor&&tutorialStep!=null&&current.type=="entry")){
                    EntryEditor(ledger,settings,vm,busy,current.kind,current=ledger.entries.find{it.id==current.id},close=close,forcePlanned=current.type=="planned",draft=entryDraft,chooseReceipt=chooseReceipt,initialDay=current.initialDay)
                }
                "schedule"->EntryEditor(ledger,settings,vm,busy,Kind.EXPENSE,schedule=ledger.schedules.find{it.id==current.id},recurring=true,close=close,draft=entryDraft,chooseReceipt=chooseReceipt)
                "detail"->ledger.entries.find{it.id==current.id}?.let{e->EntryDetails(ledger,e,vm,busy,close,{
                    editor=when(e.kind){
                        Kind.REFUND->Editor("editRefund",e.id)
                        Kind.DEBT_IN,Kind.DEBT_OUT->Editor("editPayment",e.id)
                        else->Editor("entry",e.id,e.kind)
                    }
                },{editor=Editor("refund",e.id)},{editor=Editor("entry",kind=e.kind,draft=repeatEntryDraft(ledger,e))})}
                "refund"->ledger.entries.find{it.id==current.id}?.let{RefundEditor(ledger,it,vm,busy,close,draft=entryDraft)}
                "editRefund"->ledger.entries.find{it.id==current.id}?.let{r->ledger.entries.find{it.id==r.originalId}?.let{o->RefundEditor(ledger,o,vm,busy,close,r,entryDraft)}}
                "editPayment"->ledger.entries.find{it.id==current.id}?.let{p->ledger.debts.find{it.id==p.debtId}?.let{d->DebtPaymentEditor(ledger,d,vm,busy,close,p,entryDraft)}}
                "budget"->BudgetEditor(ledger,ledger.budgets.find{it.id==current.id},vm,busy,close,entryDraft)
                "goal"->GoalEditor(ledger,ledger.goals.find{it.id==current.id},vm,busy,close,entryDraft)
                "goalFund","goalWithdraw"->ledger.goals.find{it.id==current.id}?.let{GoalFundingEditor(it,vm,busy,close,entryDraft,withdraw=current.type=="goalWithdraw")}
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
