package com.example.aiaccounting

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.aiaccounting.data.backup.*
import com.example.aiaccounting.data.local.*
import com.example.aiaccounting.data.repository.LedgerRepository
import com.example.aiaccounting.domain.model.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BackupRestoreTest {
    private lateinit var db: LedgerDatabase
    private lateinit var repo: LedgerRepository
    private lateinit var recoveryFile: File
    private lateinit var recovery: RecoveryBackupStore

    @Before fun setup() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).build()
        repo = LedgerRepository(db)
        repo.initialize()
        val food = repo.snapshot().categories.single { it.name == "餐饮" }
        repo.saveEntry(EntryDraft(amount = "28", categoryId = food.id, note = "原账本牛肉面"))
        recoveryFile = File(context.cacheDir, "recovery-test-${UUID.randomUUID()}.json")
        recovery = RecoveryBackupStore(recoveryFile)
    }

    @After fun cleanup() {
        db.close()
        listOf(recoveryFile, File(recoveryFile.path + ".bak"), File(recoveryFile.path + ".new")).forEach { it.delete() }
    }

    private fun incoming(note: String = "备份内的地铁") = LedgerBackup(
        listOf(
            CategoryEntity(101, EntryKind.EXPENSE, "其他支出", "coffee", "blue", 0, fallback = true),
            CategoryEntity(102, EntryKind.INCOME, "其他收入", "coffee", "blue", 0, fallback = true),
            CategoryEntity(103, EntryKind.EXPENSE, "旧交通", "coffee", "blue", 1, active = false),
        ),
        listOf(
            EntryEntity(201, EntryKind.EXPENSE, 600, 103, "2026-09-12", note, 10, 20),
            EntryEntity(202, EntryKind.INCOME, 10000, 102, "2026-09-13", "已删除的收入", 10, 30, 40),
        ), "2026-10-15T00:00:00Z", "0.3.0",
    )

    @Test fun restoringPreservesExactIdsArchivedAndDeletedRowsAndKeepsOriginalBackup() = runBlocking {
        val before = repo.snapshot()
        val incoming = incoming()
        repo.restoreBackup(incoming) { recovery.write(it) }
        val after = repo.snapshot()
        assertEquals(incoming.categories.toSet(), after.categories.toSet())
        assertEquals(incoming.entries.toSet(), after.entries.toSet())
        val preserved = BackupCodec.decode(recovery.read())
        assertEquals(before.categories, preserved.categories)
        assertEquals(before.entries, preserved.entries)
        // Explicit restored IDs must not cause subsequent automatic-ID collisions.
        val id = repo.saveEntry(EntryDraft(amount = "1", categoryId = 101, note = "恢复后新增"))
        assertTrue(id > 202)
    }

    @Test fun failedPreservationLeavesAllOriginalDataUntouched() = runBlocking {
        val before = repo.snapshot()
        try {
            repo.restoreBackup(incoming()) { throw IOException("Simulated unavailable backup storage") }
            fail("Restore must fail when the original cannot be preserved")
        } catch (_: IOException) { }
        assertEquals(before, repo.snapshot())
        assertFalse(recovery.exists())
    }

    @Test fun insertFailureAfterDeletesRollsBackEntireLedgerAndKeepsPreservedCopy() = runBlocking {
        val before = repo.snapshot()
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_test_restore BEFORE INSERT ON entries WHEN NEW.note = 'FAIL_RESTORE' BEGIN SELECT RAISE(ABORT, 'test failure'); END")
        try {
            repo.restoreBackup(incoming("FAIL_RESTORE")) { recovery.write(it) }
            fail("Insertion failure must roll back deletes and category inserts")
        } catch (_: android.database.sqlite.SQLiteException) { }
        assertEquals(before, repo.snapshot())
        assertEquals(before.entries, BackupCodec.decode(recovery.read()).entries)
    }

    @Test fun invalidConstructedBackupIsRejectedBeforeAnyWriteAndValidEmptyBackupWorks() = runBlocking {
        val before = repo.snapshot()
        var preserved = false
        try {
            repo.restoreBackup(incoming().let { it.copy(entries = it.entries.map { row -> row.copy(categoryId = 999) }) }) { preserved = true }
            fail("Invalid foreign keys must fail before preservation")
        } catch (_: IllegalArgumentException) { }
        assertFalse(preserved)
        assertEquals(before, repo.snapshot())
        val empty = incoming().copy(entries = emptyList())
        repo.restoreBackup(empty) { recovery.write(it) }
        assertTrue(repo.snapshot().entries.isEmpty())
        assertEquals(empty.categories.toSet(), repo.snapshot().categories.toSet())
        assertEquals(before.entries, BackupCodec.decode(recovery.read()).entries)
    }
}
