package zaujaani.roadsensebasic

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import dagger.hilt.android.HiltAndroidApp
import zaujaani.roadsensebasic.util.PreferencesManager
import javax.inject.Inject

@HiltAndroidApp
class RoadSenseApplication : Application() {

    // PreferencesManager injected after Hilt init
    @Inject lateinit var preferencesManager: PreferencesManager

    override fun onCreate() {
        super.onCreate()

        // Apply saved language at startup using the fast sync cache.
        // AppCompatDelegate.setApplicationLocales() is the modern API (AppCompat 1.6+)
        // that handles locale change without full process restart.
        applyLanguageFromCache()
    }

    /**
     * Read language from SharedPreferences cache (fast, no coroutine needed).
     * On first launch defaults to "en" (English).
     */
    private fun applyLanguageFromCache() {
        val prefs = getSharedPreferences("roadsense_lang_cache", MODE_PRIVATE)
        val langTag = prefs.getString("language", PreferencesManager.LANG_ENGLISH)
            ?: PreferencesManager.LANG_ENGLISH

        val localeList = LocaleListCompat.forLanguageTags(langTag)
        AppCompatDelegate.setApplicationLocales(localeList)
    }
}