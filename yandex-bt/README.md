# Yandex Navigator → Rokid HUD over Bluetooth (separate experimental build)

**Unmodified:** TEST9 Yandex Navigator, original Yandex apps, Rokid Maps, the existing \`yandex-hud/\` probe and Nexus.

## What is actually implemented
- Two separate Android APKs: \`phone\` and \`glasses\`.
- Paired-device RFCOMM Bluetooth SPP, with a private app UUID and versioned newline-delimited JSON packets once a second. Does **not** mirror a phone display or send screenshots.
- Phone-side notification listener restricted to \`ru.yandex.yandexnavi\` and \`ru.yandex.yandexmaps\`; also reads expanded Yandex notification RemoteViews text.
- Bounded icon extraction (<=128 px), local exact SHA-256 arrow calibration while stationary. Unknown icons are never guessed.
- Optional phone GPS speed readout; speed limit only if explicitly given by Yandex notification, otherwise blank/unknown.
- A separate 30-second test packet, **not presented as real navigation**.
- Rokid custom portrait HUD: turn + distance in upper corners, speed / speed limit in bottom corners. Empty middle view.
- Auto-reconnect on glasses while HUD app remains open. Bluetooth service on phone stays in foreground until stopped.

## Known limits — not a completed Yandex integration
- Unverified with the real Yandex Navigator/Maps notifications on user hardware and physical glasses. The remote view may be inaccessible, or omit live navigation state entirely; then only diagnostics and demo traffic work. Arrow calibration helps only for visible image samples and is not an API for the route.
- Lane guidance and miniprojection are **not** available through this parser; no fabricated values.
- The 35s update TTL is deliberately conservative; if real notifications are less frequent a route may disappear.
- Phone's Android 12 is compatible (min API 31); glasses also require API 31 or later for this initial build.
- Does not use Android Auto, NaviKit, Yandex Maps API or unofficial route calculations.

## Build
GitHub Actions: workflow \`Build isolated Yandex BT HUD\` on the \`feature/yandex-phone-bluetooth-hud\` branch.
Or Android Studio / JDK 17 / Android SDK 35:
\`\`\`sh
cd yandex-bt
gradle --no-daemon :phone:assembleDebug :glasses:assembleDebug :phone:testDebugUnitTest
\`\`\`
Outputs:
- \`phone/build/outputs/apk/debug/phone-debug.apk\`
- \`glasses/build/outputs/apk/debug/glasses-debug.apk\`

## Test safely while stationary
1. Pair phone and Rokid glasses in their regular Bluetooth settings. Keep normal Rokid/Hi Rokid pairing as needed.
2. Install phone APK on Android 12; allow Bluetooth and optionally GPS, then notification access.
3. Press **Начать передачу на очки** on phone.
4. Install glasses APK, open it, select paired **phone**, keep it open.
5. On phone press **ТЕСТ: показать 200 м →**; confirm on glasses arrow, distance, speed and limit. These are simulated values.
6. Start a real route in Yandex Navigator or Yandex Maps. On phone inspect \`Данные уведомления\`, expanded-view image previews and actual distance. If the image is a turn arrow, label its direction **while stationary**; never label a logo or a lane pictogram. Check again on every turn. If fields are missing, they cannot be guessed.
7. Do not rely on unvalidated guidance while driving; verify when stationary.

Long-press the glasses HUD to choose a different paired phone.
