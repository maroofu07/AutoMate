package app.locationreply.mobile

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground Service for extended offline hardware GPS satellite locking (22 seconds)
 * and reliable Dual-SIM SMS dispatch across Android 7 through Android 15.
 */
class LocationService : Service() {

    companion object {
        private const val TAG = "LocationService"
        private const val CHANNEL_ID = "location_reply_channel"
        private const val NOTIFICATION_ID = 1001
        private const val GPS_TIMEOUT_MS = 22000L
    }

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var wakeLock: PowerManager.WakeLock? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var activeHardwareListener: LocationListener? = null
    private var activeCts: CancellationTokenSource? = null
    private val isCompleted = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val targetNumber = intent?.getStringExtra("target_number")
        val subId = intent?.getIntExtra("sub_id", -1) ?: -1

        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LocationReply:GpsWakeLock")?.apply {
            acquire(30000L) // 30 seconds max
        }

        val hasLocationPerm =
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && hasLocationPerm) {
                startForeground(NOTIFICATION_ID, buildForegroundNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(NOTIFICATION_ID, buildForegroundNotification())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Foreground service restriction (${e.javaClass.simpleName}): ${e.message}")
            if (!targetNumber.isNullOrEmpty()) {
                val locManager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                val isGpsEnabled = locManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) ?: false
                fallbackToLastKnownLocation(targetNumber, isGpsEnabled, subId, hasLocationPerm)
            } else {
                stopSelf()
            }
            return START_NOT_STICKY
        }

        if (targetNumber.isNullOrEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }

        cleanupActiveRequests()
        isCompleted.set(false)
        fetchLocationAndReply(targetNumber, subId)

        return START_NOT_STICKY
    }

    private fun fetchLocationAndReply(targetNumber: String, subId: Int) {
        val hasFine = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        if (!hasFine && !hasCoarse) {
            Log.e(TAG, "Location permission missing.")
            val cached = PrefsHelper.getLastLocation(this)
            if (cached != null) {
                val age = PrefsHelper.formatLocationAge(cached.timeMs)
                sendCoordinatesReply(targetNumber, subId, cached.latitude, cached.longitude, cached.accuracy.toInt(), "Cached GPS ($age)")
            } else {
                val bat = SmsDispatcher.getBatteryPercentage(this)
                val msg = "Location permission not granted on device (Battery: $bat%)"
                PrefsHelper.get(this).edit().putString("last_reply_preview", msg).apply()
                SmsDispatcher.sendSms(this, targetNumber, msg, subId)
                stopSelf()
            }
            return
        }

        val locManager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        val isGpsEnabled = locManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) ?: false

        // 1. Direct Hardware GPS / GNSS & Network Listener (Critical when Mobile Data is OFF)
        val hwListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (isCompleted.compareAndSet(false, true)) {
                    cleanupActiveRequests()
                    PrefsHelper.saveLastLocation(this@LocationService, location.latitude, location.longitude, location.accuracy, location.time)
                    sendCoordinatesReply(targetNumber, subId, location.latitude, location.longitude, location.accuracy.toInt(), "Live Hardware GPS")
                }
            }
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        activeHardwareListener = hwListener

        try {
            if (locManager != null && hasFine && isGpsEnabled) {
                locManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0f, hwListener, Looper.getMainLooper())
            }
            if (locManager != null && locManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 0L, 0f, hwListener, Looper.getMainLooper())
            }
        } catch (e: Exception) {
            Log.w(TAG, "Direct GPS_PROVIDER request failed: ${e.message}")
        }

        // 2. Google Play Services Fused Location Provider (Fast when data/Wi-Fi available)
        val cts = CancellationTokenSource()
        activeCts = cts
        val priority = if (hasFine) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY
        try {
            fusedLocationClient.getCurrentLocation(priority, cts.token)
                .addOnSuccessListener { location: Location? ->
                    if (location != null && isCompleted.compareAndSet(false, true)) {
                        cleanupActiveRequests()
                        PrefsHelper.saveLastLocation(this@LocationService, location.latitude, location.longitude, location.accuracy, location.time)
                        sendCoordinatesReply(targetNumber, subId, location.latitude, location.longitude, location.accuracy.toInt(), "Live GPS")
                    }
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "Fused location unavailable: ${e.message}")
                }
        } catch (e: Exception) {
            Log.w(TAG, "Fused location call failed: ${e.message}")
        }

        // 3. Extended 22-second satellite acquisition window before falling back to cache
        mainHandler.postDelayed({
            if (isCompleted.compareAndSet(false, true)) {
                cleanupActiveRequests()
                fallbackToLastKnownLocation(targetNumber, isGpsEnabled, subId, true)
            }
        }, GPS_TIMEOUT_MS)
    }

    private fun fallbackToLastKnownLocation(
        targetNumber: String,
        isGpsEnabled: Boolean,
        subId: Int,
        hasLocationPerm: Boolean
    ) {
        val locManager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        val osBest = if (hasLocationPerm) {
            val gpsLoc = try { locManager?.getLastKnownLocation(LocationManager.GPS_PROVIDER) } catch (e: SecurityException) { null }
            val netLoc = try { locManager?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) } catch (e: SecurityException) { null }
            val passiveLoc = try { locManager?.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER) } catch (e: SecurityException) { null }
            listOfNotNull(gpsLoc, netLoc, passiveLoc).maxByOrNull { it.time }
        } else {
            null
        }

        val cached = PrefsHelper.getLastLocation(this)

        if (osBest != null && (cached == null || osBest.time >= cached.timeMs)) {
            PrefsHelper.saveLastLocation(this, osBest.latitude, osBest.longitude, osBest.accuracy, osBest.time)
            val age = PrefsHelper.formatLocationAge(osBest.time)
            sendCoordinatesReply(targetNumber, subId, osBest.latitude, osBest.longitude, osBest.accuracy.toInt(), "Last Known GPS ($age)")
        } else if (cached != null) {
            val age = PrefsHelper.formatLocationAge(cached.timeMs)
            sendCoordinatesReply(targetNumber, subId, cached.latitude, cached.longitude, cached.accuracy.toInt(), "Cached GPS ($age)")
        } else {
            val statusNote = when {
                !hasLocationPerm -> "Location permission missing"
                !isGpsEnabled -> "GPS toggled OFF in phone settings"
                else -> "Satellites blocked indoors, mobile data OFF"
            }
            val bat = SmsDispatcher.getBatteryPercentage(this)
            val msg = "Location request received ($statusNote, Battery: $bat%). Device coordinates not cached."
            PrefsHelper.get(this).edit().putString("last_reply_preview", msg).apply()
            SmsDispatcher.sendSms(
                this,
                targetNumber,
                msg,
                subId
            )
            stopSelf()
        }
    }

    private fun sendCoordinatesReply(
        targetNumber: String,
        subId: Int,
        latitude: Double,
        longitude: Double,
        accuracyMeters: Int,
        sourceLabel: String
    ) {
        val prefs = PrefsHelper.get(this)
        val template = prefs.getString("reply_template", "Current location: {maps_url}") ?: "Current location: {maps_url}"
        val includeBattery = prefs.getBoolean("include_battery", true)

        val lat = String.format("%.6f", latitude)
        val lng = String.format("%.6f", longitude)
        val mapsUrl = "https://maps.google.com/?q=$lat,$lng"
        val batteryPct = SmsDispatcher.getBatteryPercentage(this)

        var replyText = template
            .replace("{maps_url}", mapsUrl)
            .replace("{lat}", lat)
            .replace("{lng}", lng)
            .replace("{accuracy}", accuracyMeters.toString())
            .replace("{battery}", "$batteryPct%")

        if (includeBattery && !template.contains("{battery}")) {
            replyText += " (Bat: $batteryPct%)"
        }

        if (sourceLabel.isNotEmpty()) {
            replyText += " [$sourceLabel]"
        }

        prefs.edit().putString("last_reply_preview", replyText).apply()
        SmsDispatcher.sendSms(this, targetNumber, replyText, subId)
        stopSelf()
    }

    private fun cleanupActiveRequests() {
        mainHandler.removeCallbacksAndMessages(null)
        activeCts?.cancel()
        activeCts = null
        activeHardwareListener?.let { listener ->
            try {
                val locManager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                locManager?.removeUpdates(listener)
            } catch (ignored: Exception) {}
        }
        activeHardwareListener = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Location Reply Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Runs briefly in foreground to fetch location for SMS reply"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("LocationReply")
            .setContentText("Processing location inquiry...")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanupActiveRequests()
        if (wakeLock?.isHeld == true) {
            try { wakeLock?.release() } catch (ignored: Exception) {}
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
