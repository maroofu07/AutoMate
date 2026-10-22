package app.locationreply.mobile

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.PowerManager
import android.provider.Telephony
import android.telephony.SmsMessage
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.Tasks
import java.util.concurrent.TimeUnit

/**
 * High-reliability BroadcastReceiver for incoming SMS triggers.
 *
 * Uses goAsync() and a background execution thread to directly acquire GPS fix
 * and dispatch the SMS reply without depending on background ForegroundService launches
 * (which are restricted on Android 12, 13, and 14).
 * Works 100% offline using direct satellite GPS even when mobile data is disabled!
 */
class SmsRequestReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SmsRequestReceiver"
        private var lastTriggerTime: Long = 0
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            return
        }

        // Keep CPU awake while processing GPS & SMS reply
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LocationReply:SmsWakeLock")?.apply {
            acquire(35000L) // 35 seconds max
        }

        val pendingResult = goAsync()

        Thread {
            try {
                handleIncomingSms(context, intent)
            } catch (e: Exception) {
                Log.e(TAG, "Error handling incoming SMS broadcast", e)
            } finally {
                if (wakeLock?.isHeld == true) {
                    try { wakeLock.release() } catch (ignored: Exception) {}
                }
                pendingResult.finish()
            }
        }.start()
    }

    private fun handleIncomingSms(context: Context, intent: Intent) {
        val prefs = PrefsHelper.get(context)
        val isEnabled = prefs.getBoolean("service_enabled", true)
        if (!isEnabled) {
            Log.d(TAG, "Location reply service is currently disabled in app settings.")
            return
        }

        val triggerWord = prefs.getString("trigger_keyword", "#LOCATE") ?: "#LOCATE"
        val cleanTrigger = triggerWord.trim()
        val cleanTriggerNoHash = cleanTrigger.removePrefix("#").trim()

        val whitelistOnly = prefs.getBoolean("whitelist_only", false)
        val whitelistNumbers = prefs.getString("whitelist_numbers", "")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        val cooldownSeconds = 30 * 1000L

        val messages: Array<SmsMessage>? = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        // Group incoming SMS parts by sender to properly assemble concatenated/multipart SMS from iPhone or Android
        val groupedMessages = messages.groupBy { it.originatingAddress ?: "" }

        for ((senderNumber, parts) in groupedMessages) {
            if (senderNumber.isBlank()) continue
            val messageBody = parts.joinToString(separator = "") { it.messageBody ?: "" }

            Log.i(TAG, "Incoming SMS from $senderNumber: $messageBody")

            // Whitelist check: matches exact, suffix (+91, 0), or last 10 digits (standard Indian subscriber format)
            if (whitelistOnly && whitelistNumbers.isNotEmpty()) {
                val cleanSender = senderNumber.replace("[^0-9]".toRegex(), "")
                val matched = whitelistNumbers.any { allowed ->
                    val cleanAllowed = allowed.replace("[^0-9]".toRegex(), "")
                    cleanSender == cleanAllowed ||
                    cleanSender.endsWith(cleanAllowed) ||
                    cleanAllowed.endsWith(cleanSender) ||
                    (cleanSender.length >= 10 && cleanAllowed.length >= 10 && cleanSender.takeLast(10) == cleanAllowed.takeLast(10))
                }
                if (!matched) {
                    Log.w(TAG, "Sender $senderNumber is not in whitelist. Ignoring.")
                    continue
                }
            }

            // Keyword match check: flexible support for with or without '#' prefix
            val matches = if (false) {
                messageBody.contains(cleanTrigger) || (cleanTriggerNoHash.isNotEmpty() && messageBody.contains(cleanTriggerNoHash))
            } else {
                messageBody.contains(cleanTrigger, ignoreCase = true) || (cleanTriggerNoHash.isNotEmpty() && messageBody.contains(cleanTriggerNoHash, ignoreCase = true))
            }

            if (matches) {
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastTriggerTime < cooldownSeconds) {
                    Log.w(TAG, "Triggered too quickly. Cooldown in effect.")
                    continue
                }
                lastTriggerTime = currentTime

                Log.i(TAG, "Anti-Theft Trigger '$triggerWord' matched from $senderNumber! Processing GPS location...")

                // Direct asynchronous location acquisition & SMS reply
                processLocationAndReply(context, senderNumber)
                break
            }
        }
    }

    private fun processLocationAndReply(context: Context, targetNumber: String) {
        val prefs = PrefsHelper.get(context)
        val template = prefs.getString("reply_template", "My current location: {maps_url}") ?: "My current location: {maps_url}"
        val includeBattery = prefs.getBoolean("include_battery", true)

        val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        if (!hasFine && !hasCoarse) {
            Log.e(TAG, "Location permission missing when responding to trigger.")
            val bat = SmsDispatcher.getBatteryPercentage(context)
            SmsDispatcher.sendSms(context, targetNumber, "LocationReply: GPS permission not granted on device (Battery: $bat%)")
            return
        }

        val locManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        var bestLocation: Location? = null
        var locationSource = "GPS"

        // 1. Try Google Play Services Fused Location Client (up to 7 seconds)
        try {
            val fusedClient = LocationServices.getFusedLocationProviderClient(context)
            val cts = CancellationTokenSource()
            val priority = if (hasFine) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY
            val task = fusedClient.getCurrentLocation(priority, cts.token)
            val freshLocation = Tasks.await(task, 7, TimeUnit.SECONDS)
            if (freshLocation != null) {
                bestLocation = freshLocation
                locationSource = "Live GPS"
            }
        } catch (e: Exception) {
            Log.w(TAG, "Fused location provider wait failed or timed out: ${e.message}")
        }

        // 2. Hardware GPS / Network / Passive cached location fallback (Works 100% offline without mobile data!)
        if (bestLocation == null && locManager != null) {
            val gpsLoc = try { locManager.getLastKnownLocation(LocationManager.GPS_PROVIDER) } catch (e: SecurityException) { null }
            val netLoc = try { locManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) } catch (e: SecurityException) { null }
            val passiveLoc = try { locManager.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER) } catch (e: SecurityException) { null }

            bestLocation = listOfNotNull(gpsLoc, netLoc, passiveLoc).maxByOrNull { it.time }
            if (bestLocation != null) {
                locationSource = "Last Known GPS (Offline)"
            }
        }

        // 3. Format and Dispatch SMS
        if (bestLocation != null) {
            val lat = String.format("%.6f", bestLocation.latitude)
            val lng = String.format("%.6f", bestLocation.longitude)
            val mapsUrl = "https://maps.google.com/?q=$lat,$lng"
            val accuracy = bestLocation.accuracy.toInt().toString()
            val batteryPct = SmsDispatcher.getBatteryPercentage(context)

            var replyText = template
                .replace("{maps_url}", mapsUrl)
                .replace("{lat}", lat)
                .replace("{lng}", lng)
                .replace("{accuracy}", accuracy)
                .replace("{battery}", "$batteryPct%")

            if (includeBattery && !template.contains("{battery}")) {
                replyText += " (Bat: $batteryPct%)"
            }

            replyText += " [$locationSource]"

            SmsDispatcher.sendSms(context, targetNumber, replyText)
        } else {
            val bat = SmsDispatcher.getBatteryPercentage(context)
            val isGpsEnabled = locManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) ?: false
            val status = if (!isGpsEnabled) "GPS toggled OFF in phone settings" else "Satellites searching, mobile data OFF"
            SmsDispatcher.sendSms(context, targetNumber, "Location request received ($status, Battery: $bat%). Device coordinates not cached.")
        }
    }
}
