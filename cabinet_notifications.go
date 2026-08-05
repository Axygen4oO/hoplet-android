package main

func showCabinetNotifications(token string, actor cabinetActor, messageID int, edit bool) {
	telegramID := actor.effectiveUserID()

	dbMutex.Lock()
	_, user, ok := cabinetFindUserByTelegramIDLocked(telegramID)
	if !ok || user == nil {
		dbMutex.Unlock()
		showCabinetLoginPrompt(token, actor, messageID, edit)
		return
	}

	changed := cabinetEnsureUserDefaults(user)
	notify7Days := user.Notify7Days
	notify3Days := user.Notify3Days
	notifyNews := user.NotifyNews
	if changed {
		saveDBLocked()
	}
	dbMutex.Unlock()

	text := "<b>🔔 Уведомления</b>\n\nЗдесь можно сохранить ваши предпочтения для напоминаний и новостей."

	button7Days := "⬜ За 7 дней до окончания"
	if notify7Days {
		button7Days = "✅ За 7 дней до окончания"
	}

	button3Days := "⬜ За 3 дня до окончания"
	if notify3Days {
		button3Days = "✅ За 3 дня до окончания"
	}

	buttonNews := "⬜ Новости и обновления"
	if notifyNews {
		buttonNews = "✅ Новости и обновления"
	}

	cabinetRender(
		token,
		actor.ChatID,
		messageID,
		text,
		[][]map[string]interface{}{
			{cabinetButton(button7Days, "cabinet_notifications_toggle_7days")},
			{cabinetButton(button3Days, "cabinet_notifications_toggle_3days")},
			{cabinetButton(buttonNews, "cabinet_notifications_toggle_news")},
			{cabinetButton("⬅️ Назад", "cabinet_open")},
		},
		edit,
	)
}

func toggleCabinetNotification(token string, actor cabinetActor, messageID int, notification string) {
	ok := cabinetUpdateLinkedUser(actor.effectiveUserID(), func(user *UserAccount) bool {
		switch notification {
		case "7days":
			user.Notify7Days = !user.Notify7Days
		case "3days":
			user.Notify3Days = !user.Notify3Days
		case "news":
			user.NotifyNews = !user.NotifyNews
		default:
			return false
		}
		return true
	})

	if !ok {
		showCabinetLoginPrompt(token, actor, messageID, true)
		return
	}

	showCabinetNotifications(token, actor, messageID, true)
}
