package vip.mystery0.pixel.text

import android.app.Application
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import vip.mystery0.pixel.text.di.appModule

class PixelTextApp : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidLogger()
            androidContext(this@PixelTextApp)
            modules(appModule)
        }
    }
}
