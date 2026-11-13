package vip.mystery0.pixel.text.util

import android.content.Context
import androidx.annotation.StringRes

/**
 * 在非 Compose / 无直接 Context 引用的领域或数据层中读取本地化字符串资源。
 */
object AppStrings {
    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun get(@StringRes resId: Int, vararg formatArgs: Any): String {
        return if (formatArgs.isEmpty()) {
            appContext.getString(resId)
        } else {
            appContext.getString(resId, *formatArgs)
        }
    }
}
