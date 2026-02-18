package zaujaani.roadsensebasic.util

/**
 * Konstanta global aplikasi RoadSense.
 * Semua nilai magic/hardcoded harus didefinisikan di sini.
 */
object Constants {

    // ========== BUNDLE KEYS ==========
    const val KEY_SESSION_ID = "session_id"
    const val KEY_SEGMENT_ID = "segment_id"
    const val KEY_START_DISTANCE = "start_distance"
    const val KEY_END_DISTANCE = "end_distance"
    const val KEY_AVG_VIBRATION = "avg_vibration"
    const val KEY_CONFIDENCE = "confidence"
    const val KEY_CONDITION_AUTO = "condition_auto"
    const val KEY_SURVEYOR_NAME = "surveyor_name"
    const val KEY_ROAD_NAME = "road_name"

    // ========== DEFAULT THRESHOLDS (satuan g: percepatan gravitasi) ==========
    const val DEFAULT_THRESHOLD_BAIK = 0.3f
    const val DEFAULT_THRESHOLD_SEDANG = 0.6f
    const val DEFAULT_THRESHOLD_RUSAK_RINGAN = 1.0f

    // ========== GPS ==========
    const val DEFAULT_GPS_INTERVAL = 1000L       // ms
    const val DEFAULT_GPS_MIN_DISTANCE = 1.0f    // meter
    const val GPS_ACCURACY_THRESHOLD = 25f       // meter, GPS diabaikan jika > ini

    // ========== FILE PATHS ==========
    const val PHOTO_DIR = "RoadSense/Photos"
    const val AUDIO_DIR = "RoadSense/Audio"
    const val REPORT_DIR = "RoadSense/Reports"

    // ========== CONDITION TYPES ==========
    const val CONDITION_BAIK = "Baik"
    const val CONDITION_SEDANG = "Sedang"
    const val CONDITION_RUSAK_RINGAN = "Rusak Ringan"
    const val CONDITION_RUSAK_BERAT = "Rusak Berat"

    // ========== SURFACE TYPES ==========
    val SURFACE_TYPES = listOf("Aspal", "Beton", "Tanah", "Kerikil", "Paving", "Lainnya")

    // ========== TELEMETRY ==========
    const val TELEMETRY_BUFFER_SIZE = 10        // simpan ke DB setiap N point
    const val TELEMETRY_FLUSH_INTERVAL = 2000L  // atau setiap N ms
    const val VIBRATION_HISTORY_MAX = 150       // jumlah max point di chart

    // ========== FOREGROUND SERVICE ==========
    const val NOTIFICATION_ID = 1001
    const val NOTIFICATION_CHANNEL_ID = "roadsense_survey_channel"
    const val NOTIFICATION_CHANNEL_NAME = "RoadSense Survey"

    // Actions
    const val ACTION_START = "zaujaani.roadsensebasic.ACTION_START"
    const val ACTION_STOP = "zaujaani.roadsensebasic.ACTION_STOP"
    const val ACTION_PAUSE = "zaujaani.roadsensebasic.ACTION_PAUSE"
    const val ACTION_RESUME = "zaujaani.roadsensebasic.ACTION_RESUME"

    // ========== BLUETOOTH / ESP32 ==========
    const val ESP32_SPP_UUID = "00001101-0000-1000-8000-00805F9B34FB"
    const val ESP32_DEFAULT_NAME = "RoadSense_ESP32"

    // ========== SMART RECOGNITION (future) ==========
    // Placeholder untuk integrasi AI recognition kondisi permukaan dari kamera
    const val SMART_RECOGNITION_ENABLED = false
    const val SMART_RECOGNITION_MODEL_PATH = "models/surface_classifier.tflite"
}