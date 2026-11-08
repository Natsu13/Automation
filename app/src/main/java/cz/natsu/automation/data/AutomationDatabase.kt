package cz.natsu.automation.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "automations")
data class AutomationEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    @ColumnInfo(name = "icon") val icon: String = AutomationIcons.DEFAULT,
    @ColumnInfo(name = "blocks") val blocksJson: String = "[]",
    @ColumnInfo(name = "trigger_process_text") val triggerProcessText: Boolean = false,
    @ColumnInfo(name = "trigger_share") val triggerShare: Boolean = false,
    @ColumnInfo(name = "trigger_tile") val triggerTile: Boolean = false,
    @ColumnInfo(name = "trigger_clipboard", defaultValue = "0") val triggerClipboard: Boolean = false,
    /** Launch conditions (blocks of type launch_condition); empty = always offered. */
    @ColumnInfo(name = "conditions", defaultValue = "[]") val conditionsJson: String = "[]",
    /** true = any condition is enough, false = all must hold. */
    @ColumnInfo(name = "conditions_any", defaultValue = "0") val conditionsAny: Boolean = false,
    /** System events that start the automation ([EventTrigger] list). */
    @ColumnInfo(name = "events", defaultValue = "[]") val eventsJson: String = "[]",
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis(),
) {
    val blocks: List<Block> get() = BlockJson.decode(blocksJson)
    val conditions: List<Block> get() = BlockJson.decode(conditionsJson)
    val events: List<EventTrigger> get() = EventJson.decode(eventsJson)
}

@Dao
interface AutomationDao {
    @Query("SELECT * FROM automations ORDER BY updated_at DESC")
    fun observeAll(): Flow<List<AutomationEntity>>

    @Query("SELECT * FROM automations ORDER BY updated_at DESC")
    suspend fun getAll(): List<AutomationEntity>

    @Query("SELECT * FROM automations WHERE id = :id")
    suspend fun getById(id: Long): AutomationEntity?

    @Insert
    suspend fun insert(entity: AutomationEntity): Long

    @Update
    suspend fun update(entity: AutomationEntity)

    @Query("DELETE FROM automations WHERE id = :id")
    suspend fun delete(id: Long)
}

@Database(entities = [AutomationEntity::class], version = 7, exportSchema = true)
abstract class AutomationDatabase : RoomDatabase() {
    abstract fun automationDao(): AutomationDao

    companion object {
        /** 6 -> 7: system event triggers. */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE automations ADD COLUMN events TEXT NOT NULL DEFAULT '[]'")
            }
        }

        /** 5 -> 6: launch conditions. */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE automations ADD COLUMN conditions TEXT NOT NULL DEFAULT '[]'")
                db.execSQL("ALTER TABLE automations ADD COLUMN conditions_any INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** 4 -> 5: "run when the clipboard changes" trigger. */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE automations ADD COLUMN trigger_clipboard INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun build(context: Context): AutomationDatabase =
            Room.databaseBuilder(context, AutomationDatabase::class.java, "database")
                .addMigrations(MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
                // Versions 1-3 only contained the old "demo" prototype project.
                .fallbackToDestructiveMigrationFrom(1, 2, 3)
                .build()
    }
}
