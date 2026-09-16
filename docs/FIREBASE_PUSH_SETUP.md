# Firebase Cloud Messaging setup

## Конфигурация приложения

* `applicationId` production APK: `net.qwdtt.client` (namespace `com.wdtt.client`).
* Версия FCM SDK: `com.google.firebase:firebase-messaging:24.1.0`.
* Google Services Gradle plugin: `4.4.2`; он подключается условно только при наличии `app/google-services.json`.
* Файл `google-services.json` скачивается из настроек Firebase Android-приложения и кладётся **только** в `app/google-services.json` (в CI — секретный файл, не коммитить и не включать в APK вручную).

### SHA-сертификаты

Firebase Console → Project settings → Your apps → Android → Add fingerprint:

* debug: SHA-1/SHA-256 debug keystore (`%USERPROFILE%\\.android\\debug.keystore`, alias `androiddebugkey`, стандартный пароль `android`);
* release: SHA-1/SHA-256 фактического keystore из `local.properties` (`KEYSTORE_FILE`, alias `KEY_ALIAS`). Получить отпечаток: `keytool -list -v -keystore <KEYSTORE_FILE> -alias <KEY_ALIAS>`.

В текущей среде debug keystore имеет SHA-256 `FB:A4:7E:78:77:14:AB:D8:67:1F:EA:2E:2D:0D:67:5A:B8:F2:48:22:98:57:21:A4:7C:19:9D:F8:6B:FF:C5:81` (SHA-1 `0E:82:50:C3:A3:89:44:9B:CA:BA:68:57:33:1C:9D:60:C7:AD:BA:64`).

Для текущего release keystore приложения (`E:\\Keys\\hoplet-release.keystore`, alias `hoplet`) SHA-256: `3D:27:3C:91:32:64:99:C2:98:C1:B6:55:E9:66:40:D7:05:6E:D9:6D:E2:48:9A:09:4F:C6:AB:63:82:87:F2:7C` (SHA-1: `51:25:FF:78:DB:F2:CE:3F:78:8D:5F:14:F0:0E:30:C8:5B:4B:B1:EB`). Добавьте эти отпечатки в Firebase. Если signing key меняется, SHA и Firebase Android app также должны быть обновлены.

1. Создайте Firebase project и включите Cloud Messaging API (FCM HTTP v1).
2. Зарегистрируйте Android application с package name `net.qwdtt.client`. SHA-1/SHA-256 сертификатов должны соответствовать release/debug keystore. Добавьте `google-services.json` в `app/` для production-сборки, если проект использует Firebase App ID.
3. Добавьте dependency `com.google.firebase:firebase-messaging` (она уже включена в `app/build.gradle.kts`). Не помещайте service-account JSON в APK.
4. В Google Cloud Console создайте service account с ролью Firebase Cloud Messaging API Admin и скачайте JSON в защищённый файл сервера с правами только для пользователя процесса.
5. Перед запуском Go-сервера задайте **одно** из значений:

   - `FCM_SERVICE_ACCOUNT_FILE=/etc/hoplet/firebase-service-account.json`
   - `FCM_SERVICE_ACCOUNT_JSON='{...}'` (предпочтительно secret manager, не shell history)

   JSON должен содержать `project_id`, `client_email` и `private_key`. Эти данные не попадают в `passwords.json`, APK или логи.

6. Проверьте firewall/egress к `oauth2.googleapis.com` и `fcm.googleapis.com` по HTTPS. Перезапуск сервера безопасен: registrations и reminder idempotency keys персистентны.

При старте сервер пишет безопасный диагноз `[PUSH] FCM configured=true|false`. При `false` VPN/tunnel продолжает запускаться, а push-операция возвращает configuration error; секреты не сохраняются в `passwords.json`, не попадают в APK и не выводятся в лог.

## Ручной physical FCM E2E

Зафиксируйте для каждого шага PASS/FAIL и время:

1. Fresh install APK с production `google-services.json`.
2. Первый запуск: объяснение и один системный запрос `POST_NOTIFICATIONS` (Android 13+).
3. Вход пользователя и регистрация FCM installation.
4. `GET /api/push/status` показывает active registration (токен не выводится).
5. Для production-рассылки используйте `📢 Отправить push всем` или `👤 Push пользователю`; результат показывается агрегированно.
6. Повторить push при foreground, background, screen-off, force-stop/cold start и после reboot.
7. Нажатие уведомления открывает указанную destination/deep link.
8. Уведомление присутствует в Notification Center; удалить его в Telegram и дождаться polling reconciliation.
9. Проверить reminder-сценарии 7/3/1 дней и expired на тестовых фикстурах, а затем одну реальную подписку.

До прохождения этого списка система не считается полностью подтверждённой физическим E2E.

## Troubleshooting

- `GET /api/push/status` пуст: подписка ещё не импортирована/не выбрана, endpoint/peer недоступен или Firebase не инициализирован в APK. Запрос требует Bearer password подписки и `device_id` в query.
- `FCM credentials are not configured`: задайте переменную окружения процесса и перезапустите сервер.
- `invalid`: token отозван, приложение переустановлено или Firebase вернул `UNREGISTERED`; registration будет отключена автоматически.
- Android 13+: разрешение `POST_NOTIFICATIONS` должно быть выдано в системном диалоге/настройках.
