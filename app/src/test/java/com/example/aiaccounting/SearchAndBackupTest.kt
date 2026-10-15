package com.example.aiaccounting

import com.example.aiaccounting.data.backup.BackupCodec
import com.example.aiaccounting.data.local.*
import com.example.aiaccounting.data.repository.LedgerSnapshot
import com.example.aiaccounting.domain.model.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SearchAndBackupTest {
    private val categories = listOf(
        CategoryEntity(1, EntryKind.EXPENSE, "餐饮", "food", "blue", 0, active = false),
        CategoryEntity(2, EntryKind.EXPENSE, "其他支出", "other", "blue", 1, fallback = true),
        CategoryEntity(3, EntryKind.INCOME, "其他收入", "other", "green", 0, fallback = true),
    )
    private val rows = listOf(
        EntryEntity(1, EntryKind.EXPENSE, 2800, 1, "2026-09-30", "午餐：牛肉面", 1, 2),
        EntryEntity(2, EntryKind.EXPENSE, 600, 2, "2026-10-15", "地铁去公司", 2, 3),
        EntryEntity(3, EntryKind.INCOME, 30000, 3, "2026-10-15", "兼职：网站维护", 3, 4),
        EntryEntity(4, EntryKind.EXPENSE, 5000, 1, "2026-10-16", "已删除的牛肉面", 4, 5, 5),
    )

    @Test fun searchFindsDetailedNotesAcrossMonthsAndArchivedCategoriesButExcludesDeletedRows() {
        val data = LedgerSnapshot(categories, rows)
        assertEquals(listOf(1L), data.search(SearchFilters(keyword = "牛肉面")).map { it.id })
        assertEquals(listOf(1L), data.search(SearchFilters(keyword = "餐饮")).map { it.id })
        assertEquals(listOf(3L, 2L, 1L), data.search(SearchFilters()).map { it.id })
        assertEquals(listOf(2L), data.search(SearchFilters(kind = EntryKind.EXPENSE, fromDate = "2026-10-01", toDate = "2026-10-15", minAmount = "0", maxAmount = "6.00")).map { it.id })
        assertEquals(listOf(1L), data.search(SearchFilters(categoryId = 1, minAmount = "28", maxAmount = "28")).map { it.id })
    }

    @Test fun invalidFiltersShowErrorsRatherThanSilentlyApplyingPartialConditions() {
        listOf(SearchFilters(fromDate = "2026-02-30"), SearchFilters(fromDate = "2026-10-16", toDate = "2026-10-15"),
            SearchFilters(minAmount = "1.001"), SearchFilters(maxAmount = "-1"), SearchFilters(minAmount = "7", maxAmount = "6")).forEach {
            assertNotNull(it.error()); assertTrue(LedgerSnapshot(categories, rows).search(it).isEmpty())
        }
        assertNull(SearchFilters(minAmount = "0.00", maxAmount = "999999999.99").error())
    }

    @Test fun exportRoundTripKeepsIdsNotesArchivedAndDeletedRowsAndReadsV02Backups() {
        val raw = BackupCodec.encode(categories, rows, "0.4.0")
        val result = BackupCodec.read(("\uFEFF" + raw).byteInputStream())
        assertEquals(categories, result.categories); assertEquals(rows, result.entries)
        assertEquals(3, result.summary.records); assertEquals(1, result.summary.deletedRecords)
        val legacy = JSONObject(raw).put("databaseVersion", 1).put("appVersion", "0.2.0").toString()
        assertEquals(rows, BackupCodec.decode(legacy).entries)
        assertFalse(raw.contains("apiKey")); assertFalse(raw.contains("ciphertext"))
    }

    @Test fun inconsistentAndUnsupportedBackupsAreRejectedAsAWhole() {
        val raw = BackupCodec.encode(categories, rows, "0.4.0")
        val invalid = listOf(
            JSONObject(raw).put("currency", "USD"), JSONObject(raw).put("formatVersion", 2), JSONObject(raw).put("databaseVersion", 3),
            JSONObject(raw).apply { getJSONArray("entries").getJSONObject(0).put("categoryId", 3) },
            JSONObject(raw).apply { getJSONArray("entries").getJSONObject(0).put("cents", 0) },
            JSONObject(raw).apply { getJSONArray("entries").getJSONObject(0).put("cents", 1.5) },
            JSONObject(raw).apply { getJSONArray("entries").getJSONObject(0).put("date", "2026-02-30") },
            JSONObject(raw).apply { getJSONArray("entries").getJSONObject(0).put("note", "字".repeat(121)) },
            JSONObject(raw).apply { getJSONArray("entries").put(getJSONArray("entries").getJSONObject(0)) },
            JSONObject(raw).apply { getJSONArray("categories").getJSONObject(1).put("active", false) },
            JSONObject(raw).apply { getJSONArray("categories").getJSONObject(0).put("id", 2) },
        ).map { it.toString() } + listOf(raw + " trailing", "{}", "not JSON")
        invalid.forEach { text ->
            try { BackupCodec.decode(text); fail("Invalid backup accepted") } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun oversizedAndMalformedUtf8FilesAreRejectedBeforePreview() {
        try { BackupCodec.read(ByteArray(BackupCodec.MAX_BYTES + 1).inputStream()); fail("Oversize input accepted") } catch (_: IllegalArgumentException) { }
        try { BackupCodec.read(byteArrayOf(0xC3.toByte(), 0x28).inputStream()); fail("Invalid UTF-8 accepted") } catch (_: IllegalArgumentException) { }
    }
}
