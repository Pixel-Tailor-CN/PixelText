package vip.mystery0.pixel.text.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import org.koin.core.component.KoinComponent
import vip.mystery0.pixel.text.domain.settings.AppIdentityRepository

/** 覆盖升级后恢复桌面选择，不依赖用户再次打开主界面。 */
class AppIdentityUpgradeReceiver : BroadcastReceiver(), KoinComponent {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        try {
            // 与启动协调共用逻辑，保留已保存的选择并关闭多余默认入口。
            getKoin().get<AppIdentityRepository>().reconcile()
        } catch (error: Exception) {
            Log.e("AppIdentityUpgrade", "app identity upgrade reconcile failed", error)
        }
    }
}
