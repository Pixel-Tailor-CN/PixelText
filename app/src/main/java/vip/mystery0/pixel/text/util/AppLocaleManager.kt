package vip.mystery0.pixel.text.util

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import androidx.core.content.edit
import java.util.Locale

enum class AppLanguage(val languageTag: String) {
    SYSTEM(""),
    ENGLISH("en"),
    SIMPLIFIED_CHINESE("zh-CN");

    companion object {
        fun fromLocales(locales: LocaleList?): AppLanguage {
            if (locales == null || locales.isEmpty) return SYSTEM
            val primary = locales[0] ?: return SYSTEM
            return when {
                primary.language.equals("zh", ignoreCase = true) -> SIMPLIFIED_CHINESE
                primary.language.equals("en", ignoreCase = true) -> ENGLISH
                else -> SYSTEM
            }
        }

        fun fromTag(tag: String?): AppLanguage = when {
            tag.isNullOrBlank() -> SYSTEM
            tag.startsWith("zh", ignoreCase = true) -> SIMPLIFIED_CHINESE
            tag.startsWith("en", ignoreCase = true) -> ENGLISH
            else -> SYSTEM
        }
    }
}

/**
 * 适配 Android 13+ 系统级 Per-App Language（LocaleManager）并兼容 Android 12 应用内切换。
 */
object AppLocaleManager {
    private const val PREFS_NAME = "app_locale_prefs"
    private const val KEY_LANGUAGE_TAG = "app_language_tag"

    @Volatile
    private var legacyChangeListener: ((Context) -> Unit)? = null

    fun setOnLegacyLanguageChangedListener(listener: ((Context) -> Unit)?) {
        legacyChangeListener = listener
    }

    fun currentLanguage(context: Context): AppLanguage {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val localeManager = context.getSystemService(LocaleManager::class.java)
            return AppLanguage.fromLocales(localeManager?.applicationLocales)
        }
        return readLegacyLanguage(context)
    }

    fun setLanguage(context: Context, language: AppLanguage) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val localeManager = context.getSystemService(LocaleManager::class.java) ?: return
            val targetLocales = when (language) {
                AppLanguage.SYSTEM -> LocaleList.getEmptyLocaleList()
                AppLanguage.ENGLISH -> LocaleList.forLanguageTags(AppLanguage.ENGLISH.languageTag)
                AppLanguage.SIMPLIFIED_CHINESE -> LocaleList.forLanguageTags(AppLanguage.SIMPLIFIED_CHINESE.languageTag)
            }
            if (localeManager.applicationLocales != targetLocales) {
                localeManager.applicationLocales = targetLocales
            }
            return
        }

        val current = readLegacyLanguage(context)
        if (current == language) return
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putString(KEY_LANGUAGE_TAG, language.languageTag)
        }
        syncLegacyDefaultLocale(language)
        val localized = localizedContext(context.applicationContext)
        AppStrings.init(localized)
        legacyChangeListener?.invoke(localized)
        context.findActivity()?.recreate()
    }

    fun wrapBaseContext(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return base
        }
        val language = readLegacyLanguage(base)
        syncLegacyDefaultLocale(language)
        return createLocaleContext(base, language)
    }

    fun localizedContext(context: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return context
        }
        val language = readLegacyLanguage(context)
        syncLegacyDefaultLocale(language)
        return createLocaleContext(context, language)
    }

    private fun readLegacyLanguage(context: Context): AppLanguage {
        val tag = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LANGUAGE_TAG, null)
        return AppLanguage.fromTag(tag)
    }

    private fun resolveTargetLocale(language: AppLanguage): Locale? = when (language) {
        AppLanguage.SYSTEM -> null
        AppLanguage.ENGLISH -> Locale.ENGLISH
        AppLanguage.SIMPLIFIED_CHINESE -> Locale.SIMPLIFIED_CHINESE
    }

    private fun syncLegacyDefaultLocale(language: AppLanguage) {
        val target = resolveTargetLocale(language)
            ?: Resources.getSystem().configuration.locales[0]
            ?: return
        if (Locale.getDefault() != target) {
            Locale.setDefault(target)
        }
    }

    private fun createLocaleContext(context: Context, language: AppLanguage): Context {
        val targetLocale = resolveTargetLocale(language) ?: return context
        val config = Configuration(context.resources.configuration).apply {
            setLocales(LocaleList(targetLocale))
        }
        return context.createConfigurationContext(config)
    }

    private tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}
