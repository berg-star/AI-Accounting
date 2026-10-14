package com.example.aiaccounting.domain.model

import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale

enum class EntryKind(val label: String) { EXPENSE("支出"), INCOME("收入") }

object Money {
    const val MAX_CENTS = 99_999_999_999L
    fun parse(text: String): Long? {
        val value = text.trim()
        if (!Regex("[0-9]{1,9}(\\.[0-9]{1,2})?").matches(value)) return null
        val parts = value.split('.')
        val cents = parts[0].toLong() * 100 + parts.getOrElse(1) { "" }.padEnd(2, '0').toLong()
        return cents.takeIf { it in 1..MAX_CENTS }
    }
    fun input(cents: Long) = "${cents / 100}.${(cents % 100).toString().padStart(2, '0')}"
    fun format(cents: Long): String = String.format(Locale.CHINA, "%,d.%02d", kotlin.math.abs(cents) / 100, kotlin.math.abs(cents) % 100).let {
        if (cents < 0) "−$it" else it
    }
}

data class EntryDraft(
    val id: Long? = null,
    val kind: EntryKind = EntryKind.EXPENSE,
    val amount: String = "",
    val categoryId: Long? = null,
    val date: String = LocalDate.now().toString(),
    val note: String = "",
)

data class CategoryDraft(
    val id: Long? = null,
    val kind: EntryKind = EntryKind.EXPENSE,
    val name: String = "",
    val icon: String = "coffee",
    val color: String = "blue",
)

data class MonthlyTotals(val expense: Long = 0, val income: Long = 0) {
    val net get() = income - expense
}

val categoryIcons = listOf("food", "transport", "shopping", "home", "fun", "health", "study", "coffee", "work", "gift", "wallet", "other")
val categoryColors = listOf("blue", "green", "orange", "purple")

fun validDate(value: String): Boolean = runCatching { LocalDate.parse(value) }.isSuccess
fun validMonth(value: String): Boolean = runCatching { YearMonth.parse(value) }.isSuccess
