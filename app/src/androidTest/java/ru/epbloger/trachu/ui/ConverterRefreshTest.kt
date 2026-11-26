package ru.epbloger.trachu.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.datastore.preferences.core.emptyPreferences
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.epbloger.trachu.AppViewModel
import ru.epbloger.trachu.MainActivity
import ru.epbloger.trachu.core.*
import ru.epbloger.trachu.data.LedgerStore
import ru.epbloger.trachu.security.Preferences
import ru.epbloger.trachu.security.settingsStore
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ConverterRefreshTest {
    @get:Rule val rule=createEmptyComposeRule()

    @Test fun converterFetchesAutomaticallyAndPreservesManualRate() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val store=LedgerStore.get(context)
        val prefs=Preferences(context)
        val oldLedger=runBlocking{store.current()}
        val oldPrefs=runBlocking{context.settingsStore.data.first()}
        val releaseFetch=CountDownLatch(1)
        try {
            runBlocking{
                store.restore(Ledger.initial().copy(accounts=listOf(Account(name="Converter QA")),
                    rates=listOf(FxRate("EUR",today(),"99","manual"),FxRate("USD",today()-1,"200","CBR"))))
                context.settingsStore.updateData{emptyPreferences()}
                prefs.completeOnboarding()
                assertTrue(prefs.current().automaticRates)
            }
            val calls=AtomicInteger(0)
            ActivityScenario.launch(MainActivity::class.java).use{scenario->
                lateinit var vm:AppViewModel
                scenario.onActivity{
                    vm=ViewModelProvider(it)[AppViewModel::class.java]
                    vm.converterRateFetcher={base,day->
                        assertEquals("RUB",base)
                        calls.incrementAndGet()
                        check(releaseFetch.await(60,TimeUnit.SECONDS))
                        listOf(FxRate("USD",day,"100","CBR"),FxRate("EUR",day,"90","CBR"))
                    }
                }
                rule.waitUntil(20_000){rule.onAllNodesWithTag("nav-more").fetchSemanticsNodes().isNotEmpty()}
                rule.onNodeWithTag("nav-more").performClick()
                rule.onNodeWithTag("drawer-converter").performScrollTo().performClick()
                rule.waitUntil(20_000){calls.get()==1}
                rule.onNodeWithTag("converter-list").performScrollToNode(hasTestTag("field:Сумма (RUB)"))
                rule.onNodeWithTag("field:Сумма (RUB)").performTextReplacement("200")
                rule.onNodeWithTag("field:Сумма (RUB)").performImeAction()
                rule.onNodeWithTag("converter-list").performScrollToNode(hasText(Money.format(100,"USD")))
                rule.onNodeWithText(Money.format(100,"USD")).assertIsDisplayed()
                rule.onNodeWithTag("converter-loading").assertExists()
                rule.onNodeWithTag("converter-rate-age").assertExists()
                releaseFetch.countDown()
                rule.waitUntil(20_000){vm.ledger.value?.let{Finance.rate(it,"USD",today())}=="100"}
                assertEquals("99",Finance.rate(runBlocking{store.current()},"EUR",today()))
                rule.onNodeWithTag("converter-list").performScrollToNode(hasText(Money.format(200,"USD")))
                rule.onNodeWithText(Money.format(200,"USD")).assertIsDisplayed()
                rule.onNodeWithTag("converter-rate-age").assertDoesNotExist()
                rule.onNodeWithTag("converter-list").performScrollToNode(hasTestTag("currency:В валюту"))
                rule.onNodeWithTag("currency:В валюту").performClick()
                rule.onNodeWithTag("field:Поиск валюты").performTextReplacement("EUR")
                rule.onNodeWithTag("field:Поиск валюты").performImeAction()
                rule.onNodeWithTag("currency-option:EUR").performClick()
                rule.onNodeWithTag("converter-list").performScrollToNode(hasText(Money.format(202,"EUR")))
                rule.onNodeWithText(Money.format(202,"EUR")).assertIsDisplayed()
                assertEquals(1,calls.get())
                assertTrue(runBlocking{store.current().entries.isEmpty()})
            }
        } finally {
            releaseFetch.countDown()
            runBlocking{
                store.restore(oldLedger)
                context.settingsStore.updateData{oldPrefs}
            }
        }
    }
}
