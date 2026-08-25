package main

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func TestProfileStatusSupportsLegacyGet(t *testing.T) {
	withTestDB(t, &Database{
		Passwords: map[string]*PasswordEntry{
			"good": {DeviceIDs: []string{"device-1"}, MaxDevices: 2, ExpiresAt: time.Now().Add(time.Hour).Unix()},
		},
	}, func() {
		req := httptest.NewRequest(http.MethodGet, "/api/profile/status?password=good&device_id=device-1", nil)
		rec := httptest.NewRecorder()

		handleAPIProfileStatus(rec, req)

		if rec.Code != http.StatusOK {
			t.Fatalf("expected 200, got %d", rec.Code)
		}
	})
}

func TestProfileStatusSupportsPostBody(t *testing.T) {
	withTestDB(t, &Database{
		Passwords: map[string]*PasswordEntry{
			"good": {DeviceIDs: []string{"device-1"}, MaxDevices: 2},
		},
	}, func() {
		body := bytes.NewBufferString(`{"password":"good","device_id":"device-1"}`)
		req := httptest.NewRequest(http.MethodPost, "/api/profile/status", body)
		req.Header.Set("Content-Type", "application/json")
		rec := httptest.NewRecorder()

		handleAPIProfileStatus(rec, req)

		if rec.Code != http.StatusOK {
			t.Fatalf("expected 200, got %d", rec.Code)
		}

		var payload map[string]any
		if err := json.Unmarshal(rec.Body.Bytes(), &payload); err != nil {
			t.Fatalf("decode response: %v", err)
		}
		if payload["is_current_bound"] != true {
			t.Fatalf("expected is_current_bound=true, got %+v", payload)
		}
	})
}

func TestProfileStatusRejectsExpiredPassword(t *testing.T) {
	withTestDB(t, &Database{
		Passwords: map[string]*PasswordEntry{
			"expired": {ExpiresAt: time.Now().Add(-time.Minute).Unix()},
		},
	}, func() {
		body := bytes.NewBufferString(`{"password":"expired","device_id":"device-1"}`)
		req := httptest.NewRequest(http.MethodPost, "/api/profile/status", body)
		req.Header.Set("Content-Type", "application/json")
		rec := httptest.NewRecorder()

		handleAPIProfileStatus(rec, req)

		if rec.Code != http.StatusUnauthorized {
			t.Fatalf("expected 401, got %d", rec.Code)
		}
	})
}

func TestProfileStatusRejectsInvalidPassword(t *testing.T) {
	withTestDB(t, &Database{
		Passwords: map[string]*PasswordEntry{},
	}, func() {
		body := bytes.NewBufferString(`{"password":"missing","device_id":"device-1"}`)
		req := httptest.NewRequest(http.MethodPost, "/api/profile/status", body)
		req.Header.Set("Content-Type", "application/json")
		rec := httptest.NewRecorder()

		handleAPIProfileStatus(rec, req)

		if rec.Code != http.StatusUnauthorized {
			t.Fatalf("expected 401, got %d", rec.Code)
		}
	})
}
