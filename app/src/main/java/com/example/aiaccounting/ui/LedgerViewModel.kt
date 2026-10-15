package com.example.aiaccounting.ui

import android.content.ContentResolver
import android.net.Uri
import android.os.Bundle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.aiaccounting.data.local.CategoryEntity
import com.example.aiaccounting.data.local.EntryEntity
import com.example.aiaccounting.data.repository.LedgerRepository
import com.example.aiaccounting.data.repository.LedgerSnapshot
import com.example.aiaccounting.domain.model.*
import com.example.aiaccounting.domain.ai.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

enum class Page { HOME, STATISTICS, SETTINGS, ENTRY, CATEGORIES, CATEGORY_EDITOR, AI_INPUT, AI_REVIEW, AI_SETTINGS }
enum class Confirmation { DISCARD_ENTRY, DISCARD_CATEGORY, DELETE_ENTRY, DELETE_CATEGORY, STOP_USED_CATEGORY, CLEAR_API_KEY, DISCARD_AI }

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
    val aiText: String = "",
    val aiDrafts: List<EntryDraft> = emptyList(),
    val aiBatchId: String? = null,
    val aiEditIndex: Int? = null,
    val recognizing: Boolean = false,
    val keyConfigured: Boolean = false,
    val keyRevision: Int = 0,
    val aiSettingsReturn: Page = Page.SETTINGS,
)
data class LedgerMessage(val text: String, val undoEntryId: Long? = null)

class LedgerViewModel(
    private val repository: LedgerRepository,
    private val saved: SavedStateHandle,
    private val textAccounting: TextAccounting? = null,
    private val apiKeys: ApiKeyStore? = null,
) : ViewModel() {
    private val mutableState = MutableStateFlow(restoreState())
    val state = mutableState.asStateFlow()
    private val messageChannel = Channel<LedgerMessage>(Channel.UNLIMITED)
    val messages = messageChannel.receiveAsFlow()
    private var observing = false
    private var recognition: Job? = null
    private var recognitionId = 0L

    init { load() }

    private fun restoreState(): LedgerUiState = runCatching {
        LedgerUiState(
            page = Page.valueOf(saved["page"] ?: Page.HOME.name),
            month = YearMonth.parse(saved["month"] ?: YearMonth.now().toString()),
            categoryReturn = Page.valueOf(saved["categoryReturn"] ?: Page.SETTINGS.name),
            categoryKind = EntryKind.valueOf(saved["categoryKind"] ?: EntryKind.EXPENSE.name),
            entry = EntryDraft(saved["entryId"], EntryKind.valueOf(saved["entryKind"] ?: EntryKind.EXPENSE.name), saved["amount"] ?: "", saved["categoryId"], saved["date"] ?: LocalDate.now().toString(), saved["note"] ?: ""),
            category = CategoryDraft(saved["editingCategoryId"], EntryKind.valueOf(saved["editingCategoryKind"] ?: EntryKind.EXPENSE.name), saved["categoryName"] ?: "", saved["categoryIcon"] ?: "coffee", saved["categoryColor"] ?: "blue"),
            aiText = (saved.get<String>("aiText") ?: "").take(AiLimits.INPUT_LENGTH),
            aiDrafts = saved.get<ArrayList<Bundle>>("aiDrafts")?.take(AiLimits.ENTRIES)?.map { b ->
                EntryDraft(kind = EntryKind.valueOf(b.getString("kind")!!), amount = b.getString("amount")!!,
                    categoryId = b.getLong("categoryId").takeIf { it > 0 }, date = b.getString("date")!!, note = b.getString("note")!!)
            } ?: emptyList(),
            aiEditIndex = saved["aiEditIndex"],
            aiBatchId = saved["aiBatchId"],
            aiSettingsReturn = Page.valueOf(saved["aiSettingsReturn"] ?: Page.SETTINGS.name),
        )
    }.getOrDefault(LedgerUiState())

    private fun update(transform: (LedgerUiState) -> LedgerUiState) {
        mutableState.update(transform)
        val s = state.value
        saved["page"] = s.page.name; saved["month"] = s.month.toString(); saved["categoryReturn"] = s.categoryReturn.name; saved["categoryKind"] = s.categoryKind.name
        saved["entryId"] = s.entry.id; saved["entryKind"] = s.entry.kind.name; saved["amount"] = s.entry.amount; saved["categoryId"] = s.entry.categoryId; saved["date"] = s.entry.date; saved["note"] = s.entry.note
        saved["editingCategoryId"] = s.category.id; saved["editingCategoryKind"] = s.category.kind.name; saved["categoryName"] = s.category.name; saved["categoryIcon"] = s.category.icon; saved["categoryColor"] = s.category.color
        saved["aiText"] = s.aiText; saved["aiEditIndex"] = s.aiEditIndex; saved["aiSettingsReturn"] = s.aiSettingsReturn.name
        saved["aiBatchId"] = s.aiBatchId
        saved["aiDrafts"] = ArrayList(s.aiDrafts.map { d -> Bundle().apply {
            putString("kind", d.kind.name); putString("amount", d.amount); putLong("categoryId", d.categoryId ?: 0)
            putString("date", d.date); putString("note", d.note)
        } })
    }

    fun load() {
        if (observing) return
        observing = true
        viewModelScope.launch {
            update { it.copy(loading = true, error = null) }
            try {
                repository.initialize()
                val keyState = withContext(Dispatchers.IO) { runCatching { !apiKeys?.read().isNullOrBlank() } }
                update { it.copy(keyConfigured = keyState.getOrDefault(false),
                    error = if (keyState.isFailure) "本机 API Key 无法读取，请在 AI 设置中重新保存" else null) }
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
        update { it.copy(page = Page.ENTRY, aiEditIndex = null, error = null, entry = draft.copy(categoryId = draft.categoryId ?: it.data.categories.firstOrNull { c -> c.kind == draft.kind && c.active }?.id)) }
    }
    fun editEntry(record: EntryEntity) {
        if (!state.value.busy) {
            update { it.copy(page = Page.ENTRY, aiEditIndex = null, error = null,
                entry = EntryDraft(record.id, record.kind, Money.input(record.cents), record.categoryId, record.date, record.note)) }
        }
    }

    fun changeEntry(transform: (EntryDraft) -> EntryDraft) {
        if (!state.value.busy) {
            update { it.copy(entry = transform(it.entry), error = null) }
        }
    }

    fun entryKind(kind: EntryKind) {
        changeEntry { d -> d.copy(kind = kind, categoryId = state.value.data.categories.firstOrNull { it.kind == kind && it.active }?.id) }
    }

    fun saveEntry() {
        val draft = state.value.entry
        val index = state.value.aiEditIndex
        if (index != null) {
            if (state.value.busy) return
            val error = validateDraft(draft)
            if (error != null) { update { it.copy(error = error) }; return }
            update { it.copy(page = Page.AI_REVIEW, aiEditIndex = null, entry = EntryDraft(), error = null,
                aiDrafts = it.aiDrafts.mapIndexed { i, old -> if (i == index) draft else old }) }
            return
        }
        operation(success = { it.copy(page = Page.HOME, month = YearMonth.from(LocalDate.parse(draft.date)), filter = null, entry = EntryDraft()) }, message = "记录已保存") { repository.saveEntry(draft) }
    }

    private fun validateDraft(d: EntryDraft): String? = when {
        Money.parse(d.amount) == null -> "请输入大于零、最多两位小数的金额"
        !validDate(d.date) -> "请选择有效日期"
        d.note.length > 120 -> "备注最多 120 字"
        state.value.data.categories.none { it.id == d.categoryId && it.kind == d.kind && it.active } -> "分类已停用或不存在，请重新选择"
        else -> null
    }

    fun openAi() { if (!state.value.busy) update { it.copy(page = if (it.aiDrafts.isEmpty()) Page.AI_INPUT else Page.AI_REVIEW, error = null) } }
    fun changeAiText(text: String) { if (!state.value.busy) update { it.copy(aiText = text.take(AiLimits.INPUT_LENGTH), error = null) } }
    fun openAiSettings() { if (!state.value.busy) update { it.copy(page = Page.AI_SETTINGS, aiSettingsReturn = it.page, error = null) } }
    fun saveApiKey(value: String) {
        val store = apiKeys ?: return
        operation(success = { it.copy(keyConfigured = true, keyRevision = it.keyRevision + 1) }, message = "API Key 已加密保存") {
            withContext(Dispatchers.IO) { store.write(value) }
        }
    }
    fun askClearApiKey() { if (!state.value.busy) update { it.copy(confirmation = Confirmation.CLEAR_API_KEY) } }

    fun recognizeText() {
        val s = state.value
        if (s.busy || s.page != Page.AI_INPUT) return
        if (!s.keyConfigured) { update { it.copy(error = "请先在 AI 设置中保存 API Key") }; return }
        if (s.aiText.isBlank()) { update { it.copy(error = "先写下用途和金额，例如：午饭牛肉面 28，坐地铁 6") }; return }
        val parser = textAccounting ?: return
        val requestId = ++recognitionId
        val categories = s.data.categories.filter { it.active }.map { AiCategory(it.id, it.kind, it.name, it.fallback) }
        update { it.copy(busy = true, recognizing = true, error = null) }
        recognition = viewModelScope.launch {
            try {
                val drafts = parser.parse(s.aiText.trim(), categories, LocalDate.now())
                require(drafts.size in 1..AiLimits.ENTRIES && drafts.all { it.id == null }) { "没有识别到有效账目，请换一种说法" }
                drafts.forEach { d -> validateDraft(d)?.let { throw AiFailure(it) } }
                if (requestId == recognitionId) update { it.copy(page = Page.AI_REVIEW, aiDrafts = drafts, aiBatchId = UUID.randomUUID().toString(), aiEditIndex = null, busy = false, recognizing = false) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (requestId == recognitionId) update { it.copy(busy = false, recognizing = false,
                    error = if (e is AiFailure || e is IllegalArgumentException) e.message else "AI 识别失败，输入已保留，请重试") }
            }
        }
    }

    fun cancelRecognition() {
        if (!state.value.recognizing) return
        ++recognitionId
        recognition?.cancel(); recognition = null
        update { it.copy(busy = false, recognizing = false, error = null) }
    }

    fun editAiDraft(index: Int) {
        if (state.value.busy) return
        val draft = state.value.aiDrafts.getOrNull(index) ?: return
        update { it.copy(page = Page.ENTRY, entry = draft, aiEditIndex = index, error = null) }
    }
    fun removeAiDraft(index: Int) { if (!state.value.busy) update { s ->
        val drafts = s.aiDrafts.filterIndexed { i, _ -> i != index }
        s.copy(aiDrafts = drafts, page = if (drafts.isEmpty()) Page.AI_INPUT else Page.AI_REVIEW, error = null)
    } }
    fun restartAi() { if (!state.value.busy) update { it.copy(confirmation = Confirmation.DISCARD_AI) } }
    fun saveAiDrafts() {
        val s = state.value
        if (s.page != Page.AI_REVIEW || s.aiDrafts.isEmpty()) return
        val batchId = s.aiBatchId ?: UUID.randomUUID().toString().also { id -> update { it.copy(aiBatchId = id) } }
        operation(success = { it.copy(page = Page.HOME, month = YearMonth.from(LocalDate.parse(s.aiDrafts.first().date)), filter = null,
            aiText = "", aiDrafts = emptyList(), aiBatchId = null, aiEditIndex = null, entry = EntryDraft()) }, message = "${s.aiDrafts.size} 笔记录已入账") {
            repository.saveAiBatch(batchId, s.aiDrafts)
        }
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
        s.aiEditIndex?.let { return d != s.aiDrafts.getOrNull(it) }
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
        if (state.value.recognizing) { cancelRecognition(); return }
        if (state.value.busy) return
        val s = state.value
        when (s.page) {
            Page.ENTRY -> if (entryDirty(s)) update { it.copy(confirmation = Confirmation.DISCARD_ENTRY) } else update { it.copy(page = if (it.aiEditIndex == null) Page.HOME else Page.AI_REVIEW, aiEditIndex = null, entry = EntryDraft(), error = null) }
            Page.CATEGORY_EDITOR -> if (categoryDirty(s)) update { it.copy(confirmation = Confirmation.DISCARD_CATEGORY) } else update { it.copy(page = Page.CATEGORIES, error = null) }
            Page.CATEGORIES -> update { it.copy(page = it.categoryReturn, error = null) }
            Page.STATISTICS, Page.SETTINGS -> navigate(Page.HOME)
            Page.AI_INPUT, Page.AI_REVIEW -> navigate(Page.HOME)
            Page.AI_SETTINGS -> navigate(s.aiSettingsReturn)
            Page.HOME -> if (s.filter != null) filterCategory(null)
        }
    }
    fun confirm() {
        val s = state.value
        when (s.confirmation) {
            Confirmation.DISCARD_ENTRY -> update { it.copy(page = if (it.aiEditIndex == null) Page.HOME else Page.AI_REVIEW, aiEditIndex = null, entry = EntryDraft(), confirmation = null, error = null) }
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
            Confirmation.CLEAR_API_KEY -> operation(success = { it.copy(keyConfigured = false, keyRevision = it.keyRevision + 1) }, message = "本机 API Key 已移除") {
                withContext(Dispatchers.IO) { apiKeys?.clear() }
            }
            Confirmation.DISCARD_AI -> update { it.copy(page = Page.AI_INPUT, aiDrafts = emptyList(), aiBatchId = null, aiEditIndex = null, confirmation = null, error = null) }
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
