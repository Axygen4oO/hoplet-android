package main

import "fmt"

func showCabinetSettings(token string, actor cabinetActor, messageID int, edit bool) {
	telegramID := actor.effectiveUserID()

	dbMutex.Lock()
	_, user, ok := cabinetFindUserByTelegramIDLocked(telegramID)
	if !ok || user == nil {
		dbMutex.Unlock()
		showCabinetLoginPrompt(token, actor, messageID, edit)
		return
	}

	changed := cabinetEnsureUserDefaults(user)
	language := user.Language
	if changed {
		saveDBLocked()
	}
	dbMutex.Unlock()

	text := fmt.Sprintf(
		"<b>⚙️ Настройки</b>\n\n<b>Текущий язык:</b> %s",
		cabinetSafe(cabinetLanguageLabel(language)),
	)

	cabinetRender(
		token,
		actor.ChatID,
		messageID,
		text,
		[][]map[string]interface{}{
			{cabinetButton("🌐 Язык", "cabinet_settings_language")},
			{cabinetButton("❓ Помощь", "cabinet_settings_help")},
			{cabinetButton("📄 Пользовательское соглашение", "cabinet_settings_terms")},
			{cabinetButton("🔒 Политика конфиденциальности", "cabinet_settings_privacy")},
			{cabinetButton("ℹ️ О проекте", "cabinet_settings_about")},
			{cabinetButton("⬅️ Назад", "cabinet_open")},
		},
		edit,
	)
}

func showCabinetLanguageSettings(token string, actor cabinetActor, messageID int, edit bool) {
	telegramID := actor.effectiveUserID()

	dbMutex.Lock()
	_, user, ok := cabinetFindUserByTelegramIDLocked(telegramID)
	if !ok || user == nil {
		dbMutex.Unlock()
		showCabinetLoginPrompt(token, actor, messageID, edit)
		return
	}

	changed := cabinetEnsureUserDefaults(user)
	language := user.Language
	if changed {
		saveDBLocked()
	}
	dbMutex.Unlock()

	ruLabel := "Русский"
	enLabel := "English"
	if language == "en" {
		enLabel = "✅ English"
	} else {
		ruLabel = "✅ Русский"
	}

	text := "<b>🌐 Язык</b>\n\nПереключатель уже сохраняется в профиле. Полная локализация интерфейса будет подключена отдельно."

	cabinetRender(
		token,
		actor.ChatID,
		messageID,
		text,
		[][]map[string]interface{}{
			{cabinetButton(ruLabel, "cabinet_settings_language_ru")},
			{cabinetButton(enLabel, "cabinet_settings_language_en")},
			{cabinetButton("⬅️ Назад", "cabinet_settings")},
		},
		edit,
	)
}

func setCabinetLanguage(token string, actor cabinetActor, messageID int, language string) {
	ok := cabinetUpdateLinkedUser(actor.effectiveUserID(), func(user *UserAccount) bool {
		if user.Language == language {
			return false
		}
		user.Language = language
		return true
	})

	if !ok {
		showCabinetLoginPrompt(token, actor, messageID, true)
		return
	}

	showCabinetLanguageSettings(token, actor, messageID, true)
}

func showCabinetHelp(token string, actor cabinetActor, messageID int, edit bool) {
	text := "<b>❓ Помощь</b>\n\nЕсли подписка или устройства отображаются некорректно, откройте этот кабинет повторно или обратитесь к администратору сервиса."

	cabinetRender(
		token,
		actor.ChatID,
		messageID,
		text,
		[][]map[string]interface{}{
			{cabinetButton("⬅️ Назад", "cabinet_settings")},
		},
		edit,
	)
}

func showCabinetTerms(token string, actor cabinetActor, messageID int, edit bool) {
	text := "<b>📄 Пользовательское соглашение</b>\n\nРаздел подготовлен для дальнейшего наполнения. Сейчас актуальные условия использования сервиса уточняются у администратора."

	cabinetRender(
		token,
		actor.ChatID,
		messageID,
		text,
		[][]map[string]interface{}{
			{cabinetButton("⬅️ Назад", "cabinet_settings")},
		},
		edit,
	)
}

func showCabinetPrivacy(token string, actor cabinetActor, messageID int, edit bool) {
	text := "<b>🔒 Политика конфиденциальности</b>\n\nВ кабинете используются только данные вашего аккаунта, подписки, устройств и заказов. Полный текст политики можно будет подключить в этот раздел отдельно."

	cabinetRender(
		token,
		actor.ChatID,
		messageID,
		text,
		[][]map[string]interface{}{
			{cabinetButton("⬅️ Назад", "cabinet_settings")},
		},
		edit,
	)
}

func showCabinetAbout(token string, actor cabinetActor, messageID int, edit bool) {
	text := "<b>ℹ️ О проекте</b>\n\nЭто пользовательский кабинет WDTT в Telegram. Он показывает профиль, подписку, устройства, историю заказов и ваши персональные настройки без изменения текущей админской панели."

	cabinetRender(
		token,
		actor.ChatID,
		messageID,
		text,
		[][]map[string]interface{}{
			{cabinetButton("⬅️ Назад", "cabinet_settings")},
		},
		edit,
	)
}
