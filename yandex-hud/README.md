# Yandex HUD for Rokid Nexus — experimental Android 12 plugin

**Separate development branch.** No modification of original Yandex Maps, Nexus, Rokid's built-in navigator, or installed watch applications.

## Implemented in source
- Phone-side `NotificationListenerService` for ongoing navigation notifications from Yandex Maps / Yandex Navigator.
- Extract distance and any plain text hints. Expand RemoteViews, inspect image nodes and render drawable samples in memory (max 128 px).
- SHA-256 exact-image calibration: manually tag a sample **while stationary**. An unrecognized image is never guessed.
- Separate Nexus SDK 0.27.0 plugin `yandexhud`: a launcher page plus native guidance activity using the provided distance and labeled maneuver.
- Native glyph and distance, not mirroring the phone display. SDK handles delivery to Rokid.
- No notifications or images uploaded anywhere. Labels are stored locally.

## Limitations / not verified
- APK **has not passed an observable CI build** or been tested on user devices.
- Exact image fingerprints can change when the Yandex app or theme changes.
- First visible image might be a logo, not an arrow: verify before tagging.
- The `street` field and lane guidance are not extracted; never fabricate a lane.
- Navigation notifications can disappear or be incomplete when a phone is locked. If data is missing, no reliable HUD can be produced.
- Nexus **phone hub and glasses hub** compatible with SDK 0.27.0 must be installed and configured separately. This app does not install Nexus itself.
- Initial guidance activity needs the plugin approved by the Nexus hub; automatic registration and lifetime during background navigation remain to be verified.

## Build
In GitHub Actions select **Build isolated Yandex HUD probe** for branch `feature/yandex-hud` and run workflow manually, or open `yandex-hud/` in Android Studio with JDK 17 and SDK Platform 36:

```sh
gradle --no-daemon assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`.

## Testing (off-road)
1. Install built APK on phone. Open Yandex HUD Probe, grant notification access.
2. Start navigation in Yandex Maps; check whether the diagnostic page shows a distance and actual image.
3. **Only after confirming the image is the actual maneuver arrow**, label it. Never label unknown or logo images.
4. With compatible Nexus phone and glasses hubs running, enable the independent **Yandex HUD** plugin from Nexus phone hub and examine its navigation activity.
5. Validate every turn and stale-notification behavior against the live phone UI. Do not use unverified HUD guidance while driving.

### Original documentation
- Rokid Nexus: `plugins/nav/README.md` and `docs/PLUGIN_SDK.md` in Anezium/Rokid-Nexus.
- VoltFlow Nav: `docs/README.en.md` in scroodge/VoltFlow-Nav.
