package com.example.aiaccounting.ui

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.aiaccounting.data.local.CategoryEntity
import com.example.aiaccounting.data.local.EntryEntity
import com.example.aiaccounting.data.repository.LedgerRepository
import com.example.aiaccounting.data.repository.LedgerSnapshot
import com.example.aiaccounting.domain.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.YearMonth

enum class Page { HOME, STATISTICS, SETTINGS, ENTRY, CATEGORIES, CATEGORY_EDITOR }
enum class Confirmation { DISCARD_ENTRY, DISCARD_CATEGORY, DELETE_ENTRY, DELETE_CATEGORY, STOP_USED_CATEGORY }

data class LedgerUiState(
    val data: LedgerSnapshot = LedgerSnapshot(),
    val loading: Boolean = true,
    val busy: Boolean = false,
    val error: String? = null,
    val page: Page = Page.HOME,
    val month: YearMonth = YearMonth.now(),
    val filter: Long? = null,
    val entry: EntryDraft = EntryDraft(),
    val category: CategoryDraft = CategoryDraft(),
    val categoryKind: EntryKind = EntryKind.EXPENSE,
    val statisticsKind: EntryKind = EntryKind.EXPENSE,
    val categoryReturn: Page = Page.SETTINGS,
    val showArchived: Boolean = false,
    val sorting: Boolean = false,
    val confirmation: Confirmation? = null,
)
data class LedgerMessage(val text: String, val undoEntryId: Long? = null)

class LedgerViewModel(private val repository: LedgerRepository, private val saved: SavedStateHandle) : ViewModel() {
    private val mutableState = MutableStateFlow(restoreState())
    val state = mutableState.asStateFlow()
    private val messageChannel = Channel<LedgerMessage>(Channel.UNLIMITED)
    val messages = messageChannel.receiveAsFlow()
    private var observing = false

    init { load() }

    private fun restoreState(): LedgerUiState = runCatching {
        LedgerUiState(
            page = Page.valueOf(saved["page"] ?: Page.HOME.name),
            month = YearMonth.parse(saved["month"] ?: YearMonth.now().toString()),
            categoryReturn = Page.valueOf(saved["categoryReturn"] ?: Page.SETTINGS.name),
            categoryKind = EntryKind.valueOf(saved["categoryKind"] ?: EntryKind.EXPENSE.name),
            entry = EntryDraft(saved["entryId"], EntryKind.valueOf(saved["entryKind"] ?: EntryKind.EXPENSE.name), saved["amount"] ?: "", saved["categoryId"], saved["date"] ?: LocalDate.now().toString(), saved["note"] ?: ""),
            category = CategoryDraft(saved["editingCategoryId"], EntryKind.valueOf(saved["editingCategoryKind"] ?: EntryKind.EXPENSE.name), saved["categoryName"] ?: "", saved["categoryIcon"] ?: "coffee", saved["categoryColor"] ?: "blue"),
        )
    }.getOrDefault(LedgerUiState())

    private fun update(transform: (LedgerUiState) -> LedgerUiState) {
        mutableState.update(transform)
        val s = state.value
        saved["page"] = s.page.name; saved["month"] = s.month.toString(); saved["categoryReturn"] = s.categoryReturn.name; saved["categoryKind"] = s.categoryKind.name
        saved["entryId"] = s.entry.id; saved["entryKind"] = s.entry.kind.name; saved["amount"] = s.entry.amount; saved["categoryId"] = s.entry.categoryId; saved["date"] = s.entry.date; saved["note"] = s.entry.note
        saved["editingCategoryId"] = s.category.id; saved["editingCategoryKind"] = s.category.kind.name; saved["categoryName"] = s.category.name; saved["categoryIcon"] = s.category.icon; saved["categoryColor"] = s.category.color
    }

    fun load() {
        if (observing) return
        observing = true
        viewModelScope.launch {
            update { it.copy(loading = true, error = null) }
            try {
                repository.initialize()
                repository.snapshots.collect { data ->
                    update { s ->
                        val selected = data.categories.find { it.id == s.entry.categoryId }
                        val original = data.entries.find { it.id == s.entry.id }
                        val valid = selected != null && selected.kind == s.entry.kind && (selected.active || original?.categoryId == selected.id)
                        s.copy(data = data, loading = false, entry = if (valid) s.entry else s.entry.copy(categoryId = data.categories.firstOrNull { it.kind == s.entry.kind && it.active }?.id))
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { observing = false; update { it.copy(loading = false, error = "无法打开本机账本，请重试。不会清空数据。") } }
        }
    }

    private fun operation(success: (LedgerUiState) -> LedgerUiState = { it }, message: String? = null, block: suspend () -> Unit) {
        if (state.value.busy) return
        update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                block()
                val data = repository.snapshot()
                update { success(it.copy(data = data, busy = false, confirmation = null)) }
                if (message != null) messageChannel.send(LedgerMessage(message))
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                val text = if (e is IllegalArgumentException) e.message ?: "请检查输入" else "操作失败，输入已保留，请重试"
                update { it.copy(busy = false, confirmation = null, error = text) }
            }
        }
    }

    fun navigate(page: Page) { if (!state.value.busy) update { it.copy(page = page, filter = null, error = null) } }
    fun moveMonth(delta: Long) { if (!state.value.busy) update { it.copy(month = it.month.plusMonths(delta), filter = null, error = null) } }
    fun setMonth(month: YearMonth) { update { it.copy(month = month, filter = null) } }
    fun filterCategory(id: Long?) { update { it.copy(page = Page.HOME, filter = id) } }
    fun statisticsKind(kind: EntryKind) { update { it.copy(statisticsKind = kind) } }

    fun newEntry() {
        if (state.value.busy) return
        val s = state.value
        // A pending new-entry draft survives leaving the editor and visiting category management.
        val draft = if (s.entry.id == null) s.entry else EntryDraft()
        update { it.copy(page = Page.ENTRY, error = null, entry = draft.copy(categoryId = draft.categoryId ?: it.data.categories.firstOrNull { c -> c.kind == draft.kind && c.active }?.id)) }
    }
    fun editEntry(record: EntryEntity) { if (!state.value.busy) update { it.copy(page = Page.ENTRY, error = null, entry = EntryDraft(record.id, record.kind, Money.input(record.cents), record.categoryId, record.date, record.note)) } }
    fun changeEntry(transform: (EntryDraft) -> EntryDraft) { if (!state.value.busy) update { it.copy(entry = transform(it.entry), error = null) } }
    fun entryKind(kind: EntryKind) { changeEntry { d -> d.copy(kind = kind, categoryId = state.value.data.categories.firstOrNull { it.kind == kind && it.active }?.id) } }
    fun saveEntry() {
        val draft = state.value.entry
        operation(success = { it.copy(page = Page.HOME, month = YearMonth.from(LocalDate.parse(draft.date)), filter = null, entry = EntryDraft()) }, message = "记录已保存") { repository.saveEntry(draft) }
    }

    fun manageCategories() { if (!state.value.busy) update { it.copy(page = Page.CATEGORIES, categoryReturn = it.page, categoryKind = if (it.page == Page.ENTRY) it.entry.kind else EntryKind.EXPENSE, sorting = false, showArchived = false, error = null) } }
    fun categoryKind(kind: EntryKind) { update { it.copy(categoryKind = kind, error = null) } }
    fun toggleArchived() { update { it.copy(showArchived = !it.showArchived) } }
    fun toggleSorting() { update { it.copy(sorting = !it.sorting) } }
    fun moveCategory(id: Long, direction: Int) { operation { repository.moveCategory(id, direction) } }
    fun newCategory() { if (!state.value.busy) update { it.copy(page = Page.CATEGORY_EDITOR, category = CategoryDraft(kind = it.categoryKind), error = null) } }
    fun editCategory(c: CategoryEntity) { if (!state.value.busy) update { it.copy(page = Page.CATEGORY_EDITOR, category = CategoryDraft(c.id, c.kind, c.name, c.icon, c.color), error = null) } }
    fun changeCategory(transform: (CategoryDraft) -> CategoryDraft) { if (!state.value.busy) update { it.copy(category = transform(it.category), error = null) } }
    fun saveCategory() { val draft = state.value.category; operation(success = { it.copy(page = Page.CATEGORIES) }, message = "分类已保存") { repository.saveCategory(draft) } }
    fun toggleCategoryActive() {
        if (categoryDirty(state.value)) { update { it.copy(error = "请先保存分类修改，再调整停用状态") }; return }
        val c = state.value.data.categories.find { it.id == state.value.category.id } ?: return
        operation(success = { it.copy(page = Page.CATEGORIES) }, message = if (c.active) "分类已停用，历史记录仍保留" else "分类已恢复") { repository.setCategoryActive(c.id, !c.active) }
    }
    fun askDeleteCategory() {
        if (categoryDirty(state.value)) { update { it.copy(error = "请先保存分类修改，再删除或停用") }; return }
        update { it.copy(confirmation = if (it.data.usage(it.category.id ?: 0) > 0) Confirmation.STOP_USED_CATEGORY else Confirmation.DELETE_CATEGORY) }
    }
    fun askDeleteEntry() { update { it.copy(confirmation = Confirmation.DELETE_ENTRY) } }
    fun cancelConfirmation() { update { it.copy(confirmation = null) } }

    private fun entryDirty(s: LedgerUiState): Boolean {
        val d = s.entry
        if (d.id == null) return d.amount.isNotBlank() || d.note.isNotBlank() || d.date != LocalDate.now().toString()
        val old = s.data.entries.find { it.id == d.id } ?: return true
        return d.kind != old.kind || d.amount != Money.input(old.cents) || d.categoryId != old.categoryId || d.date != old.date || d.note != old.note
    }
    private fun categoryDirty(s: LedgerUiState): Boolean {
        val d = s.category
        val old = s.data.categories.find { it.id == d.id }
        return if (old == null) d.name.isNotBlank() || d.icon != "coffee" || d.color != "blue" else d.name != old.name || d.icon != old.icon || d.color != old.color
    }
    fun back() {
        if (state.value.busy) return
        val s = state.value
        when (s.page) {
            Page.ENTRY -> if (entryDirty(s)) update { it.copy(confirmation = Confirmation.DISCARD_ENTRY) } else update { it.copy(page = Page.HOME, entry = EntryDraft(), error = null) }
            Page.CATEGORY_EDITOR -> if (categoryDirty(s)) update { it.copy(confirmation = Confirmation.DISCARD_CATEGORY) } else update { it.copy(page = Page.CATEGORIES, error = null) }
            Page.CATEGORIES -> update { it.copy(page = it.categoryReturn, error = null) }
            Page.STATISTICS, Page.SETTINGS -> navigate(Page.HOME)
            Page.HOME -> if (s.filter != null) filterCategory(null)
        }
    }
    fun confirm() {
        val s = state.value
        when (s.confirmation) {
            Confirmation.DISCARD_ENTRY -> update { it.copy(page = Page.HOME, entry = EntryDraft(), confirmation = null, error = null) }
            Confirmation.DISCARD_CATEGORY -> update { it.copy(page = Page.CATEGORIES, confirmation = null, error = null) }
            Confirmation.DELETE_CATEGORY -> operation(success = { it.copy(page = Page.CATEGORIES) }, message = "分类已删除") { repository.deleteCategory(s.category.id ?: return@operation) }
            Confirmation.STOP_USED_CATEGORY -> operation(success = { it.copy(page = Page.CATEGORIES) }, message = "分类已停用，历史记录仍保留") { repository.setCategoryActive(s.category.id ?: return@operation, false) }
            Confirmation.DELETE_ENTRY -> {
                val id = s.entry.id ?: return
                operation(success = { it.copy(page = Page.HOME, entry = EntryDraft()) }) {
                    repository.deleteEntry(id)
                    messageChannel.send(LedgerMessage("记录已删除", id))
                }
            }
            null -> Unit
        }
    }
    fun undo(id: Long) { operation(message = "记录已恢复") { repository.restoreEntry(id) } }

    fun exportBackup(resolver: ContentResolver, uri: Uri) {
        operation(message = "完整备份已导出") {
            withContext(Dispatchers.IO) {
                val json = repository.backupJson()
                val stream = resolver.openOutputStream(uri, "wt") ?: throw IllegalStateException("无法写入文件")
                stream.bufferedWriter(Charsets.UTF_8).use { it.write(json) }
            }
        }
    }
}
