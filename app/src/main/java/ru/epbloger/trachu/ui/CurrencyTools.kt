package ru.epbloger.trachu.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import ru.epbloger.trachu.AppViewModel
import ru.epbloger.trachu.core.*
import java.util.Currency
import java.util.Locale

private val russian = Locale("ru", "RU")
private fun currencyName(code: String) = Currency.getInstance(code).getDisplayName(russian)
private fun currencyTitle(code: String): String {
    val currency = Currency.getInstance(code)
    return "$code · ${currency.getSymbol(russian)} · ${currencyName(code)}"
}

@Composable
fun CurrencyPicker(label: String, value: String, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth().testTag("currency:$label")) {
            Text(currencyTitle(value), Modifier.weight(1f))
            Text("⌄")
        }
    }
    if (open) {
        var query by remember { mutableStateOf("") }
        val filtered = remember(query) {
            allCurrencies.filter { currencyTitle(it).contains(query.trim(), ignoreCase = true) }
        }
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(label) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field("Поиск валюты", query, { query = it })
                    if (filtered.isEmpty()) Text("Ничего не найдено")
                    LazyColumn(Modifier.heightIn(max = 340.dp)) {
                        if (query.isBlank()) item { Text("Популярные валюты", style = MaterialTheme.typography.labelLarge) }
                        items(filtered, key = { it }) { code ->
                            if (query.isBlank() && code == allCurrencies.getOrNull(10))
                                Text("Все валюты", style = MaterialTheme.typography.labelLarge)
                            TextButton(onClick = { open = false; onChange(code) }, modifier = Modifier.fillMaxWidth().testTag("currency-option:$code")) {
                                Text(currencyTitle(code), Modifier.weight(1f))
                                if (code == value) Text("✓")
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text("Закрыть") } }
        )
    }
}

@Composable
fun CurrencyConverter(ledger: Ledger, vm: AppViewModel, busy: Boolean, back: () -> Unit, rates: () -> Unit) {
    var from by remember(ledger.baseCurrency) { mutableStateOf(ledger.baseCurrency) }
    var to by remember(ledger.baseCurrency) { mutableStateOf(if (ledger.baseCurrency == "USD") "RUB" else "USD") }
    var amount by remember { mutableStateOf("1") }
    var manual by remember { mutableStateOf(false) }
    var rateText by remember { mutableStateOf("") }
    val day = today()
    val saved = remember(ledger, from, to, day) { runCatching { CurrencyConversion.quote(ledger, from, to, day) }.getOrNull() }
    val result = runCatching {
        val minor = Money.parse(amount, from)
        val rate = if (from == to) java.math.BigDecimal.ONE else if (manual) CurrencyConversion.manualRate(rateText)
            else requireNotNull(saved) { "Нет сохранённого курса. Введите курс вручную или обновите данные." }
        CurrencyConversion.convert(minor, from, to, rate)
    }
    LazyColumn(modifier = Modifier.testTag("converter-list"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { SectionTitle("Конвертер валют", action = "Назад", onAction = back) }
        item {
            Panel {
                CurrencyPicker("Из валюты", from) { from = it; rateText = "" }
                Field("Сумма ($from)", amount, { amount = it }, numeric = true)
                OutlinedButton(onClick = { val old = from; from = to; to = old; rateText = "" }, modifier = Modifier.fillMaxWidth()) {
                    Text("⇅ Поменять валюты местами")
                }
                CurrencyPicker("В валюту", to) { to = it; rateText = "" }
            }
        }
        item {
            Panel {
                Text("Результат", style = MaterialTheme.typography.titleMedium)
                result.onSuccess { MoneyText(it, to, large = true) }
                    .onFailure { Warning(it.message ?: "Проверьте сумму и курс") }
                if (from != to) {
                    Toggle("Ввести курс вручную", manual, { manual = it })
                    if (manual) {
                        Field("$to за 1 $from", rateText, { rateText = it }, numeric = true)
                        Text("Ручной курс применяется только к этому расчёту.")
                    } else if (saved != null) {
                        Text("1 $from = ${saved.toPlainString()} $to")
                        listOf(from, to).filter { it != ledger.baseCurrency }.distinct().forEach { code ->
                            ledger.rates.filter { it.currency == code && it.day <= day }.maxByOrNull { it.day }?.let { rate ->
                                Text("$code · ${dayLabel(rate.day)} · ${if (rate.source == "CBR") "ЦБ РФ" else "Ручной курс"}",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                } else Text("Валюты совпадают: курс 1 к 1.")
                Text("Конвертация не создаёт операцию и не меняет баланс. Результат округляется до минимальной денежной единицы.", style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            Panel {
                Button(onClick = { vm.fetchRates(day) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Обновить курсы ЦБ РФ") }
                OutlinedButton(onClick = rates, modifier = Modifier.fillMaxWidth()) { Text("История и ручные курсы") }
                Text("Без интернета доступны сохранённые и ручные курсы.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
