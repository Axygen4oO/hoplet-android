package main

func showMainPanel(
	token string,
	adminID int64,
	messageID int,
	edit bool,
) {
	keyboard := [][]map[string]interface{}{
		{{
			"text":          "👥 Подписки",
			"callback_data": "panel_subs",
		}},
		{{
			"text":          "📱 Устройства",
			"callback_data": "panel_devices",
		}},
		{{
			"text":          "📊 Статистика",
			"callback_data": "panel_stats",
		}},
		{{
			"text":          "⚙️ Сервер",
			"callback_data": "panel_server",
		}},
		{{
			"text":          "📢 Отправить уведомление",
			"callback_data": notificationPanelCallback,
		}},
	}

	if edit {
		editTelegram(
			token,
			adminID,
			messageID,
			"📱 *WDTT Manager*\n\nВыберите раздел:",
			map[string]interface{}{
				"inline_keyboard": keyboard,
			},
		)
	} else {
		sendTelegram(
			token,
			adminID,
			"📱 *WDTT Manager*\n\nВыберите раздел:",
			map[string]interface{}{
				"inline_keyboard": keyboard,
			},
		)
	}
}

func showSubscriptionsPanel(
	token string,
	adminID int64,
	messageID int,
	edit bool,
) {
	keyboard := [][]map[string]interface{}{
		{{
			"text":          "➕ Создать подписку",
			"callback_data": "subs_new",
		}},
		{{
			"text":          "➕ Массовое продление",
			"callback_data": adminBulkExtendStartCallback,
		}},
		{{
			"text":          "📋 Список подписок",
			"callback_data": "subs_list",
		}},
		{{
			"text":          "◀️ Назад",
			"callback_data": "panel_main",
		}},
	}

	if edit {
		editTelegram(
			token,
			adminID,
			messageID,
			"👥 *Управление подписками*",
			map[string]interface{}{
				"inline_keyboard": keyboard,
			},
		)
	} else {
		sendTelegram(
			token,
			adminID,
			"👥 *Управление подписками*",
			map[string]interface{}{
				"inline_keyboard": keyboard,
			},
		)
	}
}
