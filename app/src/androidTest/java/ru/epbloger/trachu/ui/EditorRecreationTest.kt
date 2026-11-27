package ru.epbloger.trachu.ui

import android.content.res.Configuration
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
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
    @Before fun completeInitialSetup() { runBlocking{Preferences(context).completeOnboarding()} }
    private fun withVm(block:(AppViewModel)->Unit){
        rule.activityRule.scenario.onActivity{block(ViewModelProvider(it)[AppViewModel::class.java])}
    }
    private fun shell(command:String):String =
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use { descriptor ->
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText().trim() }
        }
    private fun restoreSystemSetting(name:String,value:String){
        if(value=="null"||value.isBlank())shell("settings delete system $name")
        else shell("settings put system $name $value")
    }
    private fun awaitTag(tag:String){
        rule.waitUntil(20_000){rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()}
    }
    private fun enter(tag:String,value:String,imeDone:Boolean=true){
        rule.onNodeWithTag(tag).performScrollTo().performTextReplacement(value)
        if(imeDone)rule.onNodeWithTag(tag).performImeAction()
    }
    private fun chooseEntryKind(label:String){
        rule.onNodeWithTag("entry-kind:$label").performScrollTo().assertIsDisplayed()
            .performClick().assertIsSelected()
    }
    private fun chooseEntryDate(label:String,expected:Long){
        rule.onNodeWithTag("entry-date:$label").performScrollTo().assertIsDisplayed().performClick().assertIsSelected()
        rule.onNodeWithTag("field:Дата").performScrollTo().assertTextContains(iso(expected))
        rule.onNodeWithTag("entry-date:${if(label=="Сегодня")"Вчера" else "Сегодня"}").assertIsNotSelected()
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

    @Test fun accountCurrencyChangeCannotDiscardOpeningBalance(){
        val store=LedgerStore.get(context)
        val currency=runBlocking{store.current().baseCurrency}
        val account=Account(name="Счёт с начальным остатком ${newId()}",currency=currency,openingMinor=12345)
        runBlocking{store.change{it.copy(accounts=it.accounts+account)}}
        try{
            awaitTag("nav-home")
            withVm{it.editor=Editor("account",account.id)}
            awaitTag("field:Название")
            rule.onNodeWithText("Валюта: $currency. Её нельзя менять, пока у счёта есть начальный остаток или операции.").performScrollTo().assertIsDisplayed()
            rule.onNodeWithTag("currency:Валюта").assertDoesNotExist()
            rule.onNodeWithTag("field:Начальный остаток").performScrollTo()
                .assertTextContains(Money.input(account.openingMinor,currency))
            rule.onNodeWithText("Отмена").performClick()
            rule.waitUntil(10_000){rule.onAllNodesWithTag("form_save").fetchSemanticsNodes().isEmpty()}
            assertEquals(account,runBlocking{store.current().accounts.single{it.id==account.id}})
        }finally{
            withVm{it.editor=null}
            runBlocking{store.change{it.copy(accounts=it.accounts.filterNot{a->a.id==account.id})}}
        }
    }

    @Test fun accountEditorCannotOverwriteAnOpeningBalanceChangedInTheBackground(){
        val store=LedgerStore.get(context)
        val currency=runBlocking{store.current().baseCurrency}
        val account=Account(name="Счёт с обновляемым остатком ${newId()}",currency=currency)
        runBlocking{store.change{it.copy(accounts=it.accounts+account)}}
        try{
            awaitTag("nav-home")
            withVm{it.editor=Editor("account",account.id)}
            awaitTag("field:Название")
            runBlocking{store.change{l->l.copy(accounts=l.accounts.map{if(it.id==account.id)it.copy(openingMinor=12345) else it})}}
            awaitTag("form_save")
            rule.onNodeWithTag("form_save").performClick()
            rule.onNodeWithText("Счёт изменился. Откройте его снова.").assertExists()
            val latest=runBlocking{store.current().accounts.single{it.id==account.id}}
            assertEquals(12345L,latest.openingMinor)
            assertEquals(currency,latest.currency)
        }finally{
            withVm{it.editor=null;it.clearMessage()}
            runBlocking{store.change{it.copy(accounts=it.accounts.filterNot{a->a.id==account.id})}}
        }
    }

    @Test fun entryDraftAndPendingReceiptSurviveRecreation(){
        val store=LedgerStore.get(context)
        val preferences=Preferences(context)
        val previous=runBlocking{preferences.current()}
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
            val draftDate=iso(today()-1)
            chooseEntryDate("Вчера",today()-1)
            rule.onNodeWithTag("field:Дата").performScrollTo().assertTextContains(draftDate)
            rule.onNodeWithTag("category-quick:transport").performScrollTo().performClick().assertIsSelected()
            rule.onNodeWithTag("toggle:Разделить по категориям").performScrollTo().performClick().assertIsOn()
            enter("field:Часть суммы 1","1234")
            rule.onNodeWithTag("entry-details").performScrollTo().performClick()
            enter("field:Комментарий","Несохранённая покупка",imeDone=false)
            enter("field:Теги","черновик")
            rule.onNodeWithTag("toggle:Запланировать").performScrollTo().performClick().assertIsOn()
            withVm{it.receiptRequest=editor.draft}
            runBlocking{preferences.setCredential(PinVault.create("1942"));preferences.timeout(0)}
            withVm{it.lock()}
            rule.waitUntil(20_000){rule.onAllNodesWithText("Ваши финансы защищены").fetchSemanticsNodes().isNotEmpty()}
            withVm{it.unlockPin("1942")}
            awaitTag(amountTag)
            withVm{assertSame(editor,it.editor);assertSame(editor.draft,it.receiptRequest)}
            rule.onNodeWithTag("choice:Счёт").performScrollTo().assertTextContains(account.name,substring=true)
            rule.onNodeWithTag("field:Дата").performScrollTo().assertTextContains(draftDate)
            rule.onNodeWithTag(amountTag).performScrollTo().assertTextContains("1234")
            rule.onNodeWithTag("field:Часть суммы 1").performScrollTo().assertTextContains("1234")
            rule.onNodeWithTag("category-selected:Категория 1").performScrollTo().assertIsSelected().assertTextContains("Транспорт")
            rule.onNodeWithTag("field:Комментарий").performScrollTo().assertTextContains("Несохранённая покупка")
            rule.onNodeWithTag("field:Теги").performScrollTo().assertTextContains("черновик")
            rule.onNodeWithTag("toggle:Запланировать").performScrollTo().assertIsOn()
            val scenario=rule.activityRule.scenario
            var originalOrientation=Configuration.ORIENTATION_UNDEFINED
            var originalDisplayRotation=0
            scenario.onActivity{
                originalOrientation=it.resources.configuration.orientation
                originalDisplayRotation=it.windowManager.defaultDisplay.rotation
            }
            val rotatedOrientation=if(originalOrientation==Configuration.ORIENTATION_LANDSCAPE)
                Configuration.ORIENTATION_PORTRAIT else Configuration.ORIENTATION_LANDSCAPE
            val originalAccelerometerRotation=shell("settings get system accelerometer_rotation")
            val originalUserRotation=shell("settings get system user_rotation")
            fun currentOrientation():Int {
                var value=Configuration.ORIENTATION_UNDEFINED
                scenario.onActivity{value=it.resources.configuration.orientation}
                return value
            }
            try {
                // Force a real 90-degree display rotation. requestedOrientation is
                // ignored by Android 16 on some large-screen devices.
                shell("settings put system accelerometer_rotation 0")
                shell("settings put system user_rotation ${(originalDisplayRotation+1)%4}")
                rule.waitUntil(20_000){currentOrientation()==rotatedOrientation}
                awaitTag(amountTag)
                withVm{assertSame(editor,it.editor);assertSame(editor.draft,it.receiptRequest)}
                rule.onNodeWithTag("choice:Счёт").performScrollTo().assertTextContains(account.name,substring=true)
                rule.onNodeWithTag("field:Дата").performScrollTo().assertTextContains(draftDate)
                rule.onNodeWithTag(amountTag).performScrollTo().assertTextContains("1234")
                rule.onNodeWithTag("field:Часть суммы 1").performScrollTo().assertTextContains("1234")
                rule.onNodeWithTag("category-selected:Категория 1").performScrollTo().assertIsSelected().assertTextContains("Транспорт")
                rule.onNodeWithTag("field:Комментарий").performScrollTo().assertTextContains("Несохранённая покупка")
                rule.onNodeWithTag("field:Теги").performScrollTo().assertTextContains("черновик")
                rule.onNodeWithTag("toggle:Запланировать").performScrollTo().assertIsOn()
            } finally {
                restoreSystemSetting("user_rotation",originalUserRotation)
                restoreSystemSetting("accelerometer_rotation",originalAccelerometerRotation)
                rule.waitUntil(20_000){currentOrientation()==originalOrientation}
            }
            awaitTag(amountTag)
            scenario.recreate()
            awaitTag(amountTag)
            withVm{assertSame(editor,it.editor);assertSame(editor.draft,it.receiptRequest);it.receiptRequest=null}
            rule.onNodeWithTag("choice:Счёт").performScrollTo().assertTextContains(account.name,substring=true)
            rule.onNodeWithTag("field:Дата").performScrollTo().assertTextContains(draftDate)
            rule.onNodeWithTag(amountTag).performScrollTo().assertTextContains("1234")
            rule.onNodeWithTag("field:Часть суммы 1").performScrollTo().assertTextContains("1234")
            rule.onNodeWithTag("category-selected:Категория 1").performScrollTo().assertIsSelected().assertTextContains("Транспорт")
            rule.onNodeWithTag("field:Комментарий").performScrollTo().assertTextContains("Несохранённая покупка")
            rule.onNodeWithTag("field:Теги").performScrollTo().assertTextContains("черновик")
            rule.onNodeWithTag("toggle:Запланировать").performScrollTo().assertIsOn()
            rule.onNodeWithText("Отмена").performClick()
            assertFalse(runBlocking{store.current().entries.any{it.accountId==account.id}})
        } finally {
            withVm{it.editor=null;it.receiptRequest=null}
            runBlocking{
                preferences.setCredential(previous.credential)
                preferences.timeout(previous.lockSeconds)
                if(previous.biometric)preferences.biometric(true)
                store.change{it.copy(accounts=it.accounts.filterNot{a->a.id==account.id})}
            }
        }
    }

    @Test fun createCategoryFromEntryKeepsDraftAndSelectsIt(){
        val store=LedgerStore.get(context)
        val initial=runBlocking{store.current()}
        val account=Account(name="Категории ${newId()}",currency=initial.baseCurrency)
        val name="Покупки ${newId()}"
        runBlocking{store.change{it.copy(accounts=it.accounts+account)}}
        try {
            awaitTag("nav-home")
            lateinit var vm:AppViewModel
            withVm{vm=it}
            rule.waitUntil(20_000){vm.ledger.value?.accounts?.any{it.id==account.id}==true}
            withVm{it.editor=Editor("entry",draft=EntryDraft().apply{state("accountId"){account.id}})}
            val amountTag="field:Сумма (${account.currency})"
            awaitTag(amountTag)
            enter(amountTag,"12")
            rule.onNodeWithTag("category-selected:Категория").performScrollTo().assertIsNotSelected()
                .assertTextContains("Выберите категорию ниже")
            // Save lives in the fixed dialog footer, outside the scrollable form body.
            rule.onNodeWithTag("form_save").performClick()
            rule.onNodeWithText("Выберите категорию операции").assertExists()
            chooseEntryKind("Доход")
            rule.onNodeWithTag("category-selected:Категория").performScrollTo().assertIsNotSelected()
            rule.onNodeWithTag("form_save").performClick()
            rule.onNodeWithText("Выберите категорию операции").assertExists()
            chooseEntryKind("Расход")
            rule.onNodeWithTag("category-selected:Категория").performScrollTo().assertIsNotSelected()
            rule.onNodeWithTag("category-quick:food").performScrollTo().performClick().assertIsSelected()
            rule.onNodeWithTag("category-selected:Категория").assertIsSelected().assertTextContains("Продукты")
            rule.onNodeWithTag("category-quick:transport").performScrollTo().performClick().assertIsSelected()
            rule.onNodeWithTag("category-quick:food").assertIsNotSelected()
            rule.onNodeWithTag("category-selected:Категория").assertIsSelected().assertTextContains("Транспорт")
            rule.onNodeWithTag("field:Поиск категории").assertDoesNotExist()
            rule.onNodeWithTag("entry-date:Сегодня").performScrollTo().assertIsSelected()
            chooseEntryDate("Вчера",today()-1)
            rule.onNodeWithTag("field:Дата").performScrollTo().assertTextContains(iso(today()-1))
            chooseEntryDate("Сегодня",today())
            rule.onNodeWithTag("field:Дата").performScrollTo().assertTextContains(iso(today()))
            chooseEntryDate("Вчера",today()-1)
            rule.onNodeWithTag("entry-details").performScrollTo().performClick()
            enter("field:Комментарий","Комментарий до создания",imeDone=false)
            rule.onNodeWithTag("category-create:Категория").performScrollTo().performClick()
            rule.onNodeWithTag("field:Название категории").performTextReplacement(name)
            rule.onNodeWithTag("field:Название категории").performImeAction()
            rule.activityRule.scenario.recreate()
            awaitTag("category-create-confirm")
            rule.onNodeWithTag("field:Название категории").assertTextContains(name)
            rule.onNodeWithTag("category-create-confirm").performClick()
            rule.waitUntil(20_000){vm.ledger.value?.categories?.any{it.name==name}==true}
            val category=runBlocking{store.current().categories.single{it.name==name}}
            assertEquals(CategoryColors.next(initial.categories),category.color)
            rule.waitUntil(20_000){rule.onAllNodesWithTag("category-create-confirm").fetchSemanticsNodes().isEmpty()}
            rule.onNodeWithTag("category-selected:Категория").performScrollTo().assertIsSelected().assertTextContains(name,substring=true)
            rule.onNodeWithTag("category-quick:transport").assertIsNotSelected()
            rule.onNodeWithTag(amountTag).performScrollTo().assertTextContains("12")
            rule.onNodeWithTag("field:Комментарий").performScrollTo().assertTextContains("Комментарий до создания")
            rule.onNodeWithTag("field:Дата").performScrollTo().assertTextContains(iso(today()-1))
            rule.onNodeWithTag("form_save").performClick()
            rule.waitUntil(20_000){rule.onAllNodesWithTag("form_save").fetchSemanticsNodes().isEmpty()}
            val saved=runBlocking{store.current().entries.single{it.accountId==account.id}}
            assertEquals(listOf(category.id),saved.splits.map{it.categoryId})
            assertEquals(Money.parse("12",account.currency),saved.minor)
            assertEquals(today()-1,saved.day)
            assertEquals("Комментарий до создания",saved.note)
        }finally{
            withVm{it.editor=null}
            runBlocking{store.change{it.copy(accounts=it.accounts.filterNot{a->a.id==account.id},entries=it.entries.filterNot{e->e.accountId==account.id},categories=it.categories.filterNot{c->c.name==name})}}
        }
    }

    @Test fun newCategoryEditorUsesDistinctPaletteColor(){
        val store=LedgerStore.get(context)
        val initial=runBlocking{store.current()}
        val name="Цвет категории ${newId()}"
        val expected=CategoryColors.next(initial.categories)
        try{
            awaitTag("nav-home")
            lateinit var vm:AppViewModel
            withVm{vm=it;it.editor=Editor("category")}
            awaitTag("field:Название")
            enter("field:Название",name)
            rule.onNodeWithTag("category-color:$expected").performScrollTo().assertIsSelected()
            rule.onNodeWithTag("form_save").performClick()
            rule.waitUntil(20_000){rule.onAllNodesWithTag("form_save").fetchSemanticsNodes().isEmpty()}
            val saved=runBlocking{store.current().categories.single{it.name==name}}
            assertEquals(expected,saved.color)
        }finally{
            withVm{it.editor=null}
            runBlocking{store.change{it.copy(categories=it.categories.filterNot{c->c.name==name})}}
        }
    }

    @Test fun categoryEditorPreservesAnExistingCustomColor(){
        val store=LedgerStore.get(context)
        val customColor=0xFF123456L
        val category=Category(name="Свой цвет ${newId()}",color=customColor)
        runBlocking{store.change{it.copy(categories=it.categories+category)}}
        try{
            awaitTag("nav-home")
            withVm{it.editor=Editor("category",category.id)}
            awaitTag("field:Название")
            rule.onNodeWithTag("category-color:$customColor").performScrollTo().assertIsSelected()
            rule.onNodeWithTag("form_save").performClick()
            rule.waitUntil(20_000){rule.onAllNodesWithTag("form_save").fetchSemanticsNodes().isEmpty()}
            assertEquals(customColor,runBlocking{store.current().categories.single{it.id==category.id}.color})
        }finally{
            withVm{it.editor=null}
            runBlocking{store.change{it.copy(categories=it.categories.filterNot{c->c.id==category.id})}}
        }
    }

    @Test fun eachNewSplitPartRequiresItsOwnCategory(){
        val store=LedgerStore.get(context)
        val initial=runBlocking{store.current()}
        val account=Account(name="Части ${newId()}",currency=initial.baseCurrency)
        runBlocking{store.change{it.copy(accounts=it.accounts+account)}}
        try{
            awaitTag("nav-home")
            lateinit var vm:AppViewModel
            withVm{vm=it}
            rule.waitUntil(20_000){vm.ledger.value?.accounts?.any{it.id==account.id}==true}
            withVm{it.editor=Editor("entry",draft=EntryDraft().apply{state("accountId"){account.id}})}
            awaitTag("field:Сумма (${account.currency})")
            enter("field:Сумма (${account.currency})","12")
            rule.onNodeWithTag("category-selected:Категория").assertIsNotSelected()
            rule.onNodeWithTag("category-quick:transport").performScrollTo().performClick().assertIsSelected()
            rule.onNodeWithTag("toggle:Разделить по категориям").performScrollTo().performClick()
            enter("field:Часть суммы 1","5")
            rule.onNodeWithText("Добавить часть").performScrollTo().performClick()
            rule.onNodeWithTag("category-selected:Категория 2").performScrollTo().assertIsNotSelected()
            enter("field:Часть суммы 2","7")
            rule.onNodeWithTag("form_save").performClick()
            rule.onNodeWithText("Выберите категорию операции").assertExists()
            rule.onNodeWithTag("category-choice:Категория 2").performScrollTo().performClick()
            // Category options are lazy items; scroll their LazyColumn before resolving the node.
            rule.onNodeWithTag("category-list").performScrollToNode(hasTestTag("category-option:food"))
            rule.onNodeWithTag("category-option:food").performClick()
            rule.onNodeWithTag("category-selected:Категория 2").assertIsSelected().assertTextContains("Продукты")
            enter("field:Часть суммы 2","0")
            rule.onNodeWithTag("form_save").performClick()
            rule.onNodeWithText("Часть суммы 2: сумма должна быть больше нуля").assertExists()
            enter("field:Часть суммы 2","7")
            rule.onNodeWithTag("form_save").performClick()
            rule.waitUntil(20_000){rule.onAllNodesWithTag("form_save").fetchSemanticsNodes().isEmpty()}
            val saved=runBlocking{store.current().entries.single{it.accountId==account.id}}
            assertEquals(listOf("transport","food"),saved.splits.map{it.categoryId})
            assertEquals(listOf(Money.parse("5",account.currency),Money.parse("7",account.currency)),saved.splits.map{it.minor})
        }finally{
            withVm{it.editor=null}
            runBlocking{store.change{it.copy(accounts=it.accounts.filterNot{a->a.id==account.id},entries=it.entries.filterNot{e->e.accountId==account.id})}}
        }
    }

    @Test fun editingEntryKeepsItsOriginalCategorySelected(){
        val store=LedgerStore.get(context)
        val initial=runBlocking{store.current()}
        val account=Account(name="Редактирование ${newId()}",currency=initial.baseCurrency)
        val category=initial.categories.first{!it.income&&!it.archived}
        val prepared=initial.copy(accounts=initial.accounts+account)
        val fixture=Finance.makeEntry(prepared,Kind.EXPENSE,account.id,1234,today()-1,listOf(category.id to 1234L))
        val original=runBlocking{store.change{it.copy(accounts=it.accounts+account,entries=it.entries+fixture)}}.entries.single{it.id==fixture.id}
        try{
            awaitTag("nav-home")
            lateinit var vm:AppViewModel
            withVm{vm=it}
            rule.waitUntil(20_000){vm.ledger.value?.entries?.any{it.id==original.id}==true}
            withVm{it.editor=Editor("entry",original.id,Kind.EXPENSE)}
            awaitTag("category-selected:Категория")
            rule.onNodeWithTag("category-selected:Категория").performScrollTo()
                .assertIsSelected().assertTextContains(category.name)
            rule.onNodeWithTag("form_save").performClick()
            rule.waitUntil(20_000){rule.onAllNodesWithTag("form_save").fetchSemanticsNodes().isEmpty()}
            assertEquals(original,runBlocking{store.current().entries.single{it.id==original.id}})
        }finally{
            withVm{it.editor=null}
            runBlocking{store.change{it.copy(accounts=it.accounts.filterNot{a->a.id==account.id},entries=it.entries.filterNot{e->e.id==original.id})}}
        }
    }

    @Test fun searchCurrencyAndConvertOnlyAfterConfirmation(){
        val store=LedgerStore.get(context)
        val initial=runBlocking{store.current()}
        val currency=initial.baseCurrency
        val foreign=if(currency=="USD")"EUR" else "USD"
        val source=if(currency=="JPY")"USD" else "JPY"
        val account=Account(name="Валюта ${newId()}",currency=currency)
        val second=Account(name="Другой счёт ${newId()}",currency=foreign)
        val third=Account(name="Та же валюта ${newId()}",currency=currency)
        val ids=setOf(account.id,second.id,third.id)
        val conversionDay=today()
        val storedRate=FxRate(source,conversionDay,"0.01")
        val originalRate=initial.rates.find{it.currency==source&&it.day==conversionDay}
        runBlocking{store.change{it.copy(accounts=it.accounts+listOf(account,second,third),
            rates=it.rates.filterNot{r->r.currency==source&&r.day==conversionDay}+storedRate)}}
        try{
            awaitTag("nav-home")
            lateinit var vm:AppViewModel
            withVm{vm=it}
            rule.waitUntil(20_000){vm.ledger.value?.accounts?.map{it.id}?.containsAll(ids)==true}
            withVm{it.editor=Editor("account")}
            awaitTag("field:Название")
            enter("field:Название","Счёт с поиском")
            rule.onNodeWithTag("currency:Валюта").performScrollTo().performClick()
            rule.onNodeWithTag("field:Поиск валюты").performTextReplacement("JPY")
            rule.onNodeWithTag("field:Поиск валюты").performImeAction()
            rule.onNodeWithTag("currency-option:JPY").performClick()
            rule.onNodeWithTag("currency:Валюта").assertTextContains("JPY",substring=true)
            rule.onNodeWithTag("field:Название").performScrollTo().assertTextContains("Счёт с поиском")
            rule.onNodeWithText("Отмена").performClick()
            rule.waitUntil(20_000){rule.onAllNodesWithTag("form_save").fetchSemanticsNodes().isEmpty()}
            android.util.Log.i("TrachuQA","Conversion editor: currency search passed; testing stored-rate preview")
            withVm{it.editor=Editor("entry",draft=EntryDraft().apply{state("accountId"){account.id}})}
            val amountTag="field:Сумма ($currency)"
            awaitTag(amountTag)
            enter(amountTag,"10")
            // New operations deliberately have no preselected category. This test
            // exercises conversion, so choose a valid expense category explicitly.
            rule.onNodeWithTag("category-quick:food").performScrollTo().performClick().assertIsSelected()
            rule.onNodeWithTag("entry-convert").performScrollTo().performClick()
            rule.onNodeWithTag("currency:Исходная валюта").performScrollTo().performClick()
            rule.onNodeWithTag("field:Поиск валюты").performTextReplacement(source)
            rule.onNodeWithTag("field:Поиск валюты").performImeAction()
            rule.onNodeWithTag("currency-option:$source").performClick()
            enter("field:Исходная сумма ($source)","0")
            rule.onNodeWithTag("entry-convert-apply").assertIsNotEnabled()
            rule.onNodeWithText("Введите положительную сумму").performScrollTo().assertIsDisplayed()
            android.util.Log.i("TrachuQA","Conversion editor: invalid amount rejected")
            enter("field:Исходная сумма ($source)","1200")
            // Stored-rate result must be applied in the account currency; the preview
            // alone must not save an entry or replace the previous amount.
            rule.onNodeWithTag("entry-convert-result").performScrollTo().assertTextContains(Money.format(Money.parse("12",currency),currency))
            enter("field:Исходная сумма ($source)","0")
            rule.onNodeWithTag("entry-convert-result").assertDoesNotExist()
            rule.onNodeWithTag("entry-convert-apply").assertIsNotEnabled()
            enter("field:Исходная сумма ($source)","1200")
            rule.onNodeWithTag("entry-convert-result").performScrollTo().assertTextContains(Money.format(Money.parse("12",currency),currency))
            rule.onNodeWithTag("entry-convert-apply").assertIsEnabled().performClick()
            rule.onNodeWithTag(amountTag).performScrollTo().assertTextContains(Money.input(Money.parse("12",currency),currency))
            assertTrue(runBlocking{store.current().entries.none{it.accountId in ids}})
            android.util.Log.i("TrachuQA","Conversion editor: stored-rate result applied to draft only")
            rule.onNodeWithTag("entry-convert").performScrollTo().performClick()
            rule.onNodeWithTag("currency:Исходная валюта").performScrollTo().performClick()
            rule.onNodeWithTag("field:Поиск валюты").performTextReplacement(source)
            rule.onNodeWithTag("field:Поиск валюты").performImeAction()
            rule.onNodeWithTag("currency-option:$source").performClick()
            enter("field:Исходная сумма ($source)","1200")
            rule.onNodeWithTag("toggle:Ручной курс").performScrollTo().performClick()
            enter("field:$currency за 1 $source","0.025")
            rule.onNodeWithTag("entry-convert-apply").assertIsEnabled()
            assertTrue(runBlocking{store.current().entries.none{it.accountId in ids}})
            rule.activityRule.scenario.recreate()
            awaitTag("entry-convert-apply")
            rule.onNodeWithTag("currency:Исходная валюта").assertExists()
            rule.onNodeWithTag("entry-convert-apply").assertIsEnabled().performClick()
            rule.onNodeWithTag(amountTag).performScrollTo().assertTextContains(Money.input(Money.parse("30",currency),currency))
            android.util.Log.i("TrachuQA","Conversion editor: manual-rate draft survives recreation")
            // Same-currency account changes do not alter the amount; cross-currency changes
            // require a decision, and cancelling preserves the original account and amount.
            rule.onNodeWithTag("choice:Счёт").performScrollTo().performClick()
            val foreignLabel=if(foreign=="USD")"$ USD" else "€ EUR"
            rule.onNodeWithTag("choice-option:Счёт:${second.id}").assertTextContains(foreignLabel)
            rule.onNodeWithTag("choice-option:Счёт:${third.id}").performClick()
            rule.onNodeWithTag(amountTag).performScrollTo().assertTextContains(Money.input(Money.parse("30",currency),currency))
            rule.onNodeWithTag("choice:Счёт").performScrollTo().performClick()
            rule.onNodeWithTag("choice-option:Счёт:${second.id}").performClick()
            awaitTag("entry-convert-apply")
            rule.onNodeWithTag("currency:Исходная валюта").assertDoesNotExist()
            rule.onNodeWithTag("field:Исходная сумма ($currency)").assertTextContains(Money.input(Money.parse("30",currency),currency))
            rule.onNodeWithTag("entry-convert-cancel").performClick()
            rule.onNodeWithTag("choice:Счёт").performScrollTo().assertTextContains(third.name,substring=true)
            rule.onNodeWithTag(amountTag).performScrollTo().assertTextContains(Money.input(Money.parse("30",currency),currency))
            rule.onNodeWithTag("choice:Счёт").performScrollTo().performClick()
            rule.onNodeWithTag("choice-option:Счёт:${second.id}").performClick()
            rule.onNodeWithTag("toggle:Ручной курс").performScrollTo().performClick()
            enter("field:$foreign за 1 $currency","2")
            rule.onNodeWithTag("entry-convert-apply").performClick()
            rule.onNodeWithTag("choice:Счёт").performScrollTo().assertTextContains(second.name,substring=true)
            rule.onNodeWithTag("choice:Счёт").assertTextContains(foreignLabel)
            rule.onNodeWithTag("field:Сумма ($foreign)").performScrollTo().assertTextContains(Money.input(Money.parse("60",foreign),foreign))
            enter("field:Курс: $currency за 1 $foreign","0.5")
            rule.onNodeWithTag("category-selected:Категория").performScrollTo()
                .assertIsSelected().assertTextContains("Продукты")
            rule.onNodeWithTag("form_save").performClick()
            rule.waitUntil(20_000){rule.onAllNodesWithTag("form_save").fetchSemanticsNodes().isEmpty()}
            val saved=runBlocking{store.current().entries.single{it.accountId in ids}}
            assertEquals(second.id,saved.accountId)
            assertEquals(Money.parse("60",foreign),saved.minor)
            assertEquals(Money.parse("30",currency),saved.baseMinor)
        }finally{
            withVm{it.editor=null}
            runBlocking{store.change{it.copy(accounts=it.accounts.filterNot{a->a.id in ids},entries=it.entries.filterNot{e->e.accountId in ids},
                rates=it.rates.filterNot{r->r.currency==source&&r.day==conversionDay}+listOfNotNull(originalRate))}}
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
            rule.onNodeWithTag("form_save").assertExists()
            rule.onNodeWithText("Дата окончания раньше следующего повтора",substring=true).assertIsDisplayed()
            rule.onNodeWithTag("toggle:Активно").performScrollTo().assertIsOn()
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
        val fixture=Finance.makeEntry(prepared,Kind.EXPENSE,account.id,1234,today()-1,
            listOf(category.id to 1234L),note="Повторяемая покупка",tags=listOf("повтор"),receiptIds=listOf(receipt.id))
        val original=runBlocking{store.change{it.copy(accounts=it.accounts+account,receipts=it.receipts+receipt,entries=it.entries+fixture)}}.entries.single{it.id==fixture.id}
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
            rule.onNodeWithTag("category-selected:Категория").performScrollTo().assertIsSelected().assertTextContains(category.name)
            rule.onNodeWithTag("field:Дата").performScrollTo().assertTextContains(iso(today()))
            rule.onNodeWithTag("field:Комментарий").performScrollTo().assertTextContains(original.note)
            assertEquals(listOf(original),runBlocking{store.current().entries.filter{it.accountId==account.id}})
            val editedMinor=4321L
            val editedNote="Повтор с изменённой суммой"
            enter(amountTag,Money.input(editedMinor,account.currency))
            enter("field:Комментарий",editedNote,imeDone=false)
            rule.onNodeWithText("Сегодня").performScrollTo().assertIsSelected()
            rule.activityRule.scenario.recreate()
            awaitTag(amountTag)
            rule.onNodeWithTag(amountTag).performScrollTo().assertTextContains(Money.input(editedMinor,account.currency))
            rule.onNodeWithTag("field:Комментарий").performScrollTo().assertTextContains(editedNote)
            assertEquals(listOf(original),runBlocking{store.current().entries.filter{it.accountId==account.id}})
            rule.onNodeWithTag("form_save").performClick()
            rule.waitUntil(20_000){rule.onAllNodesWithTag("form_save").fetchSemanticsNodes().isEmpty()}
            val saved=runBlocking{store.current().entries.filter{it.accountId==account.id}}
            assertEquals(2,saved.size)
            assertTrue(original in saved)
            val copy=saved.single{it.id!=original.id}
            assertEquals(editedMinor,copy.minor)
            assertEquals(listOf(Split(category.id,editedMinor,editedMinor)),copy.splits)
            assertEquals(editedNote,copy.note)
            assertEquals(original.tags,copy.tags)
            assertEquals(today(),copy.day)
            assertTrue(copy.receiptIds.isEmpty())
            assertFalse(copy.autoPostPlanned)
            assertEquals(-(original.minor+editedMinor),Finance.balance(runBlocking{store.current()},account.id))
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
