package vip.mystery0.pixel.text.smartspacer

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import com.kieronquinn.app.smartspacer.sdk.model.SmartspaceTarget
import com.kieronquinn.app.smartspacer.sdk.model.uitemplatedata.Icon
import com.kieronquinn.app.smartspacer.sdk.model.uitemplatedata.TapAction
import com.kieronquinn.app.smartspacer.sdk.model.uitemplatedata.Text
import com.kieronquinn.app.smartspacer.sdk.provider.SmartspacerTargetProvider
import com.kieronquinn.app.smartspacer.sdk.utils.TargetTemplate
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import vip.mystery0.pixel.text.R
import android.graphics.drawable.Icon as AndroidIcon

class VerificationCodeTargetProvider : SmartspacerTargetProvider(), KoinComponent {
    private val repository: SmartspacerSmsRepository by inject()

    override fun getSmartspaceTargets(smartspacerId: String): List<SmartspaceTarget> {
        val code = repository.getLatestUnreadVerificationCode() ?: return emptyList()
        val senderLabel = code.signature?.let { "【$it】" } ?: code.sender
        return listOf(
            TargetTemplate.Basic(
                id = "pixel_text_verification_code_${code.messageId}_$smartspacerId",
                componentName = ComponentName(
                    provideContext(),
                    VerificationCodeTargetProvider::class.java
                ),
                title = Text("验证码 ${code.code}"),
                subtitle = Text("$senderLabel · 点按复制"),
                icon = Icon(
                    AndroidIcon.createWithResource(
                        provideContext(),
                        R.drawable.ic_notification_sms
                    ),
                    contentDescription = "验证码"
                ),
                onClick = TapAction(
                    pendingIntent = createCopyPendingIntent(code),
                    shouldShowOnLockScreen = false
                )
            ).create().apply {
                isSensitive = true
                canBeDismissed = false
            }
        )
    }

    override fun getConfig(smartspacerId: String?): Config {
        return Config(
            label = "验证码",
            description = "显示最近一条未读验证码，点按复制并标记已读",
            icon = AndroidIcon.createWithResource(provideContext(), R.drawable.ic_notification_sms)
        )
    }

    override fun onDismiss(smartspacerId: String, targetId: String): Boolean {
        return false
    }

    private fun createCopyPendingIntent(code: SmartspacerVerificationCode): PendingIntent {
        val intent =
            Intent(provideContext(), VerificationCodeSmartspacerActionReceiver::class.java).apply {
                action = VerificationCodeSmartspacerActionReceiver.ACTION_COPY_VERIFICATION_CODE
                putExtra(VerificationCodeSmartspacerActionReceiver.EXTRA_MESSAGE_ID, code.messageId)
                putExtra(
                    VerificationCodeSmartspacerActionReceiver.EXTRA_VERIFICATION_CODE,
                    code.code
                )
            }
        return PendingIntent.getBroadcast(
            provideContext(),
            code.messageId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
