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
        val ledger=store.processDue()
        val prefs=Preferences(applicationContext)
        val manager=applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("finance","Финансовые напоминания",NotificationManager.IMPORTANCE_DEFAULT))
        if (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(applicationContext,Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED) {
            val pending=PendingIntent.getActivity(applicationContext,0,Intent(applicationContext,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            fun show(id: Int, message: String) {
                manager.notify(id,NotificationCompat.Builder(applicationContext,"finance")
                    .setSmallIcon(R.drawable.ic_notification).setContentTitle("ТРАЧУ.")
                    .setContentText(message).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                    .setAutoCancel(true).setOnlyAlertOnce(true).setContentIntent(pending).build())
            }
            val day=today()
            if(ledger.entries.any { it.status==EntryStatus.PLANNED && it.day<=day } && prefs.markNotice("planned",day.toString()))
                show(100,"Есть запланированные операции. Проверьте календарь.")
            if(ledger.debts.any { it.dueDay!=null && it.dueDay<=day && Finance.debtRemaining(ledger,it)>0 } && prefs.markNotice("debt",day.toString()))
                show(101,"Есть обязательства с наступившим сроком.")
            val month=YearMonth.now().toString()
            ledger.budgets.forEach { budget ->
                val percent=Finance.budget(ledger,budget,day).percent
                val level=when { percent>100->3; percent>=100->2; percent>=80->1; else->0 }
                if(prefs.markNotice("budget_${budget.id}","$month:$level") && level>0)
                    show(1000+budget.id.hashCode().and(0x3fffffff),when(level){1->"Бюджет использован на 80%.";2->"Достигнут лимит бюджета.";else->"Лимит бюджета превышен."})
            }
        }
        refreshWidgets(applicationContext)
        Result.success()
    } catch (_: Exception) { if(runAttemptCount<3) Result.retry() else Result.failure() }

    companion object {
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("finance-periodic",ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<FinanceWorker>(12,TimeUnit.HOURS).build())
        }
        fun refresh(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork("finance-refresh",ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<FinanceWorker>().build())
        }
    }
}
