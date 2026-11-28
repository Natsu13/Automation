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
    /**
     * Imported and not yet approved by the user. Such an automation never runs
     * (not by events, triggers, shortcuts or buttons) until it is approved in the editor:
     * a file from someone else could otherwise send secrets or notifications away.
     */
    @ColumnInfo(name = "needs_review", defaultValue = "0") val needsReview: Boolean = false,
    /**
     * Which quick settings tile runs the automation (when [triggerTile] is on):
     * 0 = the general tile shared by all, 1..5 = a tile of its own (see TileSlots).
     */
    @ColumnInfo(name = "tile_slot", defaultValue = "0") val tileSlot: Int = 0,
    /** Shown at the top of the home screen. */
    @ColumnInfo(name = "pinned", defaultValue = "0") val pinned: Boolean = false,
    /** Can be put on the home screen as a widget; runs whenever the widget is drawn (see HomeWidgets). */
    @ColumnInfo(name = "trigger_widget", defaultValue = "0") val triggerWidget: Boolean = false,
    /** Notifications about the size of the widget (for debugging a big widget). */
    @ColumnInfo(name = "widget_debug", defaultValue = "0") val widgetDebug: Boolean = false,
) {
    val blocks: List<Block> get() = BlockJson.decode(blocksJson)
    val conditions: List<Block> get() = BlockJson.decode(conditionsJson)
    val events: List<EventTrigger> get() = EventJson.decode(eventsJson)

    /** Events that may start the automation right now (none while it waits for review). */
    val activeEvents: List<EventTrigger> get() = if (needsReview) emptyList() else events
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

    /** Pinning does not count as a change (the order by last change stays). */
    @Query("UPDATE automations SET pinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: Long, pinned: Boolean)
}

@Database(entities = [AutomationEntity::class], version = 11, exportSchema = true)
abstract class AutomationDatabase : RoomDatabase() {
    abstract fun automationDao(): AutomationDao

    companion object {
        /** 10 -> 11: widgets on the home screen. */
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE automations ADD COLUMN trigger_widget INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE automations ADD COLUMN widget_debug INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** 9 -> 10: pinned automations. */
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE automations ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** 8 -> 9: an automation can have a quick settings tile of its own. */
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE automations ADD COLUMN tile_slot INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** 7 -> 8: imported automations wait for the user's review. */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE automations ADD COLUMN needs_review INTEGER NOT NULL DEFAULT 0")
            }
        }

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
                .addMigrations(MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11)
                // Versions 1-3 only contained the old "demo" prototype project.
                .fallbackToDestructiveMigrationFrom(1, 2, 3)
                .build()
    }
}
