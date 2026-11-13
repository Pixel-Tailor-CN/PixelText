package vip.mystery0.pixel.text.smartspacer

import android.content.Intent
import com.kieronquinn.app.smartspacer.sdk.model.SmartspaceAction
import com.kieronquinn.app.smartspacer.sdk.model.uitemplatedata.Icon
import com.kieronquinn.app.smartspacer.sdk.model.uitemplatedata.TapAction
import com.kieronquinn.app.smartspacer.sdk.model.uitemplatedata.Text
import com.kieronquinn.app.smartspacer.sdk.provider.SmartspacerComplicationProvider
import com.kieronquinn.app.smartspacer.sdk.utils.ComplicationTemplate
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import vip.mystery0.pixel.text.MainActivity
import vip.mystery0.pixel.text.R
import android.graphics.drawable.Icon as AndroidIcon

class UnreadSmsComplicationProvider : SmartspacerComplicationProvider(), KoinComponent {
    private val repository: SmartspacerSmsRepository by inject()
    private val settingsRepository: UnreadSmsComplicationSettingsRepository by inject()

    override fun getSmartspaceActions(smartspacerId: String): List<SmartspaceAction> {
        val count = repository.getUnreadSmsCount(smartspacerId)
        val label = formatUnreadCount(count) ?: return emptyList()
        return listOf(
            ComplicationTemplate.Basic(
                id = "pixel_text_unread_sms_$smartspacerId",
                icon = Icon(
                    AndroidIcon.createWithResource(
                        provideContext(),
                        R.drawable.ic_notification_sms
                    ),
                    contentDescription = provideContext().getString(R.string.sys_smartspacer_unread_cd)
                ),
                content = Text(label),
                onClick = TapAction(intent = openAppIntent())
            ).create()
        )
    }

    override fun getConfig(smartspacerId: String?): Config {
        return Config(
            label = provideContext().getString(R.string.sys_smartspacer_unread_label),
            description = provideContext().getString(R.string.sys_smartspacer_unread_desc),
            icon = AndroidIcon.createWithResource(provideContext(), R.drawable.ic_notification_sms),
            configActivity = Intent(
                provideContext(),
                UnreadSmsComplicationConfigActivity::class.java
            )
        )
    }

    override fun onProviderRemoved(smartspacerId: String) {
        settingsRepository.removeSettings(smartspacerId)
    }

    private fun openAppIntent(): Intent {
        return Intent(provideContext(), MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
    }

    private fun formatUnreadCount(count: Int): String? {
        if (count <= 0) return null
        return if (count > 99) {
            provideContext().getString(R.string.sys_smartspacer_unread_99_plus)
        } else {
            provideContext().getString(R.string.sys_smartspacer_unread_count, count)
        }
    }
}
