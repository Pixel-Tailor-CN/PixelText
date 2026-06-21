package vip.mystery0.pixel.text.smartspacer

import android.content.Context
import android.util.Log
import com.kieronquinn.app.smartspacer.sdk.provider.SmartspacerComplicationProvider
import com.kieronquinn.app.smartspacer.sdk.provider.SmartspacerTargetProvider

private const val TAG = "SmartspacerIntegration"

object SmartspacerIntegration {
    fun notifyChanged(context: Context) {
        runCatching {
            SmartspacerTargetProvider.notifyChange(
                context,
                VerificationCodeTargetProvider::class.java
            )
        }.onFailure {
            Log.d(TAG, "target notify skipped error=${it.javaClass.simpleName}")
        }

        runCatching {
            SmartspacerComplicationProvider.notifyChange(
                context,
                UnreadSmsComplicationProvider::class.java
            )
        }.onFailure {
            Log.d(TAG, "complication notify skipped error=${it.javaClass.simpleName}")
        }
    }
}
