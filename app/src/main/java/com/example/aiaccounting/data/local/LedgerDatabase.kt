package com.example.aiaccounting.data.local

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.aiaccounting.domain.model.EntryKind

@Entity(tableName = "categories", indices = [Index(value = ["kind", "name"], unique = true)])
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: EntryKind,
    val name: String,
    val icon: String,
    val color: String,
    val sortOrder: Int,
    val active: Boolean = true,
    val fallback: Boolean = false,
)

@Entity(
    tableName = "entries",
    foreignKeys = [ForeignKey(entity = CategoryEntity::class, parentColumns = ["id"], childColumns = ["categoryId"], onDelete = ForeignKey.RESTRICT)],
    indices = [Index("categoryId"), Index("date")],
)
data class EntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: EntryKind,
    val cents: Long,
    val categoryId: Long,
    val date: String,
    val note: String = "",
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
)

@Entity(tableName = "ai_batches")
data class AiBatchEntity(@PrimaryKey val id: String, val createdAt: Long)

@Dao
interface LedgerDao {
    @Query("SELECT * FROM categories ORDER BY kind, sortOrder, id") suspend fun categories(): List<CategoryEntity>
    @Query("SELECT * FROM entries ORDER BY date DESC, id DESC") suspend fun entries(): List<EntryEntity>
    @Query("SELECT * FROM categories WHERE id = :id") suspend fun category(id: Long): CategoryEntity?
    @Query("SELECT * FROM entries WHERE id = :id") suspend fun entry(id: Long): EntryEntity?
    @Insert suspend fun insertCategory(category: CategoryEntity): Long
    @Insert suspend fun insertCategories(categories: List<CategoryEntity>)
    @Update suspend fun updateCategory(category: CategoryEntity)
    @Query("DELETE FROM categories WHERE id = :id") suspend fun deleteCategory(id: Long)
    @Query("SELECT COUNT(*) FROM entries WHERE categoryId = :id") suspend fun usage(id: Long): Int
    @Insert suspend fun insertEntry(entry: EntryEntity): Long
    @Update suspend fun updateEntry(entry: EntryEntity)
    @Query("SELECT COUNT(*) FROM ai_batches WHERE id = :id") suspend fun aiBatchCount(id: String): Int
    @Insert suspend fun insertAiBatch(batch: AiBatchEntity)
}

@Database(entities = [CategoryEntity::class, EntryEntity::class, AiBatchEntity::class], version = 2, exportSchema = true)
abstract class LedgerDatabase : RoomDatabase() {
    abstract fun ledgerDao(): LedgerDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `ai_batches` (`id` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
            }
        }
        fun open(context: Context): LedgerDatabase = Room.databaseBuilder(
            context.applicationContext, LedgerDatabase::class.java, "accounting.db",
        ).addMigrations(MIGRATION_1_2).build()
    }
}
