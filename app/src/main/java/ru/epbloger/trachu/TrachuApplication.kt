package ru.epbloger.trachu

import android.app.Application
import ru.epbloger.trachu.worker.FinanceWorker

class TrachuApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        FinanceWorker.schedule(this)
    }
}
