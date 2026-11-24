# Roamer BMS — Native Android APK Project (No ESP32 Required)

This Android Studio project connects directly from your Android phone to two Roamer / JK-BMS batteries over Bluetooth Low Energy (`FFE0` / `FFE1`).

## Option 1: Build APK with Android Studio (Easiest)
1. Install **Android Studio** (Ladybug or newer).
2. Open this folder (`File -> Open`).
3. Wait for Gradle sync to finish.
4. Click **Build -> Build Bundle(s) / APK(s) -> Build APK(s)**.
5. Copy `app/build/outputs/apk/debug/app-debug.apk` to your phone and install it.

## Option 2: Build APK in the Cloud with GitHub Actions (No Local Tools)
1. Push this folder to a new GitHub repository.
2. Open the **Actions** tab on GitHub.
3. Click **Build Roamer BMS Android APK** -> download the `roamer-bms-debug-apk` artifact.

## Option 3: Command Line (Gradle 8.9 + JDK 17 + Android SDK 35)
```bash
gradle assembleDebug
```
