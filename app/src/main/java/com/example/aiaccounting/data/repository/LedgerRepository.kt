package com.example.aiaccounting.data.repository

import androidx.room.withTransaction
import com.example.aiaccounting.data.local.*
import com.example.aiaccounting.domain.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.YearMonth

data class LedgerSnapshot(val categories: List<CategoryEntity> = emptyList(), val entries: List<EntryEntity> = emptyList()) {
    fun monthly(month: YearMonth, categoryId: Long? = null) = entries.filter {
        it.deletedAt == null && it.date.startsWith(month.toString()) && (categoryId == null || categoryId == it.categoryId)
    }
    fun totals(month: YearMonth): MonthlyTotals {
        val rows = monthly(month)
        return MonthlyTotals(rows.filter { it.kind == EntryKind.EXPENSE }.sumOf { it.cents }, rows.filter { it.kind == EntryKind.INCOME }.sumOf { it.cents })
    }
    fun usage(id: Long) = entries.count { it.categoryId == id }
}

class LedgerRepository(private val db: LedgerDatabase) {
    private val dao = db.ledgerDao()
    val snapshots: Flow<LedgerSnapshot> = db.invalidationTracker.createFlow("categories", "entries").map { snapshot() }

    suspend fun snapshot(): LedgerSnapshot = db.withTransaction { LedgerSnapshot(dao.categories(), dao.entries()) }

    suspend fun initialize() = db.withTransaction {
        if (dao.categories().isEmpty()) {
            val expense = listOf("餐饮" to "food", "交通" to "transport", "购物" to "shopping", "居住" to "home", "娱乐" to "fun", "医疗" to "health", "学习" to "study", "其他支出" to "other")
            val income = listOf("工资" to "work", "奖金" to "wallet", "兼职" to "study", "红包" to "gift", "其他收入" to "other")
            dao.insertCategories(expense.mapIndexed { i, (name, icon) -> CategoryEntity(kind = EntryKind.EXPENSE, name = name, icon = icon, color = if (i == 0) "orange" else "blue", sortOrder = i, fallback = i == expense.lastIndex) } +
                income.mapIndexed { i, (name, icon) -> CategoryEntity(kind = EntryKind.INCOME, name = name, icon = icon, color = "green", sortOrder = i, fallback = i == income.lastIndex) })
        }
    }

    suspend fun saveEntry(draft: EntryDraft): Long = db.withTransaction {
        val cents = Money.parse(draft.amount) ?: throw IllegalArgumentException("金额必须大于零，最多两位小数，最高 999,999,999.99 元")
        require(validDate(draft.date)) { "请选择有效日期" }
        require(draft.note.length <= 120) { "备注最多 120 字" }
        val category = draft.categoryId?.let { dao.category(it) } ?: throw IllegalArgumentException("请选择分类")
        val old = draft.id?.let { dao.entry(it) ?: throw IllegalArgumentException("记录不存在") }
        require(old?.deletedAt == null) { "记录已删除，请先撤销删除" }
        require(category.kind == draft.kind) { "分类与收支类型不匹配" }
        require(category.active || old?.categoryId == category.id) { "分类已停用，请重新选择" }
        val now = System.currentTimeMillis()
        val entry = EntryEntity(id = old?.id ?: 0, kind = draft.kind, cents = cents, categoryId = category.id, date = draft.date, note = draft.note.trim(), createdAt = old?.createdAt ?: now, updatedAt = now)
        if (old == null) dao.insertEntry(entry) else { dao.updateEntry(entry); old.id }
    }

    suspend fun saveEntries(drafts: List<EntryDraft>): List<Long> = db.withTransaction {
        require(drafts.size in 1..20 && drafts.all { it.id == null }) { "请确认 1 至 20 笔新的账目草稿" }
        // Nested Room transactions join this transaction: any invalid row rolls the whole batch back.
        drafts.map { saveEntry(it) }
    }

    suspend fun saveAiBatch(batchId: String, drafts: List<EntryDraft>) = db.withTransaction {
        require(batchId.isNotBlank())
        // The receipt and rows commit together. Restoring an old pending draft cannot insert it twice.
        if (dao.aiBatchCount(batchId) == 0) {
            saveEntries(drafts)
            dao.insertAiBatch(AiBatchEntity(batchId, System.currentTimeMillis()))
        }
    }

    suspend fun saveCategory(draft: CategoryDraft) = db.withTransaction {
        val name = draft.name.trim()
        require(name.isNotEmpty() && name.length <= 12) { "分类名称需要 1 至 12 个字" }
        require(draft.icon in categoryIcons && draft.color in categoryColors) { "请选择有效图标与颜色" }
        val all = dao.categories()
        require(all.none { it.kind == draft.kind && it.name == name && it.id != draft.id }) { "同类型已有这个分类名称（包括已停用分类）" }
        val old = draft.id?.let { dao.category(it) ?: throw IllegalArgumentException("分类不存在") }
        require(old == null || old.kind == draft.kind) { "已有分类不能改变收支类型" }
        if (old != null) dao.updateCategory(old.copy(name = name, icon = draft.icon, color = draft.color))
        else dao.insertCategory(CategoryEntity(kind = draft.kind, name = name, icon = draft.icon, color = draft.color, sortOrder = (all.filter { it.kind == draft.kind }.maxOfOrNull { it.sortOrder } ?: -1) + 1))
    }

    suspend fun setCategoryActive(id: Long, active: Boolean) = db.withTransaction {
        val c = dao.category(id) ?: throw IllegalArgumentException("分类不存在")
        require(!c.fallback || active) { "兜底分类需要保留" }
        dao.updateCategory(c.copy(active = active))
    }

    suspend fun deleteCategory(id: Long) = db.withTransaction {
        val c = dao.category(id) ?: throw IllegalArgumentException("分类不存在")
        require(!c.fallback) { "兜底分类不能删除" }
        require(dao.usage(id) == 0) { "已有历史记录，请停用分类" }
        dao.deleteCategory(id)
    }

    suspend fun moveCategory(id: Long, direction: Int) = db.withTransaction {
        require(direction == -1 || direction == 1)
        val c = dao.category(id) ?: throw IllegalArgumentException("分类不存在")
        val rows = dao.categories().filter { it.kind == c.kind && it.active == c.active }
        val index = rows.indexOfFirst { it.id == id }
        val other = rows.getOrNull(index + direction) ?: return@withTransaction
        dao.updateCategory(c.copy(sortOrder = other.sortOrder))
        dao.updateCategory(other.copy(sortOrder = c.sortOrder))
    }

    suspend fun deleteEntry(id: Long) = db.withTransaction {
        val entry = dao.entry(id) ?: throw IllegalArgumentException("记录不存在")
        val now = System.currentTimeMillis()
        dao.updateEntry(entry.copy(deletedAt = now, updatedAt = now))
    }
    suspend fun restoreEntry(id: Long) = db.withTransaction {
        val entry = dao.entry(id) ?: throw IllegalArgumentException("记录不存在")
        dao.updateEntry(entry.copy(deletedAt = null, updatedAt = System.currentTimeMillis()))
    }

    suspend fun backupJson(): String {
        val data = snapshot()
        return JSONObject().put("format", "ai-accounting").put("formatVersion", 1).put("databaseVersion", 2).put("appVersion", "0.3.0").put("currency", "CNY").put("exportedAt", Instant.now().toString())
            .put("categories", JSONArray().apply { data.categories.forEach { c -> put(JSONObject().put("id", c.id).put("kind", c.kind.name).put("name", c.name).put("icon", c.icon).put("color", c.color).put("sortOrder", c.sortOrder).put("active", c.active).put("fallback", c.fallback)) } })
            .put("entries", JSONArray().apply { data.entries.forEach { e -> put(JSONObject().put("id", e.id).put("kind", e.kind.name).put("cents", e.cents).put("categoryId", e.categoryId).put("date", e.date).put("note", e.note).put("createdAt", e.createdAt).put("updatedAt", e.updatedAt).put("deletedAt", e.deletedAt ?: JSONObject.NULL)) } }).toString(2)
    }
}
