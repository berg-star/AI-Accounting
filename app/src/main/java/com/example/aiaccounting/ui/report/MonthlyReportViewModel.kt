package com.example.aiaccounting.ui.report

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.aiaccounting.data.repository.*
import com.example.aiaccounting.domain.ai.*
import com.example.aiaccounting.domain.model.MonthlyReport
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

class MonthlyReportViewModel(
    private val repository: LedgerRepository,
    private val savedState: SavedStateHandle,
    private val service: MonthlyReportGenerator,
    private val keys: ApiKeyStore,
    private val cache: MonthlyReportCache,
    private val today: () -> LocalDate = { LocalDate.now() },
) : ViewModel() {
    private val mutable = MutableStateFlow(MonthlyReportState(month = runCatching { YearMonth.parse(savedState.get<String>("reportMonth")) }.getOrDefault(YearMonth.from(today()))))
    val state = mutable.asStateFlow()
    private var snapshot: LedgerSnapshot? = null
    private var refreshJob: Job? = null
    private var generationJob: Job? = null
    private var revision = 0L
    private var generation = 0L
    private var lastRestoreRevision = 0

    init {
        viewModelScope.launch { repository.snapshots.collect { snapshot = it; refresh() } }
        viewModelScope.launch {
            var date = today()
            while (isActive) {
                delay(60_000)
                val current = today()
                if (current != date) { date = current; refresh() }
            }
        }
    }

    fun selectMonth(month: YearMonth) {
        if (state.value.month != month) {
            cancelGeneration()
            mutable.value = MonthlyReportState(month = month)
            savedState["reportMonth"] = month.toString()
        }
        refresh()
    }
    fun refreshDate() { refresh() }

    private fun refresh(forceCache: Boolean = false) {
        val data = snapshot ?: return
        val month = state.value.month
        val ticket = ++revision
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            val report = withContext(Dispatchers.Default) { MonthlyReport.build(month, data, today()) }
            if (ticket != revision) return@launch
            if (!forceCache && state.value.report?.fingerprint == report.fingerprint && !state.value.loadingCache) return@launch
            cancelGeneration()
            val previous = state.value
            mutable.value = MonthlyReportState(month, report, stale = previous.saved != null || previous.stale, loadingCache = true)
            try {
                val cached = withContext(Dispatchers.IO) { cache.read(month) }
                if (ticket != revision) return@launch
                val valid = cached?.takeIf { it.fingerprint == report.fingerprint }?.also { report.validateConclusions(it.conclusions) }
                mutable.update { it.copy(saved = valid, stale = cached != null && valid == null, loadingCache = false) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                if (ticket == revision) mutable.update { it.copy(loadingCache = false, stale = true, error = "已保存的报告无法读取，请重新生成；本地统计不受影响") }
            }
        }
    }

    fun generate() {
        val current = state.value
        val report = current.report ?: return
        if (current.generating || current.loadingCache || !report.available || report.rows.isEmpty()) return
        cancelGeneration()
        val ticket = generation
        mutable.update { it.copy(generating = true, error = null) }
        generationJob = viewModelScope.launch {
            try {
                if (withContext(Dispatchers.IO) { keys.read().isNullOrBlank() }) throw AiFailure("请先在 AI 设置中保存 API Key")
                val before = repository.snapshot()
                if (withContext(Dispatchers.Default) { MonthlyReport.build(report.month, before, today()).fingerprint } != report.fingerprint) {
                    snapshot = before; refresh()
                    return@launch
                }
                val claims = service.generate(report)
                currentCoroutineContext().ensureActive()
                report.validateConclusions(claims)
                val latest = repository.snapshot()
                val fresh = withContext(Dispatchers.Default) { MonthlyReport.build(report.month, latest, today()) }
                if (fresh.fingerprint != report.fingerprint) {
                    snapshot = latest; refresh()
                    return@launch
                }
                if (ticket != generation || state.value.report?.fingerprint != report.fingerprint) return@launch
                val cached = SavedMonthlyReport(report.month, report.fingerprint, Instant.now().toString(), claims)
                withContext(Dispatchers.IO) { cache.write(cached) }
                if (ticket == generation && state.value.report?.fingerprint == report.fingerprint) mutable.update {
                    it.copy(saved = cached, stale = false, generating = false, error = null)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (ticket == generation) mutable.update { it.copy(generating = false,
                    error = if (e is AiFailure) e.message else "月报生成或保存失败，请重试；本地统计及原账目保留") }
            }
        }
    }

    fun cancelGeneration() {
        ++generation
        generationJob?.cancel(); generationJob = null
        mutable.update { it.copy(generating = false) }
    }

    fun clearSavedReports(restoreRevision: Int? = null) {
        if (restoreRevision != null) {
            if (restoreRevision <= lastRestoreRevision) return
            lastRestoreRevision = restoreRevision
        }
        cancelGeneration(); refreshJob?.cancel(); ++revision
        mutable.update { it.copy(saved = null, stale = true, loadingCache = true) }
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { cache.clear() } }
            catch (_: Exception) { mutable.update { it.copy(error = "旧报告缓存无法清理，请重新生成") } }
            snapshot = repository.snapshot()
            refresh(forceCache = true)
        }
    }
}
