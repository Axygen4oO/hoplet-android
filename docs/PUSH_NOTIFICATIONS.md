# Push notifications

## Архитектура

Серверная `Database.Notifications` остаётся source of truth. Notification Engine создаёт один `NotificationRecord`, после чего запись сохраняется в `passwords.json` и передаётся в FCM через `push_service.go`. Android получает реальный FCM push, показывает системное уведомление и с тем же `notification_id` объединяет его с `ServerNotificationStore`. Существующий polling `/api/notifications` не удалён: он выполняет reconciliation/fallback и удаляет локальные записи, удалённые администратором.

Поток:

`Telegram → Notification Engine → Database → FCM → Android system notification → tap/deep link → NotificationCenter`

Для автоматизации:

`PasswordEntry previous/current → transition detector → idempotency check → NotificationRecord → FCM → Android → bell/history`

Production-типы подписки: `SUBSCRIPTION_ACTIVATED`, `SUBSCRIPTION_RENEWED`,
`SUBSCRIPTION_LIMIT_CHANGED`, `SUBSCRIPTION_EXPIRING_7D`,
`SUBSCRIPTION_EXPIRING_3D`, `SUBSCRIPTION_EXPIRING_1D`,
`SUBSCRIPTION_EXPIRED`, `SUBSCRIPTION_BLOCKED`, `SUBSCRIPTION_RESTORED`.
Все они используют `hoplet://subscription`. Для административных операций
сравниваются короткоживущие снимки previous/current непосредственно из
`Database.Passwords`; отдельная модель подписки не создаётся, а GET/status
пути не запускают transition detector.

## Регистрация устройства

Android сохраняет FCM token до появления импортированной подписки. Когда одновременно готовы token, `connectionPassword` и server peer, приложение отправляет installation ID, системный `ANDROID_ID` и FCM token на `POST /api/push/register`. Bearer credential — существующий пароль подписки: сервер проверяет его в `Database.Passwords`, срок, блокировку и привязку/лимит устройства. Пользовательский login и JWT в Android не используются. Повторная регистрация пары `subscription + device` обновляет token и `last_seen_at`; смена подписки отключает старую пару. `POST /api/push/unregister`, `POST /api/push/preferences` и `GET /api/push/status` используют тот же subscription credential и device ID.

Регистрации хранятся компактно в `push_registrations` внутри существующего JSON-хранилища. Сохранение использует существующий atomic snapshot worker.

## Каналы Android и foreground

Создаются четыре стабильных канала: общие, безопасность, подписка и обновления. При foreground data message обрабатывается сервисом и показывается ровно одно системное уведомление; duplicate IDs подавляются локальным store. При background FCM доставляет системное уведомление, tap передаёт ID в `MainActivity` и открывает Notification Center.

## Subscription scheduler

Worker запускается вместе с Go-сервером, выполняется сразу после старта и затем каждые 30 минут. Он обходит source of truth `Database.Passwords`, проверяет напоминания за 7, 3 и 1 день, а также фактическое окончание. Ключ `subscription identity + type + expiry-date` хранится в `push_reminder_keys` и резервируется в одном сохранении с `NotificationRecord`, поэтому restart и повторный проход не создают дубль. При отсутствии регистрации устройства или FCM credentials запись всё равно сохраняется в history.

## API и production-диагностика

Клиентские endpoints push защищены действующим subscription credential; `MainPassword`, admin JWT и произвольный FCM token регистрацию устройства не авторизуют. Invalid/unregistered FCM tokens деактивируются, подписка при этом не удаляется. Результаты доставки агрегируются как `targeted/sent/failed/invalid/removed`; секреты и токены не пишутся в логи.

В Telegram доступны `📢 Отправить push всем`, `👤 Push пользователю` и `📱 Push-устройства` (активные/disabled/invalid, last_seen, короткий идентификатор, enable/disable). Мастер последовательно запрашивает тип, заголовок, текст, whitelist deep link и аудиторию (активные регистрации, все пользователи, активные/истекающие/истёкшие подписки), показывает preview с количеством получателей и после подтверждения выводит агрегированные `получатели/отправлено/ошибки/недействительные`. Callback payloads не содержат токены.

В Android Settings → Уведомления локально сохраняются общий переключатель и независимые категории «Обновления подписки» (активация, продление, лимит, блокировка, восстановление), «Напоминания о подписке» (7/3/1 день и окончание), «Обновления», «Безопасность», «Акции». При изменении выполняется синхронизация `POST /api/push/preferences`; FCM registration можно сохранить при выключенных уведомлениях. Критические security-уведомления остаются отдельной категорией и могут быть отключены только явным действием пользователя.

Безопасные production-события: `[PUSH] registration added/refreshed`, `send started/result`, `invalidated`, `reminder generated`, `duplicate suppressed`. В логах отсутствуют FCM token/FID, JWT, пароли и текст сообщений.

## Manual E2E

1. Настроить Firebase и credentials согласно `FIREBASE_PUSH_SETUP.md`.
2. Установить debug/release APK на физическое Android-устройство и импортировать `wdtt://...`; вход в аккаунт не нужен.
3. Дождаться безопасных диагностик `TOKEN_READY=true`, `SUBSCRIPTION_READY=true`, `REGISTER_RESPONSE=HTTP_200` и `REGISTER_SUCCESS`.
4. Создать уведомление через существующий Telegram workflow `/notify` или вызвать серверный engine.
5. Проверить foreground, background, screen-off и reboot; нажать push и убедиться, что открывается Notification Center.
6. Удалить запись через Telegram и дождаться polling reconciliation.
7. Отозвать token в Firebase и убедиться, что следующая отправка помечает registration invalid.
