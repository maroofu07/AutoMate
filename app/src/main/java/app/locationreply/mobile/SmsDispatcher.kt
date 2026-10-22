package app.locationreply.mobile

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Robust SMS dispatcher supporting Dual SIM cards (Jio, Airtel, Vi, BSNL, etc.)
 * and handling multipart SMS (> 160 characters) gracefully across all Android versions.
 */
object SmsDispatcher {
    private const val TAG = "SmsDispatcher"

    fun sendSms(context: Context, targetNumber: String, text: String): Boolean {
        if (targetNumber == "LOCAL_TEST_DEVICE" || targetNumber.isBlank()) {
            Log.i(TAG, "Simulated SMS to $targetNumber: $text")
            return true
        }

        val hasSendSms = ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
        if (!hasSendSms) {
            Log.e(TAG, "Cannot send SMS: android.permission.SEND_SMS is not granted.")
            return false
        }

        // Clean target number (remove spaces, hyphens, parentheses often added by iOS/contacts)
        val cleanTarget = targetNumber.trim().replace(" ", "").replace("-", "").replace("(", "").replace(")", "")

        return try {
            val smsManager: SmsManager = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    context.getSystemService(SmsManager::class.java) ?: @Suppress("DEPRECATION") SmsManager.getDefault()
                } else {
                    @Suppress("DEPRECATION")
                    SmsManager.getDefault()
                }
            } catch (e: Exception) {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }

            val parts = smsManager.divideMessage(text)
            if (parts.size > 1) {
                smsManager.sendMultipartTextMessage(cleanTarget, null, parts, null, null)
            } else {
                smsManager.sendTextMessage(cleanTarget, null, text, null, null)
            }
            Log.i(TAG, "SMS successfully sent to $cleanTarget: $text")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Exception while sending SMS to $cleanTarget: ${e.message}", e)
            false
        }
    }

    fun getBatteryPercentage(context: Context): Int {
        return try {
            val ifilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val batteryStatus: Intent? = context.registerReceiver(null, ifilter)
            val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level >= 0 && scale > 0) ((level.toFloat() / scale.toFloat()) * 100).toInt() else 0
        } catch (e: Exception) {
            0
        }
    }
}
