package main

import (
	"fmt"
	"sort"
	"strings"
	"time"

	"golang.zx2c4.com/wireguard/device"
)

const adminUserCardNoData = "Нет данных"

type adminUserCardSnapshot struct {
	SubscriptionID   string
	User             *UserAccount
	Entry            *PasswordEntry
	Devices          []*ClientDevice
	Orders           []*Order
	Tickets          []*SupportTicket
	TelegramUsername string
	LastActivityAt   int64
	LastIP           string
}

type adminUserHistoryEvent struct {
	At   int64
	Text string
}

func resetAdminUserCardState() {
	tgState.WaitingUserExtendDays = false
	tgState.WaitingUserMessage = false
	tgState.TargetUserSubscriptionID = ""
}

func handleAdminUserCardCallback(
	token string,
	adminID int64,
	data string,
	messageID int,
	wgDev *device.Device,
) bool {
	switch {
	case strings.HasPrefix(data, "usercard_"):
		subscriptionID := strings.TrimPrefix(data, "usercard_")
		renderAdminUserCard(token, adminID, messageID, true, subscriptionID)
		return true

	case strings.HasPrefix(data, "user_history_"):
		subscriptionID := strings.TrimPrefix(data, "user_history_")
		renderAdminUserHistory(token, adminID, messageID, subscriptionID)
		return true

	case strings.HasPrefix(data, "user_extend_"):
		subscriptionID := strings.TrimPrefix(data, "user_extend_")
		resetAdminUserCardState()
		tgState.TargetUserSubscriptionID = subscriptionID
		tgState.WaitingUserExtendDays = true
		sendTelegram(
			token,
			adminID,
			"📅 На сколько дней продлить подписку пользователя?\n\nВведите положительное число дней.",
			nil,
		)
		return true

	case strings.HasPrefix(data, "user_plan_") && !strings.HasPrefix(data, "user_plan_set_"):
		subscriptionID := strings.TrimPrefix(data, "user_plan_")
		renderAdminPlanPicker(token, adminID, messageID, subscriptionID)
		return true

	case strings.HasPrefix(data, "user_plan_set_"):
		payload := strings.TrimPrefix(data, "user_plan_set_")
		idx := strings.LastIndex(payload, "_")
		if idx <= 0 || idx >= len(payload)-1 {
			editTelegram(
				token,
				adminID,
				messageID,
				"❌ Не удалось определить тариф.",
				cabinetKeyboard(
					[]map[string]interface{}{cabinetButton("◀️ Назад", "panel_subs")},
				),
			)
			return true
		}

		subscriptionID := payload[:idx]
		plan := payload[idx+1:]

		if err := adminChangeUserPlanBySubscriptionID(subscriptionID, plan); err != nil {
			editTelegram(
				token,
				adminID,
				messageID,
				"❌ "+err.Error(),
				cabinetKeyboard(
					[]map[string]interface{}{cabinetButton("◀️ Назад", "usercard_"+subscriptionID)},
				),
			)
			return true
		}

		renderAdminUserCard(token, adminID, messageID, true, subscriptionID)
		return true

	case strings.HasPrefix(data, "user_block_"):
		subscriptionID := strings.TrimPrefix(data, "user_block_")
		if err := adminSetSubscriptionActiveByID(subscriptionID, false, wgDev); err != nil {
			editTelegram(
				token,
				adminID,
				messageID,
				"❌ "+err.Error(),
				cabinetKeyboard(
					[]map[string]interface{}{cabinetButton("◀️ Назад", "usercard_"+subscriptionID)},
				),
			)
			return true
		}

		renderAdminUserCard(token, adminID, messageID, true, subscriptionID)
		return true

	case strings.HasPrefix(data, "user_unblock_"):
		subscriptionID := strings.TrimPrefix(data, "user_unblock_")
		if err := adminSetSubscriptionActiveByID(subscriptionID, true, wgDev); err != nil {
			editTelegram(
				token,
				adminID,
				messageID,
				"❌ "+err.Error(),
				cabinetKeyboard(
					[]map[string]interface{}{cabinetButton("◀️ Назад", "usercard_"+subscriptionID)},
				),
			)
			return true
		}

		renderAdminUserCard(token, adminID, messageID, true, subscriptionID)
		return true

	case strings.HasPrefix(data, "user_message_"):
		subscriptionID := strings.TrimPrefix(data, "user_message_")
		resetAdminUserCardState()
		tgState.TargetUserSubscriptionID = subscriptionID
		tgState.WaitingUserMessage = true
		sendTelegram(
			token,
			adminID,
			"💬 Введите текст личного сообщения для пользователя.",
			nil,
		)
		return true

	case strings.HasPrefix(data, "user_confirm_resetpass_"):
		subscriptionID := strings.TrimPrefix(data, "user_confirm_resetpass_")
		renderAdminConfirmAction(
			token,
			adminID,
			messageID,
			"🔐 *Сброс пароля*\n\nБудет создан новый временный пароль для пользователя.",
			"user_do_resetpass_"+subscriptionID,
			"usercard_"+subscriptionID,
		)
		return true

	case strings.HasPrefix(data, "user_do_resetpass_"):
		subscriptionID := strings.TrimPrefix(data, "user_do_resetpass_")
		password, err := adminResetUserPasswordBySubscriptionID(subscriptionID)
		if err != nil {
			editTelegram(
				token,
				adminID,
				messageID,
				"❌ "+err.Error(),
				cabinetKeyboard(
					[]map[string]interface{}{cabinetButton("◀️ Назад", "usercard_"+subscriptionID)},
				),
			)
			return true
		}

		editTelegram(
			token,
			adminID,
			messageID,
			fmt.Sprintf("✅ Пароль пользователя сброшен.\n\nНовый временный пароль:\n`%s`", password),
			cabinetKeyboard(
				[]map[string]interface{}{cabinetButton("👤 Вернуться в карточку", "usercard_"+subscriptionID)},
				[]map[string]interface{}{cabinetButton("◀️ К подписке", "viewpass_"+subscriptionID)},
			),
		)
		return true

	case strings.HasPrefix(data, "user_confirm_unlinktg_"):
		subscriptionID := strings.TrimPrefix(data, "user_confirm_unlinktg_")
		renderAdminConfirmAction(
			token,
			adminID,
			messageID,
			"🔌 *Отвязка Telegram*\n\nTelegram ID и авторизация в боте будут очищены.",
			"user_do_unlinktg_"+subscriptionID,
			"usercard_"+subscriptionID,
		)
		return true

	case strings.HasPrefix(data, "user_do_unlinktg_"):
		subscriptionID := strings.TrimPrefix(data, "user_do_unlinktg_")
		if err := adminUnlinkUserTelegramBySubscriptionID(subscriptionID); err != nil {
			editTelegram(
				token,
				adminID,
				messageID,
				"❌ "+err.Error(),
				cabinetKeyboard(
					[]map[string]interface{}{cabinetButton("◀️ Назад", "usercard_"+subscriptionID)},
				),
			)
			return true
		}

		renderAdminUserCard(token, adminID, messageID, true, subscriptionID)
		return true

	case strings.HasPrefix(data, "user_confirm_delete_"):
		subscriptionID := strings.TrimPrefix(data, "user_confirm_delete_")
		renderAdminConfirmAction(
			token,
			adminID,
			messageID,
			"🗑 *Удаление аккаунта*\n\nБудут удалены пользователь, его подписка, заказы и привязанные устройства.",
			"user_do_delete_"+subscriptionID,
			"usercard_"+subscriptionID,
		)
		return true

	case strings.HasPrefix(data, "user_do_delete_"):
		subscriptionID := strings.TrimPrefix(data, "user_do_delete_")
		email, err := adminDeleteUserBySubscriptionID(subscriptionID, wgDev)
		if err != nil {
			editTelegram(
				token,
				adminID,
				messageID,
				"❌ "+err.Error(),
				cabinetKeyboard(
					[]map[string]interface{}{cabinetButton("◀️ Назад", "usercard_"+subscriptionID)},
				),
			)
			return true
		}

		editTelegram(
			token,
			adminID,
			messageID,
			fmt.Sprintf("✅ Аккаунт %s удалён.", adminTelegramMarkdownEscape(email)),
			cabinetKeyboard(
				[]map[string]interface{}{cabinetButton("◀️ К списку подписок", "subs_list")},
			),
		)
		return true
	}

	return false
}

func renderAdminUserCard(
	token string,
	adminID int64,
	messageID int,
	edit bool,
	subscriptionID string,
) {
	snapshot, err := loadAdminUserCardSnapshot(subscriptionID)
	if err != nil {
		renderAdminUserCardError(token, adminID, messageID, edit, subscriptionID, err.Error())
		return
	}

	text := buildAdminUserCardText(snapshot)
	keyboard := buildAdminUserCardKeyboard(snapshot.SubscriptionID)

	if edit {
		editTelegram(
			token,
			adminID,
			messageID,
			text,
			cabinetKeyboard(keyboard...),
		)
		return
	}

	sendTelegram(
		token,
		adminID,
		text,
		cabinetKeyboard(keyboard...),
	)
}

func renderAdminUserHistory(
	token string,
	adminID int64,
	messageID int,
	subscriptionID string,
) {
	snapshot, err := loadAdminUserCardSnapshot(subscriptionID)
	if err != nil {
		renderAdminUserCardError(token, adminID, messageID, true, subscriptionID, err.Error())
		return
	}

	events := buildAdminUserHistoryEvents(snapshot)

	var builder strings.Builder
	builder.WriteString("🕘 *История действий*\n\n")

	if len(events) == 0 {
		builder.WriteString("Нет данных.")
	} else {
		limit := len(events)
		if limit > 20 {
			limit = 20
		}
		for i := 0; i < limit; i++ {
			event := events[i]
			builder.WriteString("• ")
			builder.WriteString(adminUserCardDateLabel(event.At))
			builder.WriteString(" — ")
			builder.WriteString(adminTelegramMarkdownEscape(event.Text))
			builder.WriteString("\n")
		}
	}

	editTelegram(
		token,
		adminID,
		messageID,
		builder.String(),
		cabinetKeyboard(
			[]map[string]interface{}{cabinetButton("👤 Вернуться в карточку", "usercard_"+subscriptionID)},
			[]map[string]interface{}{cabinetButton("◀️ К подписке", "viewpass_"+subscriptionID)},
		),
	)
}

func renderAdminPlanPicker(token string, adminID int64, messageID int, subscriptionID string) {
	snapshot, err := loadAdminUserCardSnapshot(subscriptionID)
	if err != nil {
		renderAdminUserCardError(token, adminID, messageID, true, subscriptionID, err.Error())
		return
	}

	editTelegram(
		token,
		adminID,
		messageID,
		fmt.Sprintf(
			"💳 *Изменение тарифа*\n\nТекущий тариф: %s",
			adminTelegramMarkdownEscape(adminUserPlanLabel(snapshot.User)),
		),
		cabinetKeyboard(
			cabinetPlanSelectionKeyboard(
				"user_plan_set_"+subscriptionID+"_",
				"usercard_"+subscriptionID,
			)...,
		),
	)
}

func renderAdminConfirmAction(
	token string,
	adminID int64,
	messageID int,
	text string,
	confirmCallback string,
	backCallback string,
) {
	editTelegram(
		token,
		adminID,
		messageID,
		text,
		cabinetKeyboard(
			[]map[string]interface{}{
				cabinetButton("✅ Подтвердить", confirmCallback),
				cabinetButton("◀️ Отмена", backCallback),
			},
		),
	)
}

func renderAdminUserCardError(
	token string,
	adminID int64,
	messageID int,
	edit bool,
	subscriptionID string,
	message string,
) {
	keyboard := cabinetKeyboard(
		[]map[string]interface{}{cabinetButton("◀️ К подписке", "viewpass_"+subscriptionID)},
		[]map[string]interface{}{cabinetButton("◀️ К списку", "subs_list")},
	)

	text := "❌ " + adminTelegramMarkdownEscape(message)
	if edit {
		editTelegram(token, adminID, messageID, text, keyboard)
		return
	}

	sendTelegram(token, adminID, text, keyboard)
}

func loadAdminUserCardSnapshot(subscriptionID string) (*adminUserCardSnapshot, error) {
	dbMutex.Lock()
	defer dbMutex.Unlock()

	entry := db.Passwords[subscriptionID]
	if entry == nil {
		return nil, fmt.Errorf("подписка не найдена")
	}

	user, ok := findUserBySubscriptionID(subscriptionID)
	if !ok || user == nil {
		return nil, fmt.Errorf("пользователь для подписки не найден")
	}

	snapshot := &adminUserCardSnapshot{
		SubscriptionID: subscriptionID,
		User:           cloneUserAccount(user),
		Entry:          clonePasswordEntry(entry),
	}

	for _, deviceID := range cabinetDeviceIDs(entry) {
		if dev := db.Devices[deviceID]; dev != nil {
			snapshot.Devices = append(snapshot.Devices, cloneClientDevice(dev))
		}
	}

	for _, order := range db.Orders {
		if order == nil {
			continue
		}
		if normalizeUserEmail(order.Email) == snapshot.User.Email {
			snapshot.Orders = append(snapshot.Orders, cloneOrder(order))
		}
	}

	for _, ticket := range db.SupportTickets {
		if ticket == nil {
			continue
		}
		if adminSupportTicketMatchesUser(ticket, snapshot.User) {
			snapshot.Tickets = append(snapshot.Tickets, cloneSupportTicket(ticket))
		}
	}

	sort.Slice(snapshot.Devices, func(i, j int) bool {
		if snapshot.Devices[i].LastSeenAt == snapshot.Devices[j].LastSeenAt {
			return snapshot.Devices[i].DeviceID < snapshot.Devices[j].DeviceID
		}
		return snapshot.Devices[i].LastSeenAt > snapshot.Devices[j].LastSeenAt
	})
	sort.Slice(snapshot.Orders, func(i, j int) bool {
		return snapshot.Orders[i].CreatedAt > snapshot.Orders[j].CreatedAt
	})
	sort.Slice(snapshot.Tickets, func(i, j int) bool {
		return snapshot.Tickets[i].LastMessageAt > snapshot.Tickets[j].LastMessageAt
	})

	snapshot.TelegramUsername = adminResolveTelegramUsername(snapshot.User, snapshot.Tickets)
	snapshot.LastActivityAt, snapshot.LastIP = adminResolveLastActivity(snapshot)

	return snapshot, nil
}

func buildAdminUserCardText(snapshot *adminUserCardSnapshot) string {
	deviceCount := cabinetBoundDeviceCount(snapshot.Entry)
	deviceLimit := adminUserDeviceLimit(snapshot.User, snapshot.Entry)

	var builder strings.Builder
	builder.WriteString("👤 *Карточка пользователя*\n\n")
	builder.WriteString("📧 Email: ")
	builder.WriteString(adminTelegramMarkdownEscape(adminUserValue(snapshot.User.Email)))
	builder.WriteString("\n")
	builder.WriteString("🆔 Telegram ID: ")
	builder.WriteString(adminTelegramMarkdownEscape(adminUserTelegramIDLabel(snapshot.User.TelegramID)))
	builder.WriteString("\n")
	builder.WriteString("👤 Username Telegram: ")
	builder.WriteString(adminTelegramMarkdownEscape(adminUserUsernameLabel(snapshot.TelegramUsername)))
	builder.WriteString("\n")
	builder.WriteString("📡 Статус подписки: ")
	builder.WriteString(adminTelegramMarkdownEscape(cabinetSubscriptionStatusLabel(snapshot.User, snapshot.Entry)))
	builder.WriteString("\n")
	builder.WriteString("💳 Тариф: ")
	builder.WriteString(adminTelegramMarkdownEscape(adminUserPlanLabel(snapshot.User)))
	builder.WriteString("\n")
	builder.WriteString("📅 Дата окончания подписки: ")
	builder.WriteString(adminTelegramMarkdownEscape(adminUserSubscriptionExpiryLabel(snapshot.User, snapshot.Entry)))
	builder.WriteString("\n")
	builder.WriteString("🗓 Дата регистрации: ")
	builder.WriteString(adminTelegramMarkdownEscape(adminUserCardDateLabel(snapshot.User.CreatedAt)))
	builder.WriteString("\n")
	builder.WriteString("🔐 Последний вход: ")
	builder.WriteString(adminTelegramMarkdownEscape(adminUserLastLoginLabel()))
	builder.WriteString("\n")
	builder.WriteString("⏱ Последняя активность: ")
	builder.WriteString(adminTelegramMarkdownEscape(adminUserCardDateLabel(snapshot.LastActivityAt)))
	builder.WriteString("\n")
	builder.WriteString("📦 Версия приложения: ")
	builder.WriteString(adminTelegramMarkdownEscape(adminUserAppVersionLabel()))
	builder.WriteString("\n")
	builder.WriteString("🌐 IP-адрес: ")
	builder.WriteString(adminTelegramMarkdownEscape(adminUserValue(snapshot.LastIP)))
	builder.WriteString("\n")
	builder.WriteString("📱 Количество устройств: ")
	builder.WriteString(fmt.Sprintf("%d/%d", deviceCount, deviceLimit))
	builder.WriteString("\n")
	builder.WriteString("🔑 ID подписки: `")
	builder.WriteString(snapshot.SubscriptionID)
	builder.WriteString("`\n")
	builder.WriteString("🧾 Заказов: ")
	builder.WriteString(fmt.Sprintf("%d", len(snapshot.Orders)))
	builder.WriteString("\n")
	builder.WriteString("💬 Обращений в поддержку: ")
	builder.WriteString(fmt.Sprintf("%d", len(snapshot.Tickets)))

	return builder.String()
}

func buildAdminUserCardKeyboard(subscriptionID string) [][]map[string]interface{} {
	return [][]map[string]interface{}{
		{cabinetButton("📅 Продлить подписку", "user_extend_"+subscriptionID)},
		{cabinetButton("💳 Изменить тариф", "user_plan_"+subscriptionID)},
		{cabinetButton("⛔ Заблокировать", "user_block_"+subscriptionID)},
		{cabinetButton("✅ Разблокировать", "user_unblock_"+subscriptionID)},
		{cabinetButton("🔐 Сбросить пароль", "user_confirm_resetpass_"+subscriptionID)},
		{cabinetButton("🔌 Отвязать Telegram", "user_confirm_unlinktg_"+subscriptionID)},
		{cabinetButton("🗑 Удалить аккаунт", "user_confirm_delete_"+subscriptionID)},
		{cabinetButton("💬 Отправить личное сообщение", "user_message_"+subscriptionID)},
		{cabinetButton("🕘 Просмотреть историю действий", "user_history_"+subscriptionID)},
		{cabinetButton("◀️ К подписке", "viewpass_"+subscriptionID)},
	}
}

func buildAdminUserHistoryEvents(snapshot *adminUserCardSnapshot) []adminUserHistoryEvent {
	events := make([]adminUserHistoryEvent, 0, len(snapshot.Devices)+len(snapshot.Orders)+len(snapshot.Tickets))

	for _, dev := range snapshot.Devices {
		if dev == nil || dev.LastSeenAt <= 0 {
			continue
		}

		label := strings.TrimSpace(dev.DeviceName)
		if label == "" {
			label = dev.DeviceID
		}

		text := fmt.Sprintf("Устройство %s выходило в сеть", label)
		if strings.TrimSpace(dev.IP) != "" {
			text += " с IP " + dev.IP
		}

		events = append(events, adminUserHistoryEvent{
			At:   dev.LastSeenAt,
			Text: text,
		})
	}

	for _, order := range snapshot.Orders {
		if order == nil || order.CreatedAt <= 0 {
			continue
		}

		events = append(events, adminUserHistoryEvent{
			At: order.CreatedAt,
			Text: fmt.Sprintf(
				"Заказ %s: %s, тариф %s, статус %s",
				order.ID,
				adminOrderTypeLabel(order.Type),
				cabinetPlanLabel(order.Plan),
				adminOrderStatusLabel(order.Status),
			),
		})
	}

	for _, ticket := range snapshot.Tickets {
		if ticket == nil {
			continue
		}

		for _, message := range ticket.Messages {
			if message == nil || message.CreatedAt <= 0 {
				continue
			}

			preview := supportMessagePreview(message)
			if preview == "" {
				preview = "без текста"
			}

			events = append(events, adminUserHistoryEvent{
				At: message.CreatedAt,
				Text: fmt.Sprintf(
					"Обращение %s: %s — %s",
					ticket.ID,
					adminSupportSenderLabel(message.SenderRole),
					preview,
				),
			})
		}
	}

	sort.Slice(events, func(i, j int) bool {
		return events[i].At > events[j].At
	})

	return events
}

func adminSupportTicketMatchesUser(ticket *SupportTicket, user *UserAccount) bool {
	if ticket == nil || user == nil {
		return false
	}

	if normalizeUserEmail(ticket.OwnerEmail) == user.Email {
		return true
	}

	return user.TelegramID != 0 && ticket.OwnerTelegramID == user.TelegramID
}

func adminResolveTelegramUsername(user *UserAccount, tickets []*SupportTicket) string {
	latestAt := int64(0)
	username := ""

	for _, ticket := range tickets {
		if ticket == nil {
			continue
		}

		if ticket.LastMessageAt > latestAt && strings.TrimSpace(ticket.OwnerUsername) != "" {
			latestAt = ticket.LastMessageAt
			username = ticket.OwnerUsername
		}

		for _, message := range ticket.Messages {
			if message == nil || strings.TrimSpace(message.SenderUsername) == "" {
				continue
			}
			if adminMessageBelongsToUser(message, user) && message.CreatedAt >= latestAt {
				latestAt = message.CreatedAt
				username = message.SenderUsername
			}
		}
	}

	return strings.TrimSpace(username)
}

func adminMessageBelongsToUser(message *SupportMessage, user *UserAccount) bool {
	if message == nil || user == nil {
		return false
	}

	if normalizeUserEmail(message.SenderEmail) == user.Email {
		return true
	}

	return user.TelegramID != 0 && message.SenderTelegramID == user.TelegramID
}

func adminResolveLastActivity(snapshot *adminUserCardSnapshot) (int64, string) {
	lastActivity := int64(0)
	lastIP := ""

	for _, dev := range snapshot.Devices {
		if dev == nil {
			continue
		}
		if dev.LastSeenAt > lastActivity {
			lastActivity = dev.LastSeenAt
		}
		if lastIP == "" && strings.TrimSpace(dev.IP) != "" {
			lastIP = dev.IP
		}
	}

	for _, ticket := range snapshot.Tickets {
		if ticket != nil && ticket.LastMessageAt > lastActivity {
			lastActivity = ticket.LastMessageAt
		}
	}

	for _, order := range snapshot.Orders {
		if order != nil && order.CreatedAt > lastActivity {
			lastActivity = order.CreatedAt
		}
	}

	return lastActivity, strings.TrimSpace(lastIP)
}

func adminSetSubscriptionActiveByID(subscriptionID string, active bool, wgDev *device.Device) error {
	dbMutex.Lock()

	entry := db.Passwords[subscriptionID]
	if entry == nil {
		dbMutex.Unlock()
		return fmt.Errorf("подписка не найдена")
	}

	user, _ := findUserBySubscriptionID(subscriptionID)
	devices := collectPasswordDevicesLocked(entry)

	var err error
	if active {
		if user != nil {
			err = unblockSubscription(user)
		} else {
			entry.IsDeactivated = false
			saveDBLocked()
		}
	} else {
		if user != nil {
			err = blockSubscription(user)
		} else {
			entry.IsDeactivated = true
			saveDBLocked()
		}
		purgeRemovedDeviceStatsLocked(devices)
	}

	dbMutex.Unlock()

	if err != nil {
		return err
	}

	if active {
		for _, dev := range devices {
			upsertPeerInWG(wgDev, dev)
		}
		return nil
	}

	applyRemovedDeviceRuntimeState(wgDev, devices)
	return nil
}

func adminExtendUserSubscriptionByID(subscriptionID string, days int64) error {
	dbMutex.Lock()
	defer dbMutex.Unlock()

	user, ok := findUserBySubscriptionID(subscriptionID)
	if !ok || user == nil {
		return fmt.Errorf("пользователь не найден")
	}

	return extendSubscription(user, days)
}

func adminChangeUserPlanBySubscriptionID(subscriptionID string, plan string) error {
	plan = cabinetPlanCode(plan)
	if plan == "" {
		return fmt.Errorf("неизвестный тариф")
	}

	dbMutex.Lock()
	defer dbMutex.Unlock()

	user, ok := findUserBySubscriptionID(subscriptionID)
	if !ok || user == nil {
		return fmt.Errorf("пользователь не найден")
	}

	return changeSubscriptionPlan(user, plan)
}

func adminResetUserPasswordBySubscriptionID(subscriptionID string) (string, error) {
	password := generatePassword()

	dbMutex.Lock()
	defer dbMutex.Unlock()

	user, ok := findUserBySubscriptionID(subscriptionID)
	if !ok || user == nil {
		return "", fmt.Errorf("пользователь не найден")
	}

	if err := setUserPasswordLocked(user, password); err != nil {
		return "", err
	}

	saveDBLocked()
	return password, nil
}

func adminUnlinkUserTelegramBySubscriptionID(subscriptionID string) error {
	dbMutex.Lock()
	defer dbMutex.Unlock()

	user, ok := findUserBySubscriptionID(subscriptionID)
	if !ok || user == nil {
		return fmt.Errorf("пользователь не найден")
	}

	user.TelegramID = 0
	user.TelegramAuthToken = ""
	user.TelegramAuthExpiresAt = 0
	saveDBLocked()

	return nil
}

func adminDeleteUserBySubscriptionID(subscriptionID string, wgDev *device.Device) (string, error) {
	dbMutex.Lock()

	user, ok := findUserBySubscriptionID(subscriptionID)
	if !ok || user == nil {
		dbMutex.Unlock()
		return "", fmt.Errorf("пользователь не найден")
	}

	email := user.Email
	removed, removedPassword, err := deleteUserAccount(user)
	dbMutex.Unlock()

	if err != nil {
		return "", err
	}

	if removedPassword != "" {
		serverWrapKeys.RemovePassword(removedPassword)
	}
	applyRemovedDeviceRuntimeState(wgDev, removed)

	return email, nil
}

func adminSendUserMessageBySubscriptionID(token string, subscriptionID string, message string) error {
	dbMutex.Lock()
	user, ok := findUserBySubscriptionID(subscriptionID)
	if !ok || user == nil {
		dbMutex.Unlock()
		return fmt.Errorf("пользователь не найден")
	}

	if user.TelegramID == 0 {
		dbMutex.Unlock()
		return fmt.Errorf("telegram не привязан")
	}

	telegramID := user.TelegramID
	dbMutex.Unlock()

	sendCabinetTelegram(
		token,
		telegramID,
		"<b>Сообщение от администратора</b>\n\n"+cabinetSafe(strings.TrimSpace(message)),
		nil,
	)

	return nil
}

func adminUserCardDateLabel(ts int64) string {
	if ts <= 0 {
		return adminUserCardNoData
	}
	return time.Unix(ts, 0).Format("02.01.2006 15:04")
}

func adminUserTelegramIDLabel(telegramID int64) string {
	if telegramID == 0 {
		return adminUserCardNoData
	}
	return fmt.Sprintf("%d", telegramID)
}

func adminUserUsernameLabel(username string) string {
	username = strings.TrimSpace(username)
	if username == "" {
		return adminUserCardNoData
	}
	if !strings.HasPrefix(username, "@") {
		return "@" + username
	}
	return username
}

func adminUserSubscriptionExpiryLabel(user *UserAccount, entry *PasswordEntry) string {
	if user != nil && user.SubscriptionExpires > 0 {
		return adminUserCardDateLabel(user.SubscriptionExpires)
	}
	if entry != nil && entry.ExpiresAt > 0 {
		return adminUserCardDateLabel(entry.ExpiresAt)
	}
	return adminUserCardNoData
}

func adminUserPlanLabel(user *UserAccount) string {
	if user == nil || strings.TrimSpace(user.SubscriptionPlan) == "" {
		return adminUserCardNoData
	}
	return cabinetPlanLabel(user.SubscriptionPlan)
}

func adminUserDeviceLimit(user *UserAccount, entry *PasswordEntry) int {
	if user != nil && user.DeviceLimit > 0 {
		return user.DeviceLimit
	}
	if entry != nil && entry.MaxDevices > 0 {
		return entry.MaxDevices
	}
	return 1
}

func adminUserValue(value string) string {
	value = strings.TrimSpace(value)
	if value == "" {
		return adminUserCardNoData
	}
	return value
}

func adminUserLastLoginLabel() string {
	return adminUserCardNoData
}

func adminUserAppVersionLabel() string {
	return adminUserCardNoData
}

func adminOrderTypeLabel(orderType string) string {
	switch strings.ToLower(strings.TrimSpace(orderType)) {
	case "new":
		return "новая подписка"
	case "renew":
		return "продление"
	case "upgrade":
		return "апгрейд"
	default:
		return adminUserValue(orderType)
	}
}

func adminOrderStatusLabel(status string) string {
	status = strings.TrimSpace(status)
	if status == "" {
		return adminUserCardNoData
	}
	return status
}

func adminSupportSenderLabel(role string) string {
	switch role {
	case supportSenderOperator:
		return "оператор"
	case supportSenderSystem:
		return "система"
	default:
		return "пользователь"
	}
}

func adminTelegramMarkdownEscape(value string) string {
	replacer := strings.NewReplacer(
		`\`, `\\`,
		"_", `\_`,
		"*", `\*`,
		"`", "\\`",
		"[", `\[`,
	)
	return replacer.Replace(value)
}
