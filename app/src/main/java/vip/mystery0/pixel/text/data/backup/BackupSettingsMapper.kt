package vip.mystery0.pixel.text.data.backup

import android.content.Context
import android.graphics.BitmapFactory
import androidx.core.net.toUri
import com.squareup.moshi.Moshi
import vip.mystery0.pixel.text.data.repository.AppSettingsRepositoryImpl
import vip.mystery0.pixel.text.domain.settings.AppSettingsKeys
import vip.mystery0.pixel.text.domain.settings.ConversationSwipeAction
import vip.mystery0.pixel.text.domain.settings.MessageTimeDisplayFormat
import vip.mystery0.pixel.text.domain.settings.SpamAutoAction
import vip.mystery0.pixel.text.domain.settings.SmsNotificationIcon
import vip.mystery0.pixel.text.domain.settings.NotificationQuickActionConfig
import vip.mystery0.pixel.text.domain.settings.NotificationQuickActionType
import vip.mystery0.pixel.text.domain.settings.preferenceLabelKey
import vip.mystery0.pixel.text.domain.settings.validationError
import vip.mystery0.pixel.text.domain.theme.*
import vip.mystery0.pixel.text.smartspacer.UnreadSmsComplicationSettings
import vip.mystery0.pixel.text.smartspacer.UnreadSmsComplicationSettingsRepository
import java.io.File

class BackupSettingsMapper(
    private val context: Context,
    private val app: AppSettingsRepositoryImpl,
    private val theme: ThemeConfigurationRepository,
    private val assets: ThemeAssetRepository,
    private val smartspacer: UnreadSmsComplicationSettingsRepository,
) {
    private val adapter = Moshi.Builder().add(ThemeColorReferenceAdapter.FACTORY)
        .add(ThemeImageReferenceAdapter.FACTORY).build().adapter(BackupSettings::class.java)

    suspend fun export(directory: File) {
        val prefs = context.getSharedPreferences(AppSettingsKeys.PREFS_NAME, Context.MODE_PRIVATE)
        val entries = SPECS.map { spec ->
            val value = when (spec.type) {
                "boolean" -> prefs.getBoolean(spec.key, spec.value.toBooleanStrict()).toString()
                "int" -> prefs.getInt(spec.key, spec.value.toInt()).toString()
                "long" -> prefs.getLong(spec.key, spec.value.toLong()).toString()
                else -> prefs.getString(spec.key, spec.value) ?: spec.value
            }
            spec.copy(value = value)
        }
        val config = theme.configuration.value
        references(config).forEach { ref ->
            val source = assets.resolve(ref) ?: throw BackupException("主题背景文件缺失，请重新选择背景后备份")
            val target = File(directory, "theme/${ref.assetId}.webp")
            target.parentFile!!.mkdirs()
            backupRequire(source.length() <= 32L * 1024 * 1024)
            source.copyTo(target)
        }
        val filters = smartspacer.getSettings(null)
        val settings = BackupSettings(entries, config, filters.includeNormalMessages,
            filters.includeSpamMessages, filters.includeArchivedMessages)
        File(directory, "settings.json").writeText(adapter.toJson(settings))
    }

    fun validate(directory: File): BackupSettings {
        val file = File(directory, "settings.json")
        backupRequire(file.isFile && file.length() <= 4L * 1024 * 1024)
        val settings = adapter.fromJson(file.readText()) ?: throw BackupException("设置数据无效")
        backupRequire(settings.preferences.map { it.key }.toSet().size == settings.preferences.size)
        backupRequire(settings.preferences.map { it.key }.toSet() == SPECS.map { it.key }.toSet())
        settings.preferences.forEach { entry ->
            val spec = SPECS.singleOrNull { it.key == entry.key }
            backupRequire(spec != null && entry.type == spec.type)
            backupRequire(entry.value.length <= 1024)
            when (entry.type) {
                "boolean" -> backupRequire(entry.value == "true" || entry.value == "false")
                "int" -> backupRequire(entry.value.toIntOrNull() != null)
                "long" -> backupRequire(entry.value.toLongOrNull() != null)
            }
            NotificationQuickActionType.entries.firstOrNull { it.preferenceLabelKey() == entry.key }?.let { type ->
                backupRequire(NotificationQuickActionConfig(type, entry.value, 0).validationError() == null, "通知快捷操作文案无效")
            }
            when (entry.key) {
                "verification_code_retention_days" -> backupRequire(entry.value.toInt() in 1..365)
                "resource_auto_check_interval_hours" -> backupRequire(entry.value.toLong() >= 1)
                "spam_auto_action" -> backupRequire(SpamAutoAction.entries.any { it.storageValue == entry.value })
                "message_time_display_format" -> backupRequire(MessageTimeDisplayFormat.entries.any { it.storageValue == entry.value })
                "right_swipe_action", "left_swipe_action" -> backupRequire(ConversationSwipeAction.entries.any { it.storageValue == entry.value })
                "sms_notification_icon_id" -> backupRequire(SmsNotificationIcon.fromId(entry.value).storageId == entry.value)
            }
        }
        backupRequire(settings.theme.schemaVersion == CURRENT_THEME_SCHEMA_VERSION)
        backupRequire(settings.theme == settings.theme.normalized(), "主题配置超出允许范围")
        val expected = references(settings.theme).map { "${it.assetId}.webp" }.toSet()
        backupRequire(File(directory, "theme").listFiles().orEmpty().map { it.name }.toSet() == expected)
        for (reference in references(settings.theme)) {
            val image = File(directory, "theme/${reference.assetId}.webp")
            backupRequire(image.isFile && image.length() <= 32L * 1024 * 1024)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(image.path, bounds)
            backupRequire(bounds.outWidth in 1..8192 && bounds.outHeight in 1..8192, "主题图片无效或尺寸过大")
        }
        return settings
    }

    suspend fun restore(directory: File) {
        val settings = validate(directory)
        val created = mutableListOf<Pair<ThemeImageReference, ThemeImageReference>>()
        var themeCommitted = false
        var previousReferences = emptyList<ThemeImageReference>()
        try {
            for (ref in references(settings.theme)) {
                val draft = assets.createDraftBackground(ThemeMode.LIGHT, File(directory, "theme/${ref.assetId}.webp").toUri().toString()).getOrThrow()
                try { created += ref to assets.commitDraft(draft).getOrThrow() }
                finally { assets.discardDraft(draft) }
            }
            fun remap(ref: ThemeImageReference?): ThemeImageReference? = ref?.let { old -> created.single { it.first == old }.second }
            val module = settings.theme.conversationDetail
            val updated = settings.theme.copy(conversationDetail = module.copy(
                light = module.light.copy(backgroundImage = remap(module.light.backgroundImage)),
                dark = module.dark.copy(backgroundImage = remap(module.dark.backgroundImage)),
            ))
            theme.update { previous ->
                previousReferences = references(previous)
                updated
            }.getOrThrow()
            themeCommitted = true
            val activeReferences = references(theme.configuration.value).toSet()
            previousReferences.filter { it !in activeReferences }.forEach(assets::deleteAsset)
            backupRequire(app.restorePortablePreferences(settings.preferences), "应用设置保存失败，部分主题可能已恢复")
            backupRequire(smartspacer.restoreDefaultSettings(UnreadSmsComplicationSettings(
                settings.includeNormal, settings.includeSpam, settings.includeArchived)), "默认筛选设置保存失败")
        } finally {
            if (!themeCommitted) created.forEach { assets.deleteAsset(it.second) }
        }
    }

    private fun references(config: ThemeConfiguration): List<ThemeImageReference> = listOfNotNull(
        config.conversationDetail.light.backgroundImage, config.conversationDetail.dark.backgroundImage).distinct()

    companion object {
        // 显式列表：资源版本、任务进度、教学提示等不会随新设置自动进入备份。
        val SPECS: List<PortablePreference> = buildList {
            fun bool(key: String, value: Boolean) { add(PortablePreference(key, "boolean", value.toString())) }
            bool("auto_download_mms", false)
            bool("spam_detection_enabled", true)
            bool("mute_spam_notifications_enabled", false)
            bool("spam_isolation_enabled", false)
            bool("show_spam_content_by_default", false)
            bool("smart_card_enabled", true)
            bool("verification_code_notification_action_enabled", true)
            bool("hide_verification_code_on_lock_screen_enabled", true)
            bool("show_verification_code_content_by_default", true)
            bool("verification_code_auto_delete_enabled", false)
            bool("unread_badge_enabled", true)
            bool("resource_auto_check_enabled", false)
            add(PortablePreference("spam_auto_action", "string", "none"))
            add(PortablePreference("verification_code_retention_days", "int", "7"))
            add(PortablePreference("message_time_display_format", "string", "humanized"))
            add(PortablePreference("right_swipe_action", "string", "toggle_read"))
            add(PortablePreference("left_swipe_action", "string", "archive"))
            add(PortablePreference("sms_notification_icon_id", "string", AppSettingsKeys.DEFAULT_SMS_NOTIFICATION_ICON_ID))
            add(PortablePreference("resource_auto_check_interval_hours", "long", "24"))
            for ((index, pair) in listOf("mark_read" to "已阅", "copy_code" to "复制 {code}", "reply" to "回复").withIndex()) {
                add(PortablePreference("notification_action_${pair.first}_label", "string", pair.second))
                add(PortablePreference("notification_action_${pair.first}_order", "int", index.toString()))
            }
        }
    }
}
