
package app.locationreply.mobile

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
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

/**
 * Foreground Service for background location retrieval & manual tests.
 */
class LocationService : Service() {

    companion object {
        private const val TAG = "LocationService"
        private const val CHANNEL_ID = "location_reply_channel"
        private const val NOTIFICATION_ID = 1001
    }

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var wakeLock: PowerManager.WakeLock? = null
    private var isReplied = false

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val targetNumber = intent?.getStringExtra("target_number")

        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        wakeLock = powerManager
            ?.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "LocationReply:GpsWakeLock"
            )
            ?.apply {
                acquire(35000L)
            }

        startForeground(NOTIFICATION_ID, buildForegroundNotification())

        if (targetNumber.isNullOrEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }

        isReplied = false
        fetchLocationAndReply(targetNumber)

        return START_NOT_STICKY
    }

    private fun fetchLocationAndReply(targetNumber: String) {
        val hasFine = ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val hasCoarse = ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasFine && !hasCoarse) {
            Log.e(TAG, "Location permission missing.")
            val bat = SmsDispatcher.getBatteryPercentage(this)
            SmsDispatcher.sendSms(
                this,
                targetNumber,
                "Location permission not granted on device (Battery: $bat%)"
            )
            stopSelf()
            return
        }

        val locManager =
            getSystemService(Context.LOCATION_SERVICE) as? LocationManager

        val isGpsEnabled =
            locManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) ?: false

        val mainHandler = Handler(Looper.getMainLooper())
        val cts = CancellationTokenSource()

        val priority = if (hasFine) {
            Priority.PRIORITY_HIGH_ACCURACY
        } else {
            Priority.PRIORITY_BALANCED_POWER_ACCURACY
        }

        fusedLocationClient.getCurrentLocation(priority, cts.token)
            .addOnSuccessListener { location: Location? ->
                if (location != null && !isReplied) {
                    isReplied = true
                    sendLocationReply(
                        targetNumber,
                        location,
                        sourceLabel = "Live GPS"
                    )
                }
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Fused location unavailable: ${e.message}")
            }

        mainHandler.postDelayed({
            if (!isReplied) {
                isReplied = true
                fallbackToLastKnownLocation(targetNumber, isGpsEnabled)
            }
        }, 12000L)
    }

    private fun fallbackToLastKnownLocation(
        targetNumber: String,
        isGpsEnabled: Boolean
    ) {
        val locManager =
            getSystemService(Context.LOCATION_SERVICE) as? LocationManager

        val gpsLoc = try {
            locManager?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        } catch (e: SecurityException) {
            null
        }

        val netLoc = try {
            locManager?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        } catch (e: SecurityException) {
            null
        }

        val passiveLoc = try {
            locManager?.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
        } catch (e: SecurityException) {
            null
        }

        val bestLoc = listOfNotNull(gpsLoc, netLoc, passiveLoc)
            .maxByOrNull { it.time }

        if (bestLoc != null) {
            sendLocationReply(
                targetNumber,
                bestLoc,
                sourceLabel = "Last Known GPS (Offline)"
            )
        } else {
            val statusNote = if (!isGpsEnabled) {
                "GPS toggled OFF in phone settings"
            } else {
                "Satellites searching, mobile data OFF"
            }

            val bat = SmsDispatcher.getBatteryPercentage(this)

            SmsDispatcher.sendSms(
                this,
                targetNumber,
                "Location request received ($statusNote, Battery: $bat%). Device coordinates not cached."
            )

            stopSelf()
        }
    }

    private fun sendLocationReply(
        targetNumber: String,
        location: Location,
        sourceLabel: String = ""
    ) {
        val prefs = PrefsHelper.get(this)

        val template = prefs.getString(
            "reply_template",
            "My current location: {maps_url}"
        ) ?: "My current location: {maps_url}"

        val includeBattery = prefs.getBoolean("include_battery", true)

        val lat = String.format("%.6f", location.latitude)
        val lng = String.format("%.6f", location.longitude)
        val mapsUrl = "https://maps.google.com/?q=$lat,$lng"
        val accuracy = location.accuracy.toInt().toString()
        val batteryPct = SmsDispatcher.getBatteryPercentage(this)

        var replyText = template
            .replace("{maps_url}", mapsUrl)
            .replace("{lat}", lat)
            .replace("{lng}", lng)
            .replace("{accuracy}", accuracy)
            .replace("{battery}", "$batteryPct%")

        if (includeBattery && !template.contains("{battery}")) {
            replyText += " (Bat: $batteryPct%)"
        }

        if (sourceLabel.isNotEmpty()) {
            replyText += " [$sourceLabel]"
        }

        SmsDispatcher.sendSms(this, targetNumber, replyText)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Location Reply Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description =
                    "Runs briefly in foreground to fetch location for SMS reply"
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

        if (wakeLock?.isHeld == true) {
            try {
                wakeLock?.release()
            } catch (ignored: Exception) {
                Log.w(TAG, "Failed to release wake lock", ignored)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
