package com.firerpa.i18n

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.firerpa.data.AppPrefs

object LocaleManager {
    const val englishTag = "en"
    const val chineseTag = "zh-CN"

    fun applySavedLocale(context: Context) {
        val tag = AppPrefs(context).languageTag.ifBlank { AppPrefs.defaultLanguageTag }
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
    }

    fun toggleLanguage(context: Context): String {
        val prefs = AppPrefs(context)
        val currentTag = resolveCurrentLanguageTag(context)
        val nextTag = if (currentTag.startsWith("zh", ignoreCase = true)) englishTag else chineseTag
        prefs.languageTag = nextTag
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(nextTag))
        return nextTag
    }

    fun stringForAppLocale(context: Context, @StringRes resId: Int): String {
        val appLocales = AppCompatDelegate.getApplicationLocales()
        if (appLocales.isEmpty) {
            return context.getString(resId)
        }
        val overrideConfig = Configuration(context.resources.configuration)
        overrideConfig.setLocales(LocaleList.forLanguageTags(appLocales.toLanguageTags()))
        return context.createConfigurationContext(overrideConfig).getString(resId)
    }

    private fun resolveCurrentLanguageTag(context: Context): String {
        val appLocales = AppCompatDelegate.getApplicationLocales()
        if (!appLocales.isEmpty) {
            return appLocales.toLanguageTags()
        }
        val prefsTag = AppPrefs(context).languageTag
        if (prefsTag.isNotBlank()) {
            return prefsTag
        }
        val locale = context.resources.configuration.locales[0]
        return if (locale.language.equals("zh", ignoreCase = true)) chineseTag else englishTag
    }
}
