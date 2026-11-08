package vip.mystery0.pixel.text.ui

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent

/**
 * 判断当前应用是否为默认短信应用。
 */
fun Context.isDefaultSmsApp(): Boolean {
    return getSystemService(RoleManager::class.java)
        .isRoleHeld(RoleManager.ROLE_SMS)
}

/**
 * 创建引导用户授予默认短信应用角色的系统 Intent。
 */
fun Context.createDefaultSmsAppRequestIntent(): Intent {
    return getSystemService(RoleManager::class.java)
        .createRequestRoleIntent(RoleManager.ROLE_SMS)
}
