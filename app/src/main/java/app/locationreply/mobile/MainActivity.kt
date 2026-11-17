package app.locationreply.mobile

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices

data class PermissionState(
    val hasReceiveSms: Boolean = false,
    val hasSendSms: Boolean = false,
    val hasFineLocation: Boolean = false,
    val hasCoarseLocation: Boolean = false,
    val hasBackgroundLocation: Boolean = false,
    val hasNotification: Boolean = false,
    val isIgnoringBattery: Boolean = false
) {
    val hasSms: Boolean get() = hasReceiveSms && hasSendSms
    val hasLocation: Boolean get() = hasFineLocation || hasCoarseLocation
    val allGranted: Boolean get() = hasSms && hasLocation && hasBackgroundLocation && hasNotification
}

class MainActivity : ComponentActivity() {

    private lateinit var prefs: SharedPreferences
    private var permissionState by mutableStateOf(PermissionState())
    private var restrictedWarningTrigger by mutableStateOf(0)

    private val smsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        updatePermissionState()
        val granted = results.values.all { it }
        if (!granted) {
            val canAskAgain = ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.RECEIVE_SMS) ||
                ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.SEND_SMS)
            if (!canAskAgain) {
                restrictedWarningTrigger++
                Toast.makeText(
                    this,
                    "SMS permission blocked by Restricted Settings. Follow the guide to unlock.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private val locationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        updatePermissionState()
        cacheLastKnownLocationSilently()
        if (!permissionState.hasLocation) {
            val canAskAgain = ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.ACCESS_FINE_LOCATION)
            if (!canAskAgain) {
                restrictedWarningTrigger++
                Toast.makeText(
                    this,
                    "Location permission blocked. Please enable in App Info Settings.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private val backgroundLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        updatePermissionState()
        if (!permissionState.hasBackgroundLocation) {
            Toast.makeText(
                this,
                "Set Location permission to 'Allow all the time' for 24/7 background replies.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private val notificationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        updatePermissionState()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PrefsHelper.sync(this)
        prefs = PrefsHelper.get(this)

        updatePermissionState()
        cacheLastKnownLocationSilently()

        setContent {
            MaterialTheme {
                LocationReplyApp(
                    prefs = prefs,
                    permissionState = permissionState,
                    restrictedWarningTrigger = restrictedWarningTrigger,
                    onRequestSms = { requestSmsPermissions() },
                    onRequestLocation = { requestLocationPermissions() },
                    onRequestBackgroundLocation = { requestBackgroundLocationPermission() },
                    onRequestNotification = { requestNotificationPermissions() },
                    onOpenAppSettings = { openAppSettings() },
                    onOpenBatterySettings = { openBatterySettings() },
                    onSaveSettings = { trigger, template, includeBat, wlOnly, wlNumbers ->
                        saveAllSettings(trigger, template, includeBat, wlOnly, wlNumbers)
                    },
                    onTestTriggerLocally = { testTriggerLocally() },
                    onSendTestSmsToSelf = { phone -> sendTestSmsToSelf(phone) }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updatePermissionState()
        cacheLastKnownLocationSilently()
    }

    private fun updatePermissionState() {
        val hasReceiveSms = ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
        val hasSendSms = ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

        val hasFine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        val hasBgLocation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        val hasNotification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        val isIgnoringBattery = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            powerManager?.isIgnoringBatteryOptimizations(packageName) ?: false
        } else {
            true
        }

        permissionState = PermissionState(
            hasReceiveSms = hasReceiveSms,
            hasSendSms = hasSendSms,
            hasFineLocation = hasFine,
            hasCoarseLocation = hasCoarse,
            hasBackgroundLocation = hasBgLocation,
            hasNotification = hasNotification,
            isIgnoringBattery = isIgnoringBattery
        )
    }

    private fun cacheLastKnownLocationSilently() {
        val hasFine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasFine && !hasCoarse) return

        try {
            val locManager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            val gpsLoc = try { locManager?.getLastKnownLocation(LocationManager.GPS_PROVIDER) } catch (e: SecurityException) { null }
            val netLoc = try { locManager?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) } catch (e: SecurityException) { null }
            val best = listOfNotNull(gpsLoc, netLoc).maxByOrNull { it.time }
            if (best != null) {
                PrefsHelper.saveLastLocation(this, best.latitude, best.longitude, best.accuracy, best.time)
            }
            LocationServices.getFusedLocationProviderClient(this).lastLocation
                .addOnSuccessListener { loc: Location? ->
                    if (loc != null) {
                        PrefsHelper.saveLastLocation(this, loc.latitude, loc.longitude, loc.accuracy, loc.time)
                    }
                }
        } catch (ignored: Exception) {
        }
    }

    private fun requestSmsPermissions() {
        smsLauncher.launch(arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.SEND_SMS))
    }

    private fun requestLocationPermissions() {
        locationLauncher.launch(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        )
    }

    private fun requestBackgroundLocationPermission() {
        if (!permissionState.hasLocation) {
            Toast.makeText(this, "First tap 'Grant GPS', then enable 'Allow all the time'.", Toast.LENGTH_LONG).show()
            requestLocationPermissions()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            } catch (e: Exception) {
                openAppSettings()
            }
        }
    }

    private fun requestNotificationPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun openAppSettings() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Could not open settings", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openBatterySettings() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } else {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (ex: Exception) {
                openAppSettings()
            }
        }
    }

    private fun saveAllSettings(
        trigger: String,
        template: String,
        includeBat: Boolean,
        wlOnly: Boolean,
        wlNumbers: String
    ) {
        val cleanTrigger = trigger.trim().ifEmpty { "#LOCATE" }
        val cleanTemplate = template.trim().ifEmpty { "Current location: {maps_url}" }
        prefs.edit()
            .putString("trigger_keyword", cleanTrigger)
            .putString("reply_template", cleanTemplate)
            .putBoolean("include_battery", includeBat)
            .putBoolean("whitelist_only", wlOnly)
            .putString("whitelist_numbers", wlNumbers.trim())
            .commit()
        PrefsHelper.sync(this)
        Toast.makeText(this, "Settings saved! Active triggers: $cleanTrigger", Toast.LENGTH_SHORT).show()
    }

    private fun testTriggerLocally() {
        val triggerWord = prefs.getString("trigger_keyword", "#LOCATE") ?: "#LOCATE"
        val serviceIntent = Intent(this, LocationService::class.java).apply {
            putExtra("target_number", "LOCAL_TEST_DEVICE")
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            Toast.makeText(this, "Locking GPS satellites... Trigger: $triggerWord", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Service start error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendTestSmsToSelf(phoneNumber: String) {
        if (phoneNumber.isBlank()) {
            Toast.makeText(this, "Enter a valid phone number (e.g. +91...)", Toast.LENGTH_SHORT).show()
            return
        }
        val triggerRaw = prefs.getString("trigger_keyword", "#LOCATE") ?: "#LOCATE"
        val firstTrigger = triggerRaw.split(",").map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: "#LOCATE"
        val testMessage = "Location test: $firstTrigger"
        val success = SmsDispatcher.sendSms(this, phoneNumber, testMessage)
        if (success) {
            Toast.makeText(this, "Test SMS sent to $phoneNumber! Watch for auto-reply.", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "Could not send SMS. Check SEND_SMS permission or SIM balance.", Toast.LENGTH_LONG).show()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationReplyApp(
    prefs: SharedPreferences,
    permissionState: PermissionState,
    restrictedWarningTrigger: Int,
    onRequestSms: () -> Unit,
    onRequestLocation: () -> Unit,
    onRequestBackgroundLocation: () -> Unit,
    onRequestNotification: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    onSaveSettings: (String, String, Boolean, Boolean, String) -> Unit,
    onTestTriggerLocally: () -> Unit,
    onSendTestSmsToSelf: (String) -> Unit
) {
    var isEnabled by remember { mutableStateOf(prefs.getBoolean("service_enabled", true)) }
    var triggerWord by remember { mutableStateOf(prefs.getString("trigger_keyword", "#LOCATE") ?: "#LOCATE") }
    var replyTemplate by remember { mutableStateOf(prefs.getString("reply_template", "Current location: {maps_url}") ?: "Current location: {maps_url}") }
    var whitelistOnly by remember { mutableStateOf(prefs.getBoolean("whitelist_only", false)) }
    var whitelistInput by remember { mutableStateOf(prefs.getString("whitelist_numbers", "+91 98765 43210") ?: "") }
    var includeBattery by remember { mutableStateOf(prefs.getBoolean("include_battery", true)) }
    var lastReplyPreview by remember { mutableStateOf(prefs.getString("last_reply_preview", "") ?: "") }
    var savedBannerText by remember { mutableStateOf("") }

    var testPhoneInput by remember { mutableStateOf("") }
    var showRestrictedHelp by remember { mutableStateOf(!permissionState.allGranted) }

    LaunchedEffect(restrictedWarningTrigger) {
        if (restrictedWarningTrigger > 0) {
            showRestrictedHelp = true
        }
    }

    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { shared, key ->
            if (key == "last_reply_preview") {
                lastReplyPreview = shared.getString("last_reply_preview", "") ?: ""
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("LocationReply", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text("app.locationreply.mobile", fontSize = 11.sp, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                actions = {
                    IconButton(onClick = onOpenAppSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // HERO STATUS BANNER
            if (permissionState.allGranted && isEnabled) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFFD1E7DD)
                    ),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(14.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF198754))
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Service Request Active",
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF0F5132),
                                fontSize = 16.sp
                            )
                            Text(
                                text = "Ready for SMS triggers: $triggerWord",
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF146C43),
                                fontSize = 13.sp
                            )
                            Text(
                                text = "Automatically answers incoming SMS with GPS link 24/7 (even offline / mobile data OFF).",
                                color = Color(0xFF198754),
                                fontSize = 11.sp,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                    }
                }
            } else {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (!isEnabled) Color(0xFFF8D7DA) else Color(0xFFFFF3CD)
                    ),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = "Warning",
                                tint = if (!isEnabled) Color(0xFF842029) else Color(0xFF664D03)
                            )
                            Text(
                                text = if (!isEnabled) "Auto-Reply Paused" else "Action Needed: Permissions Incomplete",
                                fontWeight = FontWeight.Bold,
                                color = if (!isEnabled) Color(0xFF842029) else Color(0xFF664D03),
                                fontSize = 15.sp
                            )
                        }
                        Text(
                            text = if (!isEnabled)
                                "Service is toggled off in master switch below."
                            else
                                "Android 11-15 requires SMS, Background Location ('Allow all the time'), and Unrestricted Battery to reply when screen is off.",
                            fontSize = 12.sp,
                            color = if (!isEnabled) Color(0xFF842029) else Color(0xFF664D03)
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = onOpenAppSettings,
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF664D03)),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("App Info Settings", color = Color.White, fontSize = 12.sp)
                            }

                            FilledTonalButton(
                                onClick = { showRestrictedHelp = !showRestrictedHelp },
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text(if (showRestrictedHelp) "Hide Guide" else "How to Fix", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }

            // RESTRICTED SETTINGS & OEM SLEEP GUIDE (Android 11 - 15)
            AnimatedVisibility(visible = showRestrictedHelp) {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Text(
                                "Android 13/14/15 'Restricted Settings' Fix",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        }
                        Text(
                            "If permissions are greyed out or say 'Restricted setting':\n" +
                            "1. Tap 'App Info Settings' above (or Settings > Apps > LocationReply).\n" +
                            "2. Tap the 3 DOTS (⋮) in the top right corner.\n" +
                            "3. Tap 'Allow restricted settings' and confirm your PIN/biometric.\n" +
                            "4. Return here, grant SMS + GPS + 'Allow all the time' Background GPS.",
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        Text(
                            "Samsung One UI / Xiaomi / Realme / Oppo / Vivo Fix:\n" +
                            "• Set Battery to 'Unrestricted' (below).\n" +
                            "• Samsung: Settings > Battery > Background usage limits > Never sleeping apps > Add LocationReply.\n" +
                            "• Dual-SIM: Incoming SIM is used automatically for the SMS reply.",
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // MASTER TOGGLE SWITCH
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Auto-Reply Master Switch", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Text(
                            if (isEnabled) "Active: listening for incoming SMS triggers" else "Paused (won't reply to incoming SMS)",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Switch(
                        checked = isEnabled,
                        onCheckedChange = {
                            isEnabled = it
                            prefs.edit().putBoolean("service_enabled", it).apply()
                        }
                    )
                }
            }

            // PERMISSIONS CHECKLIST SECTION
            Card(shape = RoundedCornerShape(16.dp)) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("System Permissions Checklist", fontWeight = FontWeight.Bold, fontSize = 15.sp)

                    // SMS Row
                    PermissionRow(
                        title = "SMS (Receive & Send)",
                        description = "Reads incoming trigger word & dispatches reply",
                        isGranted = permissionState.hasSms,
                        onRequest = onRequestSms,
                        buttonLabel = "Grant SMS"
                    )

                    HorizontalDivider()

                    // Foreground Location Row
                    PermissionRow(
                        title = "Location (GPS & Satellites)",
                        description = "Fetches accurate device coordinates (even offline)",
                        isGranted = permissionState.hasLocation,
                        onRequest = onRequestLocation,
                        buttonLabel = "Grant GPS"
                    )

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        HorizontalDivider()

                        // Background Location Row (Critical for Android 10 - 15)
                        PermissionRow(
                            title = "Background GPS (Allow All the Time)",
                            description = "Required on Android 10-15 to fetch GPS when screen is off",
                            isGranted = permissionState.hasBackgroundLocation,
                            onRequest = onRequestBackgroundLocation,
                            buttonLabel = "Allow Always"
                        )
                    }

                    HorizontalDivider()

                    // Notifications Row
                    PermissionRow(
                        title = "Foreground Notification",
                        description = "Required on Android 13+ to run in background",
                        isGranted = permissionState.hasNotification,
                        onRequest = onRequestNotification,
                        buttonLabel = "Allow"
                    )

                    HorizontalDivider()

                    // Battery Optimization
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Battery Optimization", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            Text(
                                if (permissionState.isIgnoringBattery) "Unrestricted (Safe from system killers)" else "Optimized (System may sleep background SMS)",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (permissionState.isIgnoringBattery) Color(0xFF198754) else Color(0xFFB02A37)
                            )
                        }
                        FilledTonalButton(onClick = onOpenBatterySettings) {
                            Text("Configure")
                        }
                    }
                }
            }

            // TRIGGER CONFIGURATION
            Card(shape = RoundedCornerShape(16.dp)) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Trigger Keywords & Reply Format", fontWeight = FontWeight.Bold, fontSize = 15.sp)

                    OutlinedTextField(
                        value = triggerWord,
                        onValueChange = {
                            triggerWord = it
                            savedBannerText = ""
                        },
                        label = { Text("Trigger Keywords (comma-separated)") },
                        placeholder = { Text("#LOCATE, locate, stall") },
                        supportingText = { Text("Add one or more triggers separated by commas (with or without '#')") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = replyTemplate,
                        onValueChange = {
                            replyTemplate = it
                            savedBannerText = ""
                        },
                        label = { Text("Reply Message Format") },
                        supportingText = { Text("Variables: {maps_url}, {lat}, {lng}, {accuracy}, {battery}") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Include Device Battery % in SMS")
                        Checkbox(
                            checked = includeBattery,
                            onCheckedChange = {
                                includeBattery = it
                                savedBannerText = ""
                            }
                        )
                    }

                    HorizontalDivider()

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Restrict to Whitelist Numbers", fontWeight = FontWeight.SemiBold)
                            Text("If off, any phone sending a trigger receives location", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(
                            checked = whitelistOnly,
                            onCheckedChange = {
                                whitelistOnly = it
                                savedBannerText = ""
                            }
                        )
                    }

                    if (whitelistOnly) {
                        OutlinedTextField(
                            value = whitelistInput,
                            onValueChange = {
                                whitelistInput = it
                                savedBannerText = ""
                            },
                            label = { Text("Authorized Phone Numbers (comma-separated)") },
                            placeholder = { Text("+919876543210, 9876543210") },
                            supportingText = { Text("Matches 10-digit Indian numbers, +91, or international") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Button(
                        onClick = {
                            onSaveSettings(triggerWord, replyTemplate, includeBattery, whitelistOnly, whitelistInput)
                            savedBannerText = "Settings saved & active! Listening for: $triggerWord"
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF198754))
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Save & Apply Changes", fontWeight = FontWeight.Bold)
                    }

                    if (savedBannerText.isNotEmpty()) {
                        Text(
                            text = "✓ $savedBannerText",
                            color = Color(0xFF198754),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            // TEST SECTION
            Card(shape = RoundedCornerShape(16.dp)) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("Instant Hardware & SIM Diagnostics", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text(
                        "Test GPS satellite locking and SMS dispatch without needing a second phone:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Button(
                        onClick = onTestTriggerLocally,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Icon(Icons.Default.MyLocation, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("1. Test GPS Satellite Lock Locally")
                    }

                    if (lastReplyPreview.isNotEmpty()) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFE7F5FF)),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "Last Reply Output:\n$lastReplyPreview",
                                fontSize = 11.sp,
                                color = Color(0xFF0C5460),
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }

                    OutlinedTextField(
                        value = testPhoneInput,
                        onValueChange = { testPhoneInput = it },
                        label = { Text("Test Recipient Phone Number") },
                        placeholder = { Text("+91 98765 43210") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    FilledTonalButton(
                        onClick = { onSendTestSmsToSelf(testPhoneInput) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("2. Dispatch Live Test SMS Now")
                    }
                }
            }
        }
    }
}

@Composable
fun PermissionRow(
    title: String,
    description: String,
    isGranted: Boolean,
    onRequest: () -> Unit,
    buttonLabel: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                if (isGranted) {
                    Text("✓ Granted", color = Color(0xFF198754), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                } else {
                    Text("Missing", color = Color(0xFFB02A37), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (!isGranted) {
            FilledTonalButton(
                onClick = onRequest,
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(buttonLabel, fontSize = 12.sp)
            }
        } else {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = "Granted",
                tint = Color(0xFF198754),
                modifier = Modifier.size(24.dp)
            )
        }
    }
}
