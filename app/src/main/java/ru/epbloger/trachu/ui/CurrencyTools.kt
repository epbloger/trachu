package ru.epbloger.trachu.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ru.epbloger.trachu.AppViewModel
import ru.epbloger.trachu.core.*
import kotlinx.coroutines.delay
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
                            TextButton(onClick = { open = false; onChange(code) }, modifier = Modifier.fillMaxWidth().testTag("currency-option:$code").semantics { selected = code == value }) {
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
    if(vm.converterBase!=ledger.baseCurrency){
        vm.converterBase=ledger.baseCurrency
        vm.converterDraft=EntryDraft()
    }
    val draft=vm.converterDraft
    var from by draft.state("from"){ledger.baseCurrency}
    var to by draft.state("to"){if(ledger.baseCurrency=="USD")"RUB" else "USD"}
    var amount by draft.state("amount"){"1"}
    var manual by draft.state("manual"){false}
    var rateText by draft.state("rate"){ "" }
    var date by draft.state("date"){iso(today())}
    val settings by vm.settings.collectAsState()
    val automatic=settings?.automaticRates==true
    val status by vm.converterRateStatus.collectAsState()
    val day = runCatching { parseDay(date).also { require(it <= today()) } }.getOrNull()
    val currentStatus=status?.takeIf{it.base==ledger.baseCurrency&&it.day==day}
    LaunchedEffect(ledger.baseCurrency,day,automatic,manual,from==to) {
        if(automatic&&!manual&&from!=to&&day!=null){
            delay(350)
            while(true){
                vm.refreshConverterRates(day)
                // Retry a failed connection while the converter stays open;
                // successful dates are throttled by the ViewModel.
                delay(60_000)
            }
        }
    }
    val saved = remember(ledger, from, to, day) { day?.let { selectedDay ->
        runCatching { CurrencyConversion.quote(ledger, from, to, selectedDay) }.getOrNull()
    } }
    val quoteRates=remember(ledger,from,to,day){
        listOf(from,to).distinct().filter{it!=ledger.baseCurrency}.mapNotNull{code->
            ledger.rates.filter{it.currency==code&&day!=null&&it.day<=day}.maxByOrNull{it.day}
        }
    }
    val result = runCatching {
        val minor = Money.parse(amount, from)
        if (from == to) CurrencyConversion.convert(minor, from, to, java.math.BigDecimal.ONE)
        else if (manual) CurrencyConversion.convert(minor, from, to, CurrencyConversion.manualRate(rateText))
        else {
            val selectedDay = requireNotNull(day) { "Укажите корректную дату, не позже сегодняшней." }
            requireNotNull(saved) { "Нет сохранённого курса. Введите курс вручную или обновите данные." }
            CurrencyConversion.convertStored(ledger, minor, from, to, selectedDay)
        }
    }
    LazyColumn(modifier = Modifier.testTag("converter-list"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { SectionTitle("Конвертер валют", action = "Назад", onAction = back) }
        item {
            Panel {
                CurrencyPicker("Из валюты", from) { from = it; rateText = "" }
                Field("Сумма ($from)", amount, { amount = it }, numeric = true)
                OutlinedButton(onClick = {
                    val converted = result.getOrNull()
                    val old = from
                    from = to
                    to = old
                    if (converted != null) amount = Money.input(converted, from)
                    else amount = ""
                    if (manual && from != to) {
                        rateText = runCatching {
                            java.math.BigDecimal.ONE.divide(CurrencyConversion.manualRate(rateText), 24, java.math.RoundingMode.HALF_EVEN)
                                .stripTrailingZeros().toPlainString()
                        }.getOrDefault("")
                    } else rateText = ""
                }, modifier = Modifier.fillMaxWidth().testTag("converter-swap")) {
                    Text("⇅ Поменять валюты местами")
                }
                CurrencyPicker("В валюту", to) { to = it; rateText = "" }
                HorizontalDivider()
                Text("Результат", style = MaterialTheme.typography.titleMedium)
                result.onSuccess { MoneyText(it, to, large = true) }
                    .onFailure {
                        if(!manual&&from!=to&&saved==null&&currentStatus?.loading==true)
                            Text("Получаем курс ЦБ РФ…",style=MaterialTheme.typography.bodyLarge)
                        else Warning(it.message?.takeIf{m->m.any{c->c in 'А'..'я'||c=='ё'||c=='Ё'}} ?: "Проверьте сумму и курс")
                    }
                if(!manual&&from!=to&&currentStatus?.loading==true){
                    LinearProgressIndicator(Modifier.fillMaxWidth().testTag("converter-loading"))
                    if(saved!=null)Text("Обновляем курс. Пока показан сохранённый.",style=MaterialTheme.typography.bodyMedium)
                }
                if(!manual&&from!=to&&saved!=null&&day!=null&&quoteRates.any{it.day<day}){
                    Text("Курс опубликован ранее выбранной даты. Используется последний доступный.",
                        modifier=Modifier.testTag("converter-rate-age"),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if(!manual&&from!=to)currentStatus?.error?.let{Text(it,modifier=Modifier.testTag("converter-error"),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)}
            }
        }
        item {
            Panel {
                if (from != to) {
                    Toggle("Ввести курс вручную", manual, { manual = it })
                    if (manual) {
                        Field("$to за 1 $from", rateText, { rateText = it }, numeric = true)
                        Text("Только для этого расчёта. Учёт не изменится.")
                    } else if (saved != null) {
                        Text("1 $from ≈ ${saved.toPlainString()} $to")
                        quoteRates.forEach { rate ->
                            Text("${rate.currency} · ${dayLabel(rate.day)} · ${if (rate.source == "CBR") "ЦБ РФ" else "Ручной курс"}",
                                style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    if (!manual) DateField("Курс на дату", date, { date = it }, supporting = "Используется последний сохранённый курс не позже этой даты.")
                } else Text("Валюты совпадают: курс 1 к 1.")
                Text("Конвертация не создаёт операцию и не меняет баланс. Результат округляется до минимальной денежной единицы.", style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            Panel {
                OutlinedButton(onClick = { day?.let{vm.refreshConverterRates(it,force=true)} }, enabled = !busy && day != null && currentStatus?.loading!=true, modifier = Modifier.fillMaxWidth()) { Text("Обновить курс сейчас") }
                Text(if(automatic)"Курсы обновляются автоматически при открытии конвертера." else "Автообновление выключено. Включите его в управлении курсами.",style=MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = rates, modifier = Modifier.fillMaxWidth().testTag("converter-rates")) { Text("Управление курсами") }
                Text("Без интернета доступны сохранённые и ручные курсы.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
