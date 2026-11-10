package vip.mystery0.pixel.text.data.source

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import androidx.core.content.PermissionChecker

/** 系统短信访问先核对默认角色；已有镜像的本地查询不使用此门槛。 */
enum class SmsAccessState(val userMessage: String) {
    READY(""),
    DEFAULT_APP_REQUIRED("请先将本应用设为默认短信应用"),
    READ_PERMISSION_REQUIRED("请授予短信读取权限后重试"),
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

fun Context.requireSmsAccess() {
    val state = smsAccessState()
    if (state != SmsAccessState.READY) throw SmsAccessException(state)
}
