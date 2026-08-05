package main

import (
	"fmt"
	"log"
	"strconv"
	"strings"
)

const (
	supportListPageSize    = 8
	supportHistoryPageSize = 5
)

type telegramChatPayload struct {
	ID int64 `json:"id"`
}

type telegramPhotoPayload struct {
	FileID       string `json:"file_id"`
	FileUniqueID string `json:"file_unique_id"`
	Width        int    `json:"width"`
	Height       int    `json:"height"`
	FileSize     int64  `json:"file_size"`
}

type telegramDocumentPayload struct {
	FileID       string `json:"file_id"`
	FileUniqueID string `json:"file_unique_id"`
	FileName     string `json:"file_name"`
	MimeType     string `json:"mime_type"`
	FileSize     int64  `json:"file_size"`
}

type telegramVoicePayload struct {
	FileID       string `json:"file_id"`
	FileUniqueID string `json:"file_unique_id"`
	MimeType     string `json:"mime_type"`
	Duration     int    `json:"duration"`
	FileSize     int64  `json:"file_size"`
}

type telegramMessagePayload struct {
	MessageID         int                               `json:"message_id"`
	Chat              telegramChatPayload               `json:"chat"`
	From              *telegramUserPayload              `json:"from"`
	Text              string                            `json:"text"`
	Caption           string                            `json:"caption"`
	Photo             []telegramPhotoPayload            `json:"photo"`
	Document          *telegramDocumentPayload          `json:"document"`
	Voice             *telegramVoicePayload             `json:"voice"`
	SuccessfulPayment *telegramSuccessfulPaymentPayload `json:"successful_payment"`
}

type telegramCallbackMessagePayload struct {
	MessageID int                 `json:"message_id"`
	Chat      telegramChatPayload `json:"chat"`
}

type telegramCallbackQueryPayload struct {
	ID      string                         `json:"id"`
	Data    string                         `json:"data"`
	From    *telegramUserPayload           `json:"from"`
	Message telegramCallbackMessagePayload `json:"message"`
}

func supportTelegramMessageText(message *telegramMessagePayload) string {
	if message == nil {
		return ""
	}

	text := strings.TrimSpace(message.Text)
	if text != "" {
		return text
	}

	return strings.TrimSpace(message.Caption)
}

func supportAttachmentsFromTelegramMessage(message *telegramMessagePayload) []SupportAttachment {
	if message == nil {
		return nil
	}

	attachments := make([]SupportAttachment, 0, 3)

	if len(message.Photo) > 0 {
		photo := message.Photo[len(message.Photo)-1]
		if strings.TrimSpace(photo.FileID) != "" {
			attachments = append(attachments, SupportAttachment{
				Type:         "photo",
				FileID:       photo.FileID,
				FileUniqueID: photo.FileUniqueID,
				FileSize:     photo.FileSize,
				Width:        photo.Width,
				Height:       photo.Height,
			})
		}
	}

	if message.Document != nil && strings.TrimSpace(message.Document.FileID) != "" {
		attachments = append(attachments, SupportAttachment{
			Type:         "document",
			FileID:       message.Document.FileID,
			FileUniqueID: message.Document.FileUniqueID,
			FileName:     message.Document.FileName,
			MimeType:     message.Document.MimeType,
			FileSize:     message.Document.FileSize,
		})
	}

	if message.Voice != nil && strings.TrimSpace(message.Voice.FileID) != "" {
		attachments = append(attachments, SupportAttachment{
			Type:         "voice",
			FileID:       message.Voice.FileID,
			FileUniqueID: message.Voice.FileUniqueID,
			MimeType:     message.Voice.MimeType,
			FileSize:     message.Voice.FileSize,
			Duration:     message.Voice.Duration,
		})
	}

	return attachments
}

func supportPromptPrivateChat(token string, actor cabinetActor) {
	sendCabinetTelegram(
		token,
		actor.ChatID,
		"<b>💬 Поддержка</b>\n\nОткройте бота в личном чате, чтобы создать обращение или ответить оператору.",
		nil,
	)
}

func supportSendTelegramPayload(token, method string, payload map[string]interface{}) {
	url := fmt.Sprintf("https://api.telegram.org/bot%s/%s", token, method)
	resp, err := postTelegramJSON(url, payload)
	if err != nil {
		log.Printf("[SUPPORT] telegram %s error: %v", method, err)
		return
	}
	defer resp.Body.Close()
}

func supportSendAttachment(token string, chatID int64, attachment SupportAttachment) {
	if chatID == 0 || strings.TrimSpace(attachment.FileID) == "" {
		return
	}

	payload := map[string]interface{}{
		"chat_id": chatID,
	}

	method := ""
	switch attachment.Type {
	case "photo":
		method = "sendPhoto"
		payload["photo"] = attachment.FileID
	case "document":
		method = "sendDocument"
		payload["document"] = attachment.FileID
	case "voice":
		method = "sendVoice"
		payload["voice"] = attachment.FileID
	default:
		return
	}

	supportSendTelegramPayload(token, method, payload)
}

func supportSendStoredMessage(token string, chatID int64, header string, message *SupportMessage, keyboard [][]map[string]interface{}) {
	if chatID == 0 {
		return
	}

	text := strings.TrimSpace(header)
	if message != nil {
		if body := strings.TrimSpace(message.Text); body != "" {
			if text != "" {
				text += "\n\n"
			}
			text += cabinetSafe(body)
		} else if summary := supportAttachmentSummary(message.Attachments); summary != "" {
			if text != "" {
				text += "\n\n"
			}
			text += "<i>" + cabinetSafe(summary) + "</i>"
		}
	}

	var replyMarkup interface{}
	if len(keyboard) > 0 {
		replyMarkup = cabinetKeyboard(keyboard...)
	}

	if text != "" || replyMarkup != nil {
		sendCabinetTelegram(token, chatID, text, replyMarkup)
	}

	if message == nil {
		return
	}

	for _, attachment := range message.Attachments {
		supportSendAttachment(token, chatID, attachment)
	}
}

func handleSupportMessage(token string, actor cabinetActor, message *telegramMessagePayload) bool {
	if message == nil {
		return false
	}

	telegramID := actor.effectiveUserID()
	state := supportGetState(telegramID)
	text := supportTelegramMessageText(message)
	attachments := supportAttachmentsFromTelegramMessage(message)
	lowerText := strings.ToLower(strings.TrimSpace(text))

	switch lowerText {
	case "/start", "/help", "/cabinet":
		if state.Mode != "" {
			supportClearMode(telegramID)
		}
		return false
	}

	if strings.HasPrefix(lowerText, "/reply") && supportCanOperateTelegramID(telegramID) {
		if !actor.isPrivateChat() {
			supportPromptPrivateChat(token, actor)
			return true
		}
		return supportHandleReplyCommand(token, actor, text)
	}

	if !actor.isPrivateChat() {
		if state.Mode != "" || lowerText == "/support" || len(attachments) > 0 {
			supportClearMode(telegramID)
			supportPromptPrivateChat(token, actor)
			return true
		}
		return false
	}

	if lowerText == "/support" {
		supportClearMode(telegramID)
		showSupportMenu(token, actor, 0, false)
		return true
	}

	if state.Mode != "" {
		switch state.Mode {
		case "user_new":
			ticketID, err := supportCreateTicket(actor, text, attachments)
			if err != nil {
				sendCabinetTelegram(token, actor.ChatID, "<b>💬 Поддержка</b>\n\n"+cabinetSafe(err.Error()), nil)
				return true
			}

			supportSetState(telegramID, SupportSessionState{
				Mode:             "user_reply",
				TicketID:         ticketID,
				SelectedTicketID: ticketID,
			})
			showSupportUserTicket(token, actor, ticketID, 0, false)
			supportNotifyOperatorsAboutUserMessage(token, ticketID, true)
			return true

		case "user_reply":
			if err := supportAddUserMessage(actor, state.TicketID, text, attachments); err != nil {
				sendCabinetTelegram(token, actor.ChatID, "<b>💬 Поддержка</b>\n\n"+cabinetSafe(err.Error()), nil)
				return true
			}

			supportSetState(telegramID, SupportSessionState{
				Mode:             "user_reply",
				TicketID:         state.TicketID,
				SelectedTicketID: state.TicketID,
			})
			showSupportUserTicket(token, actor, state.TicketID, 0, false)
			supportNotifyOperatorsAboutUserMessage(token, state.TicketID, false)
			return true

		case "operator_reply":
			if err := supportAddOperatorMessage(actor, state.TicketID, text, attachments); err != nil {
				sendCabinetTelegram(token, actor.ChatID, "<b>🛠 Поддержка</b>\n\n"+cabinetSafe(err.Error()), nil)
				return true
			}

			supportSetState(telegramID, SupportSessionState{
				Mode:             "operator_reply",
				TicketID:         state.TicketID,
				SelectedTicketID: state.TicketID,
			})
			showSupportOperatorTicket(token, actor, state.TicketID, 0, false)
			supportNotifyUserAboutOperatorReply(token, state.TicketID)
			return true

		case "operator_search_ticket":
			supportClearMode(telegramID)
			tickets, err := supportSearchOperatorTickets(actor, text)
			if err != nil {
				sendCabinetTelegram(token, actor.ChatID, "<b>🛠 Поиск обращения</b>\n\n"+cabinetSafe(err.Error()), nil)
				return true
			}
			showSupportSearchResults(token, actor, 0, false, "🔍 Результаты поиска обращений", tickets)
			return true

		case "operator_search_user":
			supportClearMode(telegramID)
			tickets, err := supportSearchOperatorUsers(actor, text)
			if err != nil {
				sendCabinetTelegram(token, actor.ChatID, "<b>🛠 Поиск пользователя</b>\n\n"+cabinetSafe(err.Error()), nil)
				return true
			}
			showSupportSearchResults(token, actor, 0, false, "👤 Результаты поиска пользователя", tickets)
			return true
		}
	}

	return false
}

func supportHandleReplyCommand(token string, actor cabinetActor, text string) bool {
	parts := strings.Fields(strings.TrimSpace(text))
	if len(parts) < 2 {
		sendCabinetTelegram(
			token,
			actor.ChatID,
			"<b>🛠 Поддержка</b>\n\nИспользуйте команду вида <code>/reply payment</code> после открытия нужного обращения.",
			nil,
		)
		return true
	}

	key := strings.ToLower(strings.TrimSpace(parts[1]))
	state := supportGetState(actor.effectiveUserID())
	ticketID := strings.TrimSpace(state.SelectedTicketID)
	if ticketID == "" {
		ticketID = strings.TrimSpace(state.TicketID)
	}
	if ticketID == "" {
		sendCabinetTelegram(
			token,
			actor.ChatID,
			"<b>🛠 Поддержка</b>\n\nСначала откройте обращение оператора, затем используйте шаблон ответа.",
			nil,
		)
		return true
	}

	if err := supportApplyQuickReply(actor, ticketID, key); err != nil {
		sendCabinetTelegram(token, actor.ChatID, "<b>🛠 Поддержка</b>\n\n"+cabinetSafe(err.Error()), nil)
		return true
	}

	supportSetState(actor.effectiveUserID(), SupportSessionState{
		Mode:             "operator_reply",
		TicketID:         ticketID,
		SelectedTicketID: ticketID,
	})
	showSupportOperatorTicket(token, actor, ticketID, 0, false)
	supportNotifyUserAboutOperatorReply(token, ticketID)
	return true
}

func handleSupportCallback(token string, actor cabinetActor, callbackID, data string, messageID int) bool {
	if !strings.HasPrefix(data, "support_") {
		return false
	}

	answerCallback(token, callbackID)

	if !actor.isPrivateChat() {
		supportPromptPrivateChat(token, actor)
		return true
	}

	telegramID := actor.effectiveUserID()

	switch {
	case data == "support_menu":
		supportClearMode(telegramID)
		showSupportMenu(token, actor, messageID, true)
		return true

	case data == "support_op_menu":
		supportClearMode(telegramID)
		showSupportOperatorMenu(token, actor, messageID, true)
		return true

	case data == "support_user_new":
		supportSetState(telegramID, SupportSessionState{
			Mode:             "user_new",
			SelectedTicketID: supportGetState(telegramID).SelectedTicketID,
		})
		showSupportNewPrompt(token, actor, messageID, true)
		return true

	case strings.HasPrefix(data, "support_user_list_"):
		page := supportParsePage(strings.TrimPrefix(data, "support_user_list_"))
		supportClearMode(telegramID)
		showSupportUserList(token, actor, messageID, true, page)
		return true

	case strings.HasPrefix(data, "support_user_ticket_"):
		ticketID := strings.TrimPrefix(data, "support_user_ticket_")
		supportSetState(telegramID, SupportSessionState{
			SelectedTicketID: ticketID,
		})
		showSupportUserTicket(token, actor, ticketID, messageID, true)
		return true

	case strings.HasPrefix(data, "support_user_reply_"):
		ticketID := strings.TrimPrefix(data, "support_user_reply_")
		supportSetState(telegramID, SupportSessionState{
			Mode:             "user_reply",
			TicketID:         ticketID,
			SelectedTicketID: ticketID,
		})
		showSupportUserReplyPrompt(token, actor, ticketID, messageID, true)
		return true

	case strings.HasPrefix(data, "support_user_close_"):
		ticketID := strings.TrimPrefix(data, "support_user_close_")
		err := supportCloseTicketByUser(actor, ticketID)
		if err != nil {
			sendCabinetTelegram(token, actor.ChatID, "<b>💬 Поддержка</b>\n\n"+cabinetSafe(err.Error()), nil)
			return true
		}
		showSupportUserTicket(token, actor, ticketID, messageID, true)
		return true

	case strings.HasPrefix(data, "support_user_hist_"):
		payload := strings.TrimPrefix(data, "support_user_hist_")
		ticketID, page := supportSplitTicketAndPage(payload)
		showSupportHistory(token, actor, ticketID, page, false, messageID, true)
		return true

	case strings.HasPrefix(data, "support_op_new_"):
		page := supportParsePage(strings.TrimPrefix(data, "support_op_new_"))
		supportClearMode(telegramID)
		showSupportOperatorTicketList(token, actor, messageID, true, "📥 Новые обращения", supportStatusNew, page)
		return true

	case strings.HasPrefix(data, "support_op_all_"):
		page := supportParsePage(strings.TrimPrefix(data, "support_op_all_"))
		supportClearMode(telegramID)
		showSupportOperatorTicketList(token, actor, messageID, true, "📂 Все обращения", "", page)
		return true

	case data == "support_op_search_ticket":
		supportSetState(telegramID, SupportSessionState{
			Mode:             "operator_search_ticket",
			SelectedTicketID: supportGetState(telegramID).SelectedTicketID,
		})
		showSupportSearchPrompt(token, actor, messageID, true, "🔍 Поиск обращения", "Отправьте ID обращения, email, username или часть текста из обращения.")
		return true

	case data == "support_op_search_user":
		supportSetState(telegramID, SupportSessionState{
			Mode:             "operator_search_user",
			SelectedTicketID: supportGetState(telegramID).SelectedTicketID,
		})
		showSupportSearchPrompt(token, actor, messageID, true, "👤 Поиск пользователя", "Отправьте email, username, имя или Telegram ID пользователя.")
		return true

	case strings.HasPrefix(data, "support_op_ticket_"):
		ticketID := strings.TrimPrefix(data, "support_op_ticket_")
		supportSetState(telegramID, SupportSessionState{
			SelectedTicketID: ticketID,
		})
		showSupportOperatorTicket(token, actor, ticketID, messageID, true)
		return true

	case strings.HasPrefix(data, "support_op_lock_"):
		ticketID := strings.TrimPrefix(data, "support_op_lock_")
		err := supportTakeTicket(actor, ticketID)
		if err != nil {
			sendCabinetTelegram(token, actor.ChatID, "<b>🛠 Поддержка</b>\n\n"+cabinetSafe(err.Error()), nil)
			return true
		}
		supportSetState(telegramID, SupportSessionState{
			SelectedTicketID: ticketID,
		})
		showSupportOperatorTicket(token, actor, ticketID, messageID, true)
		return true

	case strings.HasPrefix(data, "support_op_unlock_"):
		ticketID := strings.TrimPrefix(data, "support_op_unlock_")
		err := supportUnlockTicket(actor, ticketID)
		if err != nil {
			sendCabinetTelegram(token, actor.ChatID, "<b>🛠 Поддержка</b>\n\n"+cabinetSafe(err.Error()), nil)
			return true
		}
		supportSetState(telegramID, SupportSessionState{
			SelectedTicketID: ticketID,
		})
		showSupportOperatorTicket(token, actor, ticketID, messageID, true)
		return true

	case strings.HasPrefix(data, "support_op_reply_"):
		ticketID := strings.TrimPrefix(data, "support_op_reply_")
		supportSetState(telegramID, SupportSessionState{
			Mode:             "operator_reply",
			TicketID:         ticketID,
			SelectedTicketID: ticketID,
		})
		showSupportOperatorReplyPrompt(token, actor, ticketID, messageID, true)
		return true

	case strings.HasPrefix(data, "support_op_hist_"):
		payload := strings.TrimPrefix(data, "support_op_hist_")
		ticketID, page := supportSplitTicketAndPage(payload)
		showSupportHistory(token, actor, ticketID, page, true, messageID, true)
		return true

	case strings.HasPrefix(data, "support_op_status_"):
		payload := strings.TrimPrefix(data, "support_op_status_")
		parts := strings.SplitN(payload, "_", 2)
		if len(parts) != 2 {
			return true
		}
		status := supportStatusFromCode(parts[0])
		ticketID := parts[1]
		err := supportSetTicketStatus(actor, ticketID, status)
		if err != nil {
			sendCabinetTelegram(token, actor.ChatID, "<b>🛠 Поддержка</b>\n\n"+cabinetSafe(err.Error()), nil)
			return true
		}
		supportSetState(telegramID, SupportSessionState{
			SelectedTicketID: ticketID,
		})
		showSupportOperatorTicket(token, actor, ticketID, messageID, true)
		return true

	case strings.HasPrefix(data, "support_op_tmpls_"):
		ticketID := strings.TrimPrefix(data, "support_op_tmpls_")
		showSupportTemplateMenu(token, actor, ticketID, messageID, true)
		return true

	case strings.HasPrefix(data, "support_op_template_"):
		payload := strings.TrimPrefix(data, "support_op_template_")
		parts := strings.SplitN(payload, "_", 2)
		if len(parts) != 2 {
			return true
		}
		key := parts[0]
		ticketID := parts[1]
		err := supportApplyQuickReply(actor, ticketID, key)
		if err != nil {
			sendCabinetTelegram(token, actor.ChatID, "<b>🛠 Поддержка</b>\n\n"+cabinetSafe(err.Error()), nil)
			return true
		}
		supportSetState(telegramID, SupportSessionState{
			Mode:             "operator_reply",
			TicketID:         ticketID,
			SelectedTicketID: ticketID,
		})
		showSupportOperatorTicket(token, actor, ticketID, messageID, true)
		supportNotifyUserAboutOperatorReply(token, ticketID)
		return true
	}

	return true
}

func showSupportMenu(token string, actor cabinetActor, messageID int, edit bool) {
	telegramID := actor.effectiveUserID()

	if !cabinetHasLinkedUser(telegramID) && !supportCanOperateTelegramID(telegramID) {
		cabinetRender(
			token,
			actor.ChatID,
			messageID,
			"<b>💬 Поддержка</b>\n\nЧтобы создать обращение, сначала войдите в существующий аккаунт WDTT или зарегистрируйтесь прямо в Telegram.",
			[][]map[string]interface{}{
				{cabinetButton("🔑 Войти", "cabinet_login")},
				{cabinetButton("🆕 Зарегистрироваться", "cabinet_register")},
				{cabinetButton("⬅️ Назад", "cabinet_open")},
			},
			edit,
		)
		return
	}

	keyboard := [][]map[string]interface{}{}

	if cabinetHasLinkedUser(telegramID) {
		keyboard = append(keyboard,
			[]map[string]interface{}{cabinetButton("➕ Новое обращение", "support_user_new")},
			[]map[string]interface{}{cabinetButton("📂 Мои обращения", "support_user_list_0")},
		)
	}

	if supportCanOperateTelegramID(telegramID) {
		keyboard = append(keyboard, []map[string]interface{}{
			cabinetButton("🛠 Режим оператора", "support_op_menu"),
		})
	}

	keyboard = append(keyboard, []map[string]interface{}{
		cabinetButton("⬅️ Назад", "cabinet_open"),
	})

	cabinetRender(
		token,
		actor.ChatID,
		messageID,
		"<b>💬 Поддержка</b>\n\nВсе обращения ведутся прямо в Telegram. Вы можете создать новое обращение, посмотреть историю своих тикетов и продолжить переписку с оператором без перехода на сайт.",
		keyboard,
		edit,
	)
}

func showSupportOperatorMenu(token string, actor cabinetActor, messageID int, edit bool) {
	if !supportCanOperateTelegramID(actor.effectiveUserID()) {
		cabinetRender(
			token,
			actor.ChatID,
			messageID,
			"<b>🛠 Режим оператора</b>\n\nЭтот режим доступен только операторам поддержки.",
			[][]map[string]interface{}{
				{cabinetButton("⬅️ Назад", "support_menu")},
			},
			edit,
		)
		return
	}

	cabinetRender(
		token,
		actor.ChatID,
		messageID,
		"<b>🛠 Режим оператора</b>\n\nВыберите нужный раздел.",
		[][]map[string]interface{}{
			{cabinetButton("📥 Новые обращения", "support_op_new_0")},
			{cabinetButton("📂 Все обращения", "support_op_all_0")},
			{cabinetButton("🔍 Поиск обращения", "support_op_search_ticket")},
			{cabinetButton("👤 Поиск пользователя", "support_op_search_user")},
			{cabinetButton("⬅️ Назад", "support_menu")},
		},
		edit,
	)
}

func showSupportNewPrompt(token string, actor cabinetActor, messageID int, edit bool) {
	cabinetRender(
		token,
		actor.ChatID,
		messageID,
		"<b>➕ Новое обращение</b>\n\nОтправьте первое сообщение для поддержки. Можно приложить текст, фото, документ или голосовое сообщение.",
		[][]map[string]interface{}{
			{cabinetButton("⬅️ Назад", "support_menu")},
		},
		edit,
	)
}

func showSupportUserReplyPrompt(token string, actor cabinetActor, ticketID string, messageID int, edit bool) {
	cabinetRender(
		token,
		actor.ChatID,
		messageID,
		fmt.Sprintf(
			"<b>💬 Ответ в обращение %s</b>\n\nОтправьте сообщение, фото, документ или голосовое сообщение. После отправки ответ сразу попадет оператору.",
			cabinetSafe(ticketID),
		),
		[][]map[string]interface{}{
			{cabinetButton("⬅️ Назад к обращению", "support_user_ticket_"+ticketID)},
		},
		edit,
	)
}

func showSupportOperatorReplyPrompt(token string, actor cabinetActor, ticketID string, messageID int, edit bool) {
	cabinetRender(
		token,
		actor.ChatID,
		messageID,
		fmt.Sprintf(
			"<b>🛠 Ответ в обращение %s</b>\n\nОтправьте текст, фото, документ или голосовое сообщение. Можно также использовать шаблон командой <code>/reply payment</code> или кнопкой «Шаблоны».",
			cabinetSafe(ticketID),
		),
		[][]map[string]interface{}{
			{cabinetButton("⚡ Шаблоны", "support_op_tmpls_"+ticketID)},
			{cabinetButton("⬅️ Назад к обращению", "support_op_ticket_"+ticketID)},
		},
		edit,
	)
}

func showSupportSearchPrompt(token string, actor cabinetActor, messageID int, edit bool, title string, body string) {
	cabinetRender(
		token,
		actor.ChatID,
		messageID,
		fmt.Sprintf("<b>%s</b>\n\n%s", cabinetSafe(title), cabinetSafe(body)),
		[][]map[string]interface{}{
			{cabinetButton("⬅️ Назад", "support_op_menu")},
		},
		edit,
	)
}

func showSupportUserList(token string, actor cabinetActor, messageID int, edit bool, page int) {
	tickets, err := supportListUserTickets(actor)
	if err != nil {
		cabinetRender(
			token,
			actor.ChatID,
			messageID,
			"<b>💬 Поддержка</b>\n\n"+cabinetSafe(err.Error()),
			[][]map[string]interface{}{
				{cabinetButton("⬅️ Назад", "support_menu")},
			},
			edit,
		)
		return
	}

	supportRenderTicketList(
		token,
		actor.ChatID,
		messageID,
		edit,
		"📂 Мои обращения",
		"У вас пока нет обращений в поддержку.",
		tickets,
		page,
		"support_user_list_",
		"support_user_ticket_",
		"support_menu",
	)
}

func showSupportOperatorTicketList(token string, actor cabinetActor, messageID int, edit bool, title string, statusFilter string, page int) {
	tickets, err := supportListOperatorTickets(actor, statusFilter)
	if err != nil {
		cabinetRender(
			token,
			actor.ChatID,
			messageID,
			"<b>🛠 Поддержка</b>\n\n"+cabinetSafe(err.Error()),
			[][]map[string]interface{}{
				{cabinetButton("⬅️ Назад", "support_op_menu")},
			},
			edit,
		)
		return
	}

	pagePrefix := "support_op_all_"
	if statusFilter == supportStatusNew {
		pagePrefix = "support_op_new_"
	}

	supportRenderTicketList(
		token,
		actor.ChatID,
		messageID,
		edit,
		title,
		"Обращений пока нет.",
		tickets,
		page,
		pagePrefix,
		"support_op_ticket_",
		"support_op_menu",
	)
}

func showSupportSearchResults(token string, actor cabinetActor, messageID int, edit bool, title string, tickets []*SupportTicket) {
	text := "<b>" + cabinetSafe(title) + "</b>\n\n"
	if len(tickets) == 0 {
		text += "Ничего не найдено."
		cabinetRender(
			token,
			actor.ChatID,
			messageID,
			text,
			[][]map[string]interface{}{
				{cabinetButton("⬅️ Назад", "support_op_menu")},
			},
			edit,
		)
		return
	}

	if len(tickets) > supportListPageSize {
		text += fmt.Sprintf("Найдено %d обращений. Показываю первые %d.\n\n", len(tickets), supportListPageSize)
	}

	keyboard := make([][]map[string]interface{}, 0, supportListPageSize+1)
	limit := len(tickets)
	if limit > supportListPageSize {
		limit = supportListPageSize
	}

	for i := 0; i < limit; i++ {
		ticket := tickets[i]
		keyboard = append(keyboard, []map[string]interface{}{
			cabinetButton(supportTicketButtonLabel(ticket, true), "support_op_ticket_"+ticket.ID),
		})
	}

	keyboard = append(keyboard, []map[string]interface{}{
		cabinetButton("⬅️ Назад", "support_op_menu"),
	})

	cabinetRender(token, actor.ChatID, messageID, text, keyboard, edit)
}

func showSupportUserTicket(token string, actor cabinetActor, ticketID string, messageID int, edit bool) {
	ticket, err := supportGetUserTicket(actor, ticketID)
	if err != nil {
		cabinetRender(
			token,
			actor.ChatID,
			messageID,
			"<b>💬 Поддержка</b>\n\n"+cabinetSafe(err.Error()),
			[][]map[string]interface{}{
				{cabinetButton("⬅️ Назад", "support_user_list_0")},
			},
			edit,
		)
		return
	}

	text := fmt.Sprintf(
		"<b>💬 Обращение %s</b>\n\n"+
			"<b>ID:</b> <code>%s</code>\n"+
			"<b>Статус:</b> %s %s\n"+
			"<b>Создано:</b> %s\n"+
			"<b>Последнее сообщение:</b> %s\n",
		cabinetSafe(ticket.ID),
		cabinetSafe(ticket.ID),
		supportStatusEmoji(ticket.Status),
		cabinetSafe(supportStatusLabel(ticket.Status)),
		cabinetFormatDate(ticket.CreatedAt),
		cabinetFormatDate(ticket.LastMessageAt),
	)

	if ticket.AssignedOperatorName != "" {
		text += "<b>Оператор:</b> " + cabinetSafe(ticket.AssignedOperatorName) + "\n"
	}

	text += "\n<b>Последние сообщения:</b>\n" + supportRecentMessagesText(ticket.Messages, 3)

	keyboard := [][]map[string]interface{}{
		{
			cabinetButton("✉️ Ответить", "support_user_reply_"+ticket.ID),
			cabinetButton("📜 История", "support_user_hist_"+ticket.ID+"_0"),
		},
	}

	if ticket.Status != supportStatusClosed {
		keyboard = append(keyboard, []map[string]interface{}{
			cabinetButton("✅ Закрыть обращение", "support_user_close_"+ticket.ID),
		})
	}

	keyboard = append(keyboard,
		[]map[string]interface{}{cabinetButton("⬅️ К списку", "support_user_list_0")},
		[]map[string]interface{}{cabinetButton("🏠 Поддержка", "support_menu")},
	)

	cabinetRender(token, actor.ChatID, messageID, text, keyboard, edit)
}

func showSupportOperatorTicket(token string, actor cabinetActor, ticketID string, messageID int, edit bool) {
	snapshot, err := supportTicketSnapshotForOperator(actor, ticketID)
	if err != nil {
		cabinetRender(
			token,
			actor.ChatID,
			messageID,
			"<b>🛠 Поддержка</b>\n\n"+cabinetSafe(err.Error()),
			[][]map[string]interface{}{
				{cabinetButton("⬅️ Назад", "support_op_menu")},
			},
			edit,
		)
		return
	}

	ticket := snapshot.Ticket
	user := snapshot.User
	entry := snapshot.Entry

	registrationDate := "Нет данных"
	subscriptionStatus := "Нет данных"
	plan := "Нет данных"
	deviceUsage := "Нет данных"

	if user != nil {
		registrationDate = cabinetFormatDate(user.CreatedAt)
		subscriptionStatus = cabinetSubscriptionStatusLabel(user, entry)
		plan = cabinetPlanLabel(user.SubscriptionPlan)
		usedDevices := 0
		deviceLimit := user.DeviceLimit
		if entry != nil {
			usedDevices = len(cabinetDeviceIDs(entry))
		}
		deviceUsage = fmt.Sprintf("%d / %d", usedDevices, deviceLimit)
	}

	text := fmt.Sprintf(
		"<b>🛠 Обращение %s</b>\n\n"+
			"<b>ID:</b> <code>%s</code>\n"+
			"<b>Статус:</b> %s %s\n"+
			"<b>Создано:</b> %s\n"+
			"<b>Последнее сообщение:</b> %s\n"+
			"<b>Telegram ID:</b> <code>%d</code>\n"+
			"<b>Имя:</b> %s\n"+
			"<b>Username:</b> %s\n"+
			"<b>Дата регистрации:</b> %s\n"+
			"<b>Статус подписки:</b> %s\n"+
			"<b>Тариф:</b> %s\n"+
			"<b>Устройства:</b> %s\n",
		cabinetSafe(ticket.ID),
		cabinetSafe(ticket.ID),
		supportStatusEmoji(ticket.Status),
		cabinetSafe(supportStatusLabel(ticket.Status)),
		cabinetFormatDate(ticket.CreatedAt),
		cabinetFormatDate(ticket.LastMessageAt),
		ticket.OwnerTelegramID,
		cabinetSafe(ticket.OwnerName),
		cabinetSafe(supportUsernameLabel(ticket.OwnerUsername)),
		registrationDate,
		cabinetSafe(subscriptionStatus),
		cabinetSafe(plan),
		cabinetSafe(deviceUsage),
	)

	if ticket.LockedByTelegramID != 0 {
		text += fmt.Sprintf("<b>Блокировка:</b> %s (%s)\n", cabinetSafe(ticket.LockedByName), cabinetFormatDate(ticket.LockedAt))
	}

	text += "\n<b>История заказов:</b>\n" + supportOrdersText(snapshot.Orders, 4)
	text += "\n\n<b>Последние сообщения:</b>\n" + supportRecentMessagesText(ticket.Messages, 4)

	if hint := supportAIHintText(ticket.AIHint); hint != "" {
		text += "\n\n<b>AI-подсказка:</b>\n" + hint
	}

	lockButton := cabinetButton("🔒 Взять в работу", "support_op_lock_"+ticket.ID)
	if ticket.LockedByTelegramID == actor.effectiveUserID() {
		lockButton = cabinetButton("🔓 Снять блокировку", "support_op_unlock_"+ticket.ID)
	}

	keyboard := [][]map[string]interface{}{
		{lockButton, cabinetButton("✉️ Ответить", "support_op_reply_"+ticket.ID)},
		{cabinetButton("📜 История", "support_op_hist_"+ticket.ID+"_0"), cabinetButton("⚡ Шаблоны", "support_op_tmpls_"+ticket.ID)},
		{
			cabinetButton("🆕 Новый", "support_op_status_new_"+ticket.ID),
			cabinetButton("🛠 В работе", "support_op_status_work_"+ticket.ID),
		},
		{
			cabinetButton("⏳ Ожидание", "support_op_status_wait_"+ticket.ID),
			cabinetButton("✅ Закрыт", "support_op_status_closed_"+ticket.ID),
		},
		{cabinetButton("⬅️ К списку", "support_op_all_0")},
		{cabinetButton("🏠 Оператор", "support_op_menu")},
	}

	cabinetRender(token, actor.ChatID, messageID, text, keyboard, edit)
}

func showSupportHistory(token string, actor cabinetActor, ticketID string, page int, operatorView bool, messageID int, edit bool) {
	var (
		ticket *SupportTicket
		err    error
	)

	if operatorView {
		var snapshot *supportTicketSnapshot
		snapshot, err = supportTicketSnapshotForOperator(actor, ticketID)
		if err == nil {
			ticket = snapshot.Ticket
		}
	} else {
		ticket, err = supportGetUserTicket(actor, ticketID)
	}

	if err != nil {
		cabinetRender(
			token,
			actor.ChatID,
			messageID,
			"<b>💬 Поддержка</b>\n\n"+cabinetSafe(err.Error()),
			[][]map[string]interface{}{
				{cabinetButton("⬅️ Назад", "support_menu")},
			},
			edit,
		)
		return
	}

	messages, totalPages, page := supportHistoryWindow(ticket.Messages, page, supportHistoryPageSize)
	text := fmt.Sprintf(
		"<b>📜 История обращения %s</b>\n\n<b>Страница:</b> %d/%d\n\n",
		cabinetSafe(ticket.ID),
		page+1,
		totalPages,
	)

	for _, message := range messages {
		text += supportMessageHTML(message) + "\n\n"
	}
	if len(messages) == 0 {
		text += "История пока пуста."
	}

	keyboard := [][]map[string]interface{}{}
	if totalPages > 1 {
		row := []map[string]interface{}{}
		if page > 0 {
			row = append(row, cabinetButton("⬅️", supportHistoryPageCallback(ticket.ID, page-1, operatorView)))
		}
		if page+1 < totalPages {
			row = append(row, cabinetButton("➡️", supportHistoryPageCallback(ticket.ID, page+1, operatorView)))
		}
		if len(row) > 0 {
			keyboard = append(keyboard, row)
		}
	}

	backData := "support_user_ticket_" + ticket.ID
	if operatorView {
		backData = "support_op_ticket_" + ticket.ID
	}
	keyboard = append(keyboard, []map[string]interface{}{
		cabinetButton("⬅️ Назад к обращению", backData),
	})

	cabinetRender(token, actor.ChatID, messageID, text, keyboard, edit)
}

func showSupportTemplateMenu(token string, actor cabinetActor, ticketID string, messageID int, edit bool) {
	if !supportCanOperateTelegramID(actor.effectiveUserID()) {
		showSupportOperatorMenu(token, actor, messageID, edit)
		return
	}

	keyboard := make([][]map[string]interface{}, 0, len(supportReplyTemplateOrder)+1)
	for _, key := range supportReplyTemplateOrder {
		keyboard = append(keyboard, []map[string]interface{}{
			cabinetButton(supportQuickReplyLabel(key), "support_op_template_"+key+"_"+ticketID),
		})
	}
	keyboard = append(keyboard, []map[string]interface{}{
		cabinetButton("⬅️ Назад к обращению", "support_op_ticket_"+ticketID),
	})

	cabinetRender(
		token,
		actor.ChatID,
		messageID,
		fmt.Sprintf("<b>⚡ Шаблоны ответа</b>\n\nВыберите быстрый ответ для обращения <code>%s</code>.", cabinetSafe(ticketID)),
		keyboard,
		edit,
	)
}

func supportRenderTicketList(token string, chatID int64, messageID int, edit bool, title string, emptyText string, tickets []*SupportTicket, page int, pagePrefix string, ticketPrefix string, backData string) {
	text := "<b>" + cabinetSafe(title) + "</b>\n\n"
	if len(tickets) == 0 {
		text += cabinetSafe(emptyText)
		cabinetRender(
			token,
			chatID,
			messageID,
			text,
			[][]map[string]interface{}{
				{cabinetButton("⬅️ Назад", backData)},
			},
			edit,
		)
		return
	}

	pageTickets, totalPages, page := supportTicketWindow(tickets, page, supportListPageSize)
	text += fmt.Sprintf("<b>Страница:</b> %d/%d\n", page+1, totalPages)

	keyboard := make([][]map[string]interface{}, 0, len(pageTickets)+2)
	for _, ticket := range pageTickets {
		keyboard = append(keyboard, []map[string]interface{}{
			cabinetButton(supportTicketButtonLabel(ticket, strings.HasPrefix(ticketPrefix, "support_op_")), ticketPrefix+ticket.ID),
		})
	}

	if totalPages > 1 {
		row := []map[string]interface{}{}
		if page > 0 {
			row = append(row, cabinetButton("⬅️", pagePrefix+strconv.Itoa(page-1)))
		}
		if page+1 < totalPages {
			row = append(row, cabinetButton("➡️", pagePrefix+strconv.Itoa(page+1)))
		}
		if len(row) > 0 {
			keyboard = append(keyboard, row)
		}
	}

	keyboard = append(keyboard, []map[string]interface{}{
		cabinetButton("⬅️ Назад", backData),
	})

	cabinetRender(token, chatID, messageID, text, keyboard, edit)
}

func supportTicketButtonLabel(ticket *SupportTicket, operatorView bool) string {
	if ticket == nil {
		return "Обращение"
	}

	label := fmt.Sprintf("%s %s", supportStatusEmoji(ticket.Status), ticket.ID)
	if operatorView {
		name := strings.TrimSpace(ticket.OwnerName)
		if name != "" {
			label += " · " + name
		}
	}
	return label
}

func supportOrdersText(orders []*Order, limit int) string {
	if len(orders) == 0 {
		return "Заказов пока нет."
	}

	if limit <= 0 || limit > len(orders) {
		limit = len(orders)
	}

	lines := make([]string, 0, limit+1)
	for i := 0; i < limit; i++ {
		order := orders[i]
		lines = append(lines, fmt.Sprintf(
			"• %s · %s · %d RUB · %s",
			cabinetFormatDate(order.CreatedAt),
			cabinetSafe(cabinetOrderTypeLabel(order.Type)),
			order.Price,
			cabinetSafe(cabinetOrderStatusLabel(order.Status)),
		))
	}

	if len(orders) > limit {
		lines = append(lines, fmt.Sprintf("• И еще %d заказ(ов)", len(orders)-limit))
	}

	return strings.Join(lines, "\n")
}

func supportRecentMessagesText(messages []*SupportMessage, limit int) string {
	if len(messages) == 0 {
		return "История пока пуста."
	}

	if limit <= 0 {
		limit = 1
	}

	start := len(messages) - limit
	if start < 0 {
		start = 0
	}

	parts := make([]string, 0, len(messages)-start)
	for i := start; i < len(messages); i++ {
		parts = append(parts, supportMessageHTML(messages[i]))
	}
	return strings.Join(parts, "\n\n")
}

func supportMessageHTML(message *SupportMessage) string {
	if message == nil {
		return ""
	}

	label := "Сообщение"
	switch message.SenderRole {
	case supportSenderUser:
		label = "Пользователь"
	case supportSenderOperator:
		label = "Оператор"
	case supportSenderSystem:
		label = "Система"
	}

	if strings.TrimSpace(message.SenderName) != "" && message.SenderRole != supportSenderSystem {
		label += ": " + message.SenderName
	}

	body := strings.TrimSpace(message.Text)
	if body == "" {
		body = supportAttachmentSummary(message.Attachments)
	}
	if body == "" {
		body = "Сообщение без текста"
	}
	if len(body) > 400 {
		body = body[:397] + "..."
	}

	if summary := supportAttachmentSummary(message.Attachments); summary != "" && strings.TrimSpace(message.Text) != "" {
		body += "\nВложения: " + summary
	}

	return fmt.Sprintf(
		"<b>%s</b> · %s\n%s",
		cabinetSafe(label),
		cabinetFormatDate(message.CreatedAt),
		cabinetSafe(body),
	)
}

func supportAIHintText(hint *SupportAIHint) string {
	if hint == nil {
		return ""
	}

	parts := make([]string, 0, 3)
	if strings.TrimSpace(hint.SuggestedReply) != "" {
		parts = append(parts, "• Готовый ответ: "+cabinetSafe(hint.SuggestedReply))
	}
	if strings.TrimSpace(hint.PossibleSolution) != "" {
		parts = append(parts, "• Возможное решение: "+cabinetSafe(hint.PossibleSolution))
	}
	if len(hint.SimilarTicketIDs) > 0 {
		parts = append(parts, "• Похожие обращения: "+cabinetSafe(strings.Join(hint.SimilarTicketIDs, ", ")))
	}
	return strings.Join(parts, "\n")
}

func supportUsernameLabel(username string) string {
	username = strings.TrimSpace(username)
	if username == "" {
		return "Не указан"
	}
	if strings.HasPrefix(username, "@") {
		return username
	}
	return "@" + username
}

func supportTicketWindow(tickets []*SupportTicket, page int, pageSize int) ([]*SupportTicket, int, int) {
	if pageSize <= 0 {
		pageSize = supportListPageSize
	}
	totalPages := 1
	if len(tickets) > 0 {
		totalPages = (len(tickets) + pageSize - 1) / pageSize
	}
	if page < 0 {
		page = 0
	}
	if page >= totalPages {
		page = totalPages - 1
	}

	start := page * pageSize
	if start < 0 {
		start = 0
	}
	end := start + pageSize
	if end > len(tickets) {
		end = len(tickets)
	}

	return tickets[start:end], totalPages, page
}

func supportHistoryWindow(messages []*SupportMessage, page int, pageSize int) ([]*SupportMessage, int, int) {
	if pageSize <= 0 {
		pageSize = supportHistoryPageSize
	}
	totalPages := 1
	if len(messages) > 0 {
		totalPages = (len(messages) + pageSize - 1) / pageSize
	}
	if page < 0 {
		page = 0
	}
	if page >= totalPages {
		page = totalPages - 1
	}

	collected := make([]*SupportMessage, 0, pageSize)
	startOffset := page * pageSize
	endOffset := startOffset + pageSize
	if endOffset > len(messages) {
		endOffset = len(messages)
	}

	for offset := startOffset; offset < endOffset; offset++ {
		index := len(messages) - 1 - offset
		if index < 0 || index >= len(messages) {
			continue
		}
		collected = append(collected, messages[index])
	}

	for i, j := 0, len(collected)-1; i < j; i, j = i+1, j-1 {
		collected[i], collected[j] = collected[j], collected[i]
	}

	return collected, totalPages, page
}

func supportHistoryPageCallback(ticketID string, page int, operatorView bool) string {
	if operatorView {
		return fmt.Sprintf("support_op_hist_%s_%d", ticketID, page)
	}
	return fmt.Sprintf("support_user_hist_%s_%d", ticketID, page)
}

func supportParsePage(value string) int {
	page, err := strconv.Atoi(strings.TrimSpace(value))
	if err != nil || page < 0 {
		return 0
	}
	return page
}

func supportSplitTicketAndPage(value string) (string, int) {
	parts := strings.Split(value, "_")
	if len(parts) < 2 {
		return value, 0
	}

	page := supportParsePage(parts[len(parts)-1])
	ticketID := strings.Join(parts[:len(parts)-1], "_")
	return ticketID, page
}

func supportNotifyOperatorsAboutUserMessage(token string, ticketID string, isNewTicket bool) {
	ticket, message := supportNotificationTicket(ticketID)
	if ticket == nil || message == nil {
		return
	}

	title := fmt.Sprintf("<b>💬 Обращение %s</b>\n", cabinetSafe(ticket.ID))
	if isNewTicket {
		title = fmt.Sprintf("<b>📥 Новое обращение %s</b>\n", cabinetSafe(ticket.ID))
	}
	title += fmt.Sprintf(
		"<b>Пользователь:</b> %s\n<b>Статус:</b> %s",
		cabinetSafe(ticket.OwnerName),
		cabinetSafe(supportStatusLabel(ticket.Status)),
	)

	keyboard := [][]map[string]interface{}{
		{cabinetButton("Открыть обращение", "support_op_ticket_"+ticket.ID)},
	}

	for _, chatID := range supportOperatorChatIDs() {
		if chatID == 0 {
			continue
		}
		supportSendStoredMessage(token, chatID, title, message, keyboard)
	}
}

func supportNotifyUserAboutOperatorReply(token string, ticketID string) {
	ticket, message := supportNotificationTicket(ticketID)
	if ticket == nil || message == nil {
		return
	}

	chatID := ticket.OwnerTelegramID
	if chatID == 0 {
		return
	}

	title := fmt.Sprintf(
		"<b>💬 Ответ по обращению %s</b>\n<b>Статус:</b> %s",
		cabinetSafe(ticket.ID),
		cabinetSafe(supportStatusLabel(ticket.Status)),
	)

	supportSendStoredMessage(
		token,
		chatID,
		title,
		message,
		[][]map[string]interface{}{
			{cabinetButton("Открыть обращение", "support_user_ticket_"+ticket.ID)},
		},
	)
}

func supportNotificationTicket(ticketID string) (*SupportTicket, *SupportMessage) {
	dbMutex.Lock()
	defer dbMutex.Unlock()

	supportEnsureDBLocked()

	ticket := db.SupportTickets[ticketID]
	if ticket == nil || len(ticket.Messages) == 0 {
		return nil, nil
	}

	return ticket, ticket.Messages[len(ticket.Messages)-1]
}
