package app.locationreply.mobile

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Robust SMS dispatcher supporting Dual SIM cards (Jio, Airtel, Vi, BSNL, Samsung One UI, etc.)
 * and handling multipart SMS (> 160 characters) gracefully across Android 7 through Android 15.
 */
object SmsDispatcher {
    private const val TAG = "SmsDispatcher"

    fun sendSms(context: Context, targetNumber: String, text: String, subId: Int = -1): Boolean {
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
            val smsManager = resolveSmsManager(context, subId)
            val parts = smsManager.divideMessage(text)
            if (parts.size > 1) {
                smsManager.sendMultipartTextMessage(cleanTarget, null, parts, null, null)
            } else {
                smsManager.sendTextMessage(cleanTarget, null, text, null, null)
            }
            Log.i(TAG, "SMS successfully sent to $cleanTarget (subId=$subId): $text")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Exception while sending SMS to $cleanTarget (subId=$subId): ${e.message}", e)
            // Secondary fallback with default SmsManager if subscription-specific manager failed
            try {
                val fallbackManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    context.getSystemService(SmsManager::class.java) ?: @Suppress("DEPRECATION") SmsManager.getDefault()
                } else {
                    @Suppress("DEPRECATION")
                    SmsManager.getDefault()
                }
                val parts = fallbackManager.divideMessage(text)
                if (parts.size > 1) {
                    fallbackManager.sendMultipartTextMessage(cleanTarget, null, parts, null, null)
                } else {
                    fallbackManager.sendTextMessage(cleanTarget, null, text, null, null)
                }
                true
            } catch (ex: Exception) {
                Log.e(TAG, "Fallback SMS send also failed: ${ex.message}", ex)
                false
            }
        }
    }

    private fun resolveSmsManager(context: Context, subId: Int): SmsManager {
        val defaultSmsSubId = try {
            SubscriptionManager.getDefaultSmsSubscriptionId()
        } catch (e: Exception) {
            SubscriptionManager.INVALID_SUBSCRIPTION_ID
        }

        val resolvedSubId = when {
            subId > -1 && subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID -> subId
            defaultSmsSubId != SubscriptionManager.INVALID_SUBSCRIPTION_ID -> defaultSmsSubId
            else -> -1
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val baseManager = context.getSystemService(SmsManager::class.java)
            if (baseManager != null) {
                if (resolvedSubId > -1) {
                    return baseManager.createForSubscriptionId(resolvedSubId)
                }
                return baseManager
            }
        } else if (resolvedSubId > -1) {
            @Suppress("DEPRECATION")
            return SmsManager.getSmsManagerForSubscriptionId(resolvedSubId)
        }

        @Suppress("DEPRECATION")
        return SmsManager.getDefault()
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
