package ru.epbloger.trachu

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.mutableStateOf
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.epbloger.trachu.core.*
import ru.epbloger.trachu.data.*
import ru.epbloger.trachu.security.*
import ru.epbloger.trachu.worker.FinanceWorker
import ru.epbloger.trachu.worker.RatesWorker
import ru.epbloger.trachu.widget.refreshWidgets
import ru.epbloger.trachu.ui.EntryDraft
import ru.epbloger.trachu.ui.FileRequest
import ru.epbloger.trachu.ui.firstAccountLedger
import ru.epbloger.trachu.ui.isPristineLedger

internal sealed interface PickedResult {
    data class File(val uri:Uri,val request:FileRequest):PickedResult
    data class Photo(val uri:Uri,val draft:EntryDraft):PickedResult
}
internal fun PickedResult.isBackupRestore():Boolean = this is PickedResult.File && !request.save && request.mode=="backup"

class AppViewModel(app: Application) : AndroidViewModel(app) {
    // Activity recreation retains this state without serializing financial drafts,
    // backup passwords or receipt bytes into Android's saved-instance state.
    internal val editorState=mutableStateOf<Editor?>(null)
    internal var onboardingDraft=EntryDraft()
    internal var editor:Editor?
        get()=editorState.value
        set(value){editorState.value=value}
    internal var fileRequest:FileRequest?=null
    internal var receiptRequest:EntryDraft?=null
    private val _pickedResult=MutableStateFlow<PickedResult?>(null)
    internal val pickedResult=_pickedResult.asStateFlow()
    internal fun pickerPending()=fileRequest!=null||receiptRequest!=null||_pickedResult.value!=null
    internal fun queuePickedResult(result:PickedResult){
        if(_pickedResult.value!=null)return
        _pickedResult.value=result
    }
    internal fun deliverPickedResult(){
        val s=_settings.value ?: return
        val result=_pickedResult.value ?: return
        if((_ledger.value==null&&!result.isBackupRestore()) || _busy.value || (s.protected&&!_unlocked.value))return
        // Consume before starting I/O so activity recreation cannot repeat it.
        _pickedResult.value=null
        when(result){
            is PickedResult.File->with(result.request){
                if(save)export(result.uri,mode,password,period,account)
                else import(result.uri,mode,password,account)
            }
            is PickedResult.Photo->if(editor?.draft===result.draft){
                readReceipt(result.uri){if(editor?.draft===result.draft)result.draft.attach(it)}
            }
        }
    }
    internal var stoppedAt:Long?=null
    internal var activityCreated=false
    private val store=LedgerStore.get(app)
    val preferences=Preferences(app)
    private val _ledger=MutableStateFlow<Ledger?>(null)
    val ledger=_ledger.asStateFlow()
    private val _settings=MutableStateFlow<Settings?>(null)
    val settings=_settings.asStateFlow()
    private val _loadingError=MutableStateFlow<String?>(null)
    val loadingError=_loadingError.asStateFlow()
    private var ledgerJob:Job?=null
    private var settingsJob:Job?=null
    private val _message=MutableStateFlow<String?>(null)
    val message=_message.asStateFlow()
    private val _busy=MutableStateFlow(false)
    val busy=_busy.asStateFlow()
    private val _unlocked=MutableStateFlow(false)
    val unlocked=_unlocked.asStateFlow()
    private val _quick=MutableStateFlow<Kind?>(null)
    val quick=_quick.asStateFlow()
    init {
        retryLoad()
        FinanceWorker.refresh(app)
    }
    fun retryLoad() {
        ledgerJob?.cancel()
        settingsJob?.cancel()
        _loadingError.value=null
        _ledger.value=null
        _settings.value=null
        ledgerJob=viewModelScope.launch {
            store.ledger.catch {
                _ledger.value=null
                _loadingError.value="Не удалось открыть финансовые данные. Повторите попытку."
            }.collect {
                // A previously used ledger must not return to first launch if all accounts are removed later.
                if(!isPristineLedger(it) && _settings.value?.onboardingCompleted!=true) {
                    try { preferences.completeOnboarding() }
                    catch(e:kotlinx.coroutines.CancellationException) { throw e }
                    catch(_:Exception) { /* Settings loading reports its own errors. */ }
                }
                _ledger.value=it
            }
        }
        settingsJob=viewModelScope.launch {
            preferences.settings.catch {
                _settings.value=null
                _unlocked.value=false
                _loadingError.value="Не удалось открыть настройки защиты. Повторите попытку."
            }.collect {
                val previous=_settings.value
                _settings.value=it
                if(previous==null)runCatching { RatesWorker.configure(getApplication(),it.automaticRates) }
            }
        }
    }
    fun clearMessage() { _message.value=null }
    internal fun notice(text:String){_message.value=text}
    fun rememberAccount(id: String) { viewModelScope.launch { preferences.lastAccount(id) } }
    fun quick(kind: Kind?) { _quick.value=kind }
    internal fun finishOnboarding(currency:String,name:String,type:AccountType,opening:String) = perform {
        val s=preferences.current()
        require(!s.protected && !s.onboardingCompleted) { "Первоначальная настройка уже завершена" }
        require(!pickerPending()) { "Сначала завершите восстановление резервной копии." }
        val next=store.change { firstAccountLedger(it,currency,name,type,opening) }
        preferences.completeOnboarding(next.accounts.single().id)
        onboardingDraft=EntryDraft()
        runCatching { FinanceWorker.refresh(getApplication()) }
    }
    fun lock() { _unlocked.value=false; _message.value=null }
    fun unlockBiometric() = perform {
        val s=preferences.current()
        require(s.protected && s.biometric) { "Биометрический вход не настроен" }
        preferences.pinResult(true)
        _unlocked.value=true
    }
    private suspend fun verifyPin(pin:String, s:Settings) {
        require(System.currentTimeMillis()>=s.lockedUntil) { "Слишком много попыток. Повторите через 30 секунд." }
        val ok=withContext(Dispatchers.Default) { PinVault.verify(pin,s.credential) }
        preferences.pinResult(ok)
        require(ok) { "Неверный PIN" }
    }
    fun unlockPin(pin: String) = perform {
        val s=preferences.current()
        verifyPin(pin,s)
        _unlocked.value=true
    }
    fun changePin(pin: String, old: String, onDone: ()->Unit = {}) = perform {
        val s=preferences.current()
        if(s.protected) verifyPin(old,s)
        val credential=if(pin.isBlank()) "" else withContext(Dispatchers.Default) { PinVault.create(pin) }
        preferences.setCredential(credential)
        _unlocked.value=true
        // Never leave a previously visible balance on the launcher after enabling protection.
        runCatching { refreshWidgets(getApplication()) }
        onDone()
    }
    fun perform(task: suspend () -> Unit) {
        if(_busy.value) return
        _busy.value=true
        viewModelScope.launch {
            try { task() }
            catch(e: kotlinx.coroutines.CancellationException) { throw e }
            catch(e: Exception) { _message.value=e.message ?: "Не удалось выполнить действие" }
            finally { _busy.value=false }
        }
    }
    fun change(transform: (Ledger)->Ledger, onDone: ()->Unit = {}) = perform {
        if(_settings.value?.onboardingCompleted!=true && _ledger.value?.let{!isPristineLedger(it)}==true) {
            try { preferences.completeOnboarding() }
            catch(e:kotlinx.coroutines.CancellationException) { throw e }
            catch(_:Exception) { /* An unavailable settings store must not block ledger edits. */ }
        }
        store.change(transform)
        runCatching { FinanceWorker.refresh(getApplication()) }
        onDone()
    }
    fun saveEntry(entry: Entry, receipt: Receipt? = null, onDone: ()->Unit={}) = change({ old ->
        var next=old
        if(receipt!=null) next=next.copy(receipts=next.receipts+receipt)
        Finance.putEntry(next,entry)
    }) { viewModelScope.launch { preferences.lastAccount(entry.accountId) }; onDone() }
    fun setAutomaticRates(enabled:Boolean) = perform {
        preferences.automaticRates(enabled)
        RatesWorker.configure(getApplication(),enabled,immediate=enabled)
    }
    fun fetchRates(day: Long) = perform {
        val base=store.current().baseCurrency
        val rates=withContext(Dispatchers.IO) { ExchangeRates.fetch(base,day) }
        store.change { l -> Finance.mergePublishedRates(l,base,rates) }
        _message.value="Курсы сохранены"
    }
    fun export(uri: Uri, mode: String, password: String="", period: Period=Period.month(), accountId:String="") = perform {
        val current=store.current()
        withContext(Dispatchers.IO) {
            val resolver=getApplication<Application>().contentResolver
            if(mode=="backup") {
                // Produce and finish authentication before opening the destination.
                // A rejected backup must not truncate an existing selected file.
                Backup.writeStaged(current,password.toCharArray(),getApplication<Application>().cacheDir) {
                    requireNotNull(resolver.openOutputStream(uri,"wt"))
                }
            } else requireNotNull(resolver.openOutputStream(uri,"wt")).use { out ->
                if(mode=="pdf") PdfReport.write(current,period,out,accountId) else Csv.write(current,out)
            }
        }
        _message.value="Файл сохранён"
    }
    fun import(uri: Uri, mode: String, password: String="", accountId: String="") = perform {
        val protection=preferences.current()
        require(!protection.protected || _unlocked.value) { "Сначала разблокируйте приложение" }
        val resolver=getApplication<Application>().contentResolver
        withContext(Dispatchers.IO) {
            requireNotNull(resolver.openInputStream(uri)).use { input ->
                when(mode) {
                    "backup" -> store.restore(Backup.read(password.toCharArray(),input))
                    "statement" -> store.change { Csv.importStatement(input,it,accountId) }
                    else -> {
                        val imported=Csv.read(input)
                        store.change { if(it.accounts.isEmpty() && it.entries.isEmpty()) imported else Csv.merge(it,imported) }
                    }
                }
            }
        }
        runCatching { FinanceWorker.refresh(getApplication()) }
        if(mode=="backup") {
            try { preferences.completeOnboarding() }
            catch(e:kotlinx.coroutines.CancellationException) { throw e }
            catch(_:Exception) { /* The verified backup has already been restored. */ }
            onboardingDraft=EntryDraft()
            if(_ledger.value==null)retryLoad()
        }
        _message.value="Данные загружены"
    }
    fun readReceipt(uri: Uri, onDone: (Receipt)->Unit) = perform {
        val receipt=withContext(Dispatchers.IO) {
            val resolver=getApplication<Application>().contentResolver
            val mime=resolver.getType(uri).orEmpty()
            require(mime in setOf("image/jpeg","image/png","image/webp")) { "Выберите JPEG, PNG или WebP" }
            val bytes=requireNotNull(resolver.openInputStream(uri)).use { it.readBounded(5*1024*1024) }
            require(bytes.isNotEmpty())
            val bounds=android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds=true }
            android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
            require(bounds.outWidth>0 && bounds.outHeight>0 && bounds.outMimeType==mime) { "Файл не является поддерживаемым изображением" }
            require(bounds.outWidth<=20000 && bounds.outHeight<=20000) { "Изображение слишком большое" }
            Receipt(mime=mime,base64=android.util.Base64.encodeToString(bytes,android.util.Base64.NO_WRAP))
        }
        onDone(receipt)
    }
}
