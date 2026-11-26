package ru.epbloger.trachu.ui

import android.content.res.Configuration
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.geometry.Rect
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
import ru.epbloger.trachu.security.PinVault
import ru.epbloger.trachu.security.Preferences
import ru.epbloger.trachu.security.settingsStore
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class GuidedTutorialTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val store get() = LedgerStore.get(context)

    private fun isolated(ledger: Ledger = Ledger.initial(), block: () -> Unit) {
        val priorLedger = runBlocking { store.current() }
        val priorPrefs = runBlocking { context.settingsStore.data.first() }
        try {
            runBlocking {
                store.restore(ledger)
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
    private fun awaitText(text: String) = rule.waitUntil(20_000) {
        rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }
    private fun orientation(scenario: ActivityScenario<MainActivity>): Int {
        var value = Configuration.ORIENTATION_UNDEFINED
        scenario.onActivity { value = it.resources.configuration.orientation }
        return value
    }
    private fun awaitOrientation(scenario: ActivityScenario<MainActivity>, expected: Int) {
        rule.waitUntil(20_000) { orientation(scenario) == expected }
    }
    private fun shell(command: String): String =
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use { descriptor ->
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText().trim() }
        }
    private fun restoreSystemSetting(name: String, value: String) {
        if (value == "null" || value.isBlank()) shell("settings delete system $name")
        else shell("settings put system $name $value")
    }
    private fun assertExpenseTargetAligned(afterRevision: Long? = null): TutorialBoundsSample {
        lateinit var actual: TutorialBoundsSample
        lateinit var highlight: Rect
        fun aligned() = listOf(
            abs(actual.bounds.left - highlight.left), abs(actual.bounds.top - highlight.top),
            abs(actual.bounds.right - highlight.right), abs(actual.bounds.bottom - highlight.bottom)
        ).all { it <= 2f }

        rule.waitUntil(10_000) {
            actual = TutorialBoundsProbe.sample("expense") ?: return@waitUntil false
            highlight = rule.onAllNodesWithTag("tutorial-target-expense").fetchSemanticsNodes().singleOrNull()?.boundsInRoot
                ?: return@waitUntil false
            (afterRevision == null || actual.revision > afterRevision) && aligned()
        }
        assertTrue("Tutorial highlight $highlight must match actual control bounds ${actual.bounds} within 2px", aligned())
        return actual
    }
    private fun tapTarget(step: String) {
        val tag = "tutorial-target-$step"
        awaitTag(tag)
        // Touch the measured highlight in the actual window (not a mocked callback).
        rule.onNodeWithTag(tag).assertIsDisplayed().performTouchInput { click() }
    }
    private fun replay() {
        awaitTag("nav-more")
        rule.onNodeWithTag("nav-more").performClick()
        rule.onNodeWithTag("drawer-tutorial").performScrollTo().performClick()
        awaitTag("tutorial-target-expense")
    }
    private fun assertEditor(scenario: ActivityScenario<MainActivity>, kind: Kind) {
        awaitTag("field:Сумма (RUB)")
        scenario.onActivity {
            val editor = ViewModelProvider(it)[AppViewModel::class.java].editor
            assertEquals("entry", editor?.type)
            assertEquals(kind, editor?.kind)
        }
        // The guide opens a normal editor: its real amount and category controls
        // remain interactive, and cancelling the draft does not persist anything.
        rule.onNodeWithTag("field:Сумма (RUB)").performScrollTo().performTextReplacement("12.34")
        rule.onNodeWithTag("field:Сумма (RUB)").performImeAction()
        val category = if (kind == Kind.INCOME) "salary" else "transport"
        rule.onNodeWithTag("category-quick:$category").performScrollTo().performClick().assertIsSelected()
    }

    @Test fun onboardingStartsRealWalkthroughAndReplayCanBeSkippedWithoutEntries() = isolated {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitTag("onboarding-next")
            rule.onNodeWithTag("field:Название счёта").performScrollTo().performTextInput("Счёт обучения")
            rule.onNodeWithTag("field:Название счёта").performImeAction()
            rule.onNodeWithTag("onboarding-next").performClick()
            rule.onNodeWithTag("form_save").performClick()
            awaitTag("tutorial-target-expense")
            rule.onNodeWithTag("overview-ad-banner").assertDoesNotExist()
            val before = runBlocking { store.current() }
            assertEquals(1, before.accounts.size)
            assertTrue(before.entries.isEmpty())

            // Force a real orientation change while EXPENSE is the active step.
            val beforeRotationRevision = assertExpenseTargetAligned().revision
            val originalAccelerometerRotation = shell("settings get system accelerometer_rotation")
            val originalUserRotation = shell("settings get system user_rotation")
            var originalDisplayRotation = 0
            scenario.onActivity { originalDisplayRotation = it.windowManager.defaultDisplay.rotation }
            val originalOrientation = orientation(scenario)
            val rotatedOrientation = if (originalOrientation == Configuration.ORIENTATION_LANDSCAPE)
                Configuration.ORIENTATION_PORTRAIT else Configuration.ORIENTATION_LANDSCAPE
            var rotatedRevision = beforeRotationRevision
            try {
                // Lock auto-rotation and turn the display by 90 degrees. This also
                // works on large-screen/API 36 devices that ignore requestedOrientation.
                shell("settings put system accelerometer_rotation 0")
                shell("settings put system user_rotation ${(originalDisplayRotation + 1) % 4}")
                awaitOrientation(scenario, rotatedOrientation)
                awaitTag("tutorial-target-expense")
                rule.onNodeWithTag("tutorial-overlay").assertIsDisplayed()
                rule.onNodeWithTag("tutorial-target-expense").assertIsDisplayed()
                val rotatedSample = assertExpenseTargetAligned(afterRevision = beforeRotationRevision)
                rotatedRevision = rotatedSample.revision
            } finally {
                restoreSystemSetting("user_rotation", originalUserRotation)
                restoreSystemSetting("accelerometer_rotation", originalAccelerometerRotation)
                awaitOrientation(scenario, originalOrientation)
            }
            awaitTag("tutorial-target-expense")
            rule.onNodeWithTag("tutorial-overlay").assertIsDisplayed()
            rule.onNodeWithTag("tutorial-target-expense").assertIsDisplayed()
            val restoredSample = assertExpenseTargetAligned(afterRevision = rotatedRevision)

            // Explicit ActivityScenario recreation remains a separate lifecycle check.
            val beforeRecreateRevision = restoredSample.revision
            scenario.recreate()
            awaitTag("tutorial-target-expense")
            rule.onNodeWithTag("tutorial-overlay").assertIsDisplayed()
            rule.onNodeWithTag("tutorial-target-expense").assertIsDisplayed()
            assertExpenseTargetAligned(afterRevision = beforeRecreateRevision)
            tapTarget("expense")
            assertEditor(scenario, Kind.EXPENSE)
            rule.onNodeWithText("Отмена").performClick()
            tapTarget("income")
            assertEditor(scenario, Kind.INCOME)
            rule.onNodeWithText("Отмена").performClick()
            tapTarget("operations")
            tapTarget("plan")
            tapTarget("reports")
            tapTarget("menu")
            tapTarget("categories")
            tapTarget("menu_converter")
            tapTarget("converter")
            awaitTag("tutorial-next")
            rule.onNodeWithTag("tutorial-next").performClick()
            awaitTag("converter-list")
            rule.onNodeWithTag("tutorial-overlay").assertDoesNotExist()
            // Automatic exchange-rate refresh may legitimately update the rate cache.
            // All user financial records must remain identical after the walkthrough.
            assertEquals(before.copy(rates = emptyList()), runBlocking { store.current() }.copy(rates = emptyList()))

            replay()
            rule.onNodeWithTag("tutorial-skip").performClick()
            awaitTag("nav-home")
            rule.onNodeWithTag("tutorial-overlay").assertDoesNotExist()
            assertEquals(before.copy(rates = emptyList()), runBlocking { store.current() }.copy(rates = emptyList()))
        }
        // A new activity never forces completed users back into the guide.
        ActivityScenario.launch(MainActivity::class.java).use {
            awaitTag("nav-home")
            rule.onNodeWithTag("tutorial-overlay").assertDoesNotExist()
        }
    }

    @Test fun lockingDuringReplayRemovesTheGuideAndNeverBypassesPin() = isolated(
        Ledger.initial().copy(accounts = listOf(Account(name = "Защищённый счёт")))
    ) {
        runBlocking { Preferences(context).setCredential(PinVault.create("1942")) }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitText("Ваши финансы защищены")
            scenario.onActivity { ViewModelProvider(it)[AppViewModel::class.java].unlockPin("1942") }
            replay()
            scenario.onActivity { ViewModelProvider(it)[AppViewModel::class.java].lock() }
            awaitText("Ваши финансы защищены")
            rule.onNodeWithTag("tutorial-overlay").assertDoesNotExist()
            rule.onNodeWithTag("nav-home").assertDoesNotExist()
            scenario.recreate()
            awaitText("Ваши финансы защищены")
            rule.onNodeWithTag("tutorial-overlay").assertDoesNotExist()
            scenario.onActivity { ViewModelProvider(it)[AppViewModel::class.java].unlockPin("1942") }
            tapTarget("expense")
            assertEditor(scenario, Kind.EXPENSE)
            scenario.onActivity { ViewModelProvider(it)[AppViewModel::class.java].lock() }
            awaitText("Ваши финансы защищены")
            rule.onNodeWithTag("field:Сумма (RUB)").assertDoesNotExist()
            scenario.onActivity { ViewModelProvider(it)[AppViewModel::class.java].unlockPin("1942") }
            awaitTag("field:Сумма (RUB)")
            rule.onNodeWithTag("field:Сумма (RUB)").performScrollTo().assertTextContains("12.34")
            rule.onNodeWithTag("category-quick:transport").performScrollTo().assertIsSelected()
            rule.onNodeWithText("Отмена").performClick()
            awaitTag("tutorial-target-income")
            rule.onNodeWithTag("tutorial-skip").performClick()
            awaitTag("nav-home")
            rule.onNodeWithTag("tutorial-overlay").assertDoesNotExist()
            assertTrue(runBlocking { store.current().entries.isEmpty() })
        }
    }
}
