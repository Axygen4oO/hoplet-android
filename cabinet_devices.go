package main

import (
	"fmt"
	"strings"
)

type cabinetDeviceRow struct {
	DeviceID   string
	DeviceName string
	IP         string
	LastSeenAt int64
	Online     bool
}

func showCabinetDevices(token string, actor cabinetActor, messageID int, edit bool) {
	telegramID := actor.effectiveUserID()

	dbMutex.Lock()
	_, user, ok := cabinetFindUserByTelegramIDLocked(telegramID)
	if !ok || user == nil {
		dbMutex.Unlock()
		showCabinetLoginPrompt(token, actor, messageID, edit)
		return
	}

	changed := cabinetEnsureUserDefaults(user)
	entry := db.Passwords[user.SubscriptionID]
	if changed {
		saveDBLocked()
	}

	if user.SubscriptionID == "" || entry == nil {
		dbMutex.Unlock()
		cabinetRender(
			token,
			actor.ChatID,
			messageID,
			"<b>📱 Мои устройства</b>\n\nИнформация об устройствах временно недоступна.",
			[][]map[string]interface{}{
				{cabinetButton("⬅️ Назад", "cabinet_open")},
			},
			edit,
		)
		return
	}

	deviceIDs := cabinetDeviceIDs(entry)
	rows := make([]cabinetDeviceRow, 0, len(deviceIDs))

	activeDevicesMu.Lock()
	for _, deviceID := range deviceIDs {
		dev := db.Devices[deviceID]
		if dev == nil {
			rows = append(rows, cabinetDeviceRow{
				DeviceID:   deviceID,
				DeviceName: deviceID,
			})
			continue
		}

		name := dev.DeviceName
		if strings.TrimSpace(name) == "" {
			name = dev.DeviceID
		}

		rows = append(rows, cabinetDeviceRow{
			DeviceID:   dev.DeviceID,
			DeviceName: name,
			IP:         dev.IP,
			LastSeenAt: dev.LastSeenAt,
			Online:     activeDevices[dev.DeviceID] > 0,
		})
	}
	activeDevicesMu.Unlock()
	dbMutex.Unlock()

	text := "<b>📱 Мои устройства</b>\n\n"
	if len(rows) == 0 {
		text += "Устройства пока не подключались."
	} else {
		for i, row := range rows {
			status := "⚪ Оффлайн"
			if row.Online {
				status = "🟢 Онлайн"
			}

			text += fmt.Sprintf(
				"<b>%d. %s</b>\n<b>Статус:</b> %s\n<b>Последняя активность:</b> %s\n",
				i+1,
				cabinetSafe(row.DeviceName),
				cabinetSafe(status),
				cabinetSafe(cabinetLastSeenLabel(row.LastSeenAt, row.Online)),
			)

			if row.IP != "" {
				text += fmt.Sprintf("<b>IP:</b> <code>%s</code>\n", cabinetSafe(row.IP))
			}
			text += fmt.Sprintf("<b>ID:</b> <code>%s</code>\n\n", cabinetSafe(row.DeviceID))
		}
	}

	keyboard := [][]map[string]interface{}{
		{cabinetButton("➕ Добавить устройство", "cabinet_devices_add")},
	}
	for _, row := range rows {
		keyboard = append(keyboard, []map[string]interface{}{
			cabinetButton(
				"➖ Отвязать "+cabinetShortLabel(row.DeviceName, 22),
				"cabdev_"+row.DeviceID,
			),
		})
	}
	keyboard = append(keyboard, []map[string]interface{}{
		cabinetButton("⬅️ Назад", "cabinet_open"),
	})

	cabinetRender(token, actor.ChatID, messageID, text, keyboard, edit)
}

func handleCabinetAddDevice(token string, actor cabinetActor, messageID int) {
	telegramID := actor.effectiveUserID()

	dbMutex.Lock()
	_, user, ok := cabinetFindUserByTelegramIDLocked(telegramID)
	if !ok || user == nil {
		dbMutex.Unlock()
		showCabinetLoginPrompt(token, actor, messageID, true)
		return
	}

	changed := cabinetEnsureUserDefaults(user)
	entry := db.Passwords[user.SubscriptionID]
	if changed {
		saveDBLocked()
	}

	if user.SubscriptionID == "" || entry == nil {
		dbMutex.Unlock()
		cabinetRender(
			token,
			actor.ChatID,
			messageID,
			"<b>➕ Добавить устройство</b>\n\nСначала активируйте или привяжите подписку к аккаунту.",
			[][]map[string]interface{}{
				{cabinetButton("⬅️ Назад к устройствам", "cabinet_devices")},
			},
			true,
		)
		return
	}

	ports := entry.Ports
	vkHash := entry.VkHash
	subscriptionID := user.SubscriptionID
	if strings.TrimSpace(ports) == "" {
		ports = "56000,56001,9000"
	}
	parts := strings.Split(ports, ",")
	if len(parts) != 3 {
		parts = []string{"56000", "56001", "9000"}
	}

	dbMutex.Unlock()
	link := fmt.Sprintf(
		"wdtt://%s:%s:%s:%s:%s:%s",
		getPublicIP(),
		strings.TrimSpace(parts[0]),
		strings.TrimSpace(parts[1]),
		strings.TrimSpace(parts[2]),
		subscriptionID,
		vkHash,
	)

	cabinetRender(
		token,
		actor.ChatID,
		messageID,
		fmt.Sprintf(
			"<b>➕ Добавить устройство</b>\n\nУстановите клиент, импортируйте ссылку ниже и подключитесь. Новое устройство появится автоматически.\n\n<code>%s</code>",
			cabinetSafe(link),
		),
		[][]map[string]interface{}{
			{cabinetButton("⬅️ Назад к устройствам", "cabinet_devices")},
		},
		true,
	)
}

func handleCabinetUnbindDevice(token string, actor cabinetActor, messageID int, deviceID string) {
	telegramID := actor.effectiveUserID()

	dbMutex.Lock()

	_, user, ok := cabinetFindUserByTelegramIDLocked(telegramID)
	if !ok || user == nil {
		dbMutex.Unlock()
		showCabinetLoginPrompt(token, actor, messageID, true)
		return
	}

	changed := cabinetEnsureUserDefaults(user)
	entry := db.Passwords[user.SubscriptionID]
	if changed {
		saveDBLocked()
	}

	if user.SubscriptionID == "" || entry == nil {
		dbMutex.Unlock()
		cabinetRender(
			token,
			actor.ChatID,
			messageID,
			"<b>📱 Мои устройства</b>\n\nПодписка не найдена.",
			[][]map[string]interface{}{
				{cabinetButton("⬅️ Назад", "cabinet_open")},
			},
			true,
		)
		return
	}

	found := false
	for _, id := range cabinetDeviceIDs(entry) {
		if id == deviceID {
			found = true
			break
		}
	}

	if !found {
		dbMutex.Unlock()
		cabinetRender(
			token,
			actor.ChatID,
			messageID,
			"<b>📱 Мои устройства</b>\n\nУстройство уже отвязано или не найдено.",
			[][]map[string]interface{}{
				{cabinetButton("⬅️ Назад к устройствам", "cabinet_devices")},
			},
			true,
		)
		return
	}

	removed := unbindDevices(entry, deviceID)
	purgeRemovedDeviceStatsLocked(removed)
	saveDBLocked()

	dbMutex.Unlock()
	applyRemovedDeviceRuntimeState(globalWgDev, removed)
	showCabinetDevices(token, actor, messageID, true)
}
