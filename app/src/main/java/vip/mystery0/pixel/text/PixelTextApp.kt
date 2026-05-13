package vip.mystery0.pixel.text

import android.app.Application
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import vip.mystery0.pixel.text.di.appModule
import vip.mystery0.pixel.text.notification.SmsNotificationHelper

class PixelTextApp : Application() {
    override fun onCreate() {
        super.onCreate()
        SmsNotificationHelper.createNotificationChannel(this)
        startKoin {
            androidLogger()
            androidContext(this@PixelTextApp)
            modules(appModule)
        }
    }
}
