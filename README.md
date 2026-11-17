# LocationReply - Android SMS Location Auto-Responder

An automated Android application (Android 7 through Android 15) that continuously monitors for incoming SMS triggers (e.g. `#LOCATE`) and automatically replies on the matching SIM with current high-precision GPS coordinates, a direct Google Maps link, and device battery statistics. Works 100% offline using direct hardware GPS satellites + persistent coordinate caching even if mobile data and Bluetooth are turned off.

---

## ⚠️ Important Installation Guide (Play Protect & Android 11–15)

### 1. Uninstall any previous Debug APK first!
If you previously installed `app-debug.apk` or an older build signed with the generic Android Debug key, **uninstall it first** before installing `app-release.apk` (Android blocks overwriting an app signed with a different certificate).

### 2. Google Play Protect & "Restricted Settings" on Sideloaded APKs:
- Apps signed with your own self-signed certificate and installed directly outside Google Play will show an "Unknown developer / Unrecognized app" prompt during sideloading because `RECEIVE_SMS`, `SEND_SMS`, and `ACCESS_BACKGROUND_LOCATION` are high-privilege permissions.
- This release build removes unused `READ_SMS`, removes suspicious `priority="999"` receiver flags, disables `isDebuggable`, and uses a dedicated release keystore so it does not trip debug-malware heuristics.
- **To install:** Tap **"More details"** -> Tap **"Install anyway"**.

### 3. Android 13, 14 & 15 "Restricted Settings" + Background GPS:
On Android 13–15, sideloaded APKs require two quick one-time approvals so they work when the screen is off:
1. Open phone **Settings > Apps > LocationReply**.
2. Tap the **3 DOTS (⋮)** in the top right corner -> Tap **"Allow restricted settings"** and verify with your fingerprint/PIN.
3. Open **LocationReply** and grant:
   - **SMS (Receive & Send)**
   - **Location (GPS & Satellites)**
   - **Background GPS -> Select "Allow all the time"** *(critical on Android 10–15 / Samsung A21 so GPS works when the phone is locked!)*
   - **Foreground Notification** & **Battery -> Unrestricted**

### 4. Samsung One UI / Xiaomi / Poco / Vivo / Oppo / Realme Fix:
- **Samsung:** Settings > Battery > Background usage limits > **Never sleeping apps** > Add **LocationReply** + App Info > Battery > **Unrestricted**.
- **Xiaomi / Redmi / Poco:** App Info > Enable **Autostart** > Battery saver: **No restrictions**.
- **Dual-SIM Phones:** The app automatically replies using the exact SIM card that received the incoming trigger SMS.

---

## 🚀 How to Build the Signed Release APK File

### Method 1: Automated Free Cloud Build (GitHub Actions)
1. Download the project **ZIP** from the exporter.
2. Push or upload the folder to your GitHub repository.
3. The included `.github/workflows/build-apk.yml` generates a dedicated release keystore and compiles `app-release.apk` in ~2 minutes.
4. Go to the **Actions** tab on GitHub -> Click the latest build -> Download **location-reply-release-apk**!

### Method 2: Android Studio (Mac / Windows / Linux)
1. Open **Android Studio** -> Select **File > Open** -> Select this extracted folder.
2. Go to top menu: **Build > Build Bundle(s) / APK(s) > Build APK(s)** (or **Generate Signed App Bundle / APK** for a custom release keystore).

### Method 3: Command Line (Linux / macOS)
```bash
chmod +x ./gradlew
./gradlew assembleRelease
```

### Method 4: Command Line (Windows PowerShell / CMD)
```cmd
gradlew.bat assembleRelease
```
The APK will be at:
`app/build/outputs/apk/release/app-release.apk`

---

## 🛠️ Gradle Wrapper & Compatibility Matrix
- **Gradle Version**: 8.9 (distribution: `gradle-8.9-bin.zip`)
- **Android Gradle Plugin (AGP)**: 8.7.2
- **Kotlin**: 2.0.21 (Compose Compiler 2.0.21)
- **JDK Compatibility**: Java 17 (Temurin recommended)
- **Wrapper Files Included**: `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar` (genuine binary, SHA-256: `498495120a03b9a6ab5d155f5de3c8f0d986a449153702fb80fc80e134484f17`), and `gradle-wrapper.properties`.

---

## 🔒 Configuration Summary
- **Trigger Keywords**: `#LOCATE` (supports comma-separated keywords, matches with or without '#' prefix)
- **Whitelist Security**: Disabled (responds to any sender with the secret keyword)
- **Template**: `Current location: {maps_url}`
