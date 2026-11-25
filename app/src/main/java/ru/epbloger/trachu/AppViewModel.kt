package ru.epbloger.trachu

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
import ru.epbloger.trachu.widget.refreshWidgets

class AppViewModel(app: Application) : AndroidViewModel(app) {
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
            }.collect { _ledger.value=it }
        }
        settingsJob=viewModelScope.launch {
            preferences.settings.catch {
                _settings.value=null
                _unlocked.value=false
                _loadingError.value="Не удалось открыть настройки защиты. Повторите попытку."
            }.collect { _settings.value=it }
        }
    }
    fun clearMessage() { _message.value=null }
    fun rememberAccount(id: String) { viewModelScope.launch { preferences.lastAccount(id) } }
    fun quick(kind: Kind?) { _quick.value=kind }
    fun lock() { _unlocked.value=false; _message.value=null }
    fun unlockBiometric() { _unlocked.value=true; viewModelScope.launch { preferences.pinResult(true) } }
    fun unlockPin(pin: String) = perform {
        val s=preferences.current()
        require(System.currentTimeMillis()>=s.lockedUntil) { "Слишком много попыток. Повторите через 30 секунд." }
        val ok=withContext(Dispatchers.Default) { PinVault.verify(pin,s.credential) }
        preferences.pinResult(ok)
        require(ok) { "Неверный PIN" }
        _unlocked.value=true
    }
    fun changePin(pin: String, old: String, onDone: ()->Unit = {}) = perform {
        val s=preferences.current()
        if(s.protected) require(withContext(Dispatchers.Default) { PinVault.verify(old,s.credential) }) { "Неверный текущий PIN" }
        val credential=if(pin.isBlank()) "" else withContext(Dispatchers.Default) { PinVault.create(pin) }
        preferences.setCredential(credential)
        _unlocked.value=true
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
        store.change(transform)
        runCatching { FinanceWorker.refresh(getApplication()) }
        onDone()
    }
    fun saveEntry(entry: Entry, receipt: Receipt? = null, onDone: ()->Unit={}) = change({ old ->
        var next=old
        if(receipt!=null) next=next.copy(receipts=next.receipts+receipt)
        Finance.putEntry(next,entry)
    }) { viewModelScope.launch { preferences.lastAccount(entry.accountId) }; onDone() }
    fun fetchRates(day: Long) = perform {
        val base=store.current().baseCurrency
        val rates=withContext(Dispatchers.IO) { ExchangeRates.fetch(base,day) }
        store.change { l ->
            require(l.baseCurrency==base)
            l.copy(rates=l.rates.filter { old -> rates.none { it.currency==old.currency && it.day==old.day } }+rates)
        }
        _message.value="Курсы сохранены"
    }
    fun export(uri: Uri, mode: String, password: String="", period: Period=Period.month()) = perform {
        val current=store.current()
        withContext(Dispatchers.IO) {
            val resolver=getApplication<Application>().contentResolver
            requireNotNull(resolver.openOutputStream(uri,"wt")).use { out ->
                when(mode) {
                    "backup" -> Backup.write(current,password.toCharArray(),out)
                    "pdf" -> PdfReport.write(current,period,out)
                    else -> Csv.write(current,out)
                }
            }
        }
        _message.value="Файл сохранён"
    }
    fun import(uri: Uri, mode: String, password: String="", accountId: String="") = perform {
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
        FinanceWorker.refresh(getApplication())
        _message.value="Данные загружены"
    }
    fun readReceipt(uri: Uri, onDone: (Receipt)->Unit) = perform {
        val receipt=withContext(Dispatchers.IO) {
            val resolver=getApplication<Application>().contentResolver
            val mime=resolver.getType(uri).orEmpty()
            require(mime in setOf("image/jpeg","image/png","image/webp")) { "Выберите JPEG, PNG или WebP" }
            val bytes=requireNotNull(resolver.openInputStream(uri)).use { it.readBounded(5*1024*1024) }
            require(bytes.isNotEmpty())
            Receipt(mime=mime,base64=android.util.Base64.encodeToString(bytes,android.util.Base64.NO_WRAP))
        }
        onDone(receipt)
    }
}
