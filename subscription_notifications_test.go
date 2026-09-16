package main

import (
	"strings"
	"testing"
	"time"
)

func subscriptionNotificationTestDB() *Database {
	database := notificationTestDatabase()
	database.Passwords = map[string]*PasswordEntry{}
	database.PushRegistrations = map[string]*PushRegistration{}
	database.PushReminderKeys = map[string]int64{}
	return database
}

func recordTestTransition(t *testing.T, subscriptionID string, previous, current subscriptionState, now time.Time) (NotificationRecord, bool) {
	t.Helper()
	dbMutex.Lock()
	record, created := recordSubscriptionTransitionLocked(subscriptionID, previous, current, now)
	dbMutex.Unlock()
	return record, created
}

func TestSubscriptionAdminTransitionMatrix(t *testing.T) {
	now := time.Date(2026, 9, 16, 12, 0, 0, 0, time.Local)
	active := subscriptionState{exists: true, expiresAt: now.Add(30 * 24 * time.Hour).Unix(), deviceLimit: 3}

	tests := []struct {
		name     string
		previous subscriptionState
		current  subscriptionState
		wantType string
	}{
		{"A missing to active is activated", subscriptionState{}, active, SubscriptionActivated},
		{"B expiry extended is renewed", active, subscriptionState{true, active.expiresAt + int64(10*24*time.Hour/time.Second), 3, false}, SubscriptionRenewed},
		{"C expiry unchanged has no event", active, active, ""},
		{"D limit 3 to 5 is changed", active, subscriptionState{true, active.expiresAt, 5, false}, SubscriptionLimitChanged},
		{"E limit 5 to 5 has no event", subscriptionState{true, active.expiresAt, 5, false}, subscriptionState{true, active.expiresAt, 5, false}, ""},
		{"F active to blocked", active, subscriptionState{true, active.expiresAt, 3, true}, SubscriptionBlocked},
		{"G blocked to active", subscriptionState{true, active.expiresAt, 3, true}, active, SubscriptionRestored},
		{"expired to active is restored", subscriptionState{true, now.Add(-time.Hour).Unix(), 3, false}, active, SubscriptionRestored},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			withTestDB(t, subscriptionNotificationTestDB(), func() {
				record, created := recordTestTransition(t, "subscription-a", test.previous, test.current, now)
				if test.wantType == "" {
					if created || len(db.Notifications) != 0 {
						t.Fatalf("unexpected event: %+v", record)
					}
					return
				}
				if !created || record.Type != test.wantType || len(db.Notifications) != 1 {
					t.Fatalf("event=%+v created=%t notifications=%d", record, created, len(db.Notifications))
				}
			})
		})
	}
}

func TestSubscriptionReminderMatrixAndDuplicateRun(t *testing.T) {
	now := time.Date(2026, 9, 16, 10, 0, 0, 0, time.Local)
	for _, fixture := range []struct {
		days     int
		wantType string
	}{
		{7, SubscriptionExpiring7D},
		{3, SubscriptionExpiring3D},
		{1, SubscriptionExpiring1D},
		{0, SubscriptionExpired},
	} {
		t.Run(fixture.wantType, func(t *testing.T) {
			database := subscriptionNotificationTestDB()
			expires := now.Add(-time.Minute)
			if fixture.days > 0 {
				expires = now.AddDate(0, 0, fixture.days)
			}
			database.Passwords["subscription-reminder"] = &PasswordEntry{ExpiresAt: expires.Unix(), MaxDevices: 2}
			withTestDB(t, database, func() {
				generateSubscriptionReminders(now, fixture.days)
				generateSubscriptionReminders(now, fixture.days)
				if len(db.Notifications) != 1 || db.Notifications[0].Type != fixture.wantType || len(db.PushReminderKeys) != 1 {
					t.Fatalf("notifications=%+v keys=%v", db.Notifications, db.PushReminderKeys)
				}
			})
		})
	}
}

func TestSubscriptionEventContractDeepLinkRevisionAndDuplicateSave(t *testing.T) {
	now := time.Date(2026, 9, 16, 12, 0, 0, 0, time.Local)
	previous := subscriptionState{true, now.Add(7 * 24 * time.Hour).Unix(), 3, false}
	current := subscriptionState{true, now.Add(14 * 24 * time.Hour).Unix(), 3, false}
	withTestDB(t, subscriptionNotificationTestDB(), func() {
		first, created := recordTestTransition(t, "subscription-contract", previous, current, now)
		_, duplicateCreated := recordTestTransition(t, "subscription-contract", current, current, now)
		if !created || duplicateCreated || len(db.Notifications) != 1 {
			t.Fatalf("one transition must create one logical record: created=%t duplicate=%t count=%d", created, duplicateCreated, len(db.Notifications))
		}
		if first.DeepLink != subscriptionDeepLink || first.Revision != first.ID || first.Revision <= 0 {
			t.Fatalf("renderer contract mismatch: %+v", first)
		}
		if !strings.Contains(first.Message, "продлена до") || first.Title == "" || first.EventKey == "" {
			t.Fatalf("incomplete notification: %+v", first)
		}
	})
}

func TestRepeatedAdministrativeSaveDoesNotCreateDuplicate(t *testing.T) {
	now := time.Now().Add(30 * 24 * time.Hour).Unix()
	database := subscriptionNotificationTestDB()
	database.Passwords["subscription-save"] = &PasswordEntry{ExpiresAt: now, MaxDevices: 3}
	database.Users["admin-target@example.com"] = &UserAccount{Email: "admin-target@example.com", SubscriptionID: "subscription-save", SubscriptionStatus: "active", SubscriptionExpires: now, DeviceLimit: 3}
	withTestDB(t, database, func() {
		dbMutex.Lock()
		user := db.Users["admin-target@example.com"]
		if err := blockSubscription(user); err != nil {
			t.Fatal(err)
		}
		if err := blockSubscription(user); err != nil {
			t.Fatal(err)
		}
		dbMutex.Unlock()
		if len(db.Notifications) != 1 || db.Notifications[0].Type != SubscriptionBlocked {
			t.Fatalf("duplicate state save generated events: %+v", db.Notifications)
		}
	})
}

func TestSubscriptionPreferencesAbsentRegistrationAndMultipleDevices(t *testing.T) {
	update := NotificationRecord{Type: SubscriptionRenewed}
	reminder := NotificationRecord{Type: SubscriptionExpiring1D}
	disabledUpdates := &PushRegistration{Preferences: PushPreferences{Enabled: true, SubscriptionUpdates: false, SubscriptionReminders: true, SchemaVersion: 2}}
	if pushPreferenceAllows(disabledUpdates, update) {
		t.Fatal("subscription updates OFF must suppress administrative events")
	}
	if !pushPreferenceAllows(disabledUpdates, reminder) {
		t.Fatal("subscription reminders must remain independent")
	}
	disabledReminders := &PushRegistration{Preferences: PushPreferences{Enabled: true, SubscriptionUpdates: true, SubscriptionReminders: false, SchemaVersion: 2}}
	if pushPreferenceAllows(disabledReminders, reminder) || !pushPreferenceAllows(disabledReminders, update) {
		t.Fatal("reminders OFF must not suppress administrative updates")
	}

	database := subscriptionNotificationTestDB()
	withTestDB(t, database, func() {
		dbMutex.Lock()
		if got := len(subscriptionRegistrationsLocked("missing")); got != 0 {
			t.Fatalf("absent registration targets=%d", got)
		}
		for _, deviceID := range []string{"device-1", "device-2"} {
			id := pushRegistrationID("subscription-multi", deviceID)
			db.PushRegistrations[id] = &PushRegistration{ID: id, SubscriptionID: "subscription-multi", DeviceID: deviceID, Token: "token-" + deviceID, Enabled: true, Preferences: PushPreferences{Enabled: true, SubscriptionUpdates: true, SchemaVersion: 2}}
		}
		targets := subscriptionRegistrationsLocked("subscription-multi")
		dbMutex.Unlock()
		if len(targets) != 2 {
			t.Fatalf("multiple devices targets=%d", len(targets))
		}
	})
}
