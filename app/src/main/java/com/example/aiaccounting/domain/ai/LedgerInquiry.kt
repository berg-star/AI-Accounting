package com.example.aiaccounting.domain.ai

import com.example.aiaccounting.domain.model.*
import com.example.aiaccounting.data.local.EntryEntity
import java.time.LocalDate

interface LedgerInquiry {
    suspend fun understand(question: String, categories: List<AiCategory>, today: LocalDate): LedgerQuery
    suspend fun review(question: String, result: QueryResult, batch: List<EntryEntity>): QueryReview
    suspend fun explainComparison(question: String, result: QueryResult): ComparisonExplanation =
        throw AiFailure("当前服务暂不支持消费对比解读")
}

/** All categories and direction are validated against the locally computed comparison. */
data class ComparisonExplanation(val increased: List<Long>, val decreased: List<Long>, val unchanged: Boolean = false)

data class QueryFlag(val entryId: Long, val reason: String)
data class QueryReview(val scopeWarning: String? = null, val flags: List<QueryFlag> = emptyList())

enum class ReviewStatus { NOT_STARTED, RUNNING, COMPLETE, FAILED, CANCELLED, OUTDATED }

data class InquiryState(
    val question: String = "",
    val askedQuestion: String = "",
    val understanding: Boolean = false,
    val result: QueryResult? = null,
    val error: String? = null,
    val reviewStatus: ReviewStatus = ReviewStatus.NOT_STARTED,
    val checked: Int = 0,
    val completedBatches: Int = 0,
    val totalBatches: Int = 0,
    val warnings: List<String> = emptyList(),
    val flags: List<QueryFlag> = emptyList(),
    val reviewError: String? = null,
    val explaining: Boolean = false,
    val explanation: ComparisonExplanation? = null,
)

object InquiryLimits {
    const val QUESTION_LENGTH = 500
    const val BATCH_SIZE = 20
}
