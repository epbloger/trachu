package ru.epbloger.trachu.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FinancialResetDialogTest {
    @get:Rule val rule=createComposeRule()
    @Test fun cancelAndIncorrectWordCannotDelete() {
        var open by mutableStateOf(true)
        var calls=0
        var backupCalls=0
        rule.setContent { MaterialTheme {
            if(open)FinancialResetDialog(false,false,{open=false},{backupCalls++;open=false}){_,_->calls++;open=false}
        } }
        rule.onNodeWithTag("reset-backup").performClick()
        rule.runOnIdle{assertEquals(1,backupCalls);assertEquals(0,calls);open=true}
        rule.onNodeWithTag("reset-cancel").performClick()
        rule.runOnIdle{assertEquals(0,calls);open=true}
        rule.onNodeWithTag("reset-continue").performClick()
        rule.onNodeWithTag("reset-confirm").assertIsNotEnabled()
        rule.onNodeWithTag("field:Подтверждение").performTextInput("Да")
        rule.onNodeWithTag("reset-confirm").assertIsNotEnabled()
        rule.onNodeWithTag("reset-cancel").performClick()
        rule.runOnIdle{assertEquals(0,calls);open=true}
        rule.onNodeWithTag("reset-continue").performClick()
        rule.onNodeWithTag("reset-confirm").assertIsNotEnabled()
        rule.onNodeWithTag("field:Подтверждение").performTextInput("Подтверждаю")
        rule.onNodeWithTag("reset-confirm").assertIsEnabled().performClick()
        rule.runOnIdle{assertEquals(1,calls)}
    }
}
