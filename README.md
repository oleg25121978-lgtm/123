# Rokid Watch 4 Pro Cursor Test

Standalone Android application for Huawei Watch 4 Pro.
It advertises a custom BLE GATT peripheral compatible with the open-source **RokidKeyboard** receiver (service `6E400001-B5A3-F393-E0A9-77656B657962`, notification characteristic `6E400003-B5A3-F393-E0A9-77656B657962`).

**MVP**: start BLE, move pointer by dragging on the watch display, short tap = click, navigation back.

This is an experimental build. Huawei Watch 4 Pro's BLE peripheral support and its interoperability with Rokid Glasses have NOT been verified on the user's devices.

Receiver APK on glasses: https://github.com/mayacenote/RokidKeyboard/tree/main/dist

After installing the receiver you must enable its accessibility service according to the upstream README. Pairing the watch as an audio headset does not enable this BLE control link.

APK build: **Actions → Build Rokid Watch APK → latest successful run → Artifacts → Rokid-Watch4Pro-APK**.
