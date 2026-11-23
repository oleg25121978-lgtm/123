# Яндекс Карты 31.1.5 → Rokid HUD: исправления фонового режима, полос и мини-карты

Дата: 23.11.2026. Источник: пользовательский модифицированный APK `yandex_maps_31.1.5_mod(1).apk`; **рабочий Test9 не менялся**.

## Реально внесённые изменения в исходники

1. `MapsHudKeepAliveService`: foreground-service по схеме Test9, тип `connectedDevice`; требуется внесение service и двух разрешений в бинарный AndroidManifest конечного APK.
2. `MapsHudProvider`: при `Activity.onPause()` больше не сбрасываются маршрут и Bluetooth-соединение. Последний настоящий кадр хранится не более **15 секунд**, если источники временно пропали. В фоновой работе запрашивается нативное уведомление Яндекс Карт; если в нём *действительно* указаны манёвр/расстояние, данные отправляются на очки без придумывания манёвров. Если Яндекс не обновляет ни UI, ни уведомление, дальнейшее ведение не гарантировано.
3. `MapsHudLanes`: ищет **настоящие** виджеты полос в `ContextManeuverView` и на экране ведения, рисует отдельный нижний блок. Без нативных данных ничего не подменяется.
4. `MapsHudMiniMap`: захватывает **только карту** MapKit из `SurfaceView` через `PixelCopy` или из `TextureView`, с семикратным уменьшением дорожных линий и маркера за счёт ресэмплирования содержимого. На некоторых версиях MapKit захват может быть недоступен. Последний настоящий снимок карты сохраняется не более 30 секунд.
5. Отрисовка стрелки без её исходного непрозрачного фона и в меньшем размере сохраняется от прежней ветки.

## Проверки

- GitHub Actions: `yandex-bt/maps-relay:assembleDebug` и `maps-glasses:assembleDebug` завершились успешно.
- `repack_maps31.py` в тесте пересобрал компилятор APK без перестройки ресурсной таблицы, сохранил исходные файлы, добавил DEX + service + provider + разрешения. Новый AndroidManifest проверен **встроенным Android `aapt`**, а не только нашим парсером.
- Проверена упаковка всех зависимых Java-классов: `MapsHudProvider`, `MapsHudKeepAliveService`, `MapsHudMiniMap`, `MapsHudLanes`, `MapsHudNotificationSource`.

## Ограничения и честный статус

**Полный телефонный APK пользователя из этих исходников ещё НЕ сформирован**, так как текущая среда обработки загруженных файлов недоступна. В GitHub Actions доступен компилятор модуля, но не сам пользовательский APK. Не выдавать компилятор как готовое приложение Яндекс Карт.

Нет проверки на реальном Mate X3, Rokid или маршруте, что PixelCopy действительно видит карту, Яндекс выводит разметку полос отдельным Android View или что навигационное уведомление содержит все данные в фоне.

**Готовое APK нужно получать только из оригинального пользовательского модифицированного APK** и только после полной проверки Android подписи `apksigner`. Нельзя заменить базу на другой APK или урезать архитектуры.

### Техническая сборка конечного APK

```bash
python3 yandex-maps-v31/repack_maps31.py \
  'yandex_maps_31.1.5_mod(1).apk' \
  'maps-relay-debug.apk' \
  'Yandex_Maps_31.1.5_Rokid_HUD_UNSIGNED.apk'
zipalign -f -p 4 'Yandex_Maps_31.1.5_Rokid_HUD_UNSIGNED.apk' 'Yandex_Maps_31.1.5_Rokid_HUD_ALIGNED.apk'
apksigner sign --ks your-release-key.jks --out 'Yandex_Maps_31.1.5_Rokid_HUD.apk' 'Yandex_Maps_31.1.5_Rokid_HUD_ALIGNED.apk'
apksigner verify --verbose 'Yandex_Maps_31.1.5_Rokid_HUD.apk'
```

Подпись может не совпасть с уже установленной модифицированной версией. Не удалять исходные Карты без отдельного предупреждения о потере локальных данных.

## Дополнительные испытания — Android emulator API 32, 23.11.2026

- Убрано бесконечное повторение замороженного фонового кадра: повторная PNG-компрессия не считается новой навигацией. Предыдущий кадр сохраняется не более 15 секунд без обновлений.
- После завершения маршрута или исчезновения окна и устаревания данных фоновая служба прекращает работу.
- Уведомление «Маршрут 20 км» либо «Через 5 минут» больше не выдаётся за расстояние до следующего поворота.
- Захват MapKit SurfaceView разрешён и в фоне, но только если его GPU-поверхность остаётся валидной; на реальной версии Яндекс Карт это ещё не подтверждено.
- PixelCopy используется на Android API 26+, Bluetooth и уведомления имеют совместимый код для более старых версий Android; исходные ABI не удаляются.

**Результат GitHub Actions run 45228826581: SUCCESS**

1. Проверен настоящий метод Canvas-отрисовки HUD: PASS 480x640. Данные на маршруте тестовые.
2. Android PixelCopy реально скопировал GPU-картинку SurfaceView в 125×111: PASS PixelCopy SurfaceView 125x111, distinct colors=8. Карта тестовая.
3. Приватная foreground-служба, запущенная самим приложением, осталась активной после HOME: PASS Maps31 keepalive service survives HOME.
4. GitHub Actions run 45229042176: SUCCESS — сборка Android-модуля после правок совместимости.

**Что не проверено:** не испытан реальный маршрут Яндекс Карт на телефоне/Rokid; нельзя утверждать, что реальные полосы и MapKit GPU карта доступна в этом APK. Полный телефонный APK из загруженного пользователем yandex_maps_31.1.5_mod(1).apk ещё не получен из-за недоступной контейнерной среды. TEST9 оставлен без изменений.


## macOS Intel (23.11.2026)

- GitHub Actions `Maps31 full packaging on macOS Intel`, run `45260731577`: **SUCCESS**, compiled Maps31 relay and Rokid receiver on actual `macos-15-intel` runner, verified receiver signature using Android `apksigner`.
- Direct signed Rokid receiver APK: `yandex-maps-v31/test-builds/Maps31_Rokid_Receiver_macOS_Intel.apk` (commit from publisher `45261225942`).
- **Phone APK has not been produced on macOS.** The requested `yandex_maps_31.1.5_mod(1).apk` resides only among ChatGPT conversation/Library files. GitHub-hosted macOS runners have no access to those private file bytes. No `MAPS31_SOURCE_APK_URL` provided in Actions repository secrets/dispatch, so `Merge HUD into user APK` was correctly **skipped**. Do NOT describe receiver or compiler as a full phone APK.
- macOS workflow `.github/workflows/maps31-release-macos.yml` will verify the complete source APK, preserve native libraries/assets/res, add HUD DEX and manifest declaration, align, sign and verify it *only after the exact user mod APK is available to the runner.* No x86/ABI trimming.
- A newly built receiver is Gradle debug-signed; its signature can differ from earlier builds, so replacement may require uninstalling the older receiver, without affecting the working Test9 (different Android package).
