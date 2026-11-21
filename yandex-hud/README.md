# Yandex HUD — isolated probe (0.1.0)

Independent experimental Android 12 phone application. It **does not modify** any installed Rokid app, Yandex Maps or the working watch/bridge software.

## Current behavior
- User explicitly grants Android notification listener access.
- Observes notifications from `ru.yandex.yandexmaps` and `ru.yandex.yandexnavi`.
- Extracts available text, provisional maneuver type and distance.
- Displays diagnostic values on the phone. No mirror/capture is used.

## Not yet implemented
- Rokid Nexus bus/plugin registration, HUD output to glasses.
- Image-based Yandex maneuver arrows or recommended lanes.
- Stable behavior with the phone locked or across specific Yandex app releases.
- APK compilation/device testing.

## Build
Open `yandex-hud` in Android Studio (JDK 17, Android SDK 35). Run `gradle assembleDebug` using the local Gradle installation (wrapper is not included).
Run on the phone, grant notification access, then start turn-by-turn guidance in Yandex Maps. The displayed source text determines which maneuver formats can be decoded safely.

## Safety
This is a diagnostic prototype. Notifications are processed in memory only and are not sent over the network or saved. Do not rely on it while driving. Never infer turn instructions from unknown glyphs.
