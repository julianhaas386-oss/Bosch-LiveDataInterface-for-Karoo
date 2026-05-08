package de.dxmedia.bosch.ldi.util

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

object LocaleHelper {
    private const val PREFS = "app_prefs"
    private const val KEY_LANG = "language"

    fun getStoredLanguage(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LANG, null)

    fun setLanguage(context: Context, languageCode: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LANG, languageCode)
            .apply()
    }

    fun applyLanguage(base: Context, languageCode: String?): Context {
        if (languageCode == null) return base
        val locale = Locale(languageCode)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }
}
