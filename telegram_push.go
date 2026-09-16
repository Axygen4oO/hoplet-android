package main

import (
	"errors"
	"fmt"
	"strings"
	"sync"
	"time"
)

const (
	pushStageType     = "push_type"
	pushStageTitle    = "push_title"
	pushStageMessage  = "push_message"
	pushStageDeepLink = "push_deep_link"
	pushStageAudience = "push_audience"
	pushStageTarget   = "push_target"
	pushStageDevice   = "push_device"
	pushStageConfirm  = "push_confirm"
)

const (
	pushAudienceActive        = "active"
	pushAudienceAll           = "all"
	pushAudienceOne           = "one"
	pushAudienceSubscriptions = "subscriptions"
	pushAudienceExpiring      = "expiring"
	pushAudienceExpired       = "expired"
)

const (
	pushModeAudience = "audience"
	pushModeUser     = "user"
	maxPushTitle     = 200
	maxPushMessage   = 4000
)

var pushComposeMu sync.Mutex

func resetPushComposeState() {
	pushComposeMu.Lock()
	defer pushComposeMu.Unlock()
	resetPushComposeStateLocked()
}

func resetPushComposeStateLocked() {
	tgState.PushStage, tgState.PushType, tgState.PushTitle = "", "", ""
	tgState.PushMessage, tgState.PushDeepLink, tgState.PushAudience = "", "", ""
	tgState.PushTargetEmail, tgState.PushPreview = "", ""
	tgState.PushMode, tgState.PushTargetIdentity = "", ""
	tgState.PushTargetDevices = nil
	tgState.PushSending = false
}

func startPushWizard(token string, adminID int64) {
	resetNotificationComposeState()
	resetPushComposeState()
	tgState.PushMode = pushModeAudience
	tgState.PushStage = pushStageType
	sendPushType(token, adminID)
}

func startPushUserWizard(token string, adminID int64) {
	resetNotificationComposeState()
	resetPushComposeState()
	tgState.PushMode = pushModeUser
	tgState.PushStage = pushStageTarget
	sendTelegramPlain(token, adminID, "👤 Введите email или идентификатор подписки пользователя:", pushCancelMarkup())
}

func hasActivePushCompose() bool { return tgState.PushStage != "" }

func pushCancelMarkup() map[string]interface{} {
	return map[string]interface{}{"inline_keyboard": [][]map[string]interface{}{{{"text": "❌ Отмена", "callback_data": "push_cancel"}}}}
}

func pushBackMarkup(callback string) map[string]interface{} {
	return map[string]interface{}{"inline_keyboard": [][]map[string]interface{}{{{"text": "◀️ Назад", "callback_data": callback}, {"text": "❌ Отмена", "callback_data": "push_cancel"}}}}
}

func sendPushType(token string, adminID int64) {
	sendTelegramPlain(token, adminID, "📢 Тип push-уведомления:", map[string]interface{}{"inline_keyboard": [][]map[string]interface{}{
		{{"text": "🔔 Общее", "callback_data": "push_t_SYSTEM"}},
		{{"text": "🔐 Безопасность", "callback_data": "push_t_SECURITY"}},
		{{"text": "📅 Подписка", "callback_data": "push_t_SUBSCRIPTION"}},
		{{"text": "🆕 Обновление", "callback_data": "push_t_UPDATE"}},
		{{"text": "🎁 Акция", "callback_data": "push_t_PROMOTION"}},
		{{"text": "❌ Отмена", "callback_data": "push_cancel"}},
	}})
}

func pushDeepLinkAllowed(link string) bool {
	link = strings.ToLower(strings.TrimSpace(strings.TrimPrefix(link, "hoplet://")))
	if i := strings.IndexByte(link, '/'); i >= 0 {
		link = link[:i]
	}
	switch link {
	case "", "none", "notifications", "subscription", "support", "updates":
		return true
	default:
		return false
	}
}

func pushDeepLinkNormalize(link string) string {
	link = strings.ToLower(strings.TrimSpace(strings.TrimPrefix(link, "hoplet://")))
	if !pushDeepLinkAllowed(link) || link == "" {
		return "notifications"
	}
	return link
}

func pushDeepLinkLabel(link string) string {
	if link == "none" || link == "" {
		return "Без перехода"
	}
	switch link {
	case "notifications":
		return "Уведомления"
	case "subscription":
		return "Подписка"
	case "support":
		return "Поддержка"
	case "updates":
		return "Обновления"
	default:
		return "Без перехода"
	}
}

func pushDeepLinkMarkup() map[string]interface{} {
	return map[string]interface{}{"inline_keyboard": [][]map[string]interface{}{
		{{"text": "Без перехода", "callback_data": "push_l_none"}},
		{{"text": "Уведомления", "callback_data": "push_l_notifications"}},
		{{"text": "Подписка", "callback_data": "push_l_subscription"}},
		{{"text": "Поддержка", "callback_data": "push_l_support"}},
		{{"text": "Обновления", "callback_data": "push_l_updates"}},
		{{"text": "◀️ Назад", "callback_data": "push_back_message"}, {"text": "❌ Отмена", "callback_data": "push_cancel"}},
	}}
}

func handlePushInput(token string, adminID int64, text string) bool {
	value := strings.TrimSpace(normalizeTelegramText(text))
	switch tgState.PushStage {
	case pushStageTarget:
		if tgState.PushMode != pushModeUser {
			return false
		}
		identity, email, ok := resolvePushUser(value)
		if !ok {
			sendTelegramPlain(token, adminID, "Пользователь не найден. Введите email или идентификатор подписки.", pushCancelMarkup())
			return true
		}
		tgState.PushTargetIdentity, tgState.PushTargetEmail = identity, email
		tgState.PushTargetDevices = snapshotUserPushRegistrationIDs(email, identity)
		if len(tgState.PushTargetDevices) == 0 {
			sendTelegramPlain(token, adminID, "У пользователя нет активных push-устройств.", pushCancelMarkup())
			resetPushComposeState()
			return true
		}
		tgState.PushStage = pushStageDevice
		showPushUserDevices(token, adminID, 0)
		return true
	case pushStageTitle:
		if value == "" {
			sendTelegramPlain(token, adminID, "Заголовок не может быть пустым.", pushBackMarkup("push_back_type"))
			return true
		}
		if strings.Contains(value, "\n") || len([]rune(value)) > maxPushTitle {
			sendTelegramPlain(token, adminID, fmt.Sprintf("Заголовок должен быть одной строкой и не длиннее %d символов.", maxPushTitle), pushBackMarkup("push_back_type"))
			return true
		}
		tgState.PushTitle, tgState.PushStage = value, pushStageMessage
		sendTelegramPlain(token, adminID, fmt.Sprintf("Введите текст push-уведомления (до %d символов):", maxPushMessage), pushBackMarkup("push_back_title"))
		return true
	case pushStageMessage:
		if value == "" || len([]rune(value)) > maxPushMessage {
			sendTelegramPlain(token, adminID, fmt.Sprintf("Текст не может быть пустым и не должен превышать %d символов.", maxPushMessage), pushBackMarkup("push_back_title"))
			return true
		}
		tgState.PushMessage, tgState.PushStage = value, pushStageDeepLink
		sendTelegramPlain(token, adminID, "🔗 Выберите переход после нажатия:", pushDeepLinkMarkup())
		return true
	}
	return false
}

func showPushAudience(token string, adminID int64, messageID int) {
	text := "📢 Аудитория push:\n\nВыберите получателей"
	markup := map[string]interface{}{"inline_keyboard": [][]map[string]interface{}{
		{{"text": "🟢 Все активные", "callback_data": "push_a_active"}},
		{{"text": "👥 Все пользователи", "callback_data": "push_a_all"}},
		{{"text": "✅ Активные подписки", "callback_data": "push_a_subscriptions"}},
		{{"text": "⏳ Истекающие подписки", "callback_data": "push_a_expiring"}},
		{{"text": "⚠️ Истёкшие подписки", "callback_data": "push_a_expired"}},
		{{"text": "❌ Отмена", "callback_data": "push_cancel"}},
	}}
	if messageID > 0 {
		editTelegramPlain(token, adminID, messageID, text, markup)
	} else {
		sendTelegramPlain(token, adminID, text, markup)
	}
}

func pushAudienceLabel(a string) string {
	switch a {
	case pushAudienceActive:
		return "Все активные"
	case pushAudienceAll:
		return "Все пользователи"
	case pushAudienceSubscriptions:
		return "Активные подписки"
	case pushAudienceExpiring:
		return "Истекающие подписки"
	case pushAudienceExpired:
		return "Истёкшие подписки"
	default:
		return "Пользователь"
	}
}

func resolvePushUser(identifier string) (subscriptionID, email string, ok bool) {
	identifier = strings.TrimSpace(identifier)
	if identifier == "" {
		return "", "", false
	}
	dbMutex.Lock()
	defer dbMutex.Unlock()
	for address, user := range db.Users {
		if user == nil {
			continue
		}
		userEmail := normalizeUserEmail(address)
		if user.Email != "" {
			userEmail = normalizeUserEmail(user.Email)
		}
		if strings.EqualFold(identifier, userEmail) || (user.SubscriptionID != "" && identifier == user.SubscriptionID) {
			return user.SubscriptionID, userEmail, true
		}
	}
	for _, reg := range db.PushRegistrations {
		if reg != nil && (strings.EqualFold(identifier, reg.UserEmail) || identifier == reg.SubscriptionID) {
			return reg.SubscriptionID, normalizeUserEmail(reg.UserEmail), true
		}
	}
	return "", "", false
}

func snapshotUserPushRegistrationIDs(email, subscriptionID string) []string {
	dbMutex.Lock()
	defer dbMutex.Unlock()
	ids := []string{}
	for id, reg := range db.PushRegistrations {
		if reg == nil || !reg.Enabled || !reg.Preferences.Enabled {
			continue
		}
		if (email != "" && normalizeUserEmail(reg.UserEmail) == normalizeUserEmail(email)) || (subscriptionID != "" && reg.SubscriptionID == subscriptionID) {
			ids = append(ids, id)
		}
	}
	return ids
}

func showPushUserDevices(token string, adminID int64, messageID int) {
	lines := "👤 Устройства пользователя\n\nВыберите устройства для отправки:"
	keyboard := [][]map[string]interface{}{{{"text": "📱 Все устройства", "callback_data": "push_ud_all"}}}
	dbMutex.Lock()
	for _, id := range tgState.PushTargetDevices {
		reg := db.PushRegistrations[id]
		if reg == nil {
			continue
		}
		state := "активно"
		if reg.PushCapabilityState == "invalid" {
			state = "недействительно"
		}
		keyboard = append(keyboard, []map[string]interface{}{{"text": fmt.Sprintf("📱 %s · %s", shortPushID(id), state), "callback_data": "push_ud_" + shortPushID(id)}})
	}
	dbMutex.Unlock()
	keyboard = append(keyboard, []map[string]interface{}{{"text": "◀️ Назад", "callback_data": "push_back_target"}, {"text": "❌ Отмена", "callback_data": "push_cancel"}})
	markup := map[string]interface{}{"inline_keyboard": keyboard}
	if messageID > 0 {
		editTelegramPlain(token, adminID, messageID, lines, markup)
	} else {
		sendTelegramPlain(token, adminID, lines, markup)
	}
}

func findPushRegistrationByShortIDForUser(short string) string {
	for _, id := range tgState.PushTargetDevices {
		if shortPushID(id) == short {
			return id
		}
	}
	return ""
}

func showPushPreview(token string, adminID int64, messageID int) {
	count := len(snapshotPushAudience(tgState.PushAudience, tgState.PushTargetEmail))
	if tgState.PushMode == pushModeUser {
		count = len(tgState.PushTargetDevices)
	}
	audience := pushAudienceLabel(tgState.PushAudience)
	if tgState.PushMode == pushModeUser {
		audience = "Пользователь " + safePushUserLabel(tgState.PushTargetEmail)
	}
	tgState.PushPreview = fmt.Sprintf("🔔 ПРЕДПРОСМОТР\n\nТип:\n%s\n\nАудитория:\n%s\n\nПолучателей:\n%d\n\nЗаголовок:\n%s\n\nТекст:\n%s\n\nDeep link:\n%s", tgState.PushType, audience, count, tgState.PushTitle, tgState.PushMessage, pushDeepLinkLabel(tgState.PushDeepLink))
	tgState.PushStage = pushStageConfirm
	markup := map[string]interface{}{"inline_keyboard": [][]map[string]interface{}{{{"text": "✅ Отправить", "callback_data": "push_send"}, {"text": "❌ Отмена", "callback_data": "push_cancel"}}, {{"text": "◀️ Назад", "callback_data": "push_back_deeplink"}}}}
	if messageID > 0 {
		editTelegramPlain(token, adminID, messageID, tgState.PushPreview, markup)
	} else {
		sendTelegramPlain(token, adminID, tgState.PushPreview, markup)
	}
}

func safePushUserLabel(email string) string {
	email = strings.TrimSpace(email)
	parts := strings.SplitN(email, "@", 2)
	if len(parts) != 2 {
		if len(email) > 6 {
			return email[:6] + "…"
		}
		return "пользователь"
	}
	local := parts[0]
	if len(local) > 2 {
		local = local[:2] + "***"
	} else {
		local = "***"
	}
	return local + "@" + parts[1]
}

func handlePushCallback(token string, adminID int64, data string, messageID int, messageText string) bool {
	switch {
	case data == "push_devices":
		showPushDevices(token, adminID, messageID)
		return true
	case data == "push_new":
		startPushWizard(token, adminID)
		return true
	case data == "push_user":
		startPushUserWizard(token, adminID)
		return true
	case data == "push_cancel":
		if !hasActivePushCompose() {
			return true
		}
		resetPushComposeState()
		editTelegramPlain(token, adminID, messageID, "Отправка push отменена.", nil)
		return true
	case data == "push_back_type":
		tgState.PushStage = pushStageType
		sendPushType(token, adminID)
		return true
	case data == "push_back_title":
		tgState.PushStage = pushStageTitle
		sendTelegramPlain(token, adminID, "Введите заголовок push-уведомления:", pushBackMarkup("push_back_type"))
		return true
	case data == "push_back_message":
		tgState.PushStage = pushStageMessage
		sendTelegramPlain(token, adminID, "Введите текст push-уведомления:", pushBackMarkup("push_back_title"))
		return true
	case data == "push_back_deeplink":
		tgState.PushStage = pushStageDeepLink
		sendTelegramPlain(token, adminID, "🔗 Выберите переход после нажатия:", pushDeepLinkMarkup())
		return true
	case data == "push_back_target":
		tgState.PushStage = pushStageTarget
		sendTelegramPlain(token, adminID, "👤 Введите email или идентификатор подписки пользователя:", pushCancelMarkup())
		return true
	case strings.HasPrefix(data, "push_t_"):
		if tgState.PushStage != pushStageType {
			return true
		}
		tgState.PushType = strings.TrimPrefix(data, "push_t_")
		tgState.PushStage = pushStageTitle
		sendTelegramPlain(token, adminID, "Введите заголовок push-уведомления:", pushBackMarkup("push_back_type"))
		return true
	case strings.HasPrefix(data, "push_l_"):
		if tgState.PushStage != pushStageDeepLink {
			return true
		}
		tgState.PushDeepLink = pushDeepLinkNormalize(strings.TrimPrefix(data, "push_l_"))
		tgState.PushStage = pushStageAudience
		if tgState.PushMode == pushModeUser {
			showPushPreview(token, adminID, messageID)
		} else {
			showPushAudience(token, adminID, messageID)
		}
		return true
	case strings.HasPrefix(data, "push_a_"):
		if tgState.PushStage != pushStageAudience || tgState.PushMode == pushModeUser {
			return true
		}
		tgState.PushAudience = strings.TrimPrefix(data, "push_a_")
		showPushPreview(token, adminID, messageID)
		return true
	case data == "push_ud_all":
		if tgState.PushStage != pushStageDevice {
			return true
		}
		tgState.PushTargetDevices = snapshotUserPushRegistrationIDs(tgState.PushTargetEmail, tgState.PushTargetIdentity)
		tgState.PushStage = pushStageType
		sendPushType(token, adminID)
		return true
	case strings.HasPrefix(data, "push_ud_"):
		if tgState.PushStage != pushStageDevice {
			return true
		}
		id := findPushRegistrationByShortIDForUser(strings.TrimPrefix(data, "push_ud_"))
		if id == "" {
			showPushUserDevices(token, adminID, messageID)
			return true
		}
		tgState.PushTargetDevices = []string{id}
		tgState.PushStage = pushStageType
		sendPushType(token, adminID)
		return true
	case data == "push_send":
		return handlePushSend(token, adminID, messageID, messageText)
	}
	if strings.HasPrefix(data, "push_disable_") || strings.HasPrefix(data, "push_enable_") {
		prefix, action := "push_disable_", "disable"
		if strings.HasPrefix(data, "push_enable_") {
			prefix, action = "push_enable_", "enable"
		}
		short := strings.TrimPrefix(data, prefix)
		id := findPushRegistrationByShortID(short)
		if id == "" {
			showPushDevices(token, adminID, messageID)
			return true
		}
		dbMutex.Lock()
		if reg := db.PushRegistrations[id]; reg != nil {
			reg.Enabled = action == "enable"
			reg.Preferences.Enabled = reg.Enabled
			reg.PushCapabilityState = map[bool]string{true: "available", false: "disabled"}[reg.Enabled]
			saveDBLocked()
		}
		dbMutex.Unlock()
		showPushDevices(token, adminID, messageID)
		return true
	}
	return false
}

func handlePushSend(token string, adminID int64, messageID int, messageText string) bool {
	pushComposeMu.Lock()
	if tgState.PushStage != pushStageConfirm || normalizeTelegramText(messageText) != tgState.PushPreview || tgState.PushSending {
		pushComposeMu.Unlock()
		return true
	}
	tgState.PushSending = true
	title, message, typ, deepLink := tgState.PushTitle, tgState.PushMessage, tgState.PushType, tgState.PushDeepLink
	audience, email, mode := tgState.PushAudience, tgState.PushTargetEmail, tgState.PushMode
	deviceIDs := append([]string(nil), tgState.PushTargetDevices...)
	resetPushComposeStateLocked()
	pushComposeMu.Unlock()
	n, publishErr := publishPushRecordSafely(NotificationRecord{Title: title, Message: message, Type: typ, Priority: "high", DeepLink: deepLink, Source: "telegram_push", Audience: audience})
	if publishErr != nil {
		editTelegramPlain(token, adminID, messageID, "❌ Не удалось подготовить push к отправке. Состояние мастера очищено.", nil)
		return true
	}
	var result PushDeliveryResult
	if mode == pushModeUser {
		if len(deviceIDs) == 1 {
			result = SendPushToDevice(deviceIDs[0], n)
		} else {
			result = SendPushToUser(email, n)
		}
	} else if audience == pushAudienceActive {
		result = SendPush(n)
	} else {
		regs := snapshotPushAudience(audience, email)
		ids := make([]string, 0, len(regs))
		for _, reg := range regs {
			if reg != nil {
				ids = append(ids, reg.ID)
			}
		}
		result = SendPushToMultiple(ids, n)
	}
	resultText := fmt.Sprintf("🎉 Push отправлен\n\n👥 Получателей: %d\n✅ Отправлено: %d\n❌ Ошибок: %d\n🗑 Недействительных: %d", result.Targeted, result.Sent, result.Failed, result.Invalid)
	if mode == pushModeUser {
		resultText = fmt.Sprintf("🎉 Push отправлен\n\n👤 Пользователь: %s\n📱 Устройств: %d\n✅ Отправлено: %d\n❌ Ошибок: %d\n🗑 Недействительных: %d", safePushUserLabel(email), len(deviceIDs), result.Sent, result.Failed, result.Invalid)
	}
	editTelegramPlain(token, adminID, messageID, resultText, nil)
	return true
}

func publishPushRecordSafely(input NotificationRecord) (record NotificationRecord, err error) {
	defer func() {
		if recovered := recover(); recovered != nil {
			err = errors.New("notification storage unavailable")
		}
	}()
	return publishNotificationRecord(input), nil
}

func findPushRegistrationByShortID(short string) string {
	dbMutex.Lock()
	defer dbMutex.Unlock()
	for id := range db.PushRegistrations {
		if shortPushID(id) == short {
			return id
		}
	}
	return ""
}

func showPushDevices(token string, adminID int64, messageID int) {
	dbMutex.Lock()
	active, disabled, invalid := 0, 0, 0
	lines := "📱 PUSH-УСТРОЙСТВА\n\n"
	keyboard := [][]map[string]interface{}{}
	for id, reg := range db.PushRegistrations {
		if reg == nil {
			continue
		}
		state := "отключено"
		if reg.PushCapabilityState == "invalid" {
			state = "недействительно"
			invalid++
		} else if reg.Enabled && reg.Preferences.Enabled {
			state = "активно"
			active++
		} else {
			disabled++
		}
		lines += fmt.Sprintf("%s · %s · %s · %s\n", shortPushID(id), safePushUserLabel(reg.UserEmail), state, time.Unix(reg.LastSeenAt, 0).Local().Format("02.01 15:04"))
		label, action := "✅ Включить", "push_enable_"
		if state == "активно" {
			label, action = "⛔ Отключить", "push_disable_"
		}
		keyboard = append(keyboard, []map[string]interface{}{{"text": label, "callback_data": action + shortPushID(id)}})
	}
	dbMutex.Unlock()
	lines += fmt.Sprintf("\nАктивных: %d\nОтключено: %d\nНедействительных: %d", active, disabled, invalid)
	keyboard = append(keyboard, []map[string]interface{}{{"text": "◀️ Назад", "callback_data": "panel_main"}})
	markup := map[string]interface{}{"inline_keyboard": keyboard}
	if messageID > 0 {
		editTelegramPlain(token, adminID, messageID, lines, markup)
	} else {
		sendTelegramPlain(token, adminID, lines, markup)
	}
}

func snapshotPushAudience(audience, email string) []*PushRegistration {
	now := time.Now()
	allowedUsers := map[string]bool{}
	allowedSubscriptions := map[string]bool{}
	dbMutex.Lock()
	for address, user := range db.Users {
		if user == nil {
			continue
		}
		status := strings.ToLower(strings.TrimSpace(user.SubscriptionStatus))
		expires := time.Unix(user.SubscriptionExpires, 0)
		include := true
		switch audience {
		case pushAudienceSubscriptions:
			include = status != "blocked" && status != "expired" && user.SubscriptionExpires > now.Unix()
		case pushAudienceExpiring:
			left := expires.Sub(now)
			include = status != "blocked" && left >= 0 && left <= 7*24*time.Hour
		case pushAudienceExpired:
			include = user.SubscriptionExpires > 0 && expires.Before(now)
		case pushAudienceOne:
			include = normalizeUserEmail(address) == normalizeUserEmail(email)
		}
		if audience == pushAudienceActive || audience == pushAudienceAll {
			include = true
		}
		if include {
			allowedUsers[normalizeUserEmail(address)] = true
			if user.Email != "" {
				allowedUsers[normalizeUserEmail(user.Email)] = true
			}
			if user.SubscriptionID != "" {
				allowedSubscriptions[user.SubscriptionID] = true
			}
		}
	}
	regs := make([]*PushRegistration, 0)
	for _, reg := range db.PushRegistrations {
		if reg == nil || !reg.Enabled || !reg.Preferences.Enabled {
			continue
		}
		if audience != pushAudienceActive && audience != pushAudienceAll && !allowedUsers[normalizeUserEmail(reg.UserEmail)] && !allowedSubscriptions[reg.SubscriptionID] {
			continue
		}
		copy := *reg
		regs = append(regs, &copy)
	}
	dbMutex.Unlock()
	return regs
}
