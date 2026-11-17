package app.locationreply.mobile

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.PowerManager
import android.provider.Telephony
import android.telephony.SmsMessage
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * High-reliability BroadcastReceiver for incoming SMS triggers across Android 7 to Android 15.
 *
 * 1. Supports comma-separated triggers (e.g. "#LOCATE, locate, stall") with optional '#' prefix.
 * 2. Extracts the receiving SIM's subscriptionId for Dual-SIM Samsung / Indian carrier compatibility.
 * 3. Launches LocationService (location ForegroundService) when permitted by OS for 22s cold GPS lock;
 *    seamlessly falls back to direct bounded goAsync() + hardware GPS + cached coordinates if OS blocks background FGS.
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

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LocationReply:SmsWakeLock")?.apply {
            acquire(12000L) // Bounded wake lock for receiver work
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
        PrefsHelper.sync(context)
        val prefs = PrefsHelper.get(context)
        val isEnabled = prefs.getBoolean("service_enabled", true)
        if (!isEnabled) {
            Log.d(TAG, "Location reply service is currently disabled in app settings.")
            return
        }

        val triggerRaw = prefs.getString("trigger_keyword", "#LOCATE") ?: "#LOCATE"
        val triggerList = triggerRaw
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .ifEmpty { listOf("#LOCATE") }

        val whitelistOnly = prefs.getBoolean("whitelist_only", false)
        val whitelistNumbers = prefs.getString("whitelist_numbers", "")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        val cooldownSeconds = 30 * 1000L

        val subId = intent.getIntExtra(
            "subscription",
            intent.getIntExtra("android.telephony.extra.SUBSCRIPTION_INDEX", -1)
        )

        val messages: Array<SmsMessage>? = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        // Group incoming SMS parts by sender to properly assemble concatenated/multipart SMS from iPhone or Android
        val groupedMessages = messages.groupBy { it.originatingAddress ?: "" }

        for ((senderNumber, parts) in groupedMessages) {
            if (senderNumber.isBlank()) continue
            val messageBody = parts.joinToString(separator = "") { it.messageBody ?: "" }

            Log.i(TAG, "Incoming SMS from $senderNumber (subId=$subId): $messageBody")

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

            // Multi-keyword match check: matches any comma-separated trigger (with or without '#' prefix)
            val matches = triggerList.any { rawKeyword ->
                val cleanNoHash = rawKeyword.removePrefix("#").trim()
                if (false) {
                    messageBody.contains(rawKeyword) || (cleanNoHash.isNotEmpty() && messageBody.contains(cleanNoHash))
                } else {
                    messageBody.contains(rawKeyword, ignoreCase = true) || (cleanNoHash.isNotEmpty() && messageBody.contains(cleanNoHash, ignoreCase = true))
                }
            }

            if (matches) {
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastTriggerTime < cooldownSeconds) {
                    Log.w(TAG, "Triggered too quickly. Cooldown in effect.")
                    continue
                }
                lastTriggerTime = currentTime

                Log.i(TAG, "Trigger matched from $senderNumber (subId=$subId)! Acquiring GPS location...")

                // Prefer LocationService (foreground location service gives 22s for offline GPS lock on Android 10-15)
                val serviceIntent = Intent(context, LocationService::class.java).apply {
                    putExtra("target_number", senderNumber)
                    putExtra("sub_id", subId)
                }
                var serviceStarted = false
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                    serviceStarted = true
                } catch (e: Exception) {
                    Log.w(TAG, "Could not start ForegroundService (${e.javaClass.simpleName}): ${e.message}. Using receiver fallback.")
                }

                if (!serviceStarted) {
                    processLocationAndReplyFallback(context, senderNumber, subId)
                }
                break
            }
        }
    }

    private fun processLocationAndReplyFallback(context: Context, targetNumber: String, subId: Int) {
        val prefs = PrefsHelper.get(context)
        val template = prefs.getString("reply_template", "Current location: {maps_url}") ?: "Current location: {maps_url}"
        val includeBattery = prefs.getBoolean("include_battery", true)

        val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        if (!hasFine && !hasCoarse) {
            val cached = PrefsHelper.getLastLocation(context)
            val bat = SmsDispatcher.getBatteryPercentage(context)
            if (cached != null) {
                val age = PrefsHelper.formatLocationAge(cached.timeMs)
                sendFormattedReply(context, targetNumber, subId, cached.latitude, cached.longitude, cached.accuracy.toInt(), "Cached GPS ($age)", template, includeBattery)
            } else {
                SmsDispatcher.sendSms(context, targetNumber, "LocationReply: GPS permission not granted on device (Battery: $bat%)", subId)
            }
            return
        }

        val locManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        val resultLocation = AtomicReference<Location?>(null)
        val latch = CountDownLatch(1)
        val cts = CancellationTokenSource()

        // Engine 1: Direct Hardware GPS & Network Listener (works 100% offline with Mobile Data OFF)
        val hwListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (resultLocation.compareAndSet(null, location)) {
                    latch.countDown()
                }
            }
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }

        try {
            if (locManager != null && hasFine && locManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0f, hwListener, Looper.getMainLooper())
            }
            if (locManager != null && locManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 0L, 0f, hwListener, Looper.getMainLooper())
            }
        } catch (e: Exception) {
            Log.w(TAG, "Hardware location updates failed: ${e.message}")
        }

        // Engine 2: Google Play Services Fused Location Client
        try {
            val fusedClient = LocationServices.getFusedLocationProviderClient(context)
            val priority = if (hasFine) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY
            fusedClient.getCurrentLocation(priority, cts.token)
                .addOnSuccessListener { loc: Location? ->
                    if (loc != null && resultLocation.compareAndSet(null, loc)) {
                        latch.countDown()
                    }
                }
        } catch (e: Exception) {
            Log.w(TAG, "Fused location client failed: ${e.message}")
        }

        // Wait up to 8 seconds (safely below Android's 10s BroadcastReceiver ANR limit)
        try {
            latch.await(8, TimeUnit.SECONDS)
        } catch (ignored: InterruptedException) {
        } finally {
            cts.cancel()
            try { locManager?.removeUpdates(hwListener) } catch (ignored: Exception) {}
        }

        val liveLoc = resultLocation.get()
        if (liveLoc != null) {
            PrefsHelper.saveLastLocation(context, liveLoc.latitude, liveLoc.longitude, liveLoc.accuracy, liveLoc.time)
            sendFormattedReply(context, targetNumber, subId, liveLoc.latitude, liveLoc.longitude, liveLoc.accuracy.toInt(), "Live GPS", template, includeBattery)
            return
        }

        // Fallback 1: OS last known providers
        val gpsLoc = try { locManager?.getLastKnownLocation(LocationManager.GPS_PROVIDER) } catch (e: SecurityException) { null }
        val netLoc = try { locManager?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) } catch (e: SecurityException) { null }
        val passiveLoc = try { locManager?.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER) } catch (e: SecurityException) { null }
        val osBest = listOfNotNull(gpsLoc, netLoc, passiveLoc).maxByOrNull { it.time }

        // Fallback 2: Persistent PrefsHelper cache
        val cachedLoc = PrefsHelper.getLastLocation(context)

        if (osBest != null && (cachedLoc == null || osBest.time >= cachedLoc.timeMs)) {
            PrefsHelper.saveLastLocation(context, osBest.latitude, osBest.longitude, osBest.accuracy, osBest.time)
            val age = PrefsHelper.formatLocationAge(osBest.time)
            sendFormattedReply(context, targetNumber, subId, osBest.latitude, osBest.longitude, osBest.accuracy.toInt(), "Last Known GPS ($age)", template, includeBattery)
        } else if (cachedLoc != null) {
            val age = PrefsHelper.formatLocationAge(cachedLoc.timeMs)
            sendFormattedReply(context, targetNumber, subId, cachedLoc.latitude, cachedLoc.longitude, cachedLoc.accuracy.toInt(), "Cached GPS ($age)", template, includeBattery)
        } else {
            val bat = SmsDispatcher.getBatteryPercentage(context)
            val isGpsEnabled = locManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) ?: false
            val status = if (!isGpsEnabled) "GPS toggled OFF in phone settings" else "GPS signal blocked indoors, mobile data OFF"
            val msg = "Location request received ($status, Battery: $bat%). Enable 'Allow all the time' location for cold satellite lock."
            prefs.edit().putString("last_reply_preview", msg).apply()
            SmsDispatcher.sendSms(context, targetNumber, msg, subId)
        }
    }

    private fun sendFormattedReply(
        context: Context,
        targetNumber: String,
        subId: Int,
        latitude: Double,
        longitude: Double,
        accuracyMeters: Int,
        sourceLabel: String,
        template: String,
        includeBattery: Boolean
    ) {
        val lat = String.format("%.6f", latitude)
        val lng = String.format("%.6f", longitude)
        val mapsUrl = "https://maps.google.com/?q=$lat,$lng"
        val batteryPct = SmsDispatcher.getBatteryPercentage(context)

        var replyText = template
            .replace("{maps_url}", mapsUrl)
            .replace("{lat}", lat)
            .replace("{lng}", lng)
            .replace("{accuracy}", accuracyMeters.toString())
            .replace("{battery}", "$batteryPct%")

        if (includeBattery && !template.contains("{battery}")) {
            replyText += " (Bat: $batteryPct%)"
        }
        replyText += " [$sourceLabel]"

        PrefsHelper.get(context).edit().putString("last_reply_preview", replyText).apply()
        SmsDispatcher.sendSms(context, targetNumber, replyText, subId)
    }
}
