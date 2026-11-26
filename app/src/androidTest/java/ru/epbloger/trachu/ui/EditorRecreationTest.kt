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
import ru.epbloger.trachu.data.LedgerRecord
import ru.epbloger.trachu.data.TrachuDatabase
import ru.epbloger.trachu.data.Backup
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
        // Launcher/widget shortcut must never silently replace an open draft.
        withVm{it.quick(Kind.EXPENSE)}
        rule.waitUntil(10_000){rule.onAllNodesWithText("Сначала завершите текущую операцию. Черновик сохранён.").fetchSemanticsNodes().isNotEmpty()}
        rule.onNodeWithText("OK").performClick()
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

    @Test fun repeatExpenseRequiresSaveAndDoesNotCopyOriginalIdentityOrReceipt(){
        val store=LedgerStore.get(context)
        val initial=runBlocking{store.current()}
        val account=Account(name="Повтор ${newId()}",currency=initial.baseCurrency)
        val category=initial.categories.first{!it.income&&!it.archived}
        val receipt=Receipt(mime="image/png",base64="AAAA")
        val prepared=initial.copy(accounts=initial.accounts+account,receipts=initial.receipts+receipt)
        val original=Finance.makeEntry(prepared,Kind.EXPENSE,account.id,1234,today()-1,
            listOf(category.id to 1234L),note="Повторяемая покупка",tags=listOf("повтор"),receiptIds=listOf(receipt.id))
        runBlocking{store.change{it.copy(accounts=it.accounts+account,receipts=it.receipts+receipt,entries=it.entries+original)}}
        try {
            awaitTag("nav-home")
            lateinit var vm:AppViewModel
            withVm{vm=it}
            rule.waitUntil(20_000){vm.ledger.value?.entries?.any{it.id==original.id}==true}
            withVm{it.editor=Editor("detail",original.id)}
            rule.onNodeWithText("Повторить операцию").performScrollTo().performClick()
            val amountTag="field:Сумма (${account.currency})"
            awaitTag(amountTag)
            rule.onNodeWithTag(amountTag).performScrollTo().assertTextContains(Money.input(1234,account.currency))
            rule.onNodeWithTag("field:Дата").performScrollTo().assertTextContains(iso(today()))
            assertEquals(listOf(original),runBlocking{store.current().entries.filter{it.accountId==account.id}})
            rule.onNodeWithTag("form_save").performClick()
            rule.waitUntil(20_000){rule.onAllNodesWithTag("form_save").fetchSemanticsNodes().isEmpty()}
            val saved=runBlocking{store.current().entries.filter{it.accountId==account.id}}
            assertEquals(2,saved.size)
            assertTrue(original in saved)
            val copy=saved.single{it.id!=original.id}
            assertEquals(original.minor,copy.minor)
            assertEquals(original.splits,copy.splits)
            assertEquals(original.note,copy.note)
            assertEquals(original.tags,copy.tags)
            assertEquals(today(),copy.day)
            assertTrue(copy.receiptIds.isEmpty())
            assertFalse(copy.autoPostPlanned)
            assertEquals(-2468L,Finance.balance(runBlocking{store.current()},account.id))
        } finally {
            withVm{it.editor=null}
            runBlocking{store.change{it.copy(accounts=it.accounts.filterNot{a->a.id==account.id},entries=it.entries.filterNot{e->e.accountId==account.id})}}
        }
    }

    @Test fun staleEditorCannotUndoBackgroundAutoPosting(){
        val store=LedgerStore.get(context)
        val initial=runBlocking{store.current()}
        val account=Account(name="Автопроведение ${newId()}",currency=initial.baseCurrency)
        val category=initial.categories.first{!it.income&&!it.archived}
        val date=today()+1
        val prepared=initial.copy(accounts=initial.accounts+account)
        val planned=Finance.makeEntry(prepared,Kind.EXPENSE,account.id,100,date,listOf(category.id to 100L),
            status=EntryStatus.PLANNED,autoPostPlanned=true)
        runBlocking{store.change{it.copy(accounts=it.accounts+account,entries=it.entries+planned)}}
        try {
            awaitTag("nav-home")
            lateinit var vm:AppViewModel
            withVm{vm=it}
            rule.waitUntil(20_000){vm.ledger.value?.entries?.any{it.id==planned.id}==true}
            withVm{it.editor=Editor("entry",planned.id,planned.kind)}
            val amountTag="field:Сумма (${account.currency})"
            awaitTag(amountTag)
            enter(amountTag,"2")
            // A background transaction wins while the user is editing the prior snapshot.
            runBlocking{store.processDue(date)}
            val posted=runBlocking{store.current().entries.first{it.id==planned.id}}
            assertEquals(EntryStatus.POSTED,posted.status)
            assertFalse(posted.autoPostPlanned)
            rule.waitUntil(20_000){vm.ledger.value?.entries?.find{it.id==planned.id}==posted}
            rule.onNodeWithTag("form_save").performClick()
            rule.waitUntil(20_000){rule.onAllNodesWithText("Операция изменилась",substring=true).fetchSemanticsNodes().isNotEmpty()}
            assertEquals(posted,runBlocking{store.current().entries.first{it.id==planned.id}})
            rule.onNodeWithText("OK").performClick()
            rule.onNodeWithText("Отмена").performClick()
        } finally {
            withVm{it.editor=null;it.clearMessage()}
            runBlocking{store.change{it.copy(accounts=it.accounts.filterNot{a->a.id==account.id},entries=it.entries.filterNot{e->e.accountId==account.id})}}
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

    @Test fun corruptedLedgerCanBeRecoveredFromBackupOnlyAfterUnlock(){
        val store=LedgerStore.get(context)
        val prefs=Preferences(context)
        val previous=runBlocking{prefs.current()}
        runBlocking{store.change{it}}
        val before=runBlocking{store.current()}
        val file=File(context.cacheDir,"recovery-${newId()}.trachu")
        file.outputStream().use{Backup.write(before,"recovery-password".toCharArray(),it)}
        try {
            awaitTag("nav-home")
            lateinit var vm:AppViewModel
            withVm{vm=it}
            runBlocking{prefs.setCredential(PinVault.create("1942"));prefs.timeout(0)}
            rule.waitUntil(20_000){vm.settings.value?.protected==true}
            withVm{it.lock()}
            runBlocking{TrachuDatabase.get(context).ledgerDao().put(listOf(LedgerRecord("receipt_chunk","orphan:000000","AAAA")))}
            rule.waitUntil(20_000){vm.loadingError.value!=null&&vm.ledger.value==null}
            rule.onNodeWithText("Ваши финансы защищены").assertExists()
            withVm{
                it.queuePickedResult(PickedResult.File(Uri.fromFile(file),FileRequest("backup",false,"recovery-password")))
                it.deliverPickedResult()
                assertNotNull(it.pickedResult.value)
            }
            assertTrue(runCatching{runBlocking{store.current()}}.isFailure)
            withVm{it.unlockPin("1942")}
            rule.waitUntil(20_000){vm.loadingError.value==null&&vm.ledger.value!=null&&vm.pickedResult.value==null}
            assertEquals(before,runBlocking{store.current()})
            rule.waitUntil(20_000){rule.onAllNodesWithText("Данные загружены").fetchSemanticsNodes().isNotEmpty()}
            rule.onNodeWithText("OK").performClick()
            awaitTag("nav-home")
        }finally{
            runBlocking{store.restore(before);prefs.setCredential(previous.credential);prefs.timeout(previous.lockSeconds);if(previous.biometric)prefs.biometric(true)}
            withVm{it.clearMessage()}
            file.delete()
        }
    }
}
