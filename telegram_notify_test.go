package main

import (
	"bytes"
	"encoding/json"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
)

type telegramRecorderTransport struct {
	mu       sync.Mutex
	requests []telegramRecordedRequest
}

type telegramRecordedRequest struct {
	URL  string
	Body map[string]interface{}
}

func (t *telegramRecorderTransport) RoundTrip(req *http.Request) (*http.Response, error) {
	bodyBytes, _ := io.ReadAll(req.Body)
	_ = req.Body.Close()

	payload := map[string]interface{}{}
	_ = json.Unmarshal(bodyBytes, &payload)

	t.mu.Lock()
	t.requests = append(t.requests, telegramRecordedRequest{
		URL:  req.URL.String(),
		Body: payload,
	})
	t.mu.Unlock()

	return &http.Response{
		StatusCode: http.StatusOK,
		Header:     make(http.Header),
		Body:       io.NopCloser(bytes.NewReader([]byte(`{"ok":true}`))),
		Request:    req,
	}, nil
}

func (t *telegramRecorderTransport) lastText() string {
	t.mu.Lock()
	defer t.mu.Unlock()
	if len(t.requests) == 0 {
		return ""
	}
	text, _ := t.requests[len(t.requests)-1].Body["text"].(string)
	return text
}

func (t *telegramRecorderTransport) requestCount() int {
	t.mu.Lock()
	defer t.mu.Unlock()
	return len(t.requests)
}

func withTelegramRecorder(t *testing.T, testFn func(recorder *telegramRecorderTransport)) {
	t.Helper()

	prevClient := telegramHTTPClient
	recorder := &telegramRecorderTransport{}
	telegramHTTPClient = &http.Client{Transport: recorder}

	defer func() {
		telegramHTTPClient = prevClient
	}()

	testFn(recorder)
}

func withNotificationTestDB(t *testing.T, database *Database, testFn func(file string)) {
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
		resetNotificationComposeState()
	}()

	testFn(file)
}

func TestNotificationFlowPublishSuccess(t *testing.T) {
	withNotificationTestDB(t, &Database{
		Passwords:      map[string]*PasswordEntry{},
		Devices:        map[string]*ClientDevice{},
		Users:          map[string]*UserAccount{},
		Orders:         map[string]*Order{},
		SupportTickets: map[string]*SupportTicket{},
	}, func(file string) {
		withTelegramRecorder(t, func(recorder *telegramRecorderTransport) {
			startNotificationCompose("token", 1)
			if !handleNotificationInput("token", 1, "Server title") {
				t.Fatalf("expected title input to be handled")
			}
			if !handleNotificationInput("token", 1, "Server message") {
				t.Fatalf("expected message input to be handled")
			}

			preview := buildNotificationPreviewText("Server title", "Server message")
			if !handleNotificationCallback("token", 1, "notify_send", 10, preview) {
				t.Fatalf("expected send callback to be handled")
			}
			asyncDBSave.wait()

			if hasActiveNotificationCompose() {
				t.Fatalf("expected notification state to be cleared after publish")
			}
			if db.LastNotification.ID != 1 || db.LastNotification.Title != "Server title" || db.LastNotification.Message != "Server message" {
				t.Fatalf("unexpected last notification: %+v", db.LastNotification)
			}
			if !strings.Contains(recorder.lastText(), "ID: 1") {
				t.Fatalf("expected success confirmation with ID, got %q", recorder.lastText())
			}

			raw, err := os.ReadFile(file)
			if err != nil {
				t.Fatalf("failed to read db file: %v", err)
			}
			if !strings.Contains(string(raw), "\"last_notification\"") {
				t.Fatalf("expected last_notification to be persisted: %s", string(raw))
			}
		})
	})
}

func TestNotificationFlowDoubleSendCallbackIsIgnored(t *testing.T) {
	withNotificationTestDB(t, &Database{
		Passwords:      map[string]*PasswordEntry{},
		Devices:        map[string]*ClientDevice{},
		Users:          map[string]*UserAccount{},
		Orders:         map[string]*Order{},
		SupportTickets: map[string]*SupportTicket{},
	}, func(file string) {
		_ = file
		withTelegramRecorder(t, func(recorder *telegramRecorderTransport) {
			startNotificationCompose("token", 1)
			_ = handleNotificationInput("token", 1, "Server title")
			_ = handleNotificationInput("token", 1, "Server message")

			preview := buildNotificationPreviewText("Server title", "Server message")
			if !handleNotificationCallback("token", 1, "notify_send", 10, preview) {
				t.Fatalf("expected first send callback to be handled")
			}
			firstRequestCount := recorder.requestCount()
			firstNotification := db.LastNotification

			if !handleNotificationCallback("token", 1, "notify_send", 10, preview) {
				t.Fatalf("expected duplicate send callback to be handled")
			}

			if recorder.requestCount() != firstRequestCount {
				t.Fatalf("expected duplicate send callback to be ignored")
			}
			if db.LastNotification != firstNotification {
				t.Fatalf("expected duplicate send callback not to republish, got %+v", db.LastNotification)
			}
			if hasActiveNotificationCompose() {
				t.Fatalf("expected state to remain cleared after duplicate send callback")
			}
		})
	})
}

func TestNotificationFlowCancelAndRestart(t *testing.T) {
	withNotificationTestDB(t, &Database{
		Passwords:      map[string]*PasswordEntry{},
		Devices:        map[string]*ClientDevice{},
		Users:          map[string]*UserAccount{},
		Orders:         map[string]*Order{},
		SupportTickets: map[string]*SupportTicket{},
	}, func(file string) {
		_ = file
		withTelegramRecorder(t, func(recorder *telegramRecorderTransport) {
			startNotificationCompose("token", 1)
			if !handleNotificationInput("token", 1, "Draft title") {
				t.Fatalf("expected title input to be handled")
			}
			cancelNotificationCompose("token", 1)

			if hasActiveNotificationCompose() {
				t.Fatalf("expected state to be cleared after cancel")
			}
			if recorder.lastText() != "Отправка уведомления отменена." {
				t.Fatalf("unexpected cancel message: %q", recorder.lastText())
			}

			if !handleCommand("token", 1, "/notify", nil) {
				t.Fatalf("expected /notify to restart flow")
			}
			if tgState.NotificationStage != notificationStageTitle {
				t.Fatalf("expected restarted flow to wait for title, got %q", tgState.NotificationStage)
			}
		})
	})
}

func TestNotificationFlowPreviewCancel(t *testing.T) {
	withNotificationTestDB(t, &Database{
		Passwords:      map[string]*PasswordEntry{},
		Devices:        map[string]*ClientDevice{},
		Users:          map[string]*UserAccount{},
		Orders:         map[string]*Order{},
		SupportTickets: map[string]*SupportTicket{},
	}, func(file string) {
		_ = file
		withTelegramRecorder(t, func(recorder *telegramRecorderTransport) {
			startNotificationCompose("token", 1)
			_ = handleNotificationInput("token", 1, "Draft title")
			_ = handleNotificationInput("token", 1, "Draft body")

			if !handleNotificationCallback("token", 1, "notify_cancel", 15, buildNotificationPreviewText("Draft title", "Draft body")) {
				t.Fatalf("expected cancel callback to be handled")
			}
			if hasActiveNotificationCompose() {
				t.Fatalf("expected state to be cleared after preview cancel")
			}
			if recorder.lastText() != "Отправка уведомления отменена." {
				t.Fatalf("unexpected cancel callback message: %q", recorder.lastText())
			}
		})
	})
}

func TestNotificationFlowDoubleCancelCallbackIsIgnored(t *testing.T) {
	withNotificationTestDB(t, &Database{
		Passwords:      map[string]*PasswordEntry{},
		Devices:        map[string]*ClientDevice{},
		Users:          map[string]*UserAccount{},
		Orders:         map[string]*Order{},
		SupportTickets: map[string]*SupportTicket{},
	}, func(file string) {
		_ = file
		withTelegramRecorder(t, func(recorder *telegramRecorderTransport) {
			startNotificationCompose("token", 1)
			_ = handleNotificationInput("token", 1, "Draft title")
			_ = handleNotificationInput("token", 1, "Draft body")

			preview := buildNotificationPreviewText("Draft title", "Draft body")
			if !handleNotificationCallback("token", 1, "notify_cancel", 15, preview) {
				t.Fatalf("expected first cancel callback to be handled")
			}
			firstRequestCount := recorder.requestCount()

			if !handleNotificationCallback("token", 1, "notify_cancel", 15, preview) {
				t.Fatalf("expected duplicate cancel callback to be handled")
			}

			if recorder.requestCount() != firstRequestCount {
				t.Fatalf("expected duplicate cancel callback to be ignored")
			}
			if hasActiveNotificationCompose() {
				t.Fatalf("expected state to remain cleared after duplicate cancel callback")
			}
		})
	})
}

func TestNotificationFlowRepeatedTitleMessageDoesNotBreakState(t *testing.T) {
	withNotificationTestDB(t, &Database{
		Passwords:      map[string]*PasswordEntry{},
		Devices:        map[string]*ClientDevice{},
		Users:          map[string]*UserAccount{},
		Orders:         map[string]*Order{},
		SupportTickets: map[string]*SupportTicket{},
	}, func(file string) {
		_ = file
		withTelegramRecorder(t, func(recorder *telegramRecorderTransport) {
			startNotificationCompose("token", 1)
			if !handleNotificationInput("token", 1, "Repeat me") {
				t.Fatalf("expected title input to be handled")
			}
			if tgState.NotificationStage != notificationStageMessage {
				t.Fatalf("expected message stage after title, got %q", tgState.NotificationStage)
			}

			requestsBeforeDuplicate := recorder.requestCount()
			if !handleNotificationInput("token", 1, "Repeat me") {
				t.Fatalf("expected duplicate text to be handled")
			}
			if tgState.NotificationStage != notificationStageMessage {
				t.Fatalf("expected duplicate text not to advance wizard, got %q", tgState.NotificationStage)
			}
			if recorder.requestCount() != requestsBeforeDuplicate+1 {
				t.Fatalf("expected duplicate text to only re-prompt for message")
			}

			if !handleNotificationInput("token", 1, "Final body") {
				t.Fatalf("expected final body to be handled")
			}
			if tgState.NotificationStage != notificationStageConfirm {
				t.Fatalf("expected confirm stage after valid body, got %q", tgState.NotificationStage)
			}
		})
	})
}

func TestNotificationFlowPublishFailureClearsState(t *testing.T) {
	withTelegramRecorder(t, func(recorder *telegramRecorderTransport) {
		dbMutex.Lock()
		prevDB := db
		prevFile := dbFile
		db = nil
		dbFile = ""
		dbMutex.Unlock()
		defer func() {
			dbMutex.Lock()
			db = prevDB
			dbFile = prevFile
			dbMutex.Unlock()
			resetNotificationComposeState()
		}()

		tgState.NotificationStage = notificationStageConfirm
		tgState.NotificationTitle = "Broken title"
		tgState.NotificationPreview = buildNotificationPreviewText("Broken title", "Broken body")

		if !handleNotificationCallback("token", 1, "notify_send", 25, tgState.NotificationPreview) {
			t.Fatalf("expected failed send callback to be handled")
		}
		if hasActiveNotificationCompose() {
			t.Fatalf("expected state to be cleared after failed publish")
		}
		if !strings.Contains(recorder.lastText(), "Не удалось опубликовать уведомление") {
			t.Fatalf("expected failure message, got %q", recorder.lastText())
		}
	})
}

func TestNotificationFlowRestartAfterSuccessfulPublish(t *testing.T) {
	withNotificationTestDB(t, &Database{
		Passwords:      map[string]*PasswordEntry{},
		Devices:        map[string]*ClientDevice{},
		Users:          map[string]*UserAccount{},
		Orders:         map[string]*Order{},
		SupportTickets: map[string]*SupportTicket{},
	}, func(file string) {
		_ = file
		withTelegramRecorder(t, func(recorder *telegramRecorderTransport) {
			startNotificationCompose("token", 1)
			_ = handleNotificationInput("token", 1, "Release title")
			_ = handleNotificationInput("token", 1, "Release body")
			preview := buildNotificationPreviewText("Release title", "Release body")

			if !handleNotificationCallback("token", 1, "notify_send", 30, preview) {
				t.Fatalf("expected send callback to be handled")
			}
			if hasActiveNotificationCompose() {
				t.Fatalf("expected state to be cleared after publish")
			}

			requestsBeforeRestart := recorder.requestCount()
			if !handleCommand("token", 1, "/notify", nil) {
				t.Fatalf("expected /notify to start a new flow after publish")
			}
			if tgState.NotificationStage != notificationStageTitle {
				t.Fatalf("expected title stage after restart, got %q", tgState.NotificationStage)
			}
			if recorder.requestCount() != requestsBeforeRestart+1 {
				t.Fatalf("expected restart to send a fresh title prompt")
			}
		})
	})
}

func TestNonAdminNotifyDenied(t *testing.T) {
	withTelegramRecorder(t, func(recorder *telegramRecorderTransport) {
		denyNotificationCommandForNonAdmin("token", 42)
		if recorder.lastText() != "Команда доступна только администратору." {
			t.Fatalf("unexpected non-admin reply: %q", recorder.lastText())
		}
	})
}
