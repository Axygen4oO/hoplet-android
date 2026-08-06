package main

import (
	"fmt"
	"strings"
)

const (
	notificationStageTitle    = "title"
	notificationStageMessage  = "message"
	notificationStageConfirm  = "confirm"
	notificationPanelCallback = "panel_notify"
)

const notificationPreviewHeader = "--------------------------------"

func startNotificationWizard(token string, adminID int64) {
	resetNotificationComposeState()
	tgState.NotificationStage = notificationStageTitle

	sendTelegramPlain(
		token,
		adminID,
		"Введите заголовок уведомления",
		nil,
	)
}

func startNotificationCompose(token string, adminID int64) {
	startNotificationWizard(token, adminID)
}

func handleNotificationPanelAction(token string, adminID int64, data string) bool {
	if data != notificationPanelCallback {
		return false
	}

	startNotificationWizard(token, adminID)
	return true
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
				nil,
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
		if tgState.NotificationStage != notificationStageConfirm {
			return true
		}
		if normalizeTelegramText(messageText) != tgState.NotificationPreview {
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
			nil,
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
		"Предпросмотр\n\n%s\n\nЗаголовок:\n\n%s\n\nТекст:\n\n%s\n\n%s",
		notificationPreviewHeader,
		title,
		message,
		notificationPreviewHeader,
	)
}

func parseNotificationPreviewText(text string) (string, string, bool) {
	normalized := normalizeTelegramText(text)
	prefix := "Предпросмотр\n\n" + notificationPreviewHeader + "\n\nЗаголовок:\n\n"
	if !strings.HasPrefix(normalized, prefix) {
		return "", "", false
	}

	body := strings.TrimPrefix(normalized, prefix)
	titleSeparator := "\n\nТекст:\n\n"
	titleEnd := strings.Index(body, titleSeparator)
	if titleEnd < 0 {
		return "", "", false
	}

	title := body[:titleEnd]
	messagePart := body[titleEnd+len(titleSeparator):]
	footer := "\n\n" + notificationPreviewHeader
	messageEnd := strings.LastIndex(messagePart, footer)
	if messageEnd < 0 {
		return "", "", false
	}

	message := messagePart[:messageEnd]
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
