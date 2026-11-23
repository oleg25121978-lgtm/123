# Maps 31.1.5 → Rokid HUD: первая диагностическая сборка

## Проверенная основа
- Только оригинальный пользовательский `Yandex_Maps_31.1.5_RuStore_Original.apk`; пакет `ru.yandex.yandexmaps`.
- **Не менять Navigator Test9**, отдельная GitHub-ветка и модуль `yandex-bt/maps-relay`.
- Приёмник `yandex-bt/maps-glasses` имеет **другое имя пакета** и **отдельный Bluetooth SPP UUID**: `6c8c5612-483f-4d21-94e9-0315d3d9e615`.

## Подтверждённые вызовы в DEX
В оригинальной сборке найден обработчик `Lqk5;.onUpdated(BackgroundGuidancePanelData,Z)`, который вызывает `BackgroundGuidancePanelEtaData.getEtaInfo()`. Обработчик `BaseGuidanceNotificationViewBuilder.setEtaInfo(...)` вызывает `EtaInfo.getArrivalTime()`; это факт из байткода. Объекты `ContextEtaView`, `ContextManeuverView`, `SpeedViewImpl` присутствуют в DEX и имеют `setArrivalTime`, `setDistanceAndUnit`, `setSpeed`.

## Что реализовано (пока эксперимент)
- Добавляется независимый `ContentProvider` в оригинальное приложение с новым `classes17.dex`. Он регистрирует `ActivityLifecycleCallbacks`, но **не меняет** оригинальные методы, код расчёта маршрута и экран настройки.
- После появления **видимого** `ContextManeuverView` в иерархии текущего Activity считываются только штатные виджеты: `ContextEtaView`, `SpeedViewImpl` и `ContextManeuverView`. Манёвр изображается нативной отрисовкой конкретного виджета, не зеркалированием карты.
- ETA передаётся только если в штатном `ContextEtaView` действительно найдено время. Если приложение не показывает ETA или структура отличается, значение отсутствует.
- Bluetooth Classic RFCOMM передаёт отдельному приёмнику Rokid 480×640 PNG с прозрачной верхней частью и нижним HUD. Вывод времени/заряда/Wi-Fi/Bluetooth очков выполняет сам приёмник.
- **Нет** второго маршрутизатора, подмены Яндекс Навигатора, синтетических ETA, скорости или геометрии мини-карты.

## Что пока неизвестно
- В реальном приложении виджеты могут иметь другую видимость и иерархию; сборка должна пройти испытание на Mate X3.
- Фоновая передача не гарантирована, нет независимого NavKit-подписчика.
- Мини-карта по геометрии маршрута и полное совмещение с камерой/звонками остаются отдельными задачами. Не выдавать тестовую сборку за финальную.

## Важное при установке
Подпись пересобранного Яндекс Карт APK **не оригинальная**. Он не установится поверх подлинных Яндекс Карт без удаления оригинала, что может удалить локальные данные / загруженные карты. Тестировать только с пониманием этого ограничения. Телефонный APK 320+ МБ **не устанавливать на очки**; на очки ставится отдельный маленький APK приёмника.

Пока нет подтверждения из реального запуска/эмулятора, что устройство успешно показывает HUD. Подпись и сохранность бинарных библиотек проверяются при сборке.

## TEST9 transport baseline — 23.11.2026

User explicitly requires carrying forward the already confirmed working Test9 transport, not replacing navigation or optimizing APK size.

- Git commit `929e15d8626213f694e03a6d842cd9299fabd5b0`: copied TEST9's 3-frame route gating (`routeArmed`), 850 ms interval and existing RFCOMM packet framing. Both bridge and receiver use independent UUID `6c8c5612-483f-4d21-94e9-0315d3d9e615`.
- Source app: user's `yandex_maps_31.1.5_mod(1).apk`. In new output all 17 original DEX files and 44 native libraries retained, all original content entries unmodified except binary manifest and old signature entries. New module is classes18.dex.
- Phone output `Yandex_Maps_31.1.5_USER_MOD_Rokid_HUD_TEST9_BASE.apk`: 225792593 bytes, SHA-256 `c8ef7f65625a22fe9de627b369d4305816680e0728decd0a85ba626e76a0846f`.
- Signature: Android APK Signature Scheme v2 verified including signed content digest. **This is a fresh signer; updating over other independently signed Yandex Maps APKs generally fails.**
- Runtime **not tested on Mate X3 or Rokid**. Actual Maps navigation widgets may require additional integration: TEST9's `HudProvider$NavigationView` does NOT exist in Maps 31.1.5. Do not falsely call this a completed or working port.
- Glasses receiver built separately, uses cloned TEST9 architecture with real local glasses battery/Wi-Fi/Bluetooth/time, no phone screen mirroring.
- Required next validation: installation/first launch and a route started by "Поехали"; check live native maneuver visible on phone/eyes and BT connection. Then inspect logs for `YMaps31HUDProbe` if missing.
