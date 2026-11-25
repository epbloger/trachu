package ru.epbloger.trachu.core

import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.util.UUID

fun newId(): String = UUID.randomUUID().toString()
fun today(): Long = LocalDate.now().toEpochDay()

@Serializable enum class AccountType { CASH, CARD, SAVINGS, CREDIT, OTHER }
@Serializable enum class Kind { EXPENSE, INCOME, TRANSFER, REFUND, DEBT_IN, DEBT_OUT }
@Serializable enum class EntryStatus { POSTED, PLANNED }
@Serializable enum class Frequency { DAILY, WEEKLY, MONTHLY, YEARLY }

@Serializable data class Account(
    val id: String = newId(), val name: String, val currency: String = "RUB",
    val type: AccountType = AccountType.CASH, val openingMinor: Long = 0,
    val archived: Boolean = false, val includeInTotal: Boolean = true
)
@Serializable data class Category(
    val id: String = newId(), val name: String, val income: Boolean = false,
    val parentId: String? = null, val color: Long = 0xFF218C75,
    val icon: String = "•", val archived: Boolean = false
)
@Serializable data class Split(
    val categoryId: String, val minor: Long, val baseMinor: Long
)
@Serializable data class Entry(
    val id: String = newId(), val kind: Kind, val accountId: String,
    val minor: Long, val baseMinor: Long, val day: Long = today(),
    val timeMinutes: Int = 720, val note: String = "", val tags: List<String> = emptyList(),
    val splits: List<Split> = emptyList(), val status: EntryStatus = EntryStatus.POSTED,
    val targetAccountId: String? = null, val targetMinor: Long? = null,
    val originalId: String? = null, val debtId: String? = null,
    val principalMinor: Long = 0, val essential: Boolean = false,
    val receiptIds: List<String> = emptyList(), val rate: String = "1"
)
@Serializable data class Budget(
    val id: String = newId(), val name: String, val categoryId: String? = null,
    val monthlyMinor: Long, val startMonth: Long, val rollover: Boolean = false
)
@Serializable data class Debt(
    val id: String = newId(), val person: String, val accountId: String,
    val owedByMe: Boolean, val principalMinor: Long, val annualPercent: String = "0",
    val dueDay: Long? = null, val note: String = ""
)
@Serializable data class Goal(
    val id: String = newId(), val name: String, val currency: String = "RUB",
    val targetMinor: Long, val savedMinor: Long = 0, val dueDay: Long? = null
)
@Serializable data class Schedule(
    val id: String = newId(), val template: Entry, val nextDay: Long,
    val frequency: Frequency, val interval: Int = 1, val endDay: Long? = null,
    val autoPost: Boolean = false, val enabled: Boolean = true,
    val anchorDay: Int = LocalDate.ofEpochDay(nextDay).dayOfMonth
)
@Serializable data class FxRate(
    val currency: String, val day: Long, val unitsOfBasePerUnit: String,
    val source: String = "manual"
)
@Serializable data class Receipt(val id: String = newId(), val mime: String, val base64: String)
@Serializable data class Ledger(
    val baseCurrency: String = "RUB", val accounts: List<Account> = emptyList(),
    val categories: List<Category> = emptyList(), val entries: List<Entry> = emptyList(),
    val budgets: List<Budget> = emptyList(), val debts: List<Debt> = emptyList(),
    val goals: List<Goal> = emptyList(), val schedules: List<Schedule> = emptyList(),
    val rates: List<FxRate> = emptyList(), val receipts: List<Receipt> = emptyList()
) {
    companion object {
        fun initial() = Ledger(categories = listOf(
            Category(id = "food", name = "Продукты", icon = "🛒"),
            Category(id = "transport", name = "Транспорт", icon = "🚇", color = 0xFF4677BD),
            Category(id = "home", name = "Дом", icon = "🏠", color = 0xFF9469B6),
            Category(id = "health", name = "Здоровье", icon = "✚", color = 0xFFBF646C),
            Category(id = "leisure", name = "Досуг", icon = "★", color = 0xFFB98B40),
            Category(id = "other", name = "Другое", icon = "•"),
            Category(id = "salary", name = "Зарплата", income = true, icon = "₽"),
            Category(id = "other-income", name = "Другие доходы", income = true, icon = "+")
        ))
    }
}
