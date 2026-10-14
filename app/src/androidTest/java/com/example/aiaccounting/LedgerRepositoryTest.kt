package com.example.aiaccounting

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.aiaccounting.data.local.LedgerDatabase
import com.example.aiaccounting.data.repository.LedgerRepository
import com.example.aiaccounting.domain.model.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.time.YearMonth
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LedgerRepositoryTest {
    private lateinit var context: Context
    private lateinit var db: LedgerDatabase
    private lateinit var repo: LedgerRepository
    private lateinit var fileName: String
    private fun open() {
        db=Room.databaseBuilder(context,LedgerDatabase::class.java,fileName).build()
        repo=LedgerRepository(db)
    }
    @Before fun prepare() = runBlocking {
        context=ApplicationProvider.getApplicationContext();fileName="ledger-test-${UUID.randomUUID()}.db";open();repo.initialize()
    }
    @After fun cleanup() { db.close();context.deleteDatabase(fileName) }

    @Test fun categoryChangesPersistenceDeletionUndoAndBackupAreConsistent() = runBlocking {
        repo.initialize()
        assertEquals(13,repo.snapshot().categories.size)
        repo.saveCategory(CategoryDraft(name="咖啡",icon="coffee",color="orange"))
        var category=repo.snapshot().categories.single { it.name=="咖啡" }
        val id=repo.saveEntry(EntryDraft(amount="25.01",categoryId=category.id,date="2026-10-14",note="测试"))
        repo.saveCategory(CategoryDraft(category.id,category.kind,"咖啡饮品","coffee","green"))
        repo.setCategoryActive(category.id,false)
        assertEquals(2501,repo.snapshot().totals(YearMonth.of(2026,10)).expense)
        try { repo.deleteCategory(category.id);fail("Used category must remain") } catch (_:IllegalArgumentException) { }
        try { repo.saveEntry(EntryDraft(amount="1",categoryId=category.id));fail("Archived category cannot receive a new record") } catch (_:IllegalArgumentException) { }
        repo.saveEntry(EntryDraft(id,EntryKind.EXPENSE,"30.01",category.id,"2026-10-14","编辑旧记录"))
        db.close();open()
        category=repo.snapshot().categories.single { it.id==category.id }
        assertEquals("咖啡饮品",category.name);assertFalse(category.active)
        assertEquals(3001,repo.snapshot().entries.single().cents)
        repo.deleteEntry(id);assertEquals(0,repo.snapshot().totals(YearMonth.of(2026,10)).expense)
        repo.restoreEntry(id);assertEquals(3001,repo.snapshot().totals(YearMonth.of(2026,10)).expense)
        val backup=JSONObject(repo.backupJson())
        assertEquals(14,backup.getJSONArray("categories").length());assertEquals(1,backup.getJSONArray("entries").length())
        assertEquals(3001,backup.getJSONArray("entries").getJSONObject(0).getLong("cents"));assertFalse(backup.has("apiKey"))
        repo.setCategoryActive(category.id,true)
        val fallback=repo.snapshot().categories.first { it.fallback }
        try { repo.setCategoryActive(fallback.id,false);fail("Fallback must remain enabled") } catch (_:IllegalArgumentException) { }
        try { repo.deleteCategory(fallback.id);fail("Fallback must remain") } catch (_:IllegalArgumentException) { }
    }

    @Test fun emptyCategoryDeleteOrderingAndTypeValidation() = runBlocking {
        repo.saveCategory(CategoryDraft(name="临时分类"))
        val rows=repo.snapshot().categories.filter { it.kind==EntryKind.EXPENSE }
        val last=rows.last()
        repo.moveCategory(last.id,-1)
        assertEquals(last.id,repo.snapshot().categories.filter { it.kind==EntryKind.EXPENSE }[rows.lastIndex-1].id)
        repo.deleteCategory(last.id);assertEquals(13,repo.snapshot().categories.size)
        val expense=repo.snapshot().categories.first { it.kind==EntryKind.EXPENSE }
        try { repo.saveEntry(EntryDraft(kind=EntryKind.INCOME,amount="10",categoryId=expense.id));fail("Kind mismatch must fail") } catch (_:IllegalArgumentException) { }
        assertTrue(repo.snapshot().entries.isEmpty())
    }
}
