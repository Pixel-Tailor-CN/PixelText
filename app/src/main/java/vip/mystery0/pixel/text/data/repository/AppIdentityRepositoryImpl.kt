package vip.mystery0.pixel.text.data.repository

import android.content.ComponentName
import android.content.Context
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import vip.mystery0.pixel.text.domain.settings.AppDisplayName
import vip.mystery0.pixel.text.domain.settings.AppIconStyle
import vip.mystery0.pixel.text.domain.settings.AppIdentity
import vip.mystery0.pixel.text.domain.settings.AppIdentityRepository

class AppIdentityRepositoryImpl(context: Context) : AppIdentityRepository {
    private val packageName = context.packageName
    private val packageManager = context.packageManager
    private val preferences = context.getSharedPreferences("app_identity", Context.MODE_PRIVATE)
    private val lock = Any()
    private val combinations = AppDisplayName.entries.flatMap { name ->
        AppIconStyle.entries.map { icon -> AppIdentity(name, icon) }
    }
    private val mutableIdentity = MutableStateFlow(readActiveIdentity() ?: AppIdentity())
    override val identity: StateFlow<AppIdentity> = mutableIdentity.asStateFlow()

    override suspend fun apply(identity: AppIdentity): Result<Unit> =
        // 一旦开始切换，离开设置页不能打断持久化或入口恢复。
        withContext(NonCancellable + Dispatchers.IO) {
            synchronized(lock) {
                val previous = mutableIdentity.value
                var persistenceStarted = false
                try {
                    selectAlias(identity)
                    persistenceStarted = true
                    check(persist(identity)) { "app identity persistence failed" }
                    mutableIdentity.value = identity
                    Result.success(Unit)
                } catch (error: Exception) {
                    // commit 失败也可能改变内存缓存，因此必须重新写回旧选择。
                    if (persistenceStarted) {
                        try {
                            check(persist(previous)) { "app identity persistence rollback failed" }
                        } catch (rollbackError: Exception) {
                            error.addSuppressed(rollbackError)
                        }
                    }
                    restoreAlias(previous, error)
                    Log.e(TAG, "app identity apply failed", error)
                    Result.failure(error)
                }
            }
        }

    override fun reconcile() {
        synchronized(lock) {
            val previous = mutableIdentity.value
            try {
                // 偏好与组件状态可能来自不同设备或旧版枚举；先处理新旧枚举映射，再以已保存的稳定标识为准。
                val resolved = resolveSavedIdentity()
                selectAlias(resolved.identity)
                if (resolved.legacyIconValue != null) {
                    check(persist(resolved.identity)) { "app identity migration persistence failed" }
                    Log.i(
                        TAG,
                        "app identity icon migrated from=${resolved.legacyIconValue} to=${resolved.identity.iconStyle.storageValue}",
                    )
                }
                mutableIdentity.value = resolved.identity
            } catch (error: Exception) {
                restoreAlias(previous, error)
                Log.e(TAG, "app identity reconcile failed", error)
            }
        }
    }

    private data class SavedIdentityResolution(
        val identity: AppIdentity,
        val legacyIconValue: String?,
    )

    private fun resolveSavedIdentity(): SavedIdentityResolution {
        val values = preferences.all
        // 首装和未保存名称、图标的旧版升级均沿用经典默认组合。
        val defaults = AppIdentity()
        val rawName = values[KEY_NAME] as? String
        val rawIcon = values[KEY_ICON] as? String
        val displayName = AppDisplayName.entries.firstOrNull {
            it.storageValue == rawName
        } ?: defaults.displayName
        val directIcon = AppIconStyle.entries.firstOrNull {
            it.storageValue == rawIcon
        }
        val migratedIcon = if (directIcon == null && rawIcon != null) {
            LEGACY_ICON_MIGRATION[rawIcon]
        } else {
            null
        }
        return SavedIdentityResolution(
            identity = AppIdentity(
                displayName = displayName,
                iconStyle = directIcon ?: migratedIcon ?: defaults.iconStyle,
            ),
            legacyIconValue = if (migratedIcon != null) rawIcon else null,
        )
    }

    // 入口切换失败时依赖 commit 结果回滚，不能丢弃持久化状态。
    @SuppressLint("UseKtx")
    private fun persist(identity: AppIdentity): Boolean = preferences.edit()
        .putString(KEY_NAME, identity.displayName.storageValue)
        .putString(KEY_ICON, identity.iconStyle.storageValue)
        .commit()

    private fun readActiveIdentity(): AppIdentity? = try {
        combinations.singleOrNull(::isEnabled)
    } catch (error: Exception) {
        Log.w(TAG, "app identity component read failed", error)
        null
    }

    private fun isEnabled(identity: AppIdentity): Boolean =
        when (packageManager.getComponentEnabledSetting(component(identity))) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> identity == AppIdentity()
            else -> false
        }

    private fun selectAlias(selected: AppIdentity) {
        val changes = combinations.filter { isEnabled(it) != (it == selected) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (changes.isNotEmpty()) {
                packageManager.setComponentEnabledSettings(changes.map {
                    PackageManager.ComponentEnabledSetting(
                        component(it),
                        if (it == selected) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                        else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP,
                    )
                })
            }
        } else {
            // Android 12 无批量接口：先保证新入口可用，再关闭其他入口。
            if (selected in changes) enableAlias(selected)
            changes.filter { it != selected }.forEach {
                packageManager.setComponentEnabledSetting(
                    component(it),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP,
                )
            }
        }
        check(combinations.singleOrNull(::isEnabled) == selected) {
            "app identity component verification failed"
        }
    }

    private fun restoreAlias(previous: AppIdentity, error: Exception) {
        try {
            selectAlias(previous)
        } catch (rollbackError: Exception) {
            error.addSuppressed(rollbackError)
            // 恢复失败时优先保留可启动入口，不再尝试关闭任何别名。
            try {
                enableAlias(previous)
            } catch (rescueError: Exception) {
                error.addSuppressed(rescueError)
            }
        }
    }

    private fun enableAlias(identity: AppIdentity) {
        packageManager.setComponentEnabledSetting(
            component(identity),
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP,
        )
    }

    // 别名使用稳定存储标识，不能改为枚举序号，也不能指向 MainActivity 本体。
    private fun component(identity: AppIdentity) = ComponentName(
        packageName,
        "vip.mystery0.pixel.text.launcher.Identity_${identity.displayName.storageValue}_${identity.iconStyle.storageValue}",
    )

    private companion object {
        const val TAG = "AppIdentityRepository"
        const val KEY_NAME = "display_name"
        const val KEY_ICON = "icon_style"
        val LEGACY_ICON_MIGRATION = mapOf(
            "blue" to AppIconStyle.ORIGIN,
            "green" to AppIconStyle.ZERO,
            "azure" to AppIconStyle.MESSAGES,
            "apricot" to AppIconStyle.VIOLET,
        )
    }
}
