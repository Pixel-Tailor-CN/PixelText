package vip.mystery0.pixel.text.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log

class MmsReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "MmsReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION) {
            Log.d(TAG, "Received MMS push notification")
            // TODO: Implement MMS downloading and parsing.
            // This involves parsing the WAP Push PDU, inserting a placeholder into Telephony.Mms,
            // and starting a network request using SmsManager.downloadMultimediaMessage.
        }
    }
}
