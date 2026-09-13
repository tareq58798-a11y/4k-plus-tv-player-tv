package com.fourkplus.tvplayer

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/** Native names are intentionally hardcoded, not translated: a language's own name should stay
 *  readable to someone who cannot read the app's current language. */
internal enum class AppLanguage(val tag: String?, val nativeName: String) {
    SYSTEM(null, ""),
    ENGLISH("en", "English"),
    ARABIC("ar", "العربية"),
    FRENCH("fr", "Français"),
    SPANISH("es", "Español"),
    TURKISH("tr", "Türkçe"),
    RUSSIAN("ru", "Русский"),
    HINDI("hi", "हिन्दी"),
    URDU("ur", "اردو");

    companion object {
        fun fromTag(tag: String?): AppLanguage = entries.firstOrNull { it.tag == tag } ?: SYSTEM
    }
}

internal object LocaleHelper {
    private const val PREFS_NAME = "app_settings"
    private const val KEY_LANGUAGE = "language_tag"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getLanguage(context: Context): AppLanguage =
        AppLanguage.fromTag(prefs(context).getString(KEY_LANGUAGE, null))

    fun setLanguage(context: Context, language: AppLanguage) {
        prefs(context).edit().apply {
            if (language.tag == null) remove(KEY_LANGUAGE) else putString(KEY_LANGUAGE, language.tag)
        }.apply()
    }

    /** Wraps [context] with the saved language's locale, if one was chosen; otherwise returns it
     *  unchanged so the device's system locale keeps driving resource selection. */
    fun wrap(context: Context): Context {
        val tag = prefs(context).getString(KEY_LANGUAGE, null) ?: return context
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocale(locale)
        return context.createConfigurationContext(configuration)
    }
}
