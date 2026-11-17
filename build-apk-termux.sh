#!/data/data/com.termux/files/usr/bin/bash
# One-liner to build a signed Release APK directly on your Android phone using Termux!
pkg update -y
pkg install openjdk-17 gradle git -y
if [ ! -f app/release.keystore ]; then
  keytool -genkeypair -v -keystore app/release.keystore -alias locationreply -keyalg RSA -keysize 2048 -validity 10000 -storepass locationreply123 -keypass locationreply123 -dname "CN=LocationReply, OU=Mobile, O=app.locationreply.mobile, C=IN"
fi
chmod +x ./gradlew
./gradlew assembleRelease
echo "Release APK compiled successfully! File located at:"
ls -lh app/build/outputs/apk/release/*.apk
