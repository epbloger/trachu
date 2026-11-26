package ru.epbloger.trachu.ui

import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.epbloger.trachu.AppViewModel
import ru.epbloger.trachu.Editor
import ru.epbloger.trachu.MainActivity
import ru.epbloger.trachu.PickedResult
import ru.epbloger.trachu.core.*
import ru.epbloger.trachu.data.LedgerStore
import ru.epbloger.trachu.security.PinVault
import ru.epbloger.trachu.security.Preferences
import java.io.File

@RunWith(AndroidJUnit4::class)
class EditorRecreationTest {
    @get:Rule val rule=createAndroidComposeRule<MainActivity>()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun withVm(block:(AppViewModel)->Unit){
        rule.activityRule.scenario.onActivity{block(ViewModelProvider(it)[AppViewModel::class.java])}
    }
    private fun awaitTag(tag:String){
        rule.waitUntil(20_000){rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()}
    }
    private fun enter(tag:String,value:String,imeDone:Boolean=true){
        rule.onNodeWithTag(tag).performScrollTo().performTextReplacement(value)
        if(imeDone)rule.onNodeWithTag(tag).performImeAction()
    }

    @Test fun accountDraftSurvivesRecreationAndCancelDiscardsIt(){
        awaitTag("nav-home")
        withVm{it.editor=Editor("account")}
        awaitTag("field:Название")
        enter("field:Название","Черновик счёта")
        enter("field:Начальный остаток","1234")
        rule.onNodeWithTag("toggle:Включать в общий баланс").performScrollTo().performClick().assertIsOff()
        rule.activityRule.scenario.recreate()
        awaitTag("field:Название")
        rule.onNodeWithTag("field:Название").performScrollTo().assertTextContains("Черновик счёта")
        rule.onNodeWithTag("field:Начальный остаток").performScrollTo().assertTextContains("1234")
        rule.onNodeWithTag("toggle:Включать в общий баланс").performScrollTo().assertIsOff()
        rule.onNodeWithText("Отмена").performClick()
        rule.waitUntil(10_000){rule.onAllNodesWithTag("form_save").fetchSemanticsNodes().isEmpty()}
        withVm{assertNull(it.editor);it.editor=Editor("account")}
        awaitTag("field:Название")
        rule.onNodeWithTag("field:Название").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText,AnnotatedString("")))
        rule.onNodeWithTag("toggle:Включать в общий баланс").performScrollTo().assertIsOn()
        rule.onNodeWithText("Отмена").performClick()
    }

    @Test fun entryDraftAndPendingReceiptSurviveRecreation(){
        val store=LedgerStore.get(context)
        val account=Account(name="Черновик ${newId()}",currency=runBlocking{store.current().baseCurrency})
        runBlocking{store.change{it.copy(accounts=it.accounts+account)}}
        try {
            awaitTag("nav-home")
            lateinit var vm:AppViewModel
            withVm{vm=it}
            rule.waitUntil(20_000){vm.ledger.value?.accounts?.any{it.id==account.id}==true}
            val editor=Editor("entry",draft=EntryDraft().apply{state("accountId"){account.id}})
            withVm{it.editor=editor}
            val amountTag="field:Сумма (${account.currency})"
            awaitTag(amountTag)
            enter(amountTag,"1234")
            rule.onNodeWithTag("toggle:Разделить по категориям").performScrollTo().performClick().assertIsOn()
            enter("field:Часть суммы 1","1234")
            rule.onNodeWithText("Дата, комментарий, теги и чек").performScrollTo().performClick()
            enter("field:Комментарий","Несохранённая покупка",imeDone=false)
            enter("field:Теги","черновик")
            rule.onNodeWithTag("toggle:Запланировать").performScrollTo().performClick().assertIsOn()
            withVm{it.receiptRequest=editor.draft}
            rule.activityRule.scenario.recreate()
            awaitTag(amountTag)
            withVm{assertSame(editor,it.editor);assertSame(editor.draft,it.receiptRequest);it.receiptRequest=null}
            rule.onNodeWithTag(amountTag).performScrollTo().assertTextContains("1234")
            rule.onNodeWithTag("field:Часть суммы 1").performScrollTo().assertTextContains("1234")
            rule.onNodeWithTag("field:Комментарий").performScrollTo().assertTextContains("Несохранённая покупка")
            rule.onNodeWithTag("field:Теги").performScrollTo().assertTextContains("черновик")
            rule.onNodeWithTag("toggle:Запланировать").performScrollTo().assertIsOn()
            rule.onNodeWithText("Отмена").performClick()
            assertFalse(runBlocking{store.current().entries.any{it.accountId==account.id}})
        } finally {
            withVm{it.editor=null;it.receiptRequest=null}
            runBlocking{store.change{it.copy(accounts=it.accounts.filterNot{a->a.id==account.id})}}
        }
    }

    @Test fun completedScheduleCanBeEditedButCannotBeEnabledPastEnd(){
        val store=LedgerStore.get(context)
        val ledger=runBlocking{store.current()}
        val account=Account(name="Расписание ${newId()}",currency=ledger.baseCurrency)
        val date=today()+2
        val prepared=ledger.copy(accounts=ledger.accounts+account)
        val category=prepared.categories.first{!it.income&&!it.archived}
        val entry=Finance.makeEntry(prepared,Kind.EXPENSE,account.id,100,date,listOf(category.id to 100L),status=EntryStatus.PLANNED)
        val schedule=Schedule(template=entry,nextDay=date,frequency=Frequency.MONTHLY,endDay=date-1,enabled=false)
        runBlocking{store.change{it.copy(accounts=it.accounts+account,schedules=it.schedules+schedule)}}
        try{
            awaitTag("nav-home")
            lateinit var vm:AppViewModel
            withVm{vm=it}
            rule.waitUntil(20_000){vm.ledger.value?.schedules?.any{it.id==schedule.id}==true}
            withVm{it.editor=Editor("schedule",schedule.id)}
            awaitTag("field:Комментарий")
            enter("field:Комментарий","Завершённое расписание",imeDone=false)
            enter("field:Теги","проверка")
            rule.onNodeWithTag("toggle:Активно").performScrollTo().assertIsOff()
            rule.onNodeWithTag("form_save").performClick()
            rule.waitUntil(20_000){rule.onAllNodesWithTag("form_save").fetchSemanticsNodes().isEmpty()}
            val saved=runBlocking{store.current().schedules.first{it.id==schedule.id}}
            assertFalse(saved.enabled)
            assertEquals(date,saved.nextDay)
            assertEquals("Завершённое расписание",saved.template.note)
            rule.waitUntil(20_000){vm.ledger.value?.schedules?.find{it.id==schedule.id}==saved}
            withVm{it.editor=Editor("schedule",schedule.id)}
            awaitTag("toggle:Активно")
            rule.onNodeWithTag("toggle:Активно").performScrollTo().performClick().assertIsOn()
            rule.onNodeWithTag("form_save").performClick()
            rule.waitUntil(20_000){rule.onAllNodesWithText("Дата окончания раньше следующего повтора",substring=true).fetchSemanticsNodes().isNotEmpty()}
            assertEquals(saved,runBlocking{store.current().schedules.first{it.id==schedule.id}})
            rule.onNodeWithText("OK").performClick()
            rule.onNodeWithText("Отмена").performClick()
        }finally{
            withVm{it.editor=null;it.clearMessage()}
            runBlocking{store.change{it.copy(accounts=it.accounts.filterNot{a->a.id==account.id},schedules=it.schedules.filterNot{s->s.id==schedule.id})}}
        }
    }

    @Test fun selectedFileWaitsForUnlockAndExportsOnlyOnce(){
        val preferences=Preferences(context)
        val previous=runBlocking{preferences.current()}
        val file=File(context.cacheDir,"picker-recreation-${newId()}.csv")
        file.writeText("untouched")
        try {
            runBlocking{preferences.setCredential(PinVault.create("1942"));preferences.timeout(0)}
            withVm{it.lock()}
            rule.waitUntil(20_000){rule.onAllNodesWithText("Ваши финансы защищены").fetchSemanticsNodes().isNotEmpty()}
            val request=FileRequest("csv",true)
            withVm{it.fileRequest=request}
            rule.activityRule.scenario.recreate()
            rule.waitUntil(20_000){rule.onAllNodesWithText("Ваши финансы защищены").fetchSemanticsNodes().isNotEmpty()}
            withVm{
                assertSame(request,it.fileRequest)
                it.fileRequest=null
                it.queuePickedResult(PickedResult.File(Uri.fromFile(file),request))
                it.deliverPickedResult()
                assertNotNull(it.pickedResult.value)
            }
            rule.activityRule.scenario.recreate()
            rule.waitUntil(20_000){rule.onAllNodesWithText("Ваши финансы защищены").fetchSemanticsNodes().isNotEmpty()}
            assertEquals("untouched",file.readText())
            withVm{assertNotNull(it.pickedResult.value);it.unlockPin("1942")}
            rule.waitUntil(20_000){rule.onAllNodesWithText("Файл сохранён").fetchSemanticsNodes().isNotEmpty()}
            assertTrue(file.readText().contains("\"type\",\"id\",\"date\""))
            withVm{assertNull(it.pickedResult.value)}
            rule.onNodeWithText("OK").performClick()
            file.writeText("already exported")
            rule.activityRule.scenario.recreate()
            awaitTag("nav-home")
            assertEquals("already exported",file.readText())
        } finally {
            runBlocking{
                preferences.setCredential(previous.credential)
                preferences.timeout(previous.lockSeconds)
                if(previous.biometric)preferences.biometric(true)
            }
            file.delete()
        }
    }
}
