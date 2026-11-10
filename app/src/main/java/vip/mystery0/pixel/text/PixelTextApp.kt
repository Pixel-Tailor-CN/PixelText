package vip.mystery0.pixel.text

import android.app.Application
import android.app.Activity
import android.os.Bundle
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.provider.Telephony
import androidx.core.content.ContextCompat
import vip.mystery0.pixel.text.data.source.SmsAccessState
import vip.mystery0.pixel.text.data.source.smsAccessState
import vip.mystery0.pixel.text.data.source.recordSmsAccessTransition
import android.util.Log
import vip.mystery0.pixel.text.domain.settings.AppIdentityRepository
import vip.mystery0.pixel.text.data.repository.mirror.MirrorChangeObserver
import vip.mystery0.pixel.text.worker.MessageMirrorScheduler
import org.koin.android.ext.android.getKoin
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import vip.mystery0.pixel.text.di.appModule
import vip.mystery0.pixel.text.notification.ResourceUpdateNotificationHelper
import vip.mystery0.pixel.text.notification.SmsNotificationHelper
import vip.mystery0.pixel.text.notification.SpamScanNotificationHelper
import vip.mystery0.pixel.text.worker.ResourceUpdateScheduler
import vip.mystery0.pixel.text.worker.VerificationCodeCleanupScheduler

class PixelTextApp : Application() {
    private val smsRoleReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Telephony.Sms.Intents.ACTION_DEFAULT_SMS_PACKAGE_CHANGED) startMirror()
        }
    }

    override fun onCreate() {
        super.onCreate()
        SmsNotificationHelper.createNotificationChannel(this)
        SpamScanNotificationHelper.createNotificationChannel(this)
        ResourceUpdateNotificationHelper.createNotificationChannel(this)
        startKoin {
            androidLogger()
            androidContext(this@PixelTextApp)
            modules(appModule)
        }
        try {
            getKoin().get<AppIdentityRepository>().reconcile()
        } catch (error: Exception) {
            // 包括仓库初始化失败，身份恢复不能阻止短信应用启动。
            Log.e("PixelTextApp", "app identity startup failed", error)
        }
        getKoin().get<ResourceUpdateScheduler>().syncOnAppStart()

        getKoin().get<VerificationCodeCleanupScheduler>().sync()
        getKoin().get<MessageMirrorScheduler>().cancelLegacyPeriodic()
        ContextCompat.registerReceiver(
            this, smsRoleReceiver,
            IntentFilter(Telephony.Sms.Intents.ACTION_DEFAULT_SMS_PACKAGE_CHANGED),
            ContextCompat.RECEIVER_EXPORTED,
        )
        startMirror()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) = startMirror()
            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    private fun startMirror() {
        // 不依赖镜像 dirty 队列；角色暂时丢失也要持久处理迟到发送回调。
        getKoin().get<vip.mystery0.pixel.text.sms.SmsSendCoordinator>().scheduleRecovery()
        getKoin().get<vip.mystery0.pixel.text.mms.outgoing.MmsSendCoordinator>().scheduleRecovery()
        getKoin().get<vip.mystery0.pixel.text.data.source.ContactDataSource>().warmUp()
        val observer = getKoin().get<MirrorChangeObserver>()
        val accessReady = smsAccessState() == SmsAccessState.READY
        val accessRestored = recordSmsAccessTransition(accessReady)
        if (!accessReady) {
            observer.stop()
            return
        }
        getKoin().get<vip.mystery0.pixel.text.worker.DataInitializationScheduler>().checkOnLaunch()
        observer.start()
        if (accessRestored) {
            // 只在访问权限真正恢复时补齐暂停期间的消息与删除；普通冷启动只做增量同步。
            getKoin().get<MessageMirrorScheduler>().schedule(forceReconcile = true, reason = "sms_access_restored")
        }
    }
}
