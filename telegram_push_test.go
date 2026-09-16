package main

import (
	"strings"
	"testing"
)

func pushTestDatabase() *Database {
	return &Database{
		Passwords:         map[string]*PasswordEntry{},
		Devices:           map[string]*ClientDevice{},
		Users:             map[string]*UserAccount{},
		Orders:            map[string]*Order{},
		SupportTickets:    map[string]*SupportTicket{},
		PushRegistrations: map[string]*PushRegistration{},
	}
}

func TestPushWizardRejectsInvalidInputAndUsesDeepLinkWhitelist(t *testing.T) {
	withNotificationTestDB(t, pushTestDatabase(), func(_ string) {
		withTelegramRecorder(t, func(_ *telegramRecorderTransport) {
			startPushWizard("token", 1)
			if !handlePushCallback("token", 1, "push_t_SYSTEM", 1, "") {
				t.Fatal("type callback was not handled")
			}
			if handlePushInput("token", 1, strings.Repeat("x", maxPushTitle+1)) == false || tgState.PushStage != pushStageTitle {
				t.Fatal("oversized title must be rejected")
			}
			handlePushInput("token", 1, "Title")
			handlePushInput("token", 1, "Body")
			if !handlePushCallback("token", 1, "push_l_support", 1, "") || tgState.PushDeepLink != "support" {
				t.Fatal("deep link whitelist selection failed")
			}
		})
	})
}

func TestPushUserDoubleConfirmPublishesOnceAndClearsState(t *testing.T) {
	database := pushTestDatabase()
	database.Users["user@example.com"] = &UserAccount{Email: "user@example.com", SubscriptionID: "sub-1"}
	id := pushRegistrationID("sub-1", "device-1")
	database.PushRegistrations[id] = &PushRegistration{ID: id, SubscriptionID: "sub-1", DeviceID: "device-1", UserEmail: "user@example.com", Enabled: true, Preferences: PushPreferences{Enabled: true}}
	withNotificationTestDB(t, database, func(_ string) {
		withTelegramRecorder(t, func(_ *telegramRecorderTransport) {
			startPushUserWizard("token", 1)
			handlePushInput("token", 1, "user@example.com")
			if !handlePushCallback("token", 1, "push_ud_all", 1, "") {
				t.Fatal("device selection was not handled")
			}
			handlePushCallback("token", 1, "push_t_SYSTEM", 1, "")
			handlePushInput("token", 1, "Title")
			handlePushInput("token", 1, "Body")
			handlePushCallback("token", 1, "push_l_notifications", 1, "")
			handlePushCallback("token", 1, "push_send", 1, tgState.PushPreview)
			if hasActivePushCompose() || len(db.Notifications) != 1 {
				t.Fatalf("push state was not cleared or was not published once: active=%v notifications=%d", hasActivePushCompose(), len(db.Notifications))
			}
			handlePushCallback("token", 1, "push_send", 1, "stale preview")
			if len(db.Notifications) != 1 {
				t.Fatal("repeated confirm callback published a second notification")
			}
		})
	})
}
