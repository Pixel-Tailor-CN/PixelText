package vip.mystery0.pixel.text.data.source

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import androidx.annotation.StringRes
import androidx.core.content.PermissionChecker
import androidx.core.content.edit
import vip.mystery0.pixel.text.R
import vip.mystery0.pixel.text.util.AppStrings

/** 系统短信访问先核对默认角色；已有镜像的本地查询不使用此门槛。 */
enum class SmsAccessState(@param:StringRes val userMessageRes: Int?) {
    READY(null),
    DEFAULT_APP_REQUIRED(R.string.domain_sms_access_default_app_required),
    READ_PERMISSION_REQUIRED(R.string.domain_sms_access_read_permission_required);

    val userMessage: String get() = userMessageRes?.let { AppStrings.get(it) }.orEmpty()
}

class SmsAccessException(val state: SmsAccessState) : SecurityException(state.userMessage)

fun Context.smsAccessState(): SmsAccessState {
    val roles = getSystemService(RoleManager::class.java)
    if (roles?.isRoleHeld(RoleManager.ROLE_SMS) != true) {
        return SmsAccessState.DEFAULT_APP_REQUIRED
    }
    if (PermissionChecker.checkSelfPermission(this, Manifest.permission.READ_SMS) != PermissionChecker.PERMISSION_GRANTED) {
        return SmsAccessState.READ_PERMISSION_REQUIRED
    }
    return SmsAccessState.READY
}

/**
 * 持久记录上次观察到的短信访问状态。进程重启不等于角色恢复，
 * 只有从不可用变为可用时才返回 true，供调用方补齐暂停期间的变化。
 */
fun Context.recordSmsAccessTransition(ready: Boolean): Boolean {
    val prefs = getSharedPreferences(SMS_ACCESS_PREFS, Context.MODE_PRIVATE)
    // 从未记录视为未授权：全新安装由初始化负责首次同步，升级后首次启动补一次对账。
    val previous = prefs.getBoolean(KEY_LAST_READY, false)
    if (previous != ready) prefs.edit { putBoolean(KEY_LAST_READY, ready) }
    return ready && !previous
}

private const val SMS_ACCESS_PREFS = "sms_access_state"
private const val KEY_LAST_READY = "last_ready"

fun Context.requireSmsAccess() {
    val state = smsAccessState()
    if (state != SmsAccessState.READY) throw SmsAccessException(state)
}
