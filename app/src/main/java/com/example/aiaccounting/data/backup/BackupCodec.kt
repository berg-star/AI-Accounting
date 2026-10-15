package com.example.aiaccounting.data.backup

import com.example.aiaccounting.data.local.CategoryEntity
import com.example.aiaccounting.data.local.EntryEntity
import com.example.aiaccounting.domain.model.*
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.Instant

data class LedgerBackup(val categories: List<CategoryEntity>, val entries: List<EntryEntity>, val exportedAt: String, val appVersion: String) {
    val summary get() = BackupSummary(categories.size, entries.count { it.deletedAt == null }, entries.count { it.deletedAt != null }, exportedAt)
}
data class BackupSummary(val categories: Int, val records: Int, val deletedRecords: Int, val exportedAt: String)

object BackupCodec {
    const val MAX_BYTES = 16 * 1024 * 1024
    const val MAX_ENTRIES = 50_000
    const val MAX_CATEGORIES = 2000

    fun read(stream: InputStream): LedgerBackup {
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            require(bytes.size() + count <= MAX_BYTES) { "备份文件不能超过 16 MB" }
            bytes.write(buffer, 0, count)
        }
        val text = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes.toByteArray())).toString().removePrefix("\uFEFF")
        } catch (_: Exception) { throw IllegalArgumentException("备份必须是 UTF-8 编码的 JSON 文件") }
        return decode(text)
    }

    fun decode(text: String): LedgerBackup {
        try {
            require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "备份文件不能超过 16 MB" }
            val reader = JSONTokener(text)
            val root = reader.nextValue() as? JSONObject ?: error("Expected object")
            require(reader.nextClean() == '\u0000')
            require(root.get("format") == "ai-accounting") { "这不是本 App 导出的备份" }
            require(root.get("currency") == "CNY") { "备份货币必须是人民币 CNY" }
            require(root.integer("formatVersion") == 1L && root.integer("databaseVersion") in 1L..2L) { "备份版本暂不支持，请使用兼容版本的 App" }
            val exportedAt = root.string("exportedAt")
            Instant.parse(exportedAt)
            val categoryRows = root.getJSONArray("categories")
            val entryRows = root.getJSONArray("entries")
            require(categoryRows.length() in 2..MAX_CATEGORIES && entryRows.length() <= MAX_ENTRIES) { "备份最多支持 2000 个分类、50000 笔记录" }
            val categories = categoryRows.objects().map { row ->
                val name = row.string("name")
                val order = row.integer("sortOrder")
                require(name.isNotBlank() && name == name.trim() && name.length <= 12 && order in 0..Int.MAX_VALUE.toLong())
                val icon = row.string("icon"); val color = row.string("color")
                require(icon in categoryIcons && color in categoryColors)
                CategoryEntity(row.positive("id"), EntryKind.valueOf(row.string("kind")), name, icon, color, order.toInt(), row.boolean("active"), row.boolean("fallback"))
            }
            require(categories.map { it.id }.distinct().size == categories.size)
            require(categories.map { it.kind to it.name }.distinct().size == categories.size)
            EntryKind.entries.forEach { kind -> require(categories.count { it.kind == kind && it.fallback && it.active } == 1 && categories.count { it.kind == kind && it.fallback } == 1) }
            val categoryMap = categories.associateBy { it.id }
            val entries = entryRows.objects().map { row ->
                val kind = EntryKind.valueOf(row.string("kind"))
                val cents = row.positive("cents"); require(cents <= Money.MAX_CENTS)
                val categoryId = row.positive("categoryId"); require(categoryMap[categoryId]?.kind == kind)
                val date = row.string("date"); require(canonicalDate(date))
                val note = row.string("note"); require(note.length <= 120)
                val created = row.integer("createdAt"); val updated = row.integer("updatedAt")
                val deleted = if (row.isNull("deletedAt") && row.has("deletedAt")) null else row.integer("deletedAt")
                EntryEntity(row.positive("id"), kind, cents, categoryId, date, note, created, updated, deleted)
            }
            require(entries.map { it.id }.distinct().size == entries.size)
            return LedgerBackup(categories, entries, exportedAt, root.string("appVersion"))
        } catch (e: IllegalArgumentException) {
            if (e.message?.startsWith("备份") == true || e.message == "这不是本 App 导出的备份") throw e
            throw IllegalArgumentException("备份内容不完整或数据无效，没有修改现有账本")
        } catch (_: Exception) { throw IllegalArgumentException("备份内容不完整或数据无效，没有修改现有账本") }
    }

    fun encode(categories: List<CategoryEntity>, entries: List<EntryEntity>, version: String): String =
        JSONObject().put("format", "ai-accounting").put("formatVersion", 1).put("databaseVersion", 2).put("appVersion", version)
            .put("currency", "CNY").put("exportedAt", Instant.now().toString())
            .put("categories", JSONArray().apply { categories.forEach { c -> put(JSONObject().put("id", c.id).put("kind", c.kind.name).put("name", c.name).put("icon", c.icon).put("color", c.color).put("sortOrder", c.sortOrder).put("active", c.active).put("fallback", c.fallback)) } })
            .put("entries", JSONArray().apply { entries.forEach { e -> put(JSONObject().put("id", e.id).put("kind", e.kind.name).put("cents", e.cents).put("categoryId", e.categoryId).put("date", e.date).put("note", e.note).put("createdAt", e.createdAt).put("updatedAt", e.updatedAt).put("deletedAt", e.deletedAt ?: JSONObject.NULL)) } }).toString(2)

    private fun JSONObject.integer(key: String): Long {
        val value = get(key)
        require(value is Number && Regex("[0-9]+").matches(value.toString()))
        return value.toString().toLong()
    }
    private fun JSONObject.positive(key: String) = integer(key).also { require(it > 0 && it < Long.MAX_VALUE) }
    private fun JSONObject.string(key: String) = (get(key) as? String) ?: error("Expected string")
    private fun JSONObject.boolean(key: String) = (get(key) as? Boolean) ?: error("Expected boolean")
    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
}
