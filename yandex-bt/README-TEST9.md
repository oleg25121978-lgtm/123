# TEST9 on phone → only its original HUD on Rokid

Purpose: original TEST9 Yandex Navigator app (14.8.4) navigates on Android 12 phone with its normal map. Only the already working custom TEST9 \`HudProvider$NavigationView\` is rendered onto an offscreen bitmap and streamed to glasses over paired Bluetooth Classic SPP. Not a rerouting or notification-based replica.

Implementation: \`relay/src/main/java/.../HudRelayProvider.java\` replaces only the TEST9 content provider manifest entry, delegates startup and content-provider operations to the original \`HudProvider\` (kept unchanged in \`classes9.dex\`). On the resumed navigation Activity it detects the existing NavigationView, moves that view off-screen with translationX (keeping its route inputs active), redraws only the view to a 480x640 bitmap, and streams bounded PNG frames over RFCOMM to \`glasses/\`.

**Important:** Requires APK patch packaging (binary AndroidManifest provider class + BLUETOOTH_CONNECT permission + \`classes10.dex\` from relay build + APK v2 signing). A compiled \`relay\` APK is only a build intermediate and MUST NOT be installed as the navigator. Exact on-phone Yandex interface, telemetry and in-motion behavior are unverified until device test. This is an experimental same-process method: if Yandex navigation recreates the view or changes its accessibility/layout, test and adjust.

The receiver uses a framed binary protocol: int magic 0x48554431 ("HUD1"); int length 0..300000; PNG bytes, repeated ~0.85 sec. Zero length means idle. No route recreation, no phone-screen capture, no Android Auto.

The unchanged Test9 ZIP/APK are retained as recovery artifacts. Please test on stationary devices. Package name remains original Yandex Navigator; an installed version signed with a different certificate must be uninstalled before replacing.
