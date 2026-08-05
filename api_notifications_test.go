package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
)

func withTestDB(t *testing.T, database *Database, testFn func()) {
	t.Helper()

	dbMutex.Lock()
	prevDB := db
	prevFile := dbFile
	db = database
	dbFile = ""
	dbMutex.Unlock()

	defer func() {
		dbMutex.Lock()
		db = prevDB
		dbFile = prevFile
		dbMutex.Unlock()
	}()

	testFn()
}

func withTestDBFile(t *testing.T, database *Database, testFn func(file string)) {
	t.Helper()

	dir := t.TempDir()
	file := filepath.Join(dir, "passwords.json")

	dbMutex.Lock()
	prevDB := db
	prevFile := dbFile
	db = database
	dbFile = file
	dbMutex.Unlock()

	defer func() {
		dbMutex.Lock()
		db = prevDB
		dbFile = prevFile
		dbMutex.Unlock()
	}()

	testFn(file)
}

func readDBFromFile(t *testing.T, file string) Database {
	t.Helper()

	raw, err := os.ReadFile(file)
	if err != nil {
		t.Fatalf("failed to read db file: %v", err)
	}

	var restored Database
	if err := json.Unmarshal(raw, &restored); err != nil {
		t.Fatalf("failed to decode db file: %v", err)
	}

	return restored
}

func TestLatestNotificationHandlerMissingAfterReturnsLatest(t *testing.T) {
	withTestDB(t, &Database{
		LastNotification: AppNotification{
			ID:        42,
			Title:     "Server title",
			Message:   "Server message",
			CreatedAt: 1710000000,
		},
	}, func() {
		req := httptest.NewRequest(http.MethodGet, "/api/notifications/latest", nil)
		recorder := httptest.NewRecorder()

		latestNotificationHandler(recorder, req)

		if recorder.Code != http.StatusOK {
			t.Fatalf("expected 200, got %d", recorder.Code)
		}

		var payload AppNotification
		if err := json.Unmarshal(recorder.Body.Bytes(), &payload); err != nil {
			t.Fatalf("failed to decode response: %v", err)
		}
		if payload.ID != 42 || payload.Title != "Server title" || payload.Message != "Server message" || payload.CreatedAt != 1710000000 {
			t.Fatalf("unexpected payload: %+v", payload)
		}
	})
}

func TestLatestNotificationHandlerInvalidAfterReturnsBadRequest(t *testing.T) {
	withTestDB(t, &Database{
		LastNotification: AppNotification{ID: 7},
	}, func() {
		req := httptest.NewRequest(http.MethodGet, "/api/notifications/latest?after=oops", nil)
		recorder := httptest.NewRecorder()

		latestNotificationHandler(recorder, req)

		if recorder.Code != http.StatusBadRequest {
			t.Fatalf("expected 400, got %d", recorder.Code)
		}
	})
}

func TestLatestNotificationHandlerNoNewNotificationReturnsNoContent(t *testing.T) {
	withTestDB(t, &Database{
		LastNotification: AppNotification{ID: 7},
	}, func() {
		req := httptest.NewRequest(http.MethodGet, "/api/notifications/latest?after=7", nil)
		recorder := httptest.NewRecorder()

		latestNotificationHandler(recorder, req)

		if recorder.Code != http.StatusNoContent {
			t.Fatalf("expected 204, got %d", recorder.Code)
		}
		if recorder.Body.Len() != 0 {
			t.Fatalf("expected empty body for 204, got %q", recorder.Body.String())
		}
	})
}

func TestWriteDBSnapshotPersistsLastNotification(t *testing.T) {
	dir := t.TempDir()
	file := filepath.Join(dir, "passwords.json")
	snapshot := &Database{
		Passwords:      map[string]*PasswordEntry{},
		Devices:        map[string]*ClientDevice{},
		Users:          map[string]*UserAccount{},
		Orders:         map[string]*Order{},
		SupportTickets: map[string]*SupportTicket{},
		VKHashes:       []string{"abc"},
		LastNotification: AppNotification{
			ID:        99,
			Title:     "Persist me",
			Message:   "Keep after restart",
			CreatedAt: 1720000000,
		},
	}

	dbMutex.Lock()
	prevFile := dbFile
	dbFile = file
	dbMutex.Unlock()
	defer func() {
		dbMutex.Lock()
		dbFile = prevFile
		dbMutex.Unlock()
	}()

	if err := writeDBSnapshot(snapshot); err != nil {
		t.Fatalf("writeDBSnapshot failed: %v", err)
	}

	raw, err := os.ReadFile(file)
	if err != nil {
		t.Fatalf("failed to read db file: %v", err)
	}

	var restored Database
	if err := json.Unmarshal(raw, &restored); err != nil {
		t.Fatalf("failed to decode db file: %v", err)
	}

	if restored.LastNotification != snapshot.LastNotification {
		t.Fatalf("expected last notification %+v, got %+v", snapshot.LastNotification, restored.LastNotification)
	}
}

func TestPublishNotificationCreatesAndPersistsNotification(t *testing.T) {
	withTestDBFile(t, &Database{
		Passwords:      map[string]*PasswordEntry{},
		Devices:        map[string]*ClientDevice{},
		Users:          map[string]*UserAccount{},
		Orders:         map[string]*Order{},
		SupportTickets: map[string]*SupportTicket{},
	}, func(file string) {
		published := publishNotification("First title", "First message")
		asyncDBSave.wait()

		if published.ID != 1 {
			t.Fatalf("expected first ID to be 1, got %d", published.ID)
		}
		if published.CreatedAt <= 0 {
			t.Fatalf("expected CreatedAt to be set, got %d", published.CreatedAt)
		}

		restored := readDBFromFile(t, file)
		if restored.LastNotification != published {
			t.Fatalf("expected persisted notification %+v, got %+v", published, restored.LastNotification)
		}
	})
}

func TestPublishNotificationIncrementsIDAcrossRestart(t *testing.T) {
	withTestDBFile(t, &Database{
		Passwords:      map[string]*PasswordEntry{},
		Devices:        map[string]*ClientDevice{},
		Users:          map[string]*UserAccount{},
		Orders:         map[string]*Order{},
		SupportTickets: map[string]*SupportTicket{},
	}, func(file string) {
		first := publishNotification("First title", "First message")
		asyncDBSave.wait()

		restored := readDBFromFile(t, file)

		dbMutex.Lock()
		db = &restored
		dbFile = file
		dbMutex.Unlock()

		second := publishNotification("Second title", "Second message")
		asyncDBSave.wait()

		if first.ID != 1 {
			t.Fatalf("expected first ID to be 1, got %d", first.ID)
		}
		if second.ID != 2 {
			t.Fatalf("expected second ID to be 2 after restart, got %d", second.ID)
		}

		finalState := readDBFromFile(t, file)
		if finalState.LastNotification != second {
			t.Fatalf("expected final persisted notification %+v, got %+v", second, finalState.LastNotification)
		}
	})
}

func TestPublishNotificationMakesExistingAPIReturnLatestNotification(t *testing.T) {
	withTestDBFile(t, &Database{
		Passwords:      map[string]*PasswordEntry{},
		Devices:        map[string]*ClientDevice{},
		Users:          map[string]*UserAccount{},
		Orders:         map[string]*Order{},
		SupportTickets: map[string]*SupportTicket{},
	}, func(file string) {
		published := publishNotification("API title", "API message")
		asyncDBSave.wait()

		req := httptest.NewRequest(http.MethodGet, "/api/notifications/latest", nil)
		recorder := httptest.NewRecorder()
		latestNotificationHandler(recorder, req)

		if recorder.Code != http.StatusOK {
			t.Fatalf("expected 200, got %d", recorder.Code)
		}

		var payload AppNotification
		if err := json.Unmarshal(recorder.Body.Bytes(), &payload); err != nil {
			t.Fatalf("failed to decode response: %v", err)
		}
		if payload != published {
			t.Fatalf("expected API payload %+v, got %+v", published, payload)
		}
	})
}
