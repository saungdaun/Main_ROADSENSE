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
        // Keys untuk threshold getaran
        val THRESHOLD_BAIK = floatPreferencesKey("threshold_baik")
        val THRESHOLD_SEDANG = floatPreferencesKey("threshold_sedang")
        val THRESHOLD_RUSAK_RINGAN = floatPreferencesKey("threshold_rusak_ringan")
        // Interval GPS (ms)
        val GPS_INTERVAL = intPreferencesKey("gps_interval")
        // Unit jarak (km/mi)
        val DISTANCE_UNIT = stringPreferencesKey("distance_unit")
        // Tema (light/dark)
        val THEME = stringPreferencesKey("theme")
    }

    // Default values
    private val defaultThresholdBaik = 0.3f
    private val defaultThresholdSedang = 0.6f
    private val defaultThresholdRusakRingan = 1.0f
    private val defaultGpsInterval = 1000
    private val defaultDistanceUnit = "km"
    private val defaultTheme = "system"

    // Flow untuk threshold
    val thresholdBaik: Flow<Float> = context.dataStore.data
        .map { preferences -> preferences[THRESHOLD_BAIK] ?: defaultThresholdBaik }

    val thresholdSedang: Flow<Float> = context.dataStore.data
        .map { preferences -> preferences[THRESHOLD_SEDANG] ?: defaultThresholdSedang }

    val thresholdRusakRingan: Flow<Float> = context.dataStore.data
        .map { preferences -> preferences[THRESHOLD_RUSAK_RINGAN] ?: defaultThresholdRusakRingan }

    val gpsInterval: Flow<Int> = context.dataStore.data
        .map { preferences -> preferences[GPS_INTERVAL] ?: defaultGpsInterval }

    val distanceUnit: Flow<String> = context.dataStore.data
        .map { preferences -> preferences[DISTANCE_UNIT] ?: defaultDistanceUnit }

    val theme: Flow<String> = context.dataStore.data
        .map { preferences -> preferences[THEME] ?: defaultTheme }

    // Fungsi untuk menyimpan
    suspend fun setThresholdBaik(value: Float) {
        context.dataStore.edit { preferences ->
            preferences[THRESHOLD_BAIK] = value
        }
    }

    suspend fun setThresholdSedang(value: Float) {
        context.dataStore.edit { preferences ->
            preferences[THRESHOLD_SEDANG] = value
        }
    }

    suspend fun setThresholdRusakRingan(value: Float) {
        context.dataStore.edit { preferences ->
            preferences[THRESHOLD_RUSAK_RINGAN] = value
        }
    }

    suspend fun setGpsInterval(value: Int) {
        context.dataStore.edit { preferences ->
            preferences[GPS_INTERVAL] = value
        }
    }

    suspend fun setDistanceUnit(value: String) {
        context.dataStore.edit { preferences ->
            preferences[DISTANCE_UNIT] = value
        }
    }

    suspend fun setTheme(value: String) {
        context.dataStore.edit { preferences ->
            preferences[THEME] = value
        }
    }
}