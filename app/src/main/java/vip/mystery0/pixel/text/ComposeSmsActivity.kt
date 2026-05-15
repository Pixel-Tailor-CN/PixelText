package vip.mystery0.pixel.text

import android.content.Intent
import android.os.Bundle
import android.provider.Telephony
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import vip.mystery0.pixel.text.ui.screen.ConversationDetailScreen
import vip.mystery0.pixel.text.ui.theme.PixelTextTheme

class ComposeSmsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val address = intent.data?.schemeSpecificPart?.split('?')?.get(0) ?: ""
        val body =
            intent.getStringExtra(Intent.EXTRA_TEXT) ?: intent.getStringExtra("sms_body") ?: ""

        if (address.isBlank()) {
            finish()
            return
        }

        val threadId = getThreadIdForAddress(address)

        setContent {
            PixelTextTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ConversationDetailScreen(
                        threadId = threadId,
                        address = address,
                        onNavigateBack = { finish() }
                    )
                }
            }
        }
    }

    private fun getThreadIdForAddress(address: String): Long {
        contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms.THREAD_ID),
            "${Telephony.Sms.ADDRESS} = ?",
            arrayOf(address),
            "${Telephony.Sms.DATE} DESC LIMIT 1"
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getLong(0)
            }
        }
        return -1L
    }
}
