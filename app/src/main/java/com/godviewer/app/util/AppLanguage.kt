package com.godviewer.app.util

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/** Host UI language: follow system / Chinese / English. */
object AppLanguage {
    const val SYSTEM = "system"
    const val ZH = "zh"
    const val EN = "en"

    fun current(context: Context): String {
        return runCatching {
            when (val saved = HostPrefs.getAppLanguage(context)) {
                ZH, EN, SYSTEM -> saved
                else -> SYSTEM
            }
        }.getOrDefault(SYSTEM)
    }

    fun labelRes(mode: String): Int = when (mode) {
        ZH -> com.godviewer.app.R.string.settings_language_zh
        EN -> com.godviewer.app.R.string.settings_language_en
        else -> com.godviewer.app.R.string.settings_language_system
    }

    fun set(context: Context, mode: String) {
        val normalized = when (mode) {
            ZH, EN -> mode
            else -> SYSTEM
        }
        HostPrefs.setAppLanguage(context, normalized)
    }

    fun wrap(context: Context): Context {
        return runCatching {
            val mode = current(context)
            if (mode == SYSTEM) return@runCatching context
            val locale = localeFor(mode) ?: return@runCatching context
            applyLocale(context, locale)
        }.getOrDefault(context)
    }

    private fun localeFor(mode: String): Locale? = when (mode) {
        ZH -> Locale.SIMPLIFIED_CHINESE
        EN -> Locale.ENGLISH
        else -> null
    }

    private fun applyLocale(context: Context, locale: Locale): Context {
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.setLocale(locale)
            config.setLocales(LocaleList(locale))
            context.createConfigurationContext(config)
        } else {
            @Suppress("DEPRECATION")
            config.locale = locale
            @Suppress("DEPRECATION")
            context.resources.updateConfiguration(config, context.resources.displayMetrics)
            context
        }
    }
}
