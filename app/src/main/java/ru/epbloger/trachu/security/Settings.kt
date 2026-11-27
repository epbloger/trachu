package ru.epbloger.trachu.security

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

internal val Context.settingsStore by preferencesDataStore("trachu_preferences")

data class Settings(
    val theme: String = "system",
    val credential: String = "",
    val biometric: Boolean = false,
    val lockSeconds: Int = 30,
    val lastAccount: String = "",
    val showWidgetBalance: Boolean = false,
    val failedAttempts: Int = 0,
    val lockedUntil: Long = 0L, val monthStart: Int = 1, val automaticRates:Boolean=true,
    val adsConsent:Boolean?=null,
    val onboardingCompleted:Boolean=false,
    val notificationsEnabled:Boolean?=null
) { val protected get() = credential.isNotEmpty() }

class Preferences(context: Context) {
    private val store = context.applicationContext.settingsStore
    private object K {
        val theme = stringPreferencesKey("theme")
        val pin = stringPreferencesKey("pin_vault")
        val biometric = booleanPreferencesKey("biometric")
        val timeout = intPreferencesKey("lock_timeout")
        val account = stringPreferencesKey("last_account")
        val widget = booleanPreferencesKey("widget_balance")
        val failures = intPreferencesKey("pin_failures")
        val until = longPreferencesKey("pin_until")
        val monthStart = intPreferencesKey("month_start")
        val automaticRates = booleanPreferencesKey("automatic_rates")
        // The old choice was about enabling all ads; it is not consent to personalization.
        val adsConsent = booleanPreferencesKey("ads_personalization_consent_v2")
        val onboardingCompleted = booleanPreferencesKey("onboarding_completed_v1")
        val notificationsEnabled = booleanPreferencesKey("notifications_enabled_v1")
    }
    val settings: Flow<Settings> = store.data.map { p ->
        Settings(p[K.theme] ?: "system",p[K.pin] ?: "",p[K.biometric] ?: false,
            p[K.timeout] ?: 30,p[K.account] ?: "",p[K.widget] ?: false,
            p[K.failures] ?: 0,p[K.until] ?: 0,p[K.monthStart] ?: 1,p[K.automaticRates] ?: true,p[K.adsConsent],
            // Older installations with preferences already configured must not be sent through setup.
            p[K.onboardingCompleted] ?: p.asMap().isNotEmpty(),p[K.notificationsEnabled])
    }
    suspend fun current() = settings.first()
    suspend fun theme(value: String) { require(value in listOf("system","light","dark")); store.edit { it[K.theme]=value } }
    suspend fun lastAccount(value: String) { store.edit { it[K.account]=value } }
    suspend fun widget(value: Boolean) { store.edit { it[K.widget]=value } }
    suspend fun automaticRates(value:Boolean) { store.edit { it[K.automaticRates]=value } }
    suspend fun adsConsent(value:Boolean) { store.edit { it[K.adsConsent]=value } }
    suspend fun notificationsEnabled(value:Boolean) { store.edit {
        // A first-launch permission choice must not masquerade as a legacy setup.
        if(it.asMap().isEmpty())it[K.onboardingCompleted]=false
        it[K.notificationsEnabled]=value
    } }
    suspend fun completeOnboarding(accountId:String?=null) { store.edit {
        it[K.onboardingCompleted]=true
        if(accountId!=null)it[K.account]=accountId
    } }
    suspend fun monthStart(value: Int) { require(value in 1..31); store.edit { it[K.monthStart]=value } }
    suspend fun timeout(value: Int) { require(value in listOf(0,15,30,60,300)); store.edit { it[K.timeout]=value } }
    suspend fun biometric(value: Boolean) { store.edit { require(!value || !it[K.pin].isNullOrEmpty()); it[K.biometric]=value } }
    suspend fun setCredential(value: String) { store.edit {
        it[K.pin]=value; it[K.biometric]=false; it[K.failures]=0; it[K.until]=0L
    } }
    suspend fun pinResult(success: Boolean) { store.edit {
        if (success) { it[K.failures]=0; it[K.until]=0L }
        else {
            val count = ((it[K.failures] ?: 0).coerceIn(0,5)+1).coerceAtMost(5)
            it[K.failures]=count
            if (count >= 5) it[K.until]=System.currentTimeMillis()+30_000L
        }
    } }
    suspend fun markNotice(id: String, value: String): Boolean {
        var changed = false
        val key = stringPreferencesKey("notice_$id")
        store.edit { p -> if (p[K.notificationsEnabled]!=false && p[key] != value) { p[key]=value; changed=true } }
        return changed
    }
    /** Notify once per increasing threshold in the same calendar month. */
    suspend fun markBudgetNotice(id: String, month: String, level: Int): Boolean {
        require(level in 0..3)
        if(level==0)return false
        var changed=false
        val key=stringPreferencesKey("notice_budget_$id")
        store.edit { p ->
            val previous=p[key].orEmpty()
            val prior=if(previous.substringBefore(':')==month)previous.substringAfter(':').toIntOrNull() ?: 0 else 0
            if(p[K.notificationsEnabled]!=false && level>prior){p[key]="$month:$level";changed=true}
        }
        return changed
    }
}
