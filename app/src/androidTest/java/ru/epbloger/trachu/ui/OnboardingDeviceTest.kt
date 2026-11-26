package ru.epbloger.trachu.ui

import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
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
import ru.epbloger.trachu.PickedResult
import ru.epbloger.trachu.core.*
import ru.epbloger.trachu.data.Backup
import ru.epbloger.trachu.data.LedgerStore
import ru.epbloger.trachu.security.PinVault
import ru.epbloger.trachu.security.Preferences
import ru.epbloger.trachu.security.settingsStore
import java.io.File

@RunWith(AndroidJUnit4::class)
class OnboardingDeviceTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val store get() = LedgerStore.get(context)
    private val prefs get() = Preferences(context)

    // These scenarios run against the actual app's Room and DataStore; preserve the
    // shared instrumentation sandbox so no other test depends on execution order.
    private fun isolated(block: () -> Unit) {
        val priorLedger = runBlocking { store.current() }
        val priorPrefs = runBlocking { context.settingsStore.data.first() }
        try {
            runBlocking {
                store.restore(Ledger.initial())
                context.settingsStore.updateData { emptyPreferences() }
            }
            block()
        } finally {
            runBlocking {
                store.restore(priorLedger)
                context.settingsStore.updateData { priorPrefs }
            }
        }
    }
    private fun awaitTag(tag: String) = rule.waitUntil(20_000) {
        rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }
    private fun awaitText(text: String, substring: Boolean = false) = rule.waitUntil(20_000) {
        rule.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
    }
    private fun enter(tag: String, text: String) {
        rule.onNodeWithTag(tag).performScrollTo().performTextReplacement(text)
        rule.onNodeWithTag(tag).performImeAction()
    }
    private fun vm(scenario: ActivityScenario<MainActivity>): AppViewModel {
        lateinit var vm: AppViewModel
        scenario.onActivity { vm = ViewModelProvider(it)[AppViewModel::class.java] }
        return vm
    }

    @Test fun actualFirstSetupSurvivesRecreationAndNewActivity() = isolated {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitTag("onboarding-next")
            awaitTag("onboarding-notifications-skip")
            rule.onNodeWithTag("onboarding-notifications-allow").assertIsDisplayed()
            rule.onNodeWithTag("onboarding-notifications-skip").performClick()
            rule.waitUntil(10_000){runBlocking{prefs.current().notificationsEnabled==false}}
            rule.waitUntil(10_000){rule.onAllNodesWithTag("onboarding-notifications-skip").fetchSemanticsNodes().isEmpty()}
            assertFalse(runBlocking{prefs.current().onboardingCompleted})
            rule.onNodeWithTag("onboarding-next").performClick()
            rule.onNodeWithText("Введите название счёта").assertExists()
            rule.onNodeWithTag("currency:Основная валюта").performScrollTo().performClick()
            rule.onNodeWithTag("field:Поиск валюты").performTextInput("EUR")
            rule.onNodeWithTag("field:Поиск валюты").performImeAction()
            rule.onNodeWithTag("currency-option:EUR").performClick()
            enter("field:Название счёта", "Карта EUR")
            enter("field:Начальный остаток", "123.45")
            scenario.recreate()
            awaitTag("onboarding-next")
            rule.onNodeWithTag("currency:Основная валюта").performScrollTo().assertTextContains("EUR", substring = true)
            rule.onNodeWithTag("field:Название счёта").assertTextContains("Карта EUR")
            rule.onNodeWithTag("field:Начальный остаток").assertTextContains("123.45")
            rule.onNodeWithTag("onboarding-next").performClick()
            awaitText("Как пользоваться ТРАЧУ.")
            assertTrue(runBlocking { store.current().accounts.isEmpty() })
            rule.onNodeWithText("Пропустить обучение").performScrollTo().performClick()
            awaitTag("nav-home")
            val state = vm(scenario)
            rule.waitUntil(20_000) { !state.busy.value && state.settings.value?.onboardingCompleted == true }
            val saved = runBlocking { store.current() }
            val account = saved.accounts.single()
            assertEquals("EUR", saved.baseCurrency)
            assertEquals("EUR", account.currency)
            assertEquals(12345L, account.openingMinor)
            assertTrue(saved.entries.isEmpty())
            assertEquals(account.id, runBlocking { prefs.current().lastAccount })
            assertEquals(false,runBlocking{prefs.current().notificationsEnabled})
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            awaitTag("nav-home")
            rule.onNodeWithTag("onboarding-next").assertDoesNotExist()
            rule.onNodeWithTag("onboarding-notifications-skip").assertDoesNotExist()
            assertEquals(1, runBlocking { store.current().accounts.size })
        }
    }

    @Test fun actualLegacyPreferencesAndExistingLedgerArePreserved() = isolated {
        // Simulate the pre-1.1 preferences schema without the new completion key.
        runBlocking { context.settingsStore.updateData { preferencesOf(stringPreferencesKey("theme") to "dark") } }
        ActivityScenario.launch(MainActivity::class.java).use {
            awaitTag("nav-home")
            rule.onNodeWithTag("onboarding-next").assertDoesNotExist()
            assertEquals("dark", runBlocking { prefs.current().theme })
        }
        val existing = Ledger.initial().copy(accounts = listOf(Account(name = "Старый счёт", openingMinor = 9123)))
        runBlocking {
            context.settingsStore.updateData { emptyPreferences() }
            store.restore(existing)
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitTag("nav-home")
            val state = vm(scenario)
            rule.waitUntil(20_000) { state.settings.value?.onboardingCompleted == true }
            assertEquals(existing.accounts, runBlocking { store.current().accounts })
            // An installation that already had real data must not show setup after
            // the user deletes their last unused account.
            runBlocking { store.change { it.copy(accounts = emptyList()) } }
            rule.waitUntil(20_000) { state.ledger.value?.accounts?.isEmpty() == true }
            rule.onNodeWithTag("onboarding-next").assertDoesNotExist()
        }
    }

    @Test fun notificationChoiceAndPermissionResultSurviveRecreation() = isolated {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitTag("onboarding-notifications-allow")
            val state=vm(scenario)
            if(android.os.Build.VERSION.SDK_INT<33){
                rule.onNodeWithTag("onboarding-notifications-allow").performClick()
            }else{
                // Inject the Android permission callback, not a claim that the
                // external system permission dialog was physically exercised.
                scenario.onActivity{state.notificationPermissionResult(true)}
            }
            rule.waitUntil(10_000){runBlocking{prefs.current().notificationsEnabled==true}}
            rule.waitUntil(10_000){rule.onAllNodesWithTag("onboarding-notifications-allow").fetchSemanticsNodes().isEmpty()}
            assertFalse(runBlocking{prefs.current().onboardingCompleted})
            scenario.onActivity{state.notificationPermissionResult(false)}
            rule.waitUntil(10_000){runBlocking{prefs.current().notificationsEnabled==false}}
            scenario.recreate()
            awaitTag("onboarding-next")
            rule.onNodeWithTag("onboarding-notifications-allow").assertDoesNotExist()
            assertFalse(runBlocking{prefs.current().onboardingCompleted})
        }
    }

    @Test fun firstLaunchRestoreChecksPasswordAndCompletesEvenForEmptyBackup() = isolated {
        val file = File(context.cacheDir, "onboarding-${newId()}.trachu")
        try {
            file.outputStream().use { Backup.write(Ledger.initial(), "valid-password".toCharArray(), it) }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                awaitTag("onboarding-restore")
                awaitTag("onboarding-notifications-skip")
                rule.onNodeWithTag("onboarding-notifications-skip").performClick()
                rule.waitUntil(10_000){rule.onAllNodesWithTag("onboarding-notifications-skip").fetchSemanticsNodes().isEmpty()}
                rule.onNodeWithTag("onboarding-restore").performClick()
                rule.onNodeWithText("Пароль резервной копии").assertExists()
                rule.onNodeWithText("Отмена").performClick()
                // Inject the system file picker's result, exercising the real queued
                // restore, password validation, Room transaction and preference write.
                scenario.onActivity { activity ->
                    ViewModelProvider(activity)[AppViewModel::class.java].queuePickedResult(
                        PickedResult.File(Uri.fromFile(file), FileRequest("backup", false, "wrong-password")))
                }
                awaitText("Неверный пароль или повреждённая копия", substring = true)
                rule.onNodeWithText("OK").performClick()
                assertTrue(isPristineLedger(runBlocking { store.current() }))
                assertFalse(runBlocking { prefs.current().onboardingCompleted })
                rule.onNodeWithTag("onboarding-next").assertExists()
                scenario.onActivity { activity ->
                    ViewModelProvider(activity)[AppViewModel::class.java].queuePickedResult(
                        PickedResult.File(Uri.fromFile(file), FileRequest("backup", false, "valid-password")))
                }
                awaitText("Данные загружены")
                rule.onNodeWithText("OK").performClick()
                awaitTag("nav-home")
                assertTrue(runBlocking { prefs.current().onboardingCompleted })
                assertTrue(isPristineLedger(runBlocking { store.current() }))
                rule.onNodeWithTag("onboarding-next").assertDoesNotExist()
            }
        } finally { file.delete() }
    }

    @Test fun protectedEmptyLedgerCannotEnterSetupOrRestoreBeforePin() = isolated {
        val restored = Ledger.initial().copy(accounts = listOf(Account(name = "Восстановленный счёт", openingMinor = 725)))
        val file = File(context.cacheDir, "protected-onboarding-${newId()}.trachu")
        try {
            runBlocking { prefs.setCredential(PinVault.create("1942")) }
            file.outputStream().use { Backup.write(restored, "valid-password".toCharArray(), it) }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                awaitText("Ваши финансы защищены")
                rule.onNodeWithTag("overview-ad-banner").assertDoesNotExist()
                rule.onNodeWithTag("onboarding-next").assertDoesNotExist()
                rule.onNodeWithTag("onboarding-restore").assertDoesNotExist()
                val state = vm(scenario)
                scenario.onActivity {
                    state.queuePickedResult(PickedResult.File(Uri.fromFile(file), FileRequest("backup", false, "valid-password")))
                    state.deliverPickedResult()
                }
                assertNotNull(state.pickedResult.value)
                assertTrue(runBlocking { store.current().accounts.isEmpty() })
                // Use the existing real PIN verification path, not the unlock flag.
                scenario.onActivity { state.unlockPin("1942") }
                awaitText("Данные загружены")
                rule.onNodeWithText("OK").performClick()
                awaitTag("nav-home")
                assertEquals(restored.accounts, runBlocking { store.current().accounts })
                assertTrue(runBlocking { prefs.current().protected })
                assertNull(state.pickedResult.value)
            }
            ActivityScenario.launch(MainActivity::class.java).use {
                awaitText("Ваши финансы защищены")
                rule.onNodeWithTag("nav-home").assertDoesNotExist()
            }
        } finally { file.delete() }
    }
}
