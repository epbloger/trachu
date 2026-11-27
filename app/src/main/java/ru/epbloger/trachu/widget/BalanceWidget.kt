package ru.epbloger.trachu.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.*
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.*
import androidx.glance.layout.*
import androidx.glance.text.*
import androidx.glance.unit.ColorProvider
import ru.epbloger.trachu.MainActivity
import ru.epbloger.trachu.R
import ru.epbloger.trachu.core.*
import ru.epbloger.trachu.data.LedgerStore
import ru.epbloger.trachu.security.Preferences

internal data class WidgetSummary(val balance:String, val income:String?, val expense:String?, val partial:Boolean=false)
internal fun widgetSummary(visible:Boolean, ledger:()->Ledger, monthStart:Int=1, day:Long=today()):WidgetSummary {
    if(!visible)return WidgetSummary("••••••",null,null)
    val l=ledger()
    val v=Finance.valuation(l,day)
    val totals=Finance.totals(l,Period.month(day,monthStart))
    return WidgetSummary(Money.format(v.minor,l.baseCurrency),Money.format(totals.income,l.baseCurrency),
        Money.format(totals.expense,l.baseCurrency),v.missingCurrencies.isNotEmpty())
}

class BalanceWidget : GlanceAppWidget() {
    override val sizeMode=SizeMode.Exact
    override suspend fun provideGlance(context: Context,id: GlanceId) {
        // Read no financial data at all when protected or hidden.
        val summary=try {
            val prefs=Preferences(context).current()
            val visible=!prefs.protected && prefs.showWidgetBalance
            if(visible) {
                val ledger=LedgerStore.get(context).current()
                widgetSummary(true,{ledger},prefs.monthStart)
            } else widgetSummary(false,{error("Hidden")})
        } catch(e:kotlinx.coroutines.CancellationException){throw e}
        catch(_:Exception){WidgetSummary("••••••",null,null)}
        provideContent { Content(context,summary) }
    }

    @Composable private fun Content(context: Context,value: WidgetSummary) {
        val tall=LocalSize.current.height>=184.dp
        val white=ColorProvider(Color(0xFFF9F7FF))
        val muted=ColorProvider(Color(0xFFCEC5EC))
        Column(GlanceModifier.fillMaxSize().background(Color(0xFF33294F)).cornerRadius(24.dp).padding(12.dp),
            verticalAlignment=Alignment.CenterVertically) {
            Row(GlanceModifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Text("ТРАЧУ.",modifier=GlanceModifier.defaultWeight(),style=TextStyle(color=muted,fontWeight=FontWeight.Bold,fontSize=12.sp))
                Text("Обзор ›",modifier=GlanceModifier.clickable(actionStartActivity(Intent(context,MainActivity::class.java))),
                    style=TextStyle(color=white,fontSize=12.sp))
            }
            Spacer(GlanceModifier.height(4.dp))
            Text(if(value.income==null)"Баланс скрыт" else if(value.partial)"Баланс · не все курсы" else "Общий баланс",
                style=TextStyle(color=muted,fontSize=11.sp),maxLines=1)
            Text(value.balance,style=TextStyle(color=white,fontSize=if(value.balance.length>16)16.sp else 24.sp,fontWeight=FontWeight.Bold),maxLines=2)
            if(tall && value.income!=null && value.expense!=null) {
                Spacer(GlanceModifier.height(8.dp))
                Row(GlanceModifier.fillMaxWidth()) {
                    Column(GlanceModifier.defaultWeight()) {
                        Text("Доходы за месяц",style=TextStyle(color=muted,fontSize=10.sp),maxLines=1)
                        Text(value.income,style=TextStyle(color=ColorProvider(Color(0xFFBCE8CA)),fontSize=13.sp,fontWeight=FontWeight.Bold),maxLines=2)
                    }
                    Spacer(GlanceModifier.width(8.dp))
                    Column(GlanceModifier.defaultWeight()) {
                        Text("Расходы за месяц",style=TextStyle(color=muted,fontSize=10.sp),maxLines=1)
                        Text(value.expense,style=TextStyle(color=ColorProvider(Color(0xFFFFC3CB)),fontSize=13.sp,fontWeight=FontWeight.Bold),maxLines=2)
                    }
                }
            }
            Spacer(GlanceModifier.height(8.dp))
            Row(GlanceModifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Button("− Расход",actionStartActivity(Intent(context,MainActivity::class.java).setAction("ru.epbloger.trachu.QUICK_EXPENSE").putExtra("quick","EXPENSE")),
                    modifier=GlanceModifier.defaultWeight(),
                    colors=ButtonDefaults.buttonColors(backgroundColor=ColorProvider(Color(0xFFFFE2E5)),contentColor=ColorProvider(Color(0xFF8F243B))))
                Spacer(GlanceModifier.width(8.dp))
                Button("+ Доход",actionStartActivity(Intent(context,MainActivity::class.java).setAction("ru.epbloger.trachu.QUICK_INCOME").putExtra("quick","INCOME")),
                    modifier=GlanceModifier.defaultWeight(),
                    colors=ButtonDefaults.buttonColors(backgroundColor=ColorProvider(Color(0xFFDDF4E5)),contentColor=ColorProvider(Color(0xFF145C37))))
            }
        }
    }
}
class BalanceReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget=BalanceWidget() }
suspend fun refreshWidgets(context: Context) { BalanceWidget().updateAll(context) }
