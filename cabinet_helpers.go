package main

import (
	"fmt"
	"io"
	"log"
	"net/http"
	"strings"
	"time"
)

type telegramUserPayload struct {
	ID        int64  `json:"id"`
	Username  string `json:"username"`
	FirstName string `json:"first_name"`
	LastName  string `json:"last_name"`
}

type cabinetActor struct {
	UserID    int64
	ChatID    int64
	Username  string
	FirstName string
	LastName  string
}

func (actor cabinetActor) effectiveUserID() int64 {
	if actor.UserID != 0 {
		return actor.UserID
	}
	return actor.ChatID
}

func (actor cabinetActor) isPrivateChat() bool {
	return actor.effectiveUserID() != 0 && actor.ChatID == actor.effectiveUserID()
}

func (actor cabinetActor) displayName() string {
	name := strings.TrimSpace(strings.TrimSpace(actor.FirstName + " " + actor.LastName))
	if name != "" {
		return name
	}
	if actor.Username != "" {
		return "@" + actor.Username
	}
	return "Не указано"
}

func (actor cabinetActor) usernameLabel() string {
	if actor.Username == "" {
		return "Не указан"
	}
	return "@" + actor.Username
}

func cabinetSafe(text string) string {
	replacer := strings.NewReplacer(
		"&", "&amp;",
		"<", "&lt;",
		">", "&gt;",
		"\"", "&quot;",
	)
	return replacer.Replace(text)
}

func sendCabinetTelegram(token string, chatID int64, text string, replyMarkup interface{}) {
	url := fmt.Sprintf("https://api.telegram.org/bot%s/sendMessage", token)
	payload := map[string]interface{}{
		"chat_id":    chatID,
		"text":       text,
		"parse_mode": "HTML",
	}
	if replyMarkup != nil {
		payload["reply_markup"] = replyMarkup
	}

	resp, err := postTelegramJSON(url, payload)
	if err != nil {
		log.Println("[CABINET] send message error:", err)
		return
	}
	defer resp.Body.Close()
}

func editCabinetTelegram(token string, chatID int64, messageID int, text string, replyMarkup interface{}) {
	url := fmt.Sprintf("https://api.telegram.org/bot%s/editMessageText", token)
	payload := map[string]interface{}{
		"chat_id":    chatID,
		"message_id": messageID,
		"text":       text,
		"parse_mode": "HTML",
	}
	if replyMarkup != nil {
		payload["reply_markup"] = replyMarkup
	}

	resp, err := postTelegramJSON(url, payload)
	if err != nil {
		log.Println("[CABINET] edit message error:", err)
		return
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		respBody, _ := io.ReadAll(resp.Body)
		log.Printf("[CABINET] edit message failed: status=%d body=%s", resp.StatusCode, string(respBody))
	}
}

func cabinetRender(token string, chatID int64, messageID int, text string, keyboard [][]map[string]interface{}, edit bool) {
	var replyMarkup interface{}
	if len(keyboard) > 0 {
		replyMarkup = cabinetKeyboard(keyboard...)
	}

	if edit && messageID != 0 {
		editCabinetTelegram(token, chatID, messageID, text, replyMarkup)
		return
	}

	sendCabinetTelegram(token, chatID, text, replyMarkup)
}

func cabinetPromptPrivateChat(token string, actor cabinetActor) {
	sendCabinetTelegram(
		token,
		actor.ChatID,
		"<b>Личный кабинет</b>\n\nОткройте бота в личном чате, чтобы посмотреть свои данные и управлять подпиской.",
		nil,
	)
}

func cabinetFindUserByTelegramIDLocked(telegramID int64) (string, *UserAccount, bool) {
	for email, user := range db.Users {
		if user == nil {
			continue
		}
		if user.TelegramID == telegramID {
			return email, user, true
		}
	}
	return "", nil, false
}

func cabinetEnsureUserDefaults(user *UserAccount) bool {
	changed := false

	if user.SubscriptionStatus == "" {
		user.SubscriptionStatus = "inactive"
		changed = true
	}

	if user.SubscriptionPlan == "" {
		user.SubscriptionPlan = "Неделя"
		changed = true
	}

	if user.DeviceLimit == 0 {
		user.DeviceLimit = 5
		changed = true
	}

	if user.Language == "" {
		user.Language = "ru"
		changed = true
	}

	if user.TelegramAuthExpiresAt < 0 {
		user.TelegramAuthExpiresAt = 0
		changed = true
	}

	return changed
}

func cabinetApplyTelegramAuthLocked(user *UserAccount, telegramID int64, token string, expiresAt int64) bool {
	changed := false

	for _, existing := range db.Users {
		if existing == nil || existing == user {
			continue
		}
		if existing.TelegramID == telegramID {
			existing.TelegramID = 0
			existing.TelegramAuthToken = ""
			existing.TelegramAuthExpiresAt = 0
			changed = true
		}
	}

	if user.TelegramID != telegramID {
		user.TelegramID = telegramID
		changed = true
	}

	if user.TelegramAuthToken != token {
		user.TelegramAuthToken = token
		changed = true
	}

	if user.TelegramAuthExpiresAt != expiresAt {
		user.TelegramAuthExpiresAt = expiresAt
		changed = true
	}

	return changed
}

func cabinetAuthorizeTelegramUser(email string, telegramID int64, auth *AuthResult) error {
	if telegramID == 0 {
		return fmt.Errorf("telegram id is empty")
	}

	dbMutex.Lock()
	defer dbMutex.Unlock()

	email = normalizeUserEmail(email)

	user, ok := db.Users[email]
	if !ok || user == nil {
		return fmt.Errorf("user not found")
	}

	changed := cabinetEnsureUserDefaults(user)
	if auth != nil {
		changed = cabinetApplyTelegramAuthLocked(
			user,
			telegramID,
			auth.Token,
			auth.TokenExpiresAt,
		) || changed
	}

	if changed {
		saveDBLocked()
	}

	return nil
}

func cabinetEnsureAuthorizedUser(telegramID int64) (string, *UserAccount, string, bool) {
	if telegramID == 0 {
		return "", nil, "", false
	}

	dbMutex.Lock()
	defer dbMutex.Unlock()

	email, user, ok := cabinetFindUserByTelegramIDLocked(telegramID)
	if !ok || user == nil {
		return "", nil, "", false
	}

	changed := cabinetEnsureUserDefaults(user)
	token := strings.TrimSpace(user.TelegramAuthToken)
	nowUnix := time.Now().Unix()

	if token != "" && user.TelegramAuthExpiresAt > nowUnix {
		claims, err := ValidateJWT(token)
		if err == nil && claims != nil && claims.Email == user.Email {
			if changed {
				saveDBLocked()
			}
			return email, user, token, true
		}
	}

	auth, err := authResultForUser(user)
	if err != nil {
		if changed {
			saveDBLocked()
		}
		return "", nil, "", false
	}

	changed = cabinetApplyTelegramAuthLocked(
		user,
		telegramID,
		auth.Token,
		auth.TokenExpiresAt,
	) || changed

	if changed {
		saveDBLocked()
	}

	return email, user, auth.Token, true
}

func cabinetHasLinkedUser(telegramID int64) bool {
	_, _, _, ok := cabinetEnsureAuthorizedUser(telegramID)
	return ok
}

func cabinetUpdateLinkedUser(telegramID int64, update func(user *UserAccount) bool) bool {
	dbMutex.Lock()
	defer dbMutex.Unlock()

	_, user, ok := cabinetFindUserByTelegramIDLocked(telegramID)
	if !ok || user == nil {
		return false
	}

	changed := cabinetEnsureUserDefaults(user)
	if update != nil && update(user) {
		changed = true
	}

	if changed {
		saveDBLocked()
	}

	return true
}

func cabinetPlanCode(plan string) string {
	switch strings.ToLower(strings.TrimSpace(plan)) {
	case "week", "неделя":
		return "week"
	case "month", "месяц":
		return "month"
	case "3months", "3 месяца":
		return "3months"
	default:
		return ""
	}
}

func cabinetPlanLabel(plan string) string {
	switch cabinetPlanCode(plan) {
	case "week":
		return "Неделя"
	case "month":
		return "1 месяц"
	case "3months":
		return "3 месяца"
	default:
		if strings.TrimSpace(plan) == "" {
			return "Не указан"
		}
		return plan
	}
}

func cabinetFormatDate(ts int64) string {
	if ts <= 0 {
		return "Неизвестно"
	}
	return time.Unix(ts, 0).Format("02.01.2006 15:04")
}

func cabinetFormatRemaining(expires int64) string {
	if expires <= 0 {
		return "Без срока"
	}

	remaining := time.Until(time.Unix(expires, 0))
	if remaining <= 0 {
		return "Истекла"
	}

	if remaining >= 48*time.Hour {
		return fmt.Sprintf("%d д.", int(remaining.Hours()/24))
	}
	if remaining >= time.Hour {
		return fmt.Sprintf("%d ч.", int(remaining.Hours()))
	}
	return fmt.Sprintf("%d мин.", int(remaining.Minutes()))
}

func cabinetBoundDeviceCount(entry *PasswordEntry) int {
	if entry == nil {
		return 0
	}
	if len(entry.DeviceIDs) > 0 {
		return len(entry.DeviceIDs)
	}
	if entry.DeviceID != "" && entry.DeviceID != "multi" {
		return 1
	}
	return 0
}

func cabinetDeviceIDs(entry *PasswordEntry) []string {
	if entry == nil {
		return nil
	}
	if len(entry.DeviceIDs) > 0 {
		result := make([]string, 0, len(entry.DeviceIDs))
		for _, id := range entry.DeviceIDs {
			if strings.TrimSpace(id) == "" {
				continue
			}
			result = append(result, id)
		}
		return result
	}
	if entry.DeviceID != "" && entry.DeviceID != "multi" {
		return []string{entry.DeviceID}
	}
	return nil
}

func cabinetSubscriptionStatusLabel(user *UserAccount, entry *PasswordEntry) string {
	if user == nil || user.SubscriptionID == "" || entry == nil {
		return "Не подключена"
	}
	if entry.IsDeactivated || user.SubscriptionStatus == "blocked" {
		return "Заблокирована"
	}
	if isPasswordExpired(entry) {
		return "Истекла"
	}
	if user.SubscriptionStatus == "active" {
		return "Активна"
	}
	return "Неактивна"
}

func cabinetProfileStatusLabel(user *UserAccount, entry *PasswordEntry) string {
	if user == nil {
		return "Неизвестно"
	}
	if user.Role == "admin" {
		return "Администратор"
	}
	if user.Role == "operator" || user.Role == "support" {
		return "Оператор"
	}
	switch cabinetSubscriptionStatusLabel(user, entry) {
	case "Активна":
		return "Активный"
	case "Заблокирована":
		return "Заблокирован"
	default:
		return "Неактивный"
	}
}

func cabinetLanguageLabel(language string) string {
	switch strings.ToLower(strings.TrimSpace(language)) {
	case "en":
		return "English"
	default:
		return "Русский"
	}
}

func cabinetLastSeenLabel(ts int64, online bool) string {
	if online {
		return "Сейчас в сети"
	}
	if ts <= 0 {
		return "Нет данных"
	}

	since := time.Since(time.Unix(ts, 0))
	if since < time.Hour {
		return fmt.Sprintf("%d мин. назад", int(since.Minutes()))
	}
	if since < 24*time.Hour {
		return fmt.Sprintf("%d ч. назад", int(since.Hours()))
	}
	return time.Unix(ts, 0).Format("02.01.2006 15:04")
}

func cabinetShortLabel(value string, limit int) string {
	value = strings.TrimSpace(value)
	if value == "" || len(value) <= limit {
		return value
	}
	return value[:limit-1] + "…"
}
