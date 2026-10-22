package app.locationreply.mobile

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_LOCKED_BOOT_COMPLETED) {
            PrefsHelper.sync(context)
            Log.i("BootReceiver", "LocationReply initialized on boot. Ready to respond to SMS triggers.")
        }
    }
}
