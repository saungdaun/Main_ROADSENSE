package zaujaani.roadsensebasic.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import zaujaani.roadsensebasic.data.local.dao.EventDao
import zaujaani.roadsensebasic.data.local.dao.SessionDao
import zaujaani.roadsensebasic.data.local.dao.TelemetryDao
import zaujaani.roadsensebasic.data.local.entity.RoadEvent
import zaujaani.roadsensebasic.data.local.entity.SurveySession
import zaujaani.roadsensebasic.data.local.entity.TelemetryRaw

@Database(
    entities = [
        TelemetryRaw::class,
        SurveySession::class,
        RoadEvent::class      // ganti RoadSegment dengan RoadEvent
    ],
    version = 5, // naikkan dari 4 ke 5
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class RoadSenseDatabase : RoomDatabase() {
    abstract fun telemetryDao(): TelemetryDao
    abstract fun sessionDao(): SessionDao
    abstract fun eventDao(): EventDao   // tambahkan DAO untuk event
    // abstract fun segmentDao(): SegmentDao  // HAPUS

    companion object {
        @Volatile
        private var INSTANCE: RoadSenseDatabase? = null

        // Migrasi sebelumnya (1→2, 2→3, 3→4) tetap ada
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE road_segments ADD COLUMN startLat REAL NOT NULL DEFAULT 0.0")
                database.execSQL("ALTER TABLE road_segments ADD COLUMN startLng REAL NOT NULL DEFAULT 0.0")
                database.execSQL("ALTER TABLE road_segments ADD COLUMN endLat REAL NOT NULL DEFAULT 0.0")
                database.execSQL("ALTER TABLE road_segments ADD COLUMN endLng REAL NOT NULL DEFAULT 0.0")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE survey_sessions ADD COLUMN surveyorName TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE survey_sessions ADD COLUMN roadName TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE survey_sessions ADD COLUMN startLat REAL NOT NULL DEFAULT 0.0")
                database.execSQL("ALTER TABLE survey_sessions ADD COLUMN startLng REAL NOT NULL DEFAULT 0.0")
                database.execSQL("ALTER TABLE survey_sessions ADD COLUMN endLat REAL NOT NULL DEFAULT 0.0")
                database.execSQL("ALTER TABLE survey_sessions ADD COLUMN endLng REAL NOT NULL DEFAULT 0.0")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE telemetry_raw ADD COLUMN vibrationX REAL NOT NULL DEFAULT 0.0")
                database.execSQL("ALTER TABLE telemetry_raw ADD COLUMN vibrationY REAL NOT NULL DEFAULT 0.0")
            }
        }

        // ** BARU: Migrasi 4 → 5 **
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // 1. Buat tabel road_events
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS `road_events` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `sessionId` INTEGER NOT NULL,
                        `timestamp` INTEGER NOT NULL,
                        `latitude` REAL NOT NULL,
                        `longitude` REAL NOT NULL,
                        `distance` REAL NOT NULL,
                        `eventType` TEXT NOT NULL,
                        `value` TEXT NOT NULL,
                        `notes` TEXT
                    )
                """)

                // 2. Hapus tabel road_segments (data lama tidak dipakai)
                database.execSQL("DROP TABLE IF EXISTS `road_segments`")

                // 3. Pastikan kolom roadName sudah ada di survey_sessions (opsional, sudah ada dari migrasi 2_3)
                // Tidak perlu tambah karena sudah ada.
            }
        }

        fun getInstance(context: Context): RoadSenseDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    RoadSenseDatabase::class.java,
                    "roadsense_database"
                )
                    .addMigrations(
                        MIGRATION_1_2,
                        MIGRATION_2_3,
                        MIGRATION_3_4,
                        MIGRATION_4_5   // tambahkan migrasi baru
                    )
                    .fallbackToDestructiveMigrationOnDowngrade()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}