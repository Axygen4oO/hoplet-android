package main

import (
	"fmt"
	"strconv"
	"strings"
	"time"
)

const (
	notificationStageTitle    = "title"
	notificationStageMessage  = "message"
	notificationStageConfirm  = "confirm"
	notificationPanelCallback = "nt"
)

func startNotificationWizard(token string, adminID int64) {
	resetPushComposeState()
	resetNotificationComposeState()
	tgState.NotificationStage = notificationStageTitle

	sendTelegramPlain(
		token,
		adminID,
		"Введите заголовок уведомления",
		map[string]interface{}{"inline_keyboard": [][]map[string]interface{}{{{"text": "◀️ Назад", "callback_data": "notify_cancel"}}}},
	)
}

func startNotificationCompose(token string, adminID int64) {
	startNotificationWizard(token, adminID)
}

func handleNotificationPanelAction(token string, adminID int64, data string, messageIDs ...int) bool {
	messageID := 0
	if len(messageIDs) > 0 {
		messageID = messageIDs[0]
	}
	switch {
	case data == notificationPanelCallback || data == "nt_refresh":
		showNotificationManagement(token, adminID, messageID)
		return true
	case data == "nt_new":
		startNotificationWizard(token, adminID)
		return true
	case data == "push_new":
		startPushWizard(token, adminID)
		return true
	case data == "nt_back":
		showMainPanel(token, adminID, messageID, true)
		return true
	case strings.HasPrefix(data, "nt_v_"):
		showNotificationDetails(token, adminID, messageID, notificationCallbackID(data, "nt_v_"))
		return true
	case strings.HasPrefix(data, "nt_dc_"):
		id := notificationCallbackID(data, "nt_dc_")
		if id > 0 {
			deleteNotification(id)
		}
		showNotificationManagement(token, adminID, messageID)
		return true
	case strings.HasPrefix(data, "nt_d_"):
		showNotificationDeleteConfirmation(token, adminID, messageID, notificationCallbackID(data, "nt_d_"))
		return true
	case strings.HasPrefix(data, "nt_r_"):
		id := notificationCallbackID(data, "nt_r_")
		if source, ok := getNotification(id); ok {
			published := publishNotification(source.Title, source.Message)
			showNotificationDetails(token, adminID, messageID, published.ID)
		} else {
			showNotificationManagement(token, adminID, messageID)
		}
		return true
	}
	return false
}

func notificationCallbackID(data, prefix string) int64 {
	id, _ := strconv.ParseInt(strings.TrimPrefix(data, prefix), 10, 64)
	return id
}

func notificationHistorySnapshot() []NotificationRecord {
	dbMutex.Lock()
	defer dbMutex.Unlock()
	return append([]NotificationRecord(nil), db.Notifications...)
}

func showNotificationManagement(token string, adminID int64, messageID int) {
	items := notificationHistorySnapshot()
	var text strings.Builder
	text.WriteString("🔔 УВЕДОМЛЕНИЯ\n\nПоследние уведомления:\n")
	if len(items) == 0 {
		text.WriteString("\nУведомлений пока нет.")
	}
	keyboard := make([][]map[string]interface{}, 0, len(items)+3)
	for index, item := range items {
		fmt.Fprintf(&text, "\n%d. %s\n   %s\n", index+1, item.Title, formatTelegramNotificationTime(item.CreatedAt))
		keyboard = append(keyboard, []map[string]interface{}{{
			"text": fmt.Sprintf("Открыть #%d", item.ID), "callback_data": fmt.Sprintf("nt_v_%d", item.ID),
		}})
	}
	keyboard = append(keyboard,
		[]map[string]interface{}{{"text": "➕ Новое уведомление", "callback_data": "nt_new"}},
		[]map[string]interface{}{{"text": "📢 Отправить push всем", "callback_data": "push_new"}, {"text": "👤 Push пользователю", "callback_data": "push_user"}},
		[]map[string]interface{}{{"text": "🗑 Удалить", "callback_data": notificationDeleteMenuCallback(items)}, {"text": "🔄 Обновить", "callback_data": "nt_refresh"}},
		[]map[string]interface{}{{"text": "◀️ Назад", "callback_data": "nt_back"}},
	)
	editTelegramPlain(token, adminID, messageID, text.String(), map[string]interface{}{"inline_keyboard": keyboard})
}

func notificationDeleteMenuCallback(items []NotificationRecord) string {
	if len(items) == 0 {
		return "nt_refresh"
	}
	return fmt.Sprintf("nt_d_%d", items[0].ID)
}

func showNotificationDetails(token string, adminID int64, messageID int, id int64) {
	item, ok := getNotification(id)
	if !ok {
		showNotificationManagement(token, adminID, messageID)
		return
	}
	text := fmt.Sprintf("🔔 Уведомление #%d\n\nЗаголовок:\n%s\n\nТекст:\n%s\n\nСоздано:\n%s", item.ID, item.Title, item.Message, formatTelegramNotificationTime(item.CreatedAt))
	keyboard := [][]map[string]interface{}{
		{{"text": "🗑 Удалить", "callback_data": fmt.Sprintf("nt_d_%d", item.ID)}, {"text": "📤 Повторно отправить", "callback_data": fmt.Sprintf("nt_r_%d", item.ID)}},
		{{"text": "◀️ Назад", "callback_data": "nt_refresh"}},
	}
	editTelegramPlain(token, adminID, messageID, text, map[string]interface{}{"inline_keyboard": keyboard})
}

func showNotificationDeleteConfirmation(token string, adminID int64, messageID int, id int64) {
	if _, ok := getNotification(id); !ok {
		showNotificationManagement(token, adminID, messageID)
		return
	}
	keyboard := [][]map[string]interface{}{
		{{"text": "✅ Да, удалить", "callback_data": fmt.Sprintf("nt_dc_%d", id)}},
		{{"text": "❌ Отмена", "callback_data": fmt.Sprintf("nt_v_%d", id)}},
	}
	editTelegramPlain(token, adminID, messageID, "Вы действительно хотите удалить уведомление?", map[string]interface{}{"inline_keyboard": keyboard})
}

func formatTelegramNotificationTime(timestamp int64) string {
	if timestamp <= 0 {
		return "дата неизвестна"
	}
	return time.Unix(timestamp, 0).Local().Format("02.01 15:04")
}

func cancelNotificationCompose(token string, chatID int64) {
	resetNotificationComposeState()
	sendTelegramPlain(token, chatID, "Отправка уведомления отменена.", nil)
}

func hasActiveNotificationCompose() bool {
	return tgState.NotificationStage != ""
}

func resetNotificationComposeState() {
	tgState.NotificationStage = ""
	tgState.NotificationTitle = ""
	tgState.NotificationPreview = ""
	tgState.NotificationIgnoreNextDuplicateMessage = false
}

func handleNotificationInput(token string, adminID int64, text string) bool {
	switch tgState.NotificationStage {
	case notificationStageTitle:
		title := strings.TrimSpace(normalizeTelegramText(text))
		if title == "" {
			sendTelegramPlain(token, adminID, "Заголовок уведомления не может быть пустым.", nil)
			return true
		}
		if strings.Contains(title, "\n") {
			sendTelegramPlain(token, adminID, "Заголовок уведомления должен быть в одной строке.", nil)
			return true
		}

		tgState.NotificationTitle = title
		tgState.NotificationStage = notificationStageMessage
		tgState.NotificationIgnoreNextDuplicateMessage = true

		sendTelegramPlain(
			token,
			adminID,
			"Введите текст уведомления",
			nil,
		)
		return true

	case notificationStageMessage:
		message := strings.TrimSpace(normalizeTelegramText(text))
		if message == "" {
			sendTelegramPlain(token, adminID, "Текст уведомления не может быть пустым.", nil)
			return true
		}
		if tgState.NotificationIgnoreNextDuplicateMessage && message == tgState.NotificationTitle {
			tgState.NotificationIgnoreNextDuplicateMessage = false
			sendTelegramPlain(
				token,
				adminID,
				"Введите текст уведомления",
				map[string]interface{}{"inline_keyboard": [][]map[string]interface{}{{{"text": "◀️ Назад", "callback_data": "notify_cancel"}}}},
			)
			return true
		}

		tgState.NotificationStage = notificationStageConfirm
		tgState.NotificationIgnoreNextDuplicateMessage = false
		preview := buildNotificationPreviewText(tgState.NotificationTitle, message)
		tgState.NotificationPreview = preview

		sendTelegramPlain(
			token,
			adminID,
			preview,
			map[string]interface{}{
				"inline_keyboard": [][]map[string]interface{}{
					{
						{
							"text":          "✅ Отправить",
							"callback_data": "notify_send",
						},
						{
							"text":          "❌ Отмена",
							"callback_data": "notify_cancel",
						},
					},
				},
			},
		)
		return true

	case notificationStageConfirm:
		sendTelegramPlain(
			token,
			adminID,
			"Подтвердите отправку кнопкой «✅ Отправить» или отмените через «❌ Отмена».",
			nil,
		)
		return true
	}

	return false
}

func handleNotificationCallback(
	token string,
	adminID int64,
	data string,
	messageID int,
	messageText string,
) bool {
	switch data {
	case "notify_cancel":
		if !hasActiveNotificationCompose() {
			return true
		}
		if tgState.NotificationStage == notificationStageConfirm && normalizeTelegramText(messageText) != tgState.NotificationPreview {
			return true
		}

		resetNotificationComposeState()
		editTelegramPlain(token, adminID, messageID, "Отправка уведомления отменена.", nil)
		return true

	case "notify_send":
		if tgState.NotificationStage != notificationStageConfirm {
			return true
		}
		if normalizeTelegramText(messageText) != tgState.NotificationPreview {
			return true
		}

		title, message, ok := parseNotificationPreviewText(messageText)
		if !ok || title != tgState.NotificationTitle {
			return true
		}

		resetNotificationComposeState()
		notification, err := publishNotificationSafely(title, message)
		if err != nil {
			editTelegramPlain(
				token,
				adminID,
				messageID,
				"Не удалось опубликовать уведомление. Попробуйте ещё раз.",
				nil,
			)
			return true
		}

		editTelegramPlain(
			token,
			adminID,
			messageID,
			fmt.Sprintf("✅ Уведомление опубликовано.\n\nID: %d", notification.ID),
			map[string]interface{}{"inline_keyboard": [][]map[string]interface{}{{{"text": "🔔 К уведомлениям", "callback_data": "nt_refresh"}}}},
		)
		return true
	}

	return false
}

func publishNotificationSafely(title, message string) (notification AppNotification, err error) {
	defer func() {
		if recovered := recover(); recovered != nil {
			err = fmt.Errorf("publish notification failed: %v", recovered)
		}
	}()

	notification = publishNotification(title, message)
	return notification, nil
}

func buildNotificationPreviewText(title, message string) string {
	return fmt.Sprintf(
		"🔔 ПРЕДПРОСМОТР\n\nЗаголовок:\n%s\n\nТекст:\n%s",
		title,
		message,
	)
}

func parseNotificationPreviewText(text string) (string, string, bool) {
	normalized := normalizeTelegramText(text)
	prefix := "🔔 ПРЕДПРОСМОТР\n\nЗаголовок:\n"
	if !strings.HasPrefix(normalized, prefix) {
		return "", "", false
	}

	body := strings.TrimPrefix(normalized, prefix)
	titleSeparator := "\n\nТекст:\n"
	titleEnd := strings.Index(body, titleSeparator)
	if titleEnd < 0 {
		return "", "", false
	}

	title := body[:titleEnd]
	message := body[titleEnd+len(titleSeparator):]
	if strings.TrimSpace(title) == "" || strings.TrimSpace(message) == "" {
		return "", "", false
	}

	return title, message, true
}

func normalizeTelegramText(text string) string {
	text = strings.ReplaceAll(text, "\r\n", "\n")
	text = strings.ReplaceAll(text, "\r", "\n")
	return text
}

func sendTelegramPlain(token string, chatID int64, text string, replyMarkup interface{}) {
	url := fmt.Sprintf("https://api.telegram.org/bot%s/sendMessage", token)
	payload := map[string]interface{}{
		"chat_id": chatID,
		"text":    text,
	}
	if replyMarkup != nil {
		payload["reply_markup"] = replyMarkup
	}
	if err := postTelegramJSONAndClose(url, payload); err != nil {
		return
	}
}

func editTelegramPlain(
	token string,
	chatID int64,
	messageID int,
	text string,
	replyMarkup interface{},
) {
	url := fmt.Sprintf("https://api.telegram.org/bot%s/editMessageText", token)
	payload := map[string]interface{}{
		"chat_id":    chatID,
		"message_id": messageID,
		"text":       text,
	}
	if replyMarkup != nil {
		payload["reply_markup"] = replyMarkup
	}

	resp, err := postTelegramJSON(url, payload)
	if err != nil {
		return
	}
	defer resp.Body.Close()
}

func denyNotificationCommandForNonAdmin(token string, chatID int64) {
	sendTelegramPlain(token, chatID, "Команда доступна только администратору.", nil)
}
