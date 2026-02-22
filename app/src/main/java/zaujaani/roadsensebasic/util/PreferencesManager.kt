package zaujaani.roadsensebasic.util

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "roadsense_prefs")

@Singleton
class PreferencesManager @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    companion object {
        val THRESHOLD_BAIK        = floatPreferencesKey("threshold_baik")
        val THRESHOLD_SEDANG      = floatPreferencesKey("threshold_sedang")
        val THRESHOLD_RUSAK_RINGAN = floatPreferencesKey("threshold_rusak_ringan")
        val GPS_INTERVAL          = intPreferencesKey("gps_interval")
        val DISTANCE_UNIT         = stringPreferencesKey("distance_unit")
        val THEME                 = stringPreferencesKey("theme")

        // Language key — "en" = English (default), "id" = Bahasa Indonesia
        val LANGUAGE              = stringPreferencesKey("language")

        // Supported language tags (BCP 47)
        const val LANG_ENGLISH    = "en"
        const val LANG_INDONESIAN = "id"
    }

    // ── Defaults ──────────────────────────────────────────────────────────
    private val defaultThresholdBaik         = 0.3f
    private val defaultThresholdSedang       = 0.6f
    private val defaultThresholdRusakRingan  = 1.0f
    private val defaultGpsInterval           = 1000
    private val defaultDistanceUnit          = "km"
    private val defaultTheme                 = "system"
    private val defaultLanguage              = LANG_ENGLISH   // English is default

    // ── Flows ─────────────────────────────────────────────────────────────

    val thresholdBaik: Flow<Float> = context.dataStore.data
        .map { it[THRESHOLD_BAIK] ?: defaultThresholdBaik }

    val thresholdSedang: Flow<Float> = context.dataStore.data
        .map { it[THRESHOLD_SEDANG] ?: defaultThresholdSedang }

    val thresholdRusakRingan: Flow<Float> = context.dataStore.data
        .map { it[THRESHOLD_RUSAK_RINGAN] ?: defaultThresholdRusakRingan }

    val gpsInterval: Flow<Int> = context.dataStore.data
        .map { it[GPS_INTERVAL] ?: defaultGpsInterval }

    val distanceUnit: Flow<String> = context.dataStore.data
        .map { it[DISTANCE_UNIT] ?: defaultDistanceUnit }

    val theme: Flow<String> = context.dataStore.data
        .map { it[THEME] ?: defaultTheme }

    /** "en" or "id" */
    val language: Flow<String> = context.dataStore.data
        .map { it[LANGUAGE] ?: defaultLanguage }

    /**
     * One-shot synchronous read for language — needed at Application/Activity startup
     * before coroutines are set up.
     * Uses blocking runBlocking — safe to call only from onCreate before UI inflates.
     */
    fun getLanguageSync(): String {
        return try {
            var lang = defaultLanguage
            // Read from SharedPreferences as a fast sync fallback
            val prefs = context.getSharedPreferences("roadsense_lang_cache", Context.MODE_PRIVATE)
            prefs.getString("language", defaultLanguage) ?: defaultLanguage
        } catch (e: Exception) {
            defaultLanguage
        }
    }

    // ── Setters ───────────────────────────────────────────────────────────

    suspend fun setThresholdBaik(value: Float) {
        context.dataStore.edit { it[THRESHOLD_BAIK] = value }
    }

    suspend fun setThresholdSedang(value: Float) {
        context.dataStore.edit { it[THRESHOLD_SEDANG] = value }
    }

    suspend fun setThresholdRusakRingan(value: Float) {
        context.dataStore.edit { it[THRESHOLD_RUSAK_RINGAN] = value }
    }

    suspend fun setGpsInterval(value: Int) {
        context.dataStore.edit { it[GPS_INTERVAL] = value }
    }

    suspend fun setDistanceUnit(value: String) {
        context.dataStore.edit { it[DISTANCE_UNIT] = value }
    }

    suspend fun setTheme(value: String) {
        context.dataStore.edit { it[THEME] = value }
    }

    /**
     * Save language preference.
     * Also writes to a SharedPreferences cache for fast sync reads at startup.
     * @param langTag "en" or "id"
     */
    suspend fun setLanguage(langTag: String) {
        context.dataStore.edit { it[LANGUAGE] = langTag }
        // Cache in SharedPreferences for sync startup read
        context.getSharedPreferences("roadsense_lang_cache", Context.MODE_PRIVATE)
            .edit()
            .putString("language", langTag)
            .apply()
    }
}