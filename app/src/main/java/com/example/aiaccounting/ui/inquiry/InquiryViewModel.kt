package com.example.aiaccounting.ui.inquiry

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.aiaccounting.data.repository.LedgerRepository
import com.example.aiaccounting.data.repository.LedgerSnapshot
import com.example.aiaccounting.domain.ai.*
import com.example.aiaccounting.domain.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class InquiryViewModel(
    private val repository: LedgerRepository,
    private val saved: SavedStateHandle,
    private val service: LedgerInquiry,
    private val keys: ApiKeyStore,
) : ViewModel() {
    private val mutable = MutableStateFlow(InquiryState(question = (saved.get<String>("question") ?: "").take(InquiryLimits.QUESTION_LENGTH)))
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var generation = 0L
    private var observed: LedgerSnapshot? = null

    init {
        viewModelScope.launch {
            repository.snapshots.collect { data ->
                if (observed != null && observed != data && state.value.result != null) {
                    cancelWork()
                    val current = state.value.result!!
                    val refreshed = runCatching { current.query.execute(data, current.excludedIds) }
                    mutable.update { it.copy(result = refreshed.getOrNull(), reviewStatus = ReviewStatus.OUTDATED, checked = 0,
                        completedBatches = 0, flags = emptyList(), warnings = emptyList(), explanation = null, explaining = false,
                        reviewError = "账本已变化，已重新计算，请重新复核", error = if (refreshed.isFailure) "查询分类已变化，请重新查账" else null) }
                }
                observed = data
            }
        }
    }

    fun changeQuestion(value: String) {
        if (value == state.value.question) return
        cancelWork()
        val text = value.take(InquiryLimits.QUESTION_LENGTH)
        saved["question"] = text
        mutable.value = InquiryState(question = text)
    }

    private suspend fun requireKey() {
        val available = withContext(Dispatchers.IO) { !keys.read().isNullOrBlank() }
        if (!available) throw AiFailure("请先在 AI 设置中保存 API Key")
    }

    fun ask() {
        val question = state.value.question.trim()
        if (question.isEmpty()) { mutable.update { it.copy(error = "请写出时间和查询目标，例如：这个月坐地铁花了多少？") }; return }
        cancelWork()
        val ticket = generation
        mutable.value = InquiryState(question = state.value.question, askedQuestion = question, understanding = true)
        job = viewModelScope.launch {
            try {
                requireKey()
                val data = repository.snapshot()
                val query = service.understand(question, data.categories.map { AiCategory(it.id, it.kind, it.name, it.fallback) }, java.time.LocalDate.now())
                val latest = repository.snapshot()
                val result = withContext(Dispatchers.Default) { query.execute(latest) }
                if (ticket != generation) return@launch
                observed = latest
                mutable.update { it.copy(understanding = false, result = result) }
                reviewAll(ticket, question, result)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (ticket == generation) mutable.update { it.copy(understanding = false, error = safeError(e, "AI 查账失败，问题已保留，请重试")) }
            }
        }
    }

    fun retryReview() {
        if (state.value.understanding || state.value.reviewStatus == ReviewStatus.RUNNING) return
        val result = state.value.result ?: return
        cancelWork()
        val ticket = generation
        val question = state.value.askedQuestion
        job = viewModelScope.launch { reviewAll(ticket, question, result) }
    }

    private suspend fun reviewAll(ticket: Long, question: String, result: QueryResult) {
        val batches = result.rows.chunked(InquiryLimits.BATCH_SIZE).ifEmpty { listOf(emptyList()) }
        mutable.update { it.copy(reviewStatus = ReviewStatus.RUNNING, checked = 0, completedBatches = 0, totalBatches = batches.size,
            flags = emptyList(), warnings = emptyList(), reviewError = null, explanation = null, explaining = false) }
        try {
            requireKey()
            for (batch in batches) {
                ensureActiveQuery(ticket)
                val review = service.review(question, result, batch)
                ensureActiveQuery(ticket)
                require(review.flags.all { flag -> batch.any { it.id == flag.entryId } } && review.flags.distinctBy { it.entryId }.size == review.flags.size)
                mutable.update { it.copy(checked = it.checked + batch.size, completedBatches = it.completedBatches + 1,
                    flags = it.flags + review.flags, warnings = (it.warnings + listOfNotNull(review.scopeWarning)).distinct()) }
            }
            if (result.query.metric == QueryMetric.COMPARE) {
                ensureActiveQuery(ticket)
                mutable.update { it.copy(explaining = true) }
                val explanation = service.explainComparison(question, result)
                ensureActiveQuery(ticket)
                mutable.update { it.copy(explanation = explanation, explaining = false) }
            }
            if (ticket == generation) mutable.update { it.copy(reviewStatus = ReviewStatus.COMPLETE) }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (ticket == generation) mutable.update { it.copy(reviewStatus = ReviewStatus.FAILED, explaining = false,
                reviewError = safeError(e, "AI 复核失败，本地结果仍可查看；未复核部分不视为通过")) }
        }
    }

    private suspend fun ensureActiveQuery(ticket: Long) {
        currentCoroutineContext().ensureActive()
        if (ticket != generation) throw CancellationException()
    }

    fun toggleExcluded(id: Long) {
        val result = state.value.result ?: return
        if (result.rows.none { it.id == id }) return
        cancelWork()
        val ids = if (id in result.excludedIds) result.excludedIds - id else result.excludedIds + id
        mutable.update { it.copy(result = result.copy(excludedIds = ids), reviewStatus = ReviewStatus.OUTDATED,
            checked = 0, completedBatches = 0, explanation = null, explaining = false, reviewError = "本次统计范围已调整，请重新复核") }
    }

    fun cancelWork() {
        ++generation
        job?.cancel(); job = null
        mutable.update { it.copy(understanding = false, explaining = false, reviewStatus = if (it.reviewStatus == ReviewStatus.RUNNING) ReviewStatus.CANCELLED else it.reviewStatus) }
    }

    fun clear() { cancelWork(); saved["question"] = ""; mutable.value = InquiryState() }

    private fun safeError(e: Exception, fallback: String) = when (e) {
        is AiFailure, is IllegalArgumentException -> e.message?.take(240) ?: fallback
        else -> fallback
    }
}
