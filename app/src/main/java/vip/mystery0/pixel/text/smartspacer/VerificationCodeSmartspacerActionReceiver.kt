package vip.mystery0.pixel.text.smartspacer

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.util.Log
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class VerificationCodeSmartspacerActionReceiver : BroadcastReceiver(), KoinComponent {
    private val repository: SmartspacerSmsRepository by inject()

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_COPY_VERIFICATION_CODE) return

        val code = intent.getStringExtra(EXTRA_VERIFICATION_CODE).orEmpty()
        val messageId = intent.getLongExtra(EXTRA_MESSAGE_ID, -1L)
        if (code.isBlank() || messageId <= 0L) {
            Log.w(TAG, "copy verification skipped message_id=$messageId")
            return
        }

        copyVerificationCode(context, code)
        repository.markMessageRead(messageId)
        SmartspacerIntegration.notifyChanged(context)
    }

    private fun copyVerificationCode(context: Context, code: String) {
        runCatching {
            val clipboardManager =
                context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboardManager.setPrimaryClip(
                ClipData.newPlainText("verification code", code)
            )
        }.onFailure {
            Log.e(TAG, "failed to copy verification code", it)
        }
    }

    companion object {
        private const val TAG = "SmartspacerCodeAction"
        const val ACTION_COPY_VERIFICATION_CODE =
            "vip.mystery0.pixel.text.action.SMARTSPACER_COPY_VERIFICATION_CODE"
        const val EXTRA_MESSAGE_ID = "extra_message_id"
        const val EXTRA_VERIFICATION_CODE = "extra_verification_code"
    }
}
