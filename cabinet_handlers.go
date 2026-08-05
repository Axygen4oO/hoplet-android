package main

import (
	"fmt"
	"strings"
)

func handleCabinetMessage(token string, actor cabinetActor, text string) bool {
	telegramID := actor.effectiveUserID()
	state := cabinetGetState(telegramID)

	if !actor.isPrivateChat() {
		if state.Mode != "" || text == "/start" || text == "/help" || text == "/cabinet" {
			cabinetClearState(telegramID)
			cabinetPromptPrivateChat(token, actor)
			return true
		}
		return false
	}

	if state.Mode != "" && text != "/start" && text != "/help" && text != "/cabinet" {
		switch state.Mode {
		case "awaiting_login_email":
			email := strings.ToLower(strings.TrimSpace(text))
			if email == "" || !strings.Contains(email, "@") {
				sendCabinetTelegram(
					token,
					actor.ChatID,
					"<b>Личный кабинет</b>\n\nВведите корректный email, который вы используете в WDTT.",
					nil,
				)
				return true
			}

			cabinetSetState(telegramID, CabinetState{
				Mode:  "awaiting_login_password",
				Email: email,
			})

			sendCabinetTelegram(
				token,
				actor.ChatID,
				"<b>Личный кабинет</b>\n\nТеперь отправьте пароль от вашего аккаунта WDTT.",
				nil,
			)
			return true

		case "awaiting_login_password":
			password := strings.TrimSpace(text)
			auth, err := AuthenticateUser(state.Email, password)
			if password == "" || err != nil {
				cabinetClearState(telegramID)
				cabinetRender(
					token,
					actor.ChatID,
					0,
					"<b>Личный кабинет</b>\n\nНеверный email или пароль. Попробуйте снова.",
					[][]map[string]interface{}{
						{cabinetButton("🔐 Войти снова", "cabinet_login")},
					},
					false,
				)
				return true
			}

			if err := cabinetAuthorizeTelegramUser(state.Email, telegramID, auth); err != nil {
				cabinetClearState(telegramID)
				sendCabinetTelegram(
					token,
					actor.ChatID,
					"<b>Личный кабинет</b>\n\nНе удалось привязать Telegram к вашему аккаунту. Попробуйте позже.",
					nil,
				)
				return true
			}

			cabinetClearState(telegramID)
			showCabinetMain(token, actor, 0, false)
			return true

		case "awaiting_register_email":
			email := strings.ToLower(strings.TrimSpace(text))
			if email == "" || !strings.Contains(email, "@") {
				sendCabinetTelegram(
					token,
					actor.ChatID,
					"<b>Регистрация</b>\n\nВведите корректный email для нового аккаунта WDTT.",
					nil,
				)
				return true
			}

			cabinetSetState(telegramID, CabinetState{
				Mode:  "awaiting_register_password",
				Email: email,
			})

			sendCabinetTelegram(
				token,
				actor.ChatID,
				"<b>Регистрация</b>\n\nТеперь отправьте пароль для нового аккаунта.",
				nil,
			)
			return true

		case "awaiting_register_password":
			password := strings.TrimSpace(text)
			if password == "" {
				sendCabinetTelegram(
					token,
					actor.ChatID,
					"<b>Регистрация</b>\n\nПароль не должен быть пустым. Отправьте пароль ещё раз.",
					nil,
				)
				return true
			}

			cabinetSetState(telegramID, CabinetState{
				Mode:     "awaiting_register_password_confirm",
				Email:    state.Email,
				Password: password,
			})

			sendCabinetTelegram(
				token,
				actor.ChatID,
				"<b>Регистрация</b>\n\nПовторите пароль, чтобы завершить регистрацию.",
				nil,
			)
			return true

		case "awaiting_register_password_confirm":
			passwordConfirm := strings.TrimSpace(text)
			if passwordConfirm == "" || passwordConfirm != state.Password {
				cabinetClearState(telegramID)
				cabinetRender(
					token,
					actor.ChatID,
					0,
					"<b>Регистрация</b>\n\nПароли не совпали. Попробуйте снова.",
					[][]map[string]interface{}{
						{cabinetButton("🆕 Начать регистрацию заново", "cabinet_register")},
						{cabinetButton("⬅️ Назад", "cabinet_back")},
					},
					false,
				)
				return true
			}

			auth, err := RegisterUserAndIssueToken(state.Email, state.Password)
			if err != nil {
				cabinetClearState(telegramID)
				cabinetRender(
					token,
					actor.ChatID,
					0,
					fmt.Sprintf(
						"<b>Регистрация</b>\n\nНе удалось создать аккаунт: %s",
						cabinetSafe(err.Error()),
					),
					[][]map[string]interface{}{
						{cabinetButton("🆕 Попробовать снова", "cabinet_register")},
						{cabinetButton("🔑 Войти", "cabinet_login")},
						{cabinetButton("⬅️ Назад", "cabinet_back")},
					},
					false,
				)
				return true
			}

			if err := cabinetAuthorizeTelegramUser(state.Email, telegramID, auth); err != nil {
				cabinetClearState(telegramID)
				sendCabinetTelegram(
					token,
					actor.ChatID,
					"<b>Регистрация</b>\n\nАккаунт создан, но привязка Telegram не удалась. Попробуйте войти снова.",
					nil,
				)
				return true
			}

			cabinetClearState(telegramID)
			showCabinetMain(token, actor, 0, false)
			return true

		case "awaiting_promo":
			cabinetClearState(telegramID)
			cabinetRender(
				token,
				actor.ChatID,
				0,
				fmt.Sprintf(
					"<b>🎁 Промокод</b>\n\nКод <code>%s</code> получен, но активация промокодов в Telegram-кабинете пока не подключена.",
					cabinetSafe(strings.TrimSpace(text)),
				),
				[][]map[string]interface{}{
					{cabinetButton("⬅️ Назад к подписке", "cabinet_subscription")},
				},
				false,
			)
			return true
		}
	}

	switch text {
	case "/start", "/help":
		cabinetClearState(telegramID)
		showCabinetIntro(token, actor, 0, false)
		return true
	case "/cabinet":
		cabinetClearState(telegramID)
		if cabinetHasLinkedUser(telegramID) {
			showCabinetMain(token, actor, 0, false)
		} else {
			showCabinetLoginPrompt(token, actor, 0, false)
		}
		return true
	default:
		return false
	}
}

func handleCabinetCallback(token string, actor cabinetActor, callbackID, data string, messageID int) bool {
	if !strings.HasPrefix(data, "cabinet_") && !strings.HasPrefix(data, "cabdev_") {
		return false
	}

	answerCallback(token, callbackID)

	if !actor.isPrivateChat() {
		cabinetPromptPrivateChat(token, actor)
		return true
	}

	cabinetClearState(actor.effectiveUserID())

	switch {
	case data == "cabinet_back":
		showCabinetIntro(token, actor, messageID, true)
	case data == "cabinet_open":
		if cabinetHasLinkedUser(actor.effectiveUserID()) {
			showCabinetMain(token, actor, messageID, true)
		} else {
			showCabinetIntro(token, actor, messageID, true)
		}
	case data == "cabinet_login":
		showCabinetLoginPrompt(token, actor, messageID, true)
	case data == "cabinet_register":
		showCabinetRegisterPrompt(token, actor, messageID, true)
	case handleCabinetPurchaseCallback(token, actor, messageID, data):
		return true
	case data == "cabinet_profile":
		showCabinetProfile(token, actor, messageID, true)
	case data == "cabinet_subscription":
		showCabinetSubscription(token, actor, messageID, true)
	case data == "cabinet_app":
		showCabinetApp(token, actor, messageID, true, false)
	case data == "cabinet_app_refresh":
		showCabinetApp(token, actor, messageID, true, true)
	case data == "cabinet_subscription_promo":
		startCabinetPromoFlow(token, actor, messageID)
	case data == "cabinet_devices":
		showCabinetDevices(token, actor, messageID, true)
	case data == "cabinet_devices_add":
		handleCabinetAddDevice(token, actor, messageID)
	case strings.HasPrefix(data, "cabdev_"):
		handleCabinetUnbindDevice(
			token,
			actor,
			messageID,
			strings.TrimPrefix(data, "cabdev_"),
		)
	case data == "cabinet_history":
		showCabinetHistory(token, actor, messageID, true)
	case data == "cabinet_notifications":
		showCabinetNotifications(token, actor, messageID, true)
	case data == "cabinet_notifications_toggle_7days":
		toggleCabinetNotification(token, actor, messageID, "7days")
	case data == "cabinet_notifications_toggle_3days":
		toggleCabinetNotification(token, actor, messageID, "3days")
	case data == "cabinet_notifications_toggle_news":
		toggleCabinetNotification(token, actor, messageID, "news")
	case data == "cabinet_settings":
		showCabinetSettings(token, actor, messageID, true)
	case data == "cabinet_settings_language":
		showCabinetLanguageSettings(token, actor, messageID, true)
	case data == "cabinet_settings_language_ru":
		setCabinetLanguage(token, actor, messageID, "ru")
	case data == "cabinet_settings_language_en":
		setCabinetLanguage(token, actor, messageID, "en")
	case data == "cabinet_settings_help":
		showCabinetHelp(token, actor, messageID, true)
	case data == "cabinet_settings_terms":
		showCabinetTerms(token, actor, messageID, true)
	case data == "cabinet_settings_privacy":
		showCabinetPrivacy(token, actor, messageID, true)
	case data == "cabinet_settings_about":
		showCabinetAbout(token, actor, messageID, true)
	default:
		return false
	}

	return true
}

func showCabinetIntro(token string, actor cabinetActor, messageID int, edit bool) {
	text := "<b>WDTT</b>\n\nЛичный кабинет позволяет посмотреть профиль, подписку, устройства, историю и настройки прямо в Telegram."
	keyboard := [][]map[string]interface{}{}

	if cabinetHasLinkedUser(actor.effectiveUserID()) {
		keyboard = append(keyboard, []map[string]interface{}{
			cabinetButton("👤 Открыть кабинет", "cabinet_open"),
		})
	} else {
		keyboard = append(keyboard,
			[]map[string]interface{}{cabinetButton("🔑 Войти", "cabinet_login")},
			[]map[string]interface{}{cabinetButton("🆕 Зарегистрироваться", "cabinet_register")},
		)
	}

	cabinetRender(
		token,
		actor.ChatID,
		messageID,
		text,
		keyboard,
		edit,
	)
}

func showCabinetMain(token string, actor cabinetActor, messageID int, edit bool) {
	text := "<b>👤 Личный кабинет</b>\n\nВыберите нужный раздел."
	keyboard := [][]map[string]interface{}{
		{cabinetButton("👤 Профиль", "cabinet_profile")},
		{cabinetButton("📦 Подписка", "cabinet_subscription")},
		{cabinetButton("🛒 Купить подписку", "cabinet_purchase")},
		{cabinetButton("📱 Мои устройства", "cabinet_devices")},
		{cabinetButton("📱 Скачать приложение", "cabinet_app")},
		{cabinetButton("📜 История", "cabinet_history")},
		{cabinetButton("💬 Поддержка", "support_menu")},
		{cabinetButton("🔔 Уведомления", "cabinet_notifications")},
		{cabinetButton("⚙️ Настройки", "cabinet_settings")},
	}

	if supportCanOperateTelegramID(actor.effectiveUserID()) {
		keyboard = append(keyboard, []map[string]interface{}{
			cabinetButton("🛠 Режим оператора", "support_op_menu"),
		})
	}

	keyboard = append(keyboard, []map[string]interface{}{
		cabinetButton("⬅️ Назад", "cabinet_back"),
	})

	cabinetRender(
		token,
		actor.ChatID,
		messageID,
		text,
		keyboard,
		edit,
	)
}

func showCabinetLoginPrompt(token string, actor cabinetActor, messageID int, edit bool) {
	cabinetSetState(actor.effectiveUserID(), CabinetState{
		Mode: "awaiting_login_email",
	})

	text := "<b>👤 Личный кабинет</b>\n\nЧтобы открыть кабинет, войдите в уже существующий аккаунт WDTT.\n\nОтправьте email, который вы используете на сайте."

	cabinetRender(
		token,
		actor.ChatID,
		messageID,
		text,
		[][]map[string]interface{}{
			{cabinetButton("⬅️ Назад", "cabinet_back")},
		},
		edit,
	)
}

func showCabinetRegisterPrompt(token string, actor cabinetActor, messageID int, edit bool) {
	cabinetSetState(actor.effectiveUserID(), CabinetState{
		Mode: "awaiting_register_email",
	})

	text := "<b>🆕 Регистрация</b>\n\nСоздайте новый аккаунт WDTT прямо в Telegram.\n\nОтправьте email для регистрации."

	cabinetRender(
		token,
		actor.ChatID,
		messageID,
		text,
		[][]map[string]interface{}{
			{cabinetButton("⬅️ Назад", "cabinet_back")},
		},
		edit,
	)
}
