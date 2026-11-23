# Исследование пользовательского APK Яндекс Карт 31.1.5 (RuStore)

Дата: 23 ноября 2026. Исходный файл: `Yandex_Maps_31.1.5_RuStore_Original.apk` (получен от пользователя, **не публиковать APK в репозитории**).

## Файл и подлинность
- Размер: **320 661 625 байт** (306 MiB).
- SHA-256: `d853b06f66b1503d1cedb17a979dc469bfc873c919e99822c3deef46cb2d84fc`.
- Манифест содержит `ru.yandex.yandexmaps` и `31.1.5`.
- 16 DEX-файлов, 66 встроенных `.so` (поддерживаются разные CPU/ABI); не split XAPK.
- Встроенный сертификат `META-INF/PRIVATE_.DSA`:
  `C=RU, ST=Moscow, L=Moscow, O=OOO Yandex, OU=Mobile Development, CN=OOO Yandex`.
- SHA-256 fingerprint сертификата: `AC:A4:05:DE:D8:B2:5C:B2:E8:C6:DA:69:42:5D:2B:43:07:D0:87:C1:27:6F:C0:6A:D5:94:27:31:CC:C5:1D:BA`.
- `jarsigner -verify` вернул код 0, но с предупреждениями о JarFile/JarInputStream; это **не полная проверка Android v2/v3 подписи**.

## Найденные реальные классы и методы в DEX
Символы извлечены из таблиц DEX class/type/method IDs; это доказательство присутствия в APK, **не доказательство того, что они вызываются именно при активном маршруте**.

| Необходимые данные | Точка для исследования в APK |
| --- | --- |
| Состояние сессии/маршрут | `com.yandex.mapkit.navigation.automotive.Guidance.getCurrentRoute()`, `getRouteStatus()`, `GuidanceListener.onCurrentRouteChanged()` |
| Следующий манёвр/полосы | `Guidance.getWindshield()`, `Windshield.getManoeuvres()`, `getLaneSigns()`, `getNearestLanes()`; `WindshieldListener.onManoeuvresChanged()`, `onLaneSignChanged()` |
| Скорость/ограничение | `Guidance.getLocation()`, `getSpeedLimit()`, `getSpeedLimitStatus()`; `ru.yandex.yandexnavi.ui.guidance.speed.SpeedViewImpl.setSpeed()` |
| **Подтверждённое ETA Яндекса** | `com.yandex.navikit.ui.guidance.background_guidance_panel.EtaInfo.getArrivalTime()`, `getDistanceLeft()`, `getTimeLeft()`; `BackgroundGuidancePanelEtaData.getEtaInfo()`; `ru.yandex.yandexnavi.ui.guidance.eta.ContextEtaView.setArrivalTime()` |
| Нативное UI-ведение | `ru.yandex.yandexnavi.ui.guidance.maneuver.ContextManeuverView` (`setDistanceAndUnit`, `setManeuver`, `setLaneItems`) |

Предыдущий рабочий TEST9 использовал `ru.yandex.yandexnavi.rokidhud.HudProvider$NavigationView`. **Не копировать этот внутренний класс в Карты 31.1.5 как готовую интеграцию**. Здесь уже обнаружен штатный слой NaviKit с отдельными объектами Guidance/Windshield/EtaInfo.

## Следующее действие

1. Точно определить в DEX реальные **места вызова и время жизни** `Guidance`, `Windshield`, `EtaInfo` в собственном потоке ведения Яндекс Карт, и где доступна их актуальная сессия.
2. Сделать read-only перехват/подключение, только после запуска маршрута. Никогда не синтезировать ETA и не затрагивать выбор маршрута.
3. Передавать реальные поля существующему приёмнику Rokid в отдельном режиме, без зеркалирования и без изменения TEST9.
4. Проверить установку/подпись и работу на Mate X3 + Rokid. Файлы пользовательского APK не коммитить в публичный репозиторий.

**Статус:** оригинальный APK реально исследован; HUD для Карт 31.1.5 ещё **не собран и не проверен**.
