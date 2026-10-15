package com.example.aiaccounting.domain.model

data class SearchFilters(
    val keyword: String = "",
    val kind: EntryKind? = null,
    val categoryId: Long? = null,
    val fromDate: String = "",
    val toDate: String = "",
    val minAmount: String = "",
    val maxAmount: String = "",
) {
    fun error(): String? = when {
        fromDate.isNotBlank() && !canonicalDate(fromDate) -> "开始日期无效"
        toDate.isNotBlank() && !canonicalDate(toDate) -> "结束日期无效"
        fromDate.isNotBlank() && toDate.isNotBlank() && fromDate > toDate -> "开始日期不能晚于结束日期"
        minAmount.isNotBlank() && amountBound(minAmount) == null -> "最低金额请填写非负数，最多两位小数"
        maxAmount.isNotBlank() && amountBound(maxAmount) == null -> "最高金额请填写非负数，最多两位小数"
        minAmount.isNotBlank() && maxAmount.isNotBlank() && amountBound(minAmount)!! > amountBound(maxAmount)!! -> "最低金额不能大于最高金额"
        else -> null
    }
    val hasConditions: Boolean get() = kind != null || categoryId != null || fromDate.isNotEmpty() || toDate.isNotEmpty() || minAmount.isNotEmpty() || maxAmount.isNotEmpty()
}

fun canonicalDate(value: String) = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(value) && validDate(value)
fun amountBound(value: String): Long? = if (Regex("0{1,9}(\\.0{1,2})?").matches(value.trim())) 0 else Money.parse(value)
