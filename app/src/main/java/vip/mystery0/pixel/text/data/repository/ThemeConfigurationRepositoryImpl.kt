package vip.mystery0.pixel.text.data.repository

import android.content.Context
import android.util.Log
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import vip.mystery0.pixel.text.domain.theme.CURRENT_THEME_SCHEMA_VERSION
import vip.mystery0.pixel.text.domain.theme.ConversationDetailThemeModule
import vip.mystery0.pixel.text.domain.theme.DEFAULT_CONVERSATION_DETAIL_TEXT_SCALE
import vip.mystery0.pixel.text.domain.theme.MAX_CONVERSATION_DETAIL_TEXT_SCALE
import vip.mystery0.pixel.text.domain.theme.MIN_CONVERSATION_DETAIL_TEXT_SCALE
import vip.mystery0.pixel.text.domain.theme.ThemeConfiguration
import vip.mystery0.pixel.text.domain.theme.ThemeConfigurationRepository
import vip.mystery0.pixel.text.domain.theme.normalized

class ThemeConfigurationRepositoryImpl(
    context: Context,
) : ThemeConfigurationRepository {
    private val prefs =
        context.getSharedPreferences(THEME_PREFS_NAME, Context.MODE_PRIVATE)
    private val legacyPrefs =
        context.getSharedPreferences(LEGACY_APP_PREFS_NAME, Context.MODE_PRIVATE)
    private val adapter = Moshi.Builder()
        .build()
        .adapter(ThemeConfiguration::class.java)
    private val mutex = Mutex()
    private val _configuration = MutableStateFlow(readOrMigrate())
    override val configuration: StateFlow<ThemeConfiguration> = _configuration.asStateFlow()

    override suspend fun save(configuration: ThemeConfiguration): Result<Unit> {
        return mutex.withLock {
            persist(configuration.normalized())
        }
    }

    override suspend fun update(
        transform: (ThemeConfiguration) -> ThemeConfiguration,
    ): Result<Unit> {
        return mutex.withLock {
            val next = transform(_configuration.value).normalized()
            persist(next)
        }
    }

    private suspend fun persist(configuration: ThemeConfiguration): Result<Unit> {
        return withContext(Dispatchers.IO) {
            runCatching {
                val json = adapter.toJson(configuration)
                val committed = prefs.edit()
                    .putString(KEY_CURRENT_THEME, json)
                    .commit()
                if (!committed) {
                    error("theme config commit failed")
                }
                _configuration.value = configuration
            }
        }
    }

    private fun readOrMigrate(): ThemeConfiguration {
        val existingJson = prefs.getString(KEY_CURRENT_THEME, null)
        if (existingJson != null) {
            return parseExisting(existingJson)
        }

        val legacyScale = readLegacyTextScale()
        val migrated = ThemeConfiguration(
            conversationDetail = ConversationDetailThemeModule(
                textScale = legacyScale,
            ),
        ).normalized()
        val json = adapter.toJson(migrated)
        val committed = prefs.edit()
            .putString(KEY_CURRENT_THEME, json)
            .commit()
        if (committed) {
            clearLegacyTextScale()
        }
        return migrated
    }

    private fun parseExisting(json: String): ThemeConfiguration {
        return try {
            val parsed = adapter.fromJson(json)
            if (parsed == null) {
                Log.w(TAG, "theme config parse failed error=null")
                ThemeConfiguration()
            } else if (parsed.schemaVersion != CURRENT_THEME_SCHEMA_VERSION) {
                Log.w(
                    TAG,
                    "theme config version unsupported version=${parsed.schemaVersion}",
                )
                ThemeConfiguration()
            } else {
                parsed.normalized()
            }
        } catch (error: Exception) {
            Log.w(
                TAG,
                "theme config parse failed error=${error::class.java.simpleName}",
            )
            ThemeConfiguration()
        }
    }

    private fun readLegacyTextScale(): Float {
        if (!legacyPrefs.contains(LEGACY_TEXT_SCALE_KEY)) {
            return DEFAULT_CONVERSATION_DETAIL_TEXT_SCALE
        }
        val scale = legacyPrefs.getFloat(
            LEGACY_TEXT_SCALE_KEY,
            DEFAULT_CONVERSATION_DETAIL_TEXT_SCALE,
        )
        return scale
            .takeIf { it.isFinite() }
            ?.coerceIn(
                MIN_CONVERSATION_DETAIL_TEXT_SCALE,
                MAX_CONVERSATION_DETAIL_TEXT_SCALE,
            )
            ?: DEFAULT_CONVERSATION_DETAIL_TEXT_SCALE
    }

    private fun clearLegacyTextScale() {
        legacyPrefs.edit()
            .remove(LEGACY_TEXT_SCALE_KEY)
            .commit()
    }

    private companion object {
        private const val TAG = "ThemeConfigRepo"
        private const val THEME_PREFS_NAME = "theme_configuration"
        private const val KEY_CURRENT_THEME = "current_theme"
        private const val LEGACY_APP_PREFS_NAME = "app_settings"
        private const val LEGACY_TEXT_SCALE_KEY = "conversation_detail_text_scale"
    }
}
