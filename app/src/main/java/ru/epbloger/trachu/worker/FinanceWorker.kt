package ru.epbloger.trachu.worker

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import ru.epbloger.trachu.MainActivity
import ru.epbloger.trachu.R
import ru.epbloger.trachu.core.*
import ru.epbloger.trachu.data.LedgerStore
import ru.epbloger.trachu.security.Preferences
import ru.epbloger.trachu.widget.refreshWidgets
import java.time.YearMonth
import java.util.concurrent.TimeUnit

class FinanceWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context,params) {
    override suspend fun doWork(): Result = try {
        val store=LedgerStore.get(applicationContext)
        val generation=store.currentRateGeneration()
        val ledger=store.processDue()
        val prefs=Preferences(applicationContext)
        store.withCurrentFinancialGeneration(generation) {
            val manager=applicationContext.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel("finance","Финансовые напоминания",NotificationManager.IMPORTANCE_DEFAULT))
            if (prefs.current().notificationsEnabled!=false && manager.areNotificationsEnabled() && manager.getNotificationChannel("finance")?.importance!=NotificationManager.IMPORTANCE_NONE &&
                (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(applicationContext,Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED)) {
                val pending=PendingIntent.getActivity(applicationContext,0,Intent(applicationContext,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                suspend fun show(id: Int, message: String) {
                    if(prefs.current().notificationsEnabled==false)return
                    manager.notify(id,NotificationCompat.Builder(applicationContext,"finance")
                        .setSmallIcon(R.drawable.ic_notification).setContentTitle("ТРАЧУ.")
                        .setContentText(message).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                        .setAutoCancel(true).setOnlyAlertOnce(true).setContentIntent(pending).build())
                }
                val day=today()
                val now=java.time.LocalTime.now()
                if(ledger.entries.any { it.status==EntryStatus.PLANNED && Finance.isDue(it,day,now.hour*60+now.minute) } && prefs.markNotice("planned",day.toString()))
                    show(100,"Есть запланированные операции. Проверьте календарь.")
                if(ledger.debts.any { it.dueDay!=null && it.dueDay<=day && Finance.debtHasOutstandingAmount(ledger,it,day,includeUndatedInterest=false) } && prefs.markNotice("debt",day.toString()))
                    show(101,"Есть обязательства с наступившим сроком.")
                val month=YearMonth.now().toString()
                ledger.budgets.forEach { budget ->
                    val level=runCatching{Finance.budget(ledger,budget,day).level}.getOrNull() ?: return@forEach
                    if(prefs.markBudgetNotice(budget.id,month,level))
                        show(1000+budget.id.hashCode().and(0x3fffffff),when(level){1->"Бюджет использован на 80%.";2->"Достигнут лимит бюджета.";else->"Лимит бюджета превышен."})
                }
            }
            if(prefs.current().notificationsEnabled==false)cancelReminders(applicationContext)
        }
        refreshWidgets(applicationContext)
        Result.success()
    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
    catch (_: Exception) { if(runAttemptCount<3) Result.retry() else Result.failure() }

    companion object {
        fun cancelReminders(context: Context) {
            val manager=context.getSystemService(NotificationManager::class.java)
            manager.activeNotifications.filter{it.notification.channelId=="finance"}.forEach{manager.cancel(it.tag,it.id)}
        }
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("finance-periodic",ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<FinanceWorker>(1,TimeUnit.HOURS).build())
        }
        fun refresh(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork("finance-refresh",ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<FinanceWorker>().build())
        }
    }
}
