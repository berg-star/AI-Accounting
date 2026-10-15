package com.example.aiaccounting.domain.ai

import com.example.aiaccounting.domain.model.EntryDraft
import com.example.aiaccounting.domain.model.EntryKind
import java.time.LocalDate

data class AiCategory(val id: Long, val kind: EntryKind, val name: String, val fallback: Boolean)

fun interface TextAccounting {
    suspend fun parse(text: String, categories: List<AiCategory>, today: LocalDate): List<EntryDraft>
}

interface ApiKeyStore {
    fun read(): String?
    fun write(key: String)
    fun clear()
}

class AiFailure(message: String) : Exception(message)

object AiLimits {
    const val INPUT_LENGTH = 2000
    const val ENTRIES = 20
    const val MODEL = "Qwen/Qwen3.5-4B"
}
