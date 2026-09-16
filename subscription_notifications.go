package main

import (
	"crypto/sha256"
	"fmt"
	"log"
	"time"
)

const (
	SubscriptionActivated    = "SUBSCRIPTION_ACTIVATED"
	SubscriptionRenewed      = "SUBSCRIPTION_RENEWED"
	SubscriptionLimitChanged = "SUBSCRIPTION_LIMIT_CHANGED"
	SubscriptionExpiring7D   = "SUBSCRIPTION_EXPIRING_7D"
	SubscriptionExpiring3D   = "SUBSCRIPTION_EXPIRING_3D"
	SubscriptionExpiring1D   = "SUBSCRIPTION_EXPIRING_1D"
	SubscriptionExpired      = "SUBSCRIPTION_EXPIRED"
	SubscriptionBlocked      = "SUBSCRIPTION_BLOCKED"
	SubscriptionRestored     = "SUBSCRIPTION_RESTORED"

	subscriptionDeepLink = "hoplet://subscription"
)

// subscriptionState is a short-lived snapshot of the authoritative
// PasswordEntry. It is never persisted as a second subscription model.
type subscriptionState struct {
	exists      bool
	expiresAt   int64
	deviceLimit int
	blocked     bool
}

type subscriptionEvent struct {
	typ, title, message, key string
}

func snapshotSubscriptionState(entry *PasswordEntry) subscriptionState {
	if entry == nil {
		return subscriptionState{}
	}
	return subscriptionState{true, entry.ExpiresAt, entry.MaxDevices, entry.IsDeactivated}
}

func (state subscriptionState) activeAt(now time.Time) bool {
	return state.exists && !state.blocked && state.expiresAt > now.Unix()
}

func subscriptionDate(unix int64) string {
	return time.Unix(unix, 0).In(time.Local).Format("02.01.2006")
}

func subscriptionIdentityKey(subscriptionID string) string {
	digest := sha256.Sum256([]byte(subscriptionID))
	return fmt.Sprintf("%x", digest[:])
}

// detectSubscriptionTransition deliberately returns at most one event. State
// changes take precedence over changed attributes, so restoring an expired
// subscription is one RESTORED event rather than RESTORED + RENEWED.
func detectSubscriptionTransition(subscriptionID string, previous, current subscriptionState, now time.Time) *subscriptionEvent {
	identity := subscriptionIdentityKey(subscriptionID)
	if !previous.exists && current.activeAt(now) {
		return &subscriptionEvent{
			typ: SubscriptionActivated, title: "Подписка активирована",
			message: fmt.Sprintf("Ваша подписка успешно активирована.\nДействительна до %s.", subscriptionDate(current.expiresAt)),
			key:     fmt.Sprintf("%s|%s|%s", identity, SubscriptionActivated, subscriptionDate(current.expiresAt)),
		}
	}
	if previous.activeAt(now) && current.blocked {
		return &subscriptionEvent{
			typ: SubscriptionBlocked, title: "Подписка заблокирована",
			message: "Доступ к VPN временно ограничен.",
			key:     fmt.Sprintf("%s|%s|%d", identity, SubscriptionBlocked, current.expiresAt),
		}
	}
	previousUnavailable := previous.exists && (previous.blocked || previous.expiresAt <= now.Unix())
	if previousUnavailable && current.activeAt(now) {
		return &subscriptionEvent{
			typ: SubscriptionRestored, title: "Доступ восстановлен",
			message: "Подписка снова активна.",
			key:     fmt.Sprintf("%s|%s|%d", identity, SubscriptionRestored, current.expiresAt),
		}
	}
	if previous.exists && current.expiresAt > previous.expiresAt {
		days := (current.expiresAt - previous.expiresAt) / int64(24*time.Hour/time.Second)
		message := fmt.Sprintf("Ваша подписка продлена до %s.", subscriptionDate(current.expiresAt))
		if days > 0 {
			message += fmt.Sprintf("\nСрок продлён на %d дней.", days)
		}
		return &subscriptionEvent{
			typ: SubscriptionRenewed, title: "Подписка продлена", message: message,
			key: fmt.Sprintf("%s|%s|%d|%d", identity, SubscriptionRenewed, previous.expiresAt, current.expiresAt),
		}
	}
	if previous.exists && current.deviceLimit != previous.deviceLimit {
		return &subscriptionEvent{
			typ: SubscriptionLimitChanged, title: "Изменён лимит устройств",
			message: fmt.Sprintf("Теперь можно подключить до %d устройств.", current.deviceLimit),
			key:     fmt.Sprintf("%s|%s|%d|%d", identity, SubscriptionLimitChanged, previous.deviceLimit, current.deviceLimit),
		}
	}
	return nil
}

func subscriptionRegistrationsLocked(subscriptionID string) []*PushRegistration {
	registrations := make([]*PushRegistration, 0)
	for _, registration := range db.PushRegistrations {
		if registration == nil || registration.SubscriptionID != subscriptionID || !registration.Enabled || !registration.Preferences.Enabled {
			continue
		}
		copy := *registration
		registrations = append(registrations, &copy)
	}
	return registrations
}

func createSubscriptionNotificationLocked(subscriptionID string, event subscriptionEvent, source string) (NotificationRecord, bool) {
	return publishSubscriptionNotificationLocked(subscriptionID, event, source, true)
}

func publishSubscriptionNotificationLocked(subscriptionID string, event subscriptionEvent, source string, deduplicate bool) (NotificationRecord, bool) {
	if db.PushReminderKeys == nil {
		db.PushReminderKeys = map[string]int64{}
	}
	if deduplicate {
		if _, exists := db.PushReminderKeys[event.key]; exists {
			pushMetrics.DuplicatesSuppressed.Add(1)
			return NotificationRecord{}, false
		}
		// Reserve before NotificationRecord persistence: the same atomic DB
		// snapshot contains both the event and its idempotency key.
		db.PushReminderKeys[event.key] = time.Now().Unix()
	}
	record := publishNotificationRecordLocked(NotificationRecord{
		Title: event.title, Message: event.message, Type: event.typ,
		Priority: "high", DeepLink: subscriptionDeepLink, Source: source,
		Audience: "subscription:" + subscriptionIdentityKey(subscriptionID), EventKey: event.key,
	})
	pushMetrics.NotificationsGenerated.Add(1)
	registrations := subscriptionRegistrationsLocked(subscriptionID)
	if len(registrations) > 0 {
		go func() {
			result := sendPushToRegistrations(registrations, record)
			log.Printf("[PUSH] subscription event type=%s identity=%s targeted=%d sent=%d failed=%d", event.typ, shortPushID(subscriptionID), result.Targeted, result.Sent, result.Failed)
		}()
	}
	return record, true
}

// recordSubscriptionTransitionLocked compares immutable previous/current
// snapshots. Callers invoke it only on an actual write while holding dbMutex;
// GET/status refresh paths never call it.
func recordSubscriptionTransitionLocked(subscriptionID string, previous, current subscriptionState, now time.Time) (NotificationRecord, bool) {
	event := detectSubscriptionTransition(subscriptionID, previous, current, now)
	if event == nil {
		return NotificationRecord{}, false
	}
	// Administrative transitions are inherently idempotent because previous and
	// current are captured around the write. They are not permanently keyed by
	// expiry, so a later block/restore cycle at the same expiry remains visible.
	return publishSubscriptionNotificationLocked(subscriptionID, *event, "subscription_admin", false)
}
