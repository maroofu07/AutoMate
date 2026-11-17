package app.locationreply.mobile

import android.content.Context
import android.content.SharedPreferences
import android.os.Build

data class CachedLocation(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val timeMs: Long
)

/**
 * Shared storage helper ensuring that MainActivity, SmsRequestReceiver,
 * and LocationService all read and write from the EXACT SAME preferences file.
 * Uses Device Protected Storage Context so triggers can be received and answered
 * even before the user enters their PIN after a phone reboot (Direct Boot mode).
 */
object PrefsHelper {
    private const val PREFS_NAME = "location_reply_prefs"

    fun get(context: Context): SharedPreferences {
        val storageContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.createDeviceProtectedStorageContext()
        } else {
            context
        }
        return storageContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Migrates or synchronizes any settings saved in standard credential storage
     * to device-protected storage so they are immediately available in the background.
     */
    fun sync(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                val directBoot = context.createDeviceProtectedStorageContext()
                val directPrefs = directBoot.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val standardPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

                if (standardPrefs.all.isNotEmpty() && directPrefs.all.isEmpty()) {
                    val editor = directPrefs.edit()
                    for ((k, v) in standardPrefs.all) {
                        when (v) {
                            is Boolean -> editor.putBoolean(k, v)
                            is String -> editor.putString(k, v)
                            is Int -> editor.putInt(k, v)
                            is Long -> editor.putLong(k, v)
                            is Float -> editor.putFloat(k, v)
                        }
                    }
                    editor.apply()
                }
            } catch (ignored: Exception) {
                // Silently ignore sync failure
            }
        }
    }

    fun saveLastLocation(context: Context, lat: Double, lng: Double, accuracy: Float, timeMs: Long) {
        try {
            get(context).edit()
                .putString("cached_lat", lat.toString())
                .putString("cached_lng", lng.toString())
                .putFloat("cached_accuracy", accuracy)
                .putLong("cached_time", timeMs)
                .apply()
        } catch (ignored: Exception) {
        }
    }

    fun getLastLocation(context: Context): CachedLocation? {
        return try {
            val prefs = get(context)
            val lat = prefs.getString("cached_lat", null)?.toDoubleOrNull() ?: return null
            val lng = prefs.getString("cached_lng", null)?.toDoubleOrNull() ?: return null
            val accuracy = prefs.getFloat("cached_accuracy", 50f)
            val timeMs = prefs.getLong("cached_time", 0L)
            CachedLocation(lat, lng, accuracy, timeMs)
        } catch (e: Exception) {
            null
        }
    }

    fun formatLocationAge(timeMs: Long): String {
        if (timeMs <= 0L) return "cached"
        val diffSec = ((System.currentTimeMillis() - timeMs) / 1000L).coerceAtLeast(0L)
        return when {
            diffSec < 60L -> "just now"
            diffSec < 3600L -> "${diffSec / 60L}m ago"
            diffSec < 86400L -> "${diffSec / 3600L}h ago"
            else -> "${diffSec / 86400L}d ago"
        }
    }
}
