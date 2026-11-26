package ru.epbloger.trachu.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import ru.epbloger.trachu.AppViewModel
import ru.epbloger.trachu.core.*
import ru.epbloger.trachu.security.Settings

/** The background worker may materialize the default categories in ID order. */
internal fun isPristineLedger(ledger: Ledger): Boolean {
    val initial = Ledger.initial()
    return ledger.copy(categories = ledger.categories.sortedBy { it.id }) ==
        initial.copy(categories = initial.categories.sortedBy { it.id })
}

internal fun needsOnboarding(ledger: Ledger, settings: Settings): Boolean =
    !settings.onboardingCompleted && !settings.protected && isPristineLedger(ledger)

/** Apply the entire first-account setup in one ledger transaction. */
internal fun firstAccountLedger(ledger: Ledger, currency: String, name: String, type: AccountType, opening: String): Ledger {
    require(isPristineLedger(ledger)) { "Данные уже существуют. Откройте настройки счёта." }
    require(name.isNotBlank()) { "Введите название счёта" }
    val minor = runCatching { Money.parse(opening.ifBlank { "0" }, currency) }.getOrElse {
        throw IllegalArgumentException("Проверьте начальный остаток и число знаков после запятой.")
    }
    return ledger.copy(baseCurrency = currency, accounts = listOf(Account(name = name.trim(),
        currency = currency, type = type, openingMinor = minor))).validated()
}

@Composable
internal fun OnboardingScreen(ledger: Ledger, vm: AppViewModel, busy: Boolean, file: (FileRequest) -> Unit) {
    val draft = vm.onboardingDraft
    var currency by draft.state("currency") { ledger.baseCurrency }
    var name by draft.state("name") { "" }
    var type by draft.state("type") { AccountType.CARD }
    var opening by draft.state("opening") { "0" }
    var error by draft.state("error") { "" }
    var tutorial by draft.state("tutorial") { false }
    var restoring by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 540.dp).fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp)) {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("ТРАЧУ.", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
                Text("Начнём с вашего счёта", style = MaterialTheme.typography.headlineSmall)
                Text("Выберите основную валюту и укажите, сколько денег у вас сейчас. Остальные счета можно добавить позже.")
                CurrencyPicker("Основная валюта", currency) { currency = it; error = "" }
                Field("Название счёта", name, { name = it; error = "" }, supporting = "Например, основная карта или кошелёк")
                Choice("Тип счёта", type, AccountType.entries, { it.label() }) { type = it }
                Field("Начальный остаток", opening, { opening = it; error = "" }, numeric = true,
                    supporting = "В выбранной валюте. Этот остаток не считается доходом.")
            }
            if (error.isNotBlank()) Warning(error)
            Button(onClick = {
                focus.clearFocus()
                runCatching { firstAccountLedger(ledger, currency, name, type, opening) }
                    .onSuccess { error = ""; tutorial = true }
                    .onFailure { error = it.message ?: "Проверьте данные" }
            }, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("onboarding-next")) { Text("Далее") }
            TextButton(onClick = { focus.clearFocus(); restoring = true }, enabled = !busy,
                modifier = Modifier.fillMaxWidth().testTag("onboarding-restore")) { Text("Восстановить из копии") }
        }
    }
    if (tutorial) TutorialDialog(onClose = { if (!busy) tutorial = false }, onStart = {
        vm.finishOnboarding(currency, name, type, opening)
    }, busy = busy)
    if (restoring) {
        var password by remember { mutableStateOf("") }
        Form("Восстановление", { restoring = false }, busy, "Выбрать файл", onSave = {
            if (password.length >= 8) { file(FileRequest("backup", false, password)); restoring = false }
        }) {
            Text("Выберите зашифрованную копию ТРАЧУ. и введите её пароль. До проверки файла финансовые данные не меняются.")
            OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text("Пароль резервной копии") },
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), singleLine = true)
            if (password.length < 8) Text("Пароль содержит не менее 8 символов.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun TutorialDialog(onStart: (() -> Unit)? = null, busy: Boolean = false, onClose: () -> Unit) {
    Form("Как пользоваться ТРАЧУ.", onClose, busy, if (onStart == null) "Готово" else "Начать", onStart ?: onClose) {
        if (onStart != null) TextButton(onClick = onStart, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Пропустить обучение") }
        TutorialItem("01", "Записывайте покупки и доходы", "На обзоре добавьте расход или доход, выберите счёт и категорию. Перевод между своими счетами не считается расходом.")
        TutorialItem("02", "Планируйте заранее", "В разделе «План» создавайте бюджеты, цели, долги и запланированные или регулярные платежи.")
        TutorialItem("03", "Смотрите, куда уходят деньги", "В аналитике сравнивайте доходы и расходы, а в операциях ищите и исправляйте записи.")
        TutorialItem("04", "Защитите свои данные", "В боковом меню можно настроить PIN, открыть конвертер валют и сохранить зашифрованную резервную копию. Интернет для учёта не нужен.")
    }
}

@Composable
private fun TutorialItem(number: String, title: String, description: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(number, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
