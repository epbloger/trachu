package ru.epbloger.trachu.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.*
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.*
import androidx.glance.layout.*
import androidx.glance.text.*
import androidx.glance.unit.ColorProvider
import ru.epbloger.trachu.MainActivity
import ru.epbloger.trachu.core.Finance
import ru.epbloger.trachu.core.Money
import ru.epbloger.trachu.data.LedgerStore
import ru.epbloger.trachu.security.Preferences

class BalanceWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context,id: GlanceId) {
        val prefs=Preferences(context).current()
        val ledger=LedgerStore.get(context).current()
        val value=if(!prefs.protected && prefs.showWidgetBalance) {
            val v=Finance.valuation(ledger)
            Money.format(v.minor,ledger.baseCurrency)+(if(v.missingCurrencies.isNotEmpty()) " *" else "")
        } else "••••••"
        provideContent { Content(context,value) }
    }
    @Composable private fun Content(context: Context,value: String) {
        val text=ColorProvider(Color(0xFFB9F3D5))
        Column(GlanceModifier.fillMaxSize().background(Color(0xFF173B35)).padding(12.dp),verticalAlignment=Alignment.CenterVertically) {
            Text("ТРАЧУ.",style=TextStyle(color=text,fontSize=13.sp,fontWeight=FontWeight.Bold))
            Text(value,style=TextStyle(color=ColorProvider(Color.White),fontSize=22.sp,fontWeight=FontWeight.Bold),maxLines=1)
            Spacer(GlanceModifier.height(8.dp))
            Row(horizontalAlignment=Alignment.Start) {
                Button("− Расход",actionStartActivity(Intent(context,MainActivity::class.java).setAction("ru.epbloger.trachu.QUICK_EXPENSE").putExtra("quick","EXPENSE")))
                Spacer(GlanceModifier.width(6.dp))
                Button("+ Доход",actionStartActivity(Intent(context,MainActivity::class.java).setAction("ru.epbloger.trachu.QUICK_INCOME").putExtra("quick","INCOME")))
            }
        }
    }
}
class BalanceReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget=BalanceWidget() }
suspend fun refreshWidgets(context: Context) { BalanceWidget().updateAll(context) }
