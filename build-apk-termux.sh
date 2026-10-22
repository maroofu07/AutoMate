#!/data/data/com.termux/files/usr/bin/bash
# One-liner to build this APK directly on your Android phone using Termux!
pkg update -y
pkg install openjdk-17 gradle git -y
chmod +x ./gradlew
./gradlew assembleDebug
echo "APK compiled successfully! File located at:"
ls -lh app/build/outputs/apk/debug/*.apk
