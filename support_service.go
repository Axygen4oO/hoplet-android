package main

import (
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"sort"
	"strconv"
	"strings"
	"time"
)

var supportReplyTemplateOrder = []string{"payment", "subscription", "devices", "update"}

var supportReplyTemplateLabels = map[string]string{
	"payment":      "💳 Оплата",
	"subscription": "🔄 Подписка",
	"devices":      "📱 Устройства",
	"update":       "⬆️ Обновление",
}

var supportReplyTemplates = map[string]string{
	"payment":      "Проверили типовой сценарий оплаты. Пожалуйста, откройте историю заказов и сообщите номер последнего заказа, если платеж уже прошел, но подписка не обновилась. Мы дополнительно сверим статус заказа на сервере.",
	"subscription": "Проверяем состояние вашей подписки и срок действия. Если подписка уже оплачена, но статус не обновился, уточните примерное время оплаты или продления, чтобы мы сверили данные на сервере.",
	"devices":      "Проверяем лимит устройств и текущие привязки. Если ошибка возникает при подключении нового устройства, напишите название устройства и что именно показывает бот или приложение.",
	"update":       "Похоже, вопрос связан с обновлением приложения или конфигурации. Уточните, пожалуйста, на каком устройстве возникает проблема и после какого действия она появилась, чтобы мы подсказали точное решение.",
}

func supportQuickReplyTemplate(key string) (string, bool) {
	text, ok := supportReplyTemplates[strings.ToLower(strings.TrimSpace(key))]
	return text, ok
}

func supportQuickReplyLabel(key string) string {
	if label, ok := supportReplyTemplateLabels[key]; ok {
		return label
	}
	return key
}

func supportEnsureDBLocked() {
	if db.SupportTickets == nil {
		db.SupportTickets = make(map[string]*SupportTicket)
	}
}

func supportCanOperateRole(role string) bool {
	switch strings.ToLower(strings.TrimSpace(role)) {
	case "admin", "operator", "support":
		return true
	default:
		return false
	}
}

func supportCanOperateTelegramID(telegramID int64) bool {
	if telegramID == 0 {
		return false
	}

	dbMutex.Lock()
	defer dbMutex.Unlock()

	if telegramID == botAdminIDGlobal {
		return true
	}

	_, user, ok := cabinetFindUserByTelegramIDLocked(telegramID)
	return ok && user != nil && supportCanOperateRole(user.Role)
}

func supportResolveUserIdentityLocked(actor cabinetActor) (supportIdentity, *UserAccount, bool) {
	email, user, ok := cabinetFindUserByTelegramIDLocked(actor.effectiveUserID())
	if !ok || user == nil {
		return supportIdentity{}, nil, false
	}

	name := strings.TrimSpace(actor.displayName())
	if name == "" || name == "Не указано" {
		name = email
	}

	return supportIdentity{
		Email:      email,
		Name:       name,
		Username:   actor.Username,
		TelegramID: actor.effectiveUserID(),
		Role:       user.Role,
	}, user, true
}

func supportResolveOperatorIdentityLocked(actor cabinetActor) (supportIdentity, bool) {
	identity, user, ok := supportResolveUserIdentityLocked(actor)
	if ok && user != nil && supportCanOperateRole(user.Role) {
		return identity, true
	}

	if actor.effectiveUserID() != 0 && actor.effectiveUserID() == botAdminIDGlobal {
		name := strings.TrimSpace(actor.displayName())
		if name == "" || name == "Не указано" {
			name = "Главный администратор"
		}
		return supportIdentity{
			Name:       name,
			Username:   actor.Username,
			TelegramID: actor.effectiveUserID(),
			Role:       "admin",
		}, true
	}

	return supportIdentity{}, false
}

func supportIsAdminIdentity(identity supportIdentity) bool {
	return strings.EqualFold(strings.TrimSpace(identity.Role), "admin")
}

func supportNewID(prefix string) string {
	raw := make([]byte, 6)
	if _, err := rand.Read(raw); err != nil {
		return fmt.Sprintf("%s-%d", prefix, time.Now().UnixNano())
	}
	return prefix + "-" + strings.ToUpper(hex.EncodeToString(raw))
}

func supportBuildMessage(senderRole string, identity supportIdentity, text string, attachments []SupportAttachment) *SupportMessage {
	cleanAttachments := make([]SupportAttachment, 0, len(attachments))
	for _, attachment := range attachments {
		if strings.TrimSpace(attachment.FileID) == "" {
			continue
		}
		cleanAttachments = append(cleanAttachments, attachment)
	}

	return &SupportMessage{
		ID:               supportNewID("SUPMSG"),
		CreatedAt:        time.Now().Unix(),
		SenderRole:       senderRole,
		SenderEmail:      strings.TrimSpace(identity.Email),
		SenderTelegramID: identity.TelegramID,
		SenderName:       strings.TrimSpace(identity.Name),
		SenderUsername:   strings.TrimSpace(identity.Username),
		Text:             strings.TrimSpace(text),
		Attachments:      cleanAttachments,
	}
}

func supportMessageHasContent(text string, attachments []SupportAttachment) bool {
	return strings.TrimSpace(text) != "" || len(attachments) > 0
}

func supportAttachmentLabel(attachment SupportAttachment) string {
	switch attachment.Type {
	case "photo":
		return "Фото"
	case "document":
		if strings.TrimSpace(attachment.FileName) != "" {
			return "Документ: " + attachment.FileName
		}
		return "Документ"
	case "voice":
		return "Голосовое сообщение"
	default:
		return "Вложение"
	}
}

func supportAttachmentSummary(attachments []SupportAttachment) string {
	if len(attachments) == 0 {
		return ""
	}

	parts := make([]string, 0, len(attachments))
	for _, attachment := range attachments {
		parts = append(parts, supportAttachmentLabel(attachment))
	}

	return strings.Join(parts, ", ")
}

func supportMessagePreview(message *SupportMessage) string {
	if message == nil {
		return ""
	}

	if text := strings.TrimSpace(message.Text); text != "" {
		if len(text) > 120 {
			return text[:117] + "..."
		}
		return text
	}

	if summary := supportAttachmentSummary(message.Attachments); summary != "" {
		return summary
	}

	return "Сообщение без текста"
}

func supportAppendMessageLocked(ticket *SupportTicket, message *SupportMessage) {
	if ticket == nil || message == nil {
		return
	}

	ticket.Messages = append(ticket.Messages, message)
	ticket.LastMessageAt = message.CreatedAt
	ticket.Preview = supportMessagePreview(message)
}

func supportAppendSystemMessageLocked(ticket *SupportTicket, text string) {
	message := supportBuildMessage(
		supportSenderSystem,
		supportIdentity{Name: "Система"},
		text,
		nil,
	)
	supportAppendMessageLocked(ticket, message)
}

type supportAIHintJob struct {
	ticketID      string
	lastMessageAt int64
	messageCount  int
	ticket        *SupportTicket
	related       []*SupportTicket
}

func supportPrepareAIHintJobLocked(ticket *SupportTicket) *supportAIHintJob {
	if ticket == nil || supportAdvisor == nil {
		return nil
	}

	related := make([]*SupportTicket, 0, len(db.SupportTickets))
	for _, other := range db.SupportTickets {
		if other == nil {
			continue
		}
		related = append(related, cloneSupportTicket(other))
	}

	return &supportAIHintJob{
		ticketID:      ticket.ID,
		lastMessageAt: ticket.LastMessageAt,
		messageCount:  len(ticket.Messages),
		ticket:        cloneSupportTicket(ticket),
		related:       related,
	}
}

func supportPersistTicketMutationLocked(ticket *SupportTicket) *supportAIHintJob {
	job := supportPrepareAIHintJobLocked(ticket)
	saveDBLocked()
	return job
}

func supportFinalizeAIHintJob(job *supportAIHintJob) {
	if job == nil || supportAdvisor == nil {
		return
	}

	hint := supportAdvisor.Analyze(job.ticket, job.related)

	dbMutex.Lock()
	defer dbMutex.Unlock()

	current := db.SupportTickets[job.ticketID]
	if current == nil {
		return
	}
	if current.LastMessageAt != job.lastMessageAt || len(current.Messages) != job.messageCount {
		return
	}

	current.AIHint = hint
	saveDBLocked()
}

func supportSortTickets(tickets []*SupportTicket) {
	sort.Slice(tickets, func(i, j int) bool {
		if tickets[i].LastMessageAt == tickets[j].LastMessageAt {
			return tickets[i].CreatedAt > tickets[j].CreatedAt
		}
		return tickets[i].LastMessageAt > tickets[j].LastMessageAt
	})
}

func supportListUserTickets(actor cabinetActor) ([]*SupportTicket, error) {
	dbMutex.Lock()
	defer dbMutex.Unlock()

	supportEnsureDBLocked()

	identity, _, ok := supportResolveUserIdentityLocked(actor)
	if !ok {
		return nil, fmt.Errorf("Сначала войдите в личный кабинет, чтобы пользоваться поддержкой.")
	}

	tickets := make([]*SupportTicket, 0)
	for _, ticket := range db.SupportTickets {
		if ticket == nil {
			continue
		}
		if ticket.OwnerEmail == identity.Email || ticket.OwnerTelegramID == identity.TelegramID {
			tickets = append(tickets, ticket)
		}
	}

	supportSortTickets(tickets)
	return tickets, nil
}

func supportGetUserTicket(actor cabinetActor, ticketID string) (*SupportTicket, error) {
	dbMutex.Lock()
	defer dbMutex.Unlock()

	supportEnsureDBLocked()

	identity, _, ok := supportResolveUserIdentityLocked(actor)
	if !ok {
		return nil, fmt.Errorf("Сначала войдите в личный кабинет, чтобы пользоваться поддержкой.")
	}

	ticket, exists := db.SupportTickets[ticketID]
	if !exists || ticket == nil {
		return nil, fmt.Errorf("Обращение не найдено.")
	}

	if ticket.OwnerEmail != identity.Email && ticket.OwnerTelegramID != identity.TelegramID {
		return nil, fmt.Errorf("Обращение недоступно.")
	}

	return ticket, nil
}

func supportListOperatorTickets(actor cabinetActor, statusFilter string) ([]*SupportTicket, error) {
	dbMutex.Lock()
	defer dbMutex.Unlock()

	supportEnsureDBLocked()

	if _, ok := supportResolveOperatorIdentityLocked(actor); !ok {
		return nil, fmt.Errorf("Режим оператора недоступен для этого аккаунта.")
	}

	tickets := make([]*SupportTicket, 0)
	for _, ticket := range db.SupportTickets {
		if ticket == nil {
			continue
		}
		if statusFilter != "" && ticket.Status != statusFilter {
			continue
		}
		tickets = append(tickets, ticket)
	}

	supportSortTickets(tickets)
	return tickets, nil
}

func supportSearchOperatorTickets(actor cabinetActor, query string) ([]*SupportTicket, error) {
	dbMutex.Lock()
	defer dbMutex.Unlock()

	supportEnsureDBLocked()

	if _, ok := supportResolveOperatorIdentityLocked(actor); !ok {
		return nil, fmt.Errorf("Режим оператора недоступен для этого аккаунта.")
	}

	needle := strings.ToLower(strings.TrimSpace(query))
	if needle == "" {
		return nil, fmt.Errorf("Введите ID или текст для поиска.")
	}

	tickets := make([]*SupportTicket, 0)
	for _, ticket := range db.SupportTickets {
		if ticket == nil {
			continue
		}

		haystack := strings.ToLower(strings.Join([]string{
			ticket.ID,
			ticket.OwnerEmail,
			ticket.OwnerName,
			ticket.OwnerUsername,
			ticket.Preview,
		}, " "))

		if strings.Contains(haystack, needle) {
			tickets = append(tickets, ticket)
		}
	}

	supportSortTickets(tickets)
	return tickets, nil
}

func supportSearchOperatorUsers(actor cabinetActor, query string) ([]*SupportTicket, error) {
	dbMutex.Lock()
	defer dbMutex.Unlock()

	supportEnsureDBLocked()

	if _, ok := supportResolveOperatorIdentityLocked(actor); !ok {
		return nil, fmt.Errorf("Режим оператора недоступен для этого аккаунта.")
	}

	needle := strings.ToLower(strings.TrimSpace(query))
	if needle == "" {
		return nil, fmt.Errorf("Введите email, username, имя или Telegram ID.")
	}

	tickets := make([]*SupportTicket, 0)
	for _, ticket := range db.SupportTickets {
		if ticket == nil {
			continue
		}

		haystack := strings.ToLower(strings.Join([]string{
			ticket.OwnerEmail,
			ticket.OwnerName,
			ticket.OwnerUsername,
			strconv.FormatInt(ticket.OwnerTelegramID, 10),
			ticket.ID,
		}, " "))

		if strings.Contains(haystack, needle) {
			tickets = append(tickets, ticket)
		}
	}

	supportSortTickets(tickets)
	return tickets, nil
}

func supportTicketSnapshotForOperator(actor cabinetActor, ticketID string) (*supportTicketSnapshot, error) {
	dbMutex.Lock()
	defer dbMutex.Unlock()

	supportEnsureDBLocked()

	if _, ok := supportResolveOperatorIdentityLocked(actor); !ok {
		return nil, fmt.Errorf("Режим оператора недоступен для этого аккаунта.")
	}

	ticket, exists := db.SupportTickets[ticketID]
	if !exists || ticket == nil {
		return nil, fmt.Errorf("Обращение не найдено.")
	}

	user := db.Users[ticket.OwnerEmail]
	var entry *PasswordEntry
	orders := make([]*Order, 0)
	if user != nil {
		entry = db.Passwords[user.SubscriptionID]
		for _, order := range db.Orders {
			if order == nil {
				continue
			}
			if order.Email == user.Email {
				orders = append(orders, order)
			}
		}
	}

	sort.Slice(orders, func(i, j int) bool {
		return orders[i].CreatedAt > orders[j].CreatedAt
	})

	return &supportTicketSnapshot{
		Ticket: ticket,
		User:   user,
		Entry:  entry,
		Orders: orders,
	}, nil
}

func supportCreateTicket(actor cabinetActor, text string, attachments []SupportAttachment) (string, error) {
	if !supportMessageHasContent(text, attachments) {
		return "", fmt.Errorf("Отправьте текст, фото, документ или голосовое сообщение, чтобы создать обращение.")
	}

	ticketID, job, err := func() (string, *supportAIHintJob, error) {
		dbMutex.Lock()
		defer dbMutex.Unlock()

		supportEnsureDBLocked()

		identity, _, ok := supportResolveUserIdentityLocked(actor)
		if !ok {
			return "", nil, fmt.Errorf("Сначала войдите в личный кабинет, чтобы пользоваться поддержкой.")
		}

		ticketID := supportNewID("SUP")
		message := supportBuildMessage(supportSenderUser, identity, text, attachments)
		ticket := &SupportTicket{
			ID:              ticketID,
			OwnerEmail:      identity.Email,
			OwnerTelegramID: identity.TelegramID,
			OwnerName:       identity.Name,
			OwnerUsername:   identity.Username,
			CreatedAt:       message.CreatedAt,
			LastMessageAt:   message.CreatedAt,
			Status:          supportStatusNew,
			Preview:         supportMessagePreview(message),
			Messages:        []*SupportMessage{message},
		}

		db.SupportTickets[ticketID] = ticket
		return ticketID, supportPersistTicketMutationLocked(ticket), nil
	}()
	if err != nil {
		return "", err
	}

	supportFinalizeAIHintJob(job)
	return ticketID, nil
}

func supportAddUserMessage(actor cabinetActor, ticketID string, text string, attachments []SupportAttachment) error {
	if !supportMessageHasContent(text, attachments) {
		return fmt.Errorf("Отправьте текст, фото, документ или голосовое сообщение.")
	}

	job, err := func() (*supportAIHintJob, error) {
		dbMutex.Lock()
		defer dbMutex.Unlock()

		supportEnsureDBLocked()

		identity, _, ok := supportResolveUserIdentityLocked(actor)
		if !ok {
			return nil, fmt.Errorf("Сначала войдите в личный кабинет, чтобы пользоваться поддержкой.")
		}

		ticket, exists := db.SupportTickets[ticketID]
		if !exists || ticket == nil {
			return nil, fmt.Errorf("Обращение не найдено.")
		}

		if ticket.OwnerEmail != identity.Email && ticket.OwnerTelegramID != identity.TelegramID {
			return nil, fmt.Errorf("Обращение недоступно.")
		}

		if ticket.Status == supportStatusClosed {
			return nil, fmt.Errorf("Обращение уже закрыто. Создайте новое обращение, если вопрос снова актуален.")
		}

		message := supportBuildMessage(supportSenderUser, identity, text, attachments)
		supportAppendMessageLocked(ticket, message)

		if ticket.AssignedOperatorTelegramID != 0 || ticket.LockedByTelegramID != 0 {
			ticket.Status = supportStatusInProgress
		} else {
			ticket.Status = supportStatusNew
		}
		ticket.ClosedAt = 0
		return supportPersistTicketMutationLocked(ticket), nil
	}()
	if err != nil {
		return err
	}

	supportFinalizeAIHintJob(job)
	return nil
}

func supportCloseTicketByUser(actor cabinetActor, ticketID string) error {
	job, err := func() (*supportAIHintJob, error) {
		dbMutex.Lock()
		defer dbMutex.Unlock()

		supportEnsureDBLocked()

		identity, _, ok := supportResolveUserIdentityLocked(actor)
		if !ok {
			return nil, fmt.Errorf("Сначала войдите в личный кабинет, чтобы пользоваться поддержкой.")
		}

		ticket, exists := db.SupportTickets[ticketID]
		if !exists || ticket == nil {
			return nil, fmt.Errorf("Обращение не найдено.")
		}

		if ticket.OwnerEmail != identity.Email && ticket.OwnerTelegramID != identity.TelegramID {
			return nil, fmt.Errorf("Обращение недоступно.")
		}

		if ticket.Status == supportStatusClosed {
			return nil, nil
		}

		ticket.Status = supportStatusClosed
		ticket.ClosedAt = time.Now().Unix()
		ticket.LockedByTelegramID = 0
		ticket.LockedByEmail = ""
		ticket.LockedByName = ""
		ticket.LockedAt = 0
		supportAppendSystemMessageLocked(ticket, "Пользователь закрыл обращение.")
		return supportPersistTicketMutationLocked(ticket), nil
	}()
	if err != nil {
		return err
	}

	supportFinalizeAIHintJob(job)
	return nil
}

func supportEnsureOperatorLockLocked(ticket *SupportTicket, identity supportIdentity, explicit bool) error {
	if ticket == nil {
		return fmt.Errorf("Обращение не найдено.")
	}

	if ticket.LockedByTelegramID != 0 && ticket.LockedByTelegramID != identity.TelegramID && !supportIsAdminIdentity(identity) {
		lockedBy := ticket.LockedByName
		if strings.TrimSpace(lockedBy) == "" {
			lockedBy = "другой оператор"
		}
		return fmt.Errorf("Обращение уже взял в работу %s.", lockedBy)
	}

	changed := ticket.LockedByTelegramID != identity.TelegramID ||
		ticket.LockedByEmail != identity.Email ||
		ticket.LockedByName != identity.Name

	ticket.LockedByTelegramID = identity.TelegramID
	ticket.LockedByEmail = identity.Email
	ticket.LockedByName = identity.Name
	ticket.LockedAt = time.Now().Unix()
	ticket.AssignedOperatorTelegramID = identity.TelegramID
	ticket.AssignedOperatorEmail = identity.Email
	ticket.AssignedOperatorName = identity.Name

	if explicit && changed {
		supportAppendSystemMessageLocked(ticket, fmt.Sprintf("Оператор %s взял обращение в работу.", identity.Name))
	}

	return nil
}

func supportTakeTicket(actor cabinetActor, ticketID string) error {
	job, err := func() (*supportAIHintJob, error) {
		dbMutex.Lock()
		defer dbMutex.Unlock()

		supportEnsureDBLocked()

		identity, ok := supportResolveOperatorIdentityLocked(actor)
		if !ok {
			return nil, fmt.Errorf("Режим оператора недоступен для этого аккаунта.")
		}

		ticket, exists := db.SupportTickets[ticketID]
		if !exists || ticket == nil {
			return nil, fmt.Errorf("Обращение не найдено.")
		}

		if ticket.Status == supportStatusClosed {
			ticket.ClosedAt = 0
		}

		if err := supportEnsureOperatorLockLocked(ticket, identity, true); err != nil {
			return nil, err
		}

		ticket.Status = supportStatusInProgress
		return supportPersistTicketMutationLocked(ticket), nil
	}()
	if err != nil {
		return err
	}

	supportFinalizeAIHintJob(job)
	return nil
}

func supportUnlockTicket(actor cabinetActor, ticketID string) error {
	job, err := func() (*supportAIHintJob, error) {
		dbMutex.Lock()
		defer dbMutex.Unlock()

		supportEnsureDBLocked()

		identity, ok := supportResolveOperatorIdentityLocked(actor)
		if !ok {
			return nil, fmt.Errorf("Режим оператора недоступен для этого аккаунта.")
		}

		ticket, exists := db.SupportTickets[ticketID]
		if !exists || ticket == nil {
			return nil, fmt.Errorf("Обращение не найдено.")
		}

		if ticket.LockedByTelegramID != 0 && ticket.LockedByTelegramID != identity.TelegramID && !supportIsAdminIdentity(identity) {
			return nil, fmt.Errorf("Разблокировать обращение может оператор, который его взял, или администратор.")
		}

		ticket.LockedByTelegramID = 0
		ticket.LockedByEmail = ""
		ticket.LockedByName = ""
		ticket.LockedAt = 0
		supportAppendSystemMessageLocked(ticket, fmt.Sprintf("Оператор %s снял блокировку обращения.", identity.Name))
		return supportPersistTicketMutationLocked(ticket), nil
	}()
	if err != nil {
		return err
	}

	supportFinalizeAIHintJob(job)
	return nil
}

func supportSetTicketStatus(actor cabinetActor, ticketID string, status string) error {
	job, err := func() (*supportAIHintJob, error) {
		dbMutex.Lock()
		defer dbMutex.Unlock()

		supportEnsureDBLocked()

		identity, ok := supportResolveOperatorIdentityLocked(actor)
		if !ok {
			return nil, fmt.Errorf("Режим оператора недоступен для этого аккаунта.")
		}

		ticket, exists := db.SupportTickets[ticketID]
		if !exists || ticket == nil {
			return nil, fmt.Errorf("Обращение не найдено.")
		}

		if err := supportEnsureOperatorLockLocked(ticket, identity, false); err != nil {
			return nil, err
		}

		switch status {
		case supportStatusNew, supportStatusInProgress, supportStatusWaitingUser, supportStatusClosed:
		default:
			status = supportStatusFromCode(status)
		}
		if ticket.Status == status {
			return nil, nil
		}

		ticket.Status = status
		if status == supportStatusClosed {
			ticket.ClosedAt = time.Now().Unix()
			ticket.LockedByTelegramID = 0
			ticket.LockedByEmail = ""
			ticket.LockedByName = ""
			ticket.LockedAt = 0
		} else {
			ticket.ClosedAt = 0
		}

		supportAppendSystemMessageLocked(ticket, fmt.Sprintf("Статус обращения изменен на «%s».", supportStatusLabel(status)))
		return supportPersistTicketMutationLocked(ticket), nil
	}()
	if err != nil {
		return err
	}

	supportFinalizeAIHintJob(job)
	return nil
}

func supportAddOperatorMessage(actor cabinetActor, ticketID string, text string, attachments []SupportAttachment) error {
	if !supportMessageHasContent(text, attachments) {
		return fmt.Errorf("Отправьте текст, фото, документ или голосовое сообщение.")
	}

	job, err := func() (*supportAIHintJob, error) {
		dbMutex.Lock()
		defer dbMutex.Unlock()

		supportEnsureDBLocked()

		identity, ok := supportResolveOperatorIdentityLocked(actor)
		if !ok {
			return nil, fmt.Errorf("Режим оператора недоступен для этого аккаунта.")
		}

		ticket, exists := db.SupportTickets[ticketID]
		if !exists || ticket == nil {
			return nil, fmt.Errorf("Обращение не найдено.")
		}

		if err := supportEnsureOperatorLockLocked(ticket, identity, false); err != nil {
			return nil, err
		}

		message := supportBuildMessage(supportSenderOperator, identity, text, attachments)
		supportAppendMessageLocked(ticket, message)
		ticket.Status = supportStatusWaitingUser
		ticket.ClosedAt = 0
		return supportPersistTicketMutationLocked(ticket), nil
	}()
	if err != nil {
		return err
	}

	supportFinalizeAIHintJob(job)
	return nil
}

func supportApplyQuickReply(actor cabinetActor, ticketID string, key string) error {
	template, ok := supportQuickReplyTemplate(key)
	if !ok {
		return fmt.Errorf("Шаблон ответа не найден.")
	}

	return supportAddOperatorMessage(actor, ticketID, template, nil)
}

func supportOperatorChatIDs() []int64 {
	dbMutex.Lock()
	defer dbMutex.Unlock()

	supportEnsureDBLocked()

	result := make([]int64, 0)
	seen := make(map[int64]struct{})

	if botAdminIDGlobal != 0 {
		seen[botAdminIDGlobal] = struct{}{}
		result = append(result, botAdminIDGlobal)
	}

	for _, user := range db.Users {
		if user == nil || user.TelegramID == 0 || !supportCanOperateRole(user.Role) {
			continue
		}
		if _, exists := seen[user.TelegramID]; exists {
			continue
		}
		seen[user.TelegramID] = struct{}{}
		result = append(result, user.TelegramID)
	}

	sort.Slice(result, func(i, j int) bool {
		return result[i] < result[j]
	})

	return result
}

func supportTicketOwnerChatID(ticketID string) int64 {
	dbMutex.Lock()
	defer dbMutex.Unlock()

	supportEnsureDBLocked()

	ticket := db.SupportTickets[ticketID]
	if ticket == nil {
		return 0
	}
	return ticket.OwnerTelegramID
}
