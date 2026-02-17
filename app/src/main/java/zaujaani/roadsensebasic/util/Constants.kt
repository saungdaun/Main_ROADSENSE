package zaujaani.roadsensebasic.util

object Constants {
    // Bundle keys
    const val KEY_SESSION_ID = "session_id"
    const val KEY_SEGMENT_ID = "segment_id"
    const val KEY_START_DISTANCE = "start_distance"
    const val KEY_END_DISTANCE = "end_distance"
    const val KEY_AVG_VIBRATION = "avg_vibration"
    const val KEY_CONFIDENCE = "confidence"
    const val KEY_CONDITION_AUTO = "condition_auto"

    // Default thresholds (g)
    const val DEFAULT_THRESHOLD_BAIK = 0.3f
    const val DEFAULT_THRESHOLD_SEDANG = 0.6f
    const val DEFAULT_THRESHOLD_RUSAK_RINGAN = 1.0f

    // GPS default interval (ms)
    const val DEFAULT_GPS_INTERVAL = 1000

    // File paths
    const val PHOTO_DIR = "RoadSense/Photos"
    const val AUDIO_DIR = "RoadSense/Audio"
    const val REPORT_DIR = "RoadSense/Reports"

    // Condition types
    const val CONDITION_BAIK = "Baik"
    const val CONDITION_SEDANG = "Sedang"
    const val CONDITION_RUSAK_RINGAN = "Rusak Ringan"
    const val CONDITION_RUSAK_BERAT = "Rusak Berat"

    // Surface types
    val SURFACE_TYPES = listOf("Aspal", "Beton", "Tanah", "Kerikil", "Paving", "Lainnya")

    // Foreground Service
    const val NOTIFICATION_ID = 1001
    const val NOTIFICATION_CHANNEL_ID = "roadsense_channel"
    const val NOTIFICATION_CHANNEL_NAME = "RoadSense Survey"
    const val ACTION_START = "zaujaani.roadsensebasic.ACTION_START"
    const val ACTION_STOP = "zaujaani.roadsensebasic.ACTION_STOP"
    const val ACTION_PAUSE = "zaujaani.roadsensebasic.ACTION_PAUSE"


}