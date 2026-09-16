package main

import (
	"encoding/json"
	"log"
	"net/http"
	"sort"
	"strconv"
	"strings"
	"time"
)

const notificationHistoryLimit = 5

const (
	NotificationTypeSystem       = "SYSTEM"
	NotificationTypeSecurity     = "SECURITY"
	NotificationTypeSubscription = "SUBSCRIPTION"
	NotificationTypePayment      = "PAYMENT"
	NotificationTypeUpdate       = "UPDATE"
	NotificationTypeMaintenance  = "MAINTENANCE"
	NotificationTypePromotion    = "PROMOTION"
	NotificationTypeSupport      = "SUPPORT"
	NotificationTypeCustom       = "CUSTOM"
)

type NotificationRecord struct {
	ID          int64  `json:"id"`
	Revision    int64  `json:"revision"`
	CreatedAt   int64  `json:"created_at"`
	Title       string `json:"title"`
	Message     string `json:"message"`
	Type        string `json:"type,omitempty"`
	Priority    string `json:"priority,omitempty"`
	DeepLink    string `json:"deep_link,omitempty"`
	Source      string `json:"source,omitempty"`
	ExpiresAt   int64  `json:"expires_at,omitempty"`
	ScheduledAt int64  `json:"scheduled_at,omitempty"`
	Audience    string `json:"audience,omitempty"`
	EventKey    string `json:"event_key,omitempty"`
}

type AppNotification = NotificationRecord

type notificationsResponse struct {
	Notifications []NotificationRecord `json:"notifications"`
}

func migrateNotifications(database *Database) {
	if database == nil {
		return
	}
	if len(database.Notifications) == 0 && database.LastNotification.ID > 0 {
		database.Notifications = []NotificationRecord{database.LastNotification}
	}
	database.LastNotification = NotificationRecord{}
	normalizeNotificationHistory(database)
}

func normalizeNotificationHistory(database *Database) {
	if database.Notifications == nil {
		database.Notifications = []NotificationRecord{}
	}
	sort.SliceStable(database.Notifications, func(i, j int) bool {
		return database.Notifications[i].ID > database.Notifications[j].ID
	})
	seen := make(map[int64]bool, len(database.Notifications))
	filtered := make([]NotificationRecord, 0, notificationHistoryLimit)
	maxID := database.NotificationNextID
	for _, item := range database.Notifications {
		if item.ID <= 0 || seen[item.ID] {
			continue
		}
		seen[item.ID] = true
		filtered = append(filtered, item)
		if item.ID > maxID {
			maxID = item.ID
		}
		if len(filtered) == notificationHistoryLimit {
			break
		}
	}
	database.Notifications = filtered
	database.NotificationNextID = maxID
}

func publishNotification(title, message string) NotificationRecord {
	record := publishNotificationRecord(NotificationRecord{Title: title, Message: message, Type: "CUSTOM", Source: "telegram", Audience: "all_active"})
	// History is committed before delivery. FCM failure must never roll back the
	// source-of-truth record or make Telegram publish it a second time.
	if regs := snapshotActivePushRegistrations(); len(regs) > 0 {
		go func() {
			log.Printf("[PUSH] send started audience=%s targeted=%d", record.Audience, len(regs))
			result := sendPushToRegistrations(regs, record)
			log.Printf("[PUSH] send result targeted=%d sent=%d failed=%d invalid=%d removed=%d", result.Targeted, result.Sent, result.Failed, result.Invalid, result.Removed)
		}()
	}
	return record
}

func publishNotificationRecord(input NotificationRecord) NotificationRecord {
	dbMutex.Lock()
	defer dbMutex.Unlock()
	return publishNotificationRecordLocked(input)
}

// publishNotificationRecordLocked is the single write path used by automatic
// subscription events and manual notifications. Callers must hold dbMutex.
// Keeping allocation and persistence together makes one logical event exactly
// one NotificationRecord, independently of the number of target devices.
func publishNotificationRecordLocked(input NotificationRecord) NotificationRecord {
	migrateNotifications(db)
	nextID := db.NotificationNextID + 1
	if nextID <= 0 {
		nextID = 1
	}
	input.Title = strings.TrimSpace(input.Title)
	input.Message = strings.TrimSpace(input.Message)
	input.Type = strings.TrimSpace(input.Type)
	if input.Type == "" {
		input.Type = "CUSTOM"
	}
	if input.Source == "" {
		input.Source = "system"
	}
	notification := NotificationRecord{
		ID:        nextID,
		Revision:  nextID,
		CreatedAt: time.Now().Unix(),
		Title:     input.Title, Message: input.Message, Type: input.Type,
		Priority: input.Priority, DeepLink: input.DeepLink, Source: input.Source,
		ExpiresAt: input.ExpiresAt, ScheduledAt: input.ScheduledAt,
		Audience: input.Audience, EventKey: input.EventKey,
	}
	db.NotificationNextID = nextID
	db.Notifications = append([]NotificationRecord{notification}, db.Notifications...)
	if len(db.Notifications) > notificationHistoryLimit {
		db.Notifications = db.Notifications[:notificationHistoryLimit]
	}
	saveDBLocked()
	log.Printf("[NOTIFY] PUBLISHED id=%d", notification.ID)
	return notification
}

func deleteNotification(id int64) bool {
	dbMutex.Lock()
	defer dbMutex.Unlock()
	for index, item := range db.Notifications {
		if item.ID != id {
			continue
		}
		db.Notifications = append(db.Notifications[:index:index], db.Notifications[index+1:]...)
		saveDBLocked()
		return true
	}
	return false
}

func getNotification(id int64) (NotificationRecord, bool) {
	dbMutex.Lock()
	defer dbMutex.Unlock()
	for _, item := range db.Notifications {
		if item.ID == id {
			return item, true
		}
	}
	return NotificationRecord{}, false
}

func notificationRequestAuthorized(r *http.Request) bool {
	_, _, authorized := notificationRequestScope(r)
	return authorized
}

// notificationRequestScope returns the hashed PasswordEntry identity used by
// targeted subscription records. Admin/main-password callers are unrestricted.
func notificationRequestScope(r *http.Request) (identity string, unrestricted bool, authorized bool) {
	token := getTokenFromRequest(r)
	if token == "" {
		return "", false, false
	}
	if claims, err := ValidateJWT(token); err == nil {
		if claims.Role == "admin" {
			return "", true, true
		}
		dbMutex.Lock()
		user := db.Users[normalizeUserEmail(claims.Email)]
		subscriptionID := ""
		if user != nil {
			subscriptionID = user.SubscriptionID
		}
		dbMutex.Unlock()
		if subscriptionID != "" {
			return subscriptionIdentityKey(subscriptionID), false, true
		}
		return "", false, true
	}
	if requireAdmin(r) {
		return "", true, true
	}
	dbMutex.Lock()
	defer dbMutex.Unlock()
	// Android's connectionPassword may be the server owner password (the
	// same credential accepted by /api/profile/status and the tunnel).  It is
	// a valid notifications credential too; unlike an admin JWT it is checked
	// by exact match and never logged or returned to the client.
	if db.MainPassword != "" && token == db.MainPassword {
		return "", true, true
	}
	entry, ok := db.Passwords[token]
	if !ok || isPasswordExpired(entry) {
		return "", false, false
	}
	return subscriptionIdentityKey(token), false, true
}

func notificationVisibleForScope(item NotificationRecord, identity string, unrestricted bool) bool {
	if unrestricted || !strings.HasPrefix(item.Audience, "subscription:") {
		return true
	}
	return item.Audience == "subscription:"+identity
}

func notificationsHandler(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	identity, unrestricted, authorized := notificationRequestScope(r)
	if !authorized {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}
	dbMutex.Lock()
	items := make([]NotificationRecord, 0, len(db.Notifications))
	for _, item := range db.Notifications {
		if notificationVisibleForScope(item, identity, unrestricted) {
			items = append(items, item)
		}
	}
	dbMutex.Unlock()
	writeNotificationsResponse(w, items)
}

func latestNotificationHandler(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	identity, unrestricted, authorized := notificationRequestScope(r)
	if !authorized {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}
	afterID := int64(0)
	if raw := r.URL.Query().Get("after"); raw != "" {
		parsed, err := strconv.ParseInt(raw, 10, 64)
		if err != nil || parsed < 0 {
			http.Error(w, "invalid after", http.StatusBadRequest)
			return
		}
		afterID = parsed
	}
	dbMutex.Lock()
	items := make([]NotificationRecord, 0, notificationHistoryLimit)
	for _, item := range db.Notifications {
		if item.ID > afterID && notificationVisibleForScope(item, identity, unrestricted) {
			items = append(items, item)
		}
	}
	dbMutex.Unlock()
	writeNotificationsResponse(w, items)
}

func writeNotificationsResponse(w http.ResponseWriter, items []NotificationRecord) {
	if len(items) > notificationHistoryLimit {
		items = items[:notificationHistoryLimit]
	}
	if items == nil {
		items = []NotificationRecord{}
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(notificationsResponse{Notifications: items})
}
