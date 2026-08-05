package main

import "fmt"

func showCabinetProfile(token string, actor cabinetActor, messageID int, edit bool) {
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
	email := user.Email
	createdAt := user.CreatedAt
	subscriptionCount := 0
	if user.SubscriptionID != "" && entry != nil {
		subscriptionCount = 1
	}
	status := cabinetProfileStatusLabel(user, entry)

	if changed {
		saveDBLocked()
	}
	dbMutex.Unlock()

	text := fmt.Sprintf(
		"<b>👤 Профиль</b>\n\n"+
			"<b>Email:</b> <code>%s</code>\n"+
			"<b>Telegram ID:</b> <code>%d</code>\n"+
			"<b>Username:</b> %s\n"+
			"<b>Имя:</b> %s\n"+
			"<b>Дата регистрации:</b> %s\n"+
			"<b>Статус пользователя:</b> %s\n"+
			"<b>Количество подписок:</b> %d",
		cabinetSafe(email),
		telegramID,
		cabinetSafe(actor.usernameLabel()),
		cabinetSafe(actor.displayName()),
		cabinetFormatDate(createdAt),
		cabinetSafe(status),
		subscriptionCount,
	)

	cabinetRender(
		token,
		actor.ChatID,
		messageID,
		text,
		[][]map[string]interface{}{
			{cabinetButton("⬅️ Назад", "cabinet_open")},
		},
		edit,
	)
}
