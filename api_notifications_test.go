package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"slices"
	"testing"
	"time"
)

func notificationTestDatabase() *Database {
	return &Database{
		Passwords: map[string]*PasswordEntry{"client-secret": {}},
		Devices:   map[string]*ClientDevice{}, Users: map[string]*UserAccount{},
		Orders: map[string]*Order{}, SupportTickets: map[string]*SupportTicket{},
		Notifications: []NotificationRecord{},
	}
}

func withTestDB(t *testing.T, database *Database, testFn func()) {
	t.Helper()
	dbMutex.Lock()
	prevDB, prevFile := db, dbFile
	db, dbFile = database, ""
	dbMutex.Unlock()
	defer func() {
		dbMutex.Lock()
		db, dbFile = prevDB, prevFile
		dbMutex.Unlock()
	}()
	testFn()
}

func withTestDBFile(t *testing.T, database *Database, testFn func(file string)) {
	t.Helper()
	file := filepath.Join(t.TempDir(), "passwords.json")
	dbMutex.Lock()
	prevDB, prevFile := db, dbFile
	db, dbFile = database, file
	dbMutex.Unlock()
	defer func() {
		asyncDBSave.wait()
		dbMutex.Lock()
		db, dbFile = prevDB, prevFile
		dbMutex.Unlock()
	}()
	testFn(file)
}

func authorizedNotificationRequest(method, target string) *http.Request {
	req := httptest.NewRequest(method, target, nil)
	req.Header.Set("Authorization", "Bearer client-secret")
	return req
}

func decodeNotifications(t *testing.T, recorder *httptest.ResponseRecorder) []NotificationRecord {
	t.Helper()
	var payload notificationsResponse
	if err := json.Unmarshal(recorder.Body.Bytes(), &payload); err != nil {
		t.Fatalf("decode response: %v", err)
	}
	return payload.Notifications
}

func TestNotificationCreateSequentialOrderingAndLimit(t *testing.T) {
	withTestDB(t, notificationTestDatabase(), func() {
		for i := 1; i <= 7; i++ {
			item := publishNotification("title", "message")
			if item.ID != int64(i) {
				t.Fatalf("id %d, want %d", item.ID, i)
			}
		}
		if len(db.Notifications) != 5 {
			t.Fatalf("got %d notifications", len(db.Notifications))
		}
		for index, want := range []int64{7, 6, 5, 4, 3} {
			if db.Notifications[index].ID != want {
				t.Fatalf("index %d id=%d want=%d", index, db.Notifications[index].ID, want)
			}
		}
	})
}

func TestNotificationHistoryAndLatestAPI(t *testing.T) {
	withTestDB(t, notificationTestDatabase(), func() {
		for i := 0; i < 5; i++ {
			publishNotification("title", "message")
		}
		historyRecorder := httptest.NewRecorder()
		notificationsHandler(historyRecorder, authorizedNotificationRequest(http.MethodGet, "/api/notifications"))
		if historyRecorder.Code != http.StatusOK || len(decodeNotifications(t, historyRecorder)) != 5 {
			t.Fatalf("bad history response: %d %s", historyRecorder.Code, historyRecorder.Body.String())
		}

		latestRecorder := httptest.NewRecorder()
		latestNotificationHandler(latestRecorder, authorizedNotificationRequest(http.MethodGet, "/api/notifications/latest?after=2"))
		latest := decodeNotifications(t, latestRecorder)
		if len(latest) != 3 || latest[0].ID != 5 || latest[2].ID != 3 {
			t.Fatalf("unexpected latest: %+v", latest)
		}

		emptyRecorder := httptest.NewRecorder()
		latestNotificationHandler(emptyRecorder, authorizedNotificationRequest(http.MethodGet, "/api/notifications/latest?after=5"))
		if emptyRecorder.Code != http.StatusOK || len(decodeNotifications(t, emptyRecorder)) != 0 {
			t.Fatalf("expected empty array: %s", emptyRecorder.Body.String())
		}
	})
}

func TestNotificationAPIRejectsUnknownClientAndBadAfter(t *testing.T) {
	withTestDB(t, notificationTestDatabase(), func() {
		unauthorized := httptest.NewRecorder()
		notificationsHandler(unauthorized, httptest.NewRequest(http.MethodGet, "/api/notifications", nil))
		if unauthorized.Code != http.StatusUnauthorized {
			t.Fatalf("got %d", unauthorized.Code)
		}
		bad := httptest.NewRecorder()
		latestNotificationHandler(bad, authorizedNotificationRequest(http.MethodGet, "/api/notifications/latest?after=oops"))
		if bad.Code != http.StatusBadRequest {
			t.Fatalf("got %d", bad.Code)
		}
	})
}

func TestNotificationAPIAcceptsMainPasswordCredential(t *testing.T) {
	database := notificationTestDatabase()
	database.MainPassword = "owner-secret"
	withTestDB(t, database, func() {
		recorder := httptest.NewRecorder()
		req := httptest.NewRequest(http.MethodGet, "/api/notifications", nil)
		req.Header.Set("Authorization", "Bearer owner-secret")
		notificationsHandler(recorder, req)
		if recorder.Code != http.StatusOK {
			t.Fatalf("got %d, want %d", recorder.Code, http.StatusOK)
		}
	})
}

func TestNotificationAPIIsolatesSubscriptionAudience(t *testing.T) {
	database := notificationTestDatabase()
	database.Passwords["subscription-a"] = &PasswordEntry{ExpiresAt: time.Now().Add(time.Hour).Unix()}
	database.Passwords["subscription-b"] = &PasswordEntry{ExpiresAt: time.Now().Add(time.Hour).Unix()}
	withTestDB(t, database, func() {
		publishNotificationRecord(NotificationRecord{Title: "A", Type: SubscriptionRenewed, Audience: "subscription:" + subscriptionIdentityKey("subscription-a")})
		publishNotificationRecord(NotificationRecord{Title: "B", Type: SubscriptionRenewed, Audience: "subscription:" + subscriptionIdentityKey("subscription-b")})
		recorder := httptest.NewRecorder()
		req := httptest.NewRequest(http.MethodGet, "/api/notifications", nil)
		req.Header.Set("Authorization", "Bearer subscription-a")
		notificationsHandler(recorder, req)
		items := decodeNotifications(t, recorder)
		if len(items) != 1 || items[0].Title != "A" {
			t.Fatalf("targeted history leaked across subscriptions: %+v", items)
		}
	})
}

func TestNotificationDeleteAndResendUseNewID(t *testing.T) {
	withTestDB(t, notificationTestDatabase(), func() {
		first := publishNotification("title", "message")
		if !deleteNotification(first.ID) || len(db.Notifications) != 0 {
			t.Fatal("delete failed")
		}
		resent := publishNotification(first.Title, first.Message)
		if resent.ID != first.ID+1 {
			t.Fatalf("resent id=%d", resent.ID)
		}
	})
}

func TestNotificationHistorySnapshotReflectsDeletes(t *testing.T) {
	withTestDB(t, notificationTestDatabase(), func() {
		for i := 0; i < 5; i++ {
			publishNotification("title", "message")
		}

		if !deleteNotification(3) {
			t.Fatal("delete middle notification failed")
		}
		middleDeleted := httptest.NewRecorder()
		notificationsHandler(middleDeleted, authorizedNotificationRequest(http.MethodGet, "/api/notifications"))
		middleItems := decodeNotifications(t, middleDeleted)
		if got, want := notificationIDs(middleItems), []int64{5, 4, 2, 1}; !slices.Equal(got, want) {
			t.Fatalf("after middle delete got %v, want %v", got, want)
		}

		if !deleteNotification(5) {
			t.Fatal("delete latest notification failed")
		}
		latestDeleted := httptest.NewRecorder()
		notificationsHandler(latestDeleted, authorizedNotificationRequest(http.MethodGet, "/api/notifications"))
		latestItems := decodeNotifications(t, latestDeleted)
		if got, want := notificationIDs(latestItems), []int64{4, 2, 1}; !slices.Equal(got, want) {
			t.Fatalf("after latest delete got %v, want %v", got, want)
		}

		for _, item := range latestItems {
			if !deleteNotification(item.ID) {
				t.Fatalf("delete notification %d failed", item.ID)
			}
		}
		empty := httptest.NewRecorder()
		notificationsHandler(empty, authorizedNotificationRequest(http.MethodGet, "/api/notifications"))
		if items := decodeNotifications(t, empty); len(items) != 0 {
			t.Fatalf("after deleting all got %+v", items)
		}
	})
}

func TestNotificationDeletePersistsAuthoritativeHistory(t *testing.T) {
	withTestDBFile(t, notificationTestDatabase(), func(file string) {
		first := publishNotification("first", "message")
		latest := publishNotification("latest", "message")
		asyncDBSave.wait()

		if !deleteNotification(latest.ID) {
			t.Fatal("delete latest notification failed")
		}
		asyncDBSave.wait()

		raw, err := os.ReadFile(file)
		if err != nil {
			t.Fatal(err)
		}
		var persisted Database
		if err := json.Unmarshal(raw, &persisted); err != nil {
			t.Fatal(err)
		}
		migrateNotifications(&persisted)
		if got, want := notificationIDs(persisted.Notifications), []int64{first.ID}; !slices.Equal(got, want) {
			t.Fatalf("persisted history got %v, want %v", got, want)
		}
	})
}

func notificationIDs(items []NotificationRecord) []int64 {
	ids := make([]int64, len(items))
	for index, item := range items {
		ids[index] = item.ID
	}
	return ids
}

func TestNotificationCloneAndPersistenceAcrossRestart(t *testing.T) {
	withTestDBFile(t, notificationTestDatabase(), func(file string) {
		for i := 0; i < 6; i++ {
			publishNotification("title", "message")
		}
		asyncDBSave.wait()
		dbMutex.Lock()
		snapshot := cloneDatabaseLocked()
		dbMutex.Unlock()
		if len(snapshot.Notifications) != 5 || snapshot.NotificationNextID != 6 {
			t.Fatalf("bad clone: %+v", snapshot.Notifications)
		}

		raw, err := os.ReadFile(file)
		if err != nil {
			t.Fatal(err)
		}
		var restored Database
		if err := json.Unmarshal(raw, &restored); err != nil {
			t.Fatal(err)
		}
		migrateNotifications(&restored)
		dbMutex.Lock()
		db = &restored
		dbFile = file
		dbMutex.Unlock()
		next := publishNotification("after restart", "message")
		if next.ID != 7 || len(db.Notifications) != 5 {
			t.Fatalf("restart state id=%d len=%d", next.ID, len(db.Notifications))
		}
	})
}

func TestLegacyLastNotificationMigrates(t *testing.T) {
	database := notificationTestDatabase()
	database.LastNotification = NotificationRecord{ID: 42, Title: "legacy", Message: "body", CreatedAt: 1}
	migrateNotifications(database)
	if len(database.Notifications) != 1 || database.Notifications[0].ID != 42 || database.NotificationNextID != 42 {
		t.Fatalf("migration failed: %+v", database)
	}
}
