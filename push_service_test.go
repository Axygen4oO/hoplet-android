package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestSubscriptionReminderFixtureIsIdempotentAndTracksRenewal(t *testing.T) {
	database := notificationTestDatabase()
	database.Passwords["subscription-secret"] = &PasswordEntry{MaxDevices: 1}
	withTestDB(t, database, func() {
		now := time.Date(2026, 9, 14, 10, 0, 0, 0, time.Local)
		database.Passwords["subscription-secret"].ExpiresAt = now.AddDate(0, 0, 7).Unix()
		generateSubscriptionReminders(now, 7)
		generateSubscriptionReminders(now, 7)
		if len(db.Notifications) != 1 || len(db.PushReminderKeys) != 1 {
			t.Fatalf("duplicate reminder generated: notifications=%d keys=%d", len(db.Notifications), len(db.PushReminderKeys))
		}
		database.Passwords["subscription-secret"].ExpiresAt = now.AddDate(0, 0, 14).Unix()
		generateSubscriptionReminders(now.AddDate(0, 0, 7), 7)
		if len(db.Notifications) != 2 {
			t.Fatalf("renewed subscription did not create a new reminder: %d", len(db.Notifications))
		}
		database.Passwords["subscription-secret"].ExpiresAt = now.Add(-time.Hour).Unix()
		generateSubscriptionReminders(now, 0)
		if len(db.Notifications) != 3 || db.Notifications[0].Type != "SUBSCRIPTION_EXPIRED" {
			t.Fatalf("expired reminder missing: %+v", db.Notifications)
		}
	})
}

func TestPushRegistrationRequiresSubscriptionAndRefreshesToken(t *testing.T) {
	database := notificationTestDatabase()
	database.Passwords["subscription-secret"] = &PasswordEntry{MaxDevices: 1}
	withTestDB(t, database, func() {
		register := func(fcmToken string) *httptest.ResponseRecorder {
			body := `{"installation_id":"install-1","device_id":"android-1","token":"` + fcmToken + `","platform":"android"}`
			req := httptest.NewRequest(http.MethodPost, "/api/push/register", strings.NewReader(body))
			req.Header.Set("Authorization", "Bearer subscription-secret")
			recorder := httptest.NewRecorder()
			pushRegisterHandler(recorder, req)
			return recorder
		}
		if register("token-a").Code != http.StatusOK {
			t.Fatalf("first registration failed: %d", register("token-a").Code)
		}
		if register("token-b").Code != http.StatusOK {
			t.Fatalf("refresh failed")
		}
		if len(db.PushRegistrations) != 1 {
			t.Fatalf("registrations=%d", len(db.PushRegistrations))
		}
		for _, reg := range db.PushRegistrations {
			if reg.Token != "token-b" || reg.SubscriptionID != "subscription-secret" || reg.DeviceID != "android-1" {
				t.Fatalf("unexpected registration: %+v", reg)
			}
		}
		status := httptest.NewRecorder()
		req := httptest.NewRequest(http.MethodGet, "/api/push/status", nil)
		req.Header.Set("Authorization", "Bearer subscription-secret")
		req.URL.RawQuery = "device_id=android-1"
		pushStatusHandler(status, req)
		var payload struct {
			Active int `json:"active"`
		}
		if json.Unmarshal(status.Body.Bytes(), &payload) != nil || payload.Active != 1 {
			t.Fatalf("bad status: %s", status.Body.String())
		}
	})
}

func TestPushUnregisterKeepsRegistrationButDisablesIt(t *testing.T) {
	database := notificationTestDatabase()
	database.Passwords["subscription-secret"] = &PasswordEntry{MaxDevices: 1, DeviceIDs: []string{"android-1"}}
	withTestDB(t, database, func() {
		id := pushRegistrationID("subscription-secret", "android-1")
		database.PushRegistrations = map[string]*PushRegistration{id: {ID: id, SubscriptionID: "subscription-secret", DeviceID: "android-1", InstallationID: "install-1", Token: "token", Enabled: true, Preferences: PushPreferences{Enabled: true}}}
		req := httptest.NewRequest(http.MethodPost, "/api/push/unregister", strings.NewReader(`{"installation_id":"install-1","device_id":"android-1"}`))
		req.Header.Set("Authorization", "Bearer subscription-secret")
		recorder := httptest.NewRecorder()
		pushUnregisterHandler(recorder, req)
		if recorder.Code != http.StatusOK || database.PushRegistrations[id].Enabled {
			t.Fatalf("unregister failed: %d", recorder.Code)
		}
	})
}

func TestPushRegistrationRejectsJWTAndUnboundDeviceLimit(t *testing.T) {
	database := notificationTestDatabase()
	database.JWTSecret = "test-secret"
	database.Passwords["subscription-secret"] = &PasswordEntry{MaxDevices: 1, DeviceIDs: []string{"android-1"}}
	database.Users["push@example.com"] = &UserAccount{Email: "push@example.com", Role: "user"}
	withTestDB(t, database, func() {
		jwt, _ := GenerateJWT(database.Users["push@example.com"])
		body := `{"installation_id":"install-1","device_id":"android-2","token":"token","platform":"android"}`
		req := httptest.NewRequest(http.MethodPost, "/api/push/register", strings.NewReader(body))
		req.Header.Set("Authorization", "Bearer "+jwt)
		rec := httptest.NewRecorder()
		pushRegisterHandler(rec, req)
		if rec.Code != http.StatusUnauthorized {
			t.Fatalf("JWT must not authorize push registration: %d", rec.Code)
		}
		req = httptest.NewRequest(http.MethodPost, "/api/push/register", strings.NewReader(body))
		req.Header.Set("Authorization", "Bearer missing-subscription")
		rec = httptest.NewRecorder()
		pushRegisterHandler(rec, req)
		if rec.Code != http.StatusUnauthorized {
			t.Fatalf("unknown subscription must be rejected: %d", rec.Code)
		}
		req = httptest.NewRequest(http.MethodPost, "/api/push/register", strings.NewReader(body))
		req.Header.Set("Authorization", "Bearer subscription-secret")
		rec = httptest.NewRecorder()
		pushRegisterHandler(rec, req)
		if rec.Code != http.StatusUnauthorized {
			t.Fatalf("device over subscription limit must be rejected: %d", rec.Code)
		}
	})
}

func TestBuildFCMPayloadIsDataOnlyAndCarriesRendererFields(t *testing.T) {
	n := NotificationRecord{
		ID: 42, Title: "Title", Message: "Body", Type: "SECURITY",
		DeepLink: "subscription", Priority: "high",
	}
	payload := buildFCMPayload("token", n)
	message, ok := payload["message"].(map[string]any)
	if !ok {
		t.Fatalf("message payload has unexpected type: %#v", payload["message"])
	}
	if _, exists := message["notification"]; exists {
		t.Fatal("FCM payload must not contain a notification block")
	}
	data, ok := message["data"].(map[string]string)
	if !ok {
		t.Fatalf("data payload has unexpected type: %#v", message["data"])
	}
	for key, want := range map[string]string{
		"notification_id": "42", "title": "Title", "message": "Body",
		"type": "SECURITY", "deep_link": "subscription", "priority": "high", "revision": "42",
	} {
		if data[key] != want {
			t.Errorf("data[%q]=%q, want %q", key, data[key], want)
		}
	}
	androidConfig, ok := message["android"].(map[string]any)
	if !ok || androidConfig["priority"] != "HIGH" {
		t.Fatalf("unexpected android transport config: %#v", message["android"])
	}
}
