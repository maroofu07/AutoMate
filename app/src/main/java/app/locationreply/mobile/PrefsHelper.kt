package app.locationreply.mobile

import android.content.Context
import android.content.SharedPreferences
import android.os.Build

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
}
