package zaujaani.roadsensebasic.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import zaujaani.roadsensebasic.data.local.dao.*
import zaujaani.roadsensebasic.data.local.entity.*

@Database(
    entities = [
        TelemetryRaw::class,
        SurveySession::class,
        RoadEvent::class,
        SegmentSdi::class,
        DistressItem::class
    ],
    version = 7, // Naikkan dari 6 ke 7
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class RoadSenseDatabase : RoomDatabase() {

    abstract fun telemetryDao(): TelemetryDao
    abstract fun sessionDao(): SessionDao
    abstract fun eventDao(): EventDao
    abstract fun segmentSdiDao(): SegmentSdiDao
    abstract fun distressItemDao(): DistressItemDao

    companion object {

        @Volatile
        private var INSTANCE: RoadSenseDatabase? = null

        // =========================
        // MIGRATION 1 → 2
        // =========================
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE road_segments ADD COLUMN startLat REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE road_segments ADD COLUMN startLng REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE road_segments ADD COLUMN endLat REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE road_segments ADD COLUMN endLng REAL NOT NULL DEFAULT 0.0")
            }
        }

        // =========================
        // MIGRATION 2 → 3
        // =========================
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE survey_sessions ADD COLUMN surveyorName TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE survey_sessions ADD COLUMN roadName TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE survey_sessions ADD COLUMN startLat REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE survey_sessions ADD COLUMN startLng REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE survey_sessions ADD COLUMN endLat REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE survey_sessions ADD COLUMN endLng REAL NOT NULL DEFAULT 0.0")
            }
        }

        // =========================
        // MIGRATION 3 → 4
        // =========================
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE telemetry_raw ADD COLUMN vibrationX REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE telemetry_raw ADD COLUMN vibrationY REAL NOT NULL DEFAULT 0.0")
            }
        }

        // =========================
        // MIGRATION 4 → 5
        // =========================
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. Buat tabel road_events
                db.execSQL("""
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
                // 2. Hapus tabel lama
                db.execSQL("DROP TABLE IF EXISTS `road_segments`")
            }
        }

        // =========================
        // MIGRATION 5 → 6
        // =========================
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. Tambah kolom mode ke survey_sessions
                db.execSQL("ALTER TABLE survey_sessions ADD COLUMN mode TEXT NOT NULL DEFAULT 'GENERAL'")

                // 2. Buat tabel segment_sdi
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `segment_sdi` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `sessionId` INTEGER NOT NULL,
                        `segmentIndex` INTEGER NOT NULL,
                        `startSta` TEXT NOT NULL,
                        `endSta` TEXT NOT NULL,
                        `sdiScore` INTEGER NOT NULL DEFAULT 0,
                        `distressCount` INTEGER NOT NULL DEFAULT 0,
                        `createdAt` INTEGER NOT NULL
                    )
                """)

                // 3. Buat tabel distress_items
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `distress_items` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `segmentId` INTEGER NOT NULL,
                        `sessionId` INTEGER NOT NULL,
                        `type` TEXT NOT NULL,
                        `severity` TEXT NOT NULL,
                        `lengthOrArea` REAL NOT NULL,
                        `photoPath` TEXT NOT NULL,
                        `audioPath` TEXT NOT NULL,
                        `gpsLat` REAL NOT NULL,
                        `gpsLng` REAL NOT NULL,
                        `sta` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL
                    )
                """)

                // 4. Buat indeks untuk performa
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_segment_sdi_session ON segment_sdi(sessionId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_distress_segment ON distress_items(segmentId)")
            }
        }

        // =========================
        // MIGRATION 6 → 7 (tambah kolom avgSdi)
        // =========================
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE survey_sessions ADD COLUMN avgSdi INTEGER NOT NULL DEFAULT 0")
            }
        }

        // =========================
        // GET INSTANCE
        // =========================
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
                        MIGRATION_4_5,
                        MIGRATION_5_6,
                        MIGRATION_6_7   // tambahkan migrasi baru
                    )
                    .fallbackToDestructiveMigrationOnDowngrade()
                    .build()

                INSTANCE = instance
                instance
            }
        }
    }
}