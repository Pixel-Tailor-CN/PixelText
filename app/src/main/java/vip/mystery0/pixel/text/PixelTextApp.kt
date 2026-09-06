package vip.mystery0.pixel.text

import android.app.Application
import android.app.Activity
import android.os.Bundle
import android.Manifest
import android.content.pm.PackageManager
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
import vip.mystery0.pixel.text.worker.VerificationCodeIndexScheduler
import vip.mystery0.pixel.text.worker.VerificationCodeCleanupScheduler

class PixelTextApp : Application() {
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
        getKoin().get<ResourceUpdateScheduler>().syncOnAppStart()
        getKoin().get<VerificationCodeIndexScheduler>().scheduleReconcile()
        getKoin().get<VerificationCodeCleanupScheduler>().sync()
        getKoin().get<MessageMirrorScheduler>().ensurePeriodic()
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
        if (checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) return
        getKoin().get<MirrorChangeObserver>().start()
        getKoin().get<MessageMirrorScheduler>().schedule(forceReconcile = true)
    }
}
