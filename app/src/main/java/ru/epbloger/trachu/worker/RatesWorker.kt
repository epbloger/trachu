package ru.epbloger.trachu.worker

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.epbloger.trachu.core.Finance
import ru.epbloger.trachu.core.today
import ru.epbloger.trachu.data.ExchangeRates
import ru.epbloger.trachu.data.LedgerStore
import ru.epbloger.trachu.security.Preferences
import ru.epbloger.trachu.widget.refreshWidgets
import java.util.concurrent.TimeUnit

class RatesWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params) {
    override suspend fun doWork():Result = try {
        val preferences=Preferences(applicationContext)
        if(preferences.current().let{it.automaticRates&&it.onboardingCompleted}) {
            val store=LedgerStore.get(applicationContext)
            val ledger=store.current()
            if(ledger.accounts.isNotEmpty()) {
                val base=ledger.baseCurrency
                val published=withContext(Dispatchers.IO){ExchangeRates.fetch(base,today())}
                if(preferences.current().automaticRates) {
                    store.change { current ->
                        if(current.baseCurrency==base&&current.accounts.isNotEmpty())Finance.mergePublishedRates(current,base,published) else current
                    }
                    refreshWidgets(applicationContext)
                }
            }
        }
        Result.success()
    } catch(e:kotlinx.coroutines.CancellationException){throw e}
    catch(_:Exception){if(runAttemptCount<3)Result.retry() else Result.failure()}

    companion object {
        private const val PERIODIC="rates-periodic"
        private const val IMMEDIATE="rates-refresh"
        fun configure(context:Context,enabled:Boolean,immediate:Boolean=false) {
            val manager=WorkManager.getInstance(context)
            if(!enabled) {
                manager.cancelUniqueWork(PERIODIC)
                manager.cancelUniqueWork(IMMEDIATE)
                return
            }
            val network=Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            manager.enqueueUniquePeriodicWork(PERIODIC,ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<RatesWorker>(24,TimeUnit.HOURS).setConstraints(network).setInitialDelay(24,TimeUnit.HOURS).build())
            if(immediate)manager.enqueueUniqueWork(IMMEDIATE,ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<RatesWorker>().setConstraints(network).build())
        }
    }
}
