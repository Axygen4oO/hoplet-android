package main

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func performDeviceNameRequest(t *testing.T, body DeviceNameRequest) *httptest.ResponseRecorder {
	t.Helper()

	payload, err := json.Marshal(body)
	if err != nil {
		t.Fatalf("marshal request: %v", err)
	}

	req := httptest.NewRequest(http.MethodPost, "/api/device/name", bytes.NewReader(payload))
	req.Header.Set("Content-Type", "application/json")
	rec := httptest.NewRecorder()
	deviceNameHandler(rec, req)
	return rec
}

func TestDeviceNameHandlerRejectsInvalidPassword(t *testing.T) {
	withTestDB(t, &Database{
		Passwords: map[string]*PasswordEntry{},
		Devices:   map[string]*ClientDevice{},
	}, func() {
		rec := performDeviceNameRequest(t, DeviceNameRequest{
			Password:   "missing",
			DeviceID:   "device-1",
			DeviceName: "Phone",
		})
		if rec.Code != http.StatusNotFound {
			t.Fatalf("expected 404, got %d", rec.Code)
		}
	})
}

func TestDeviceNameHandlerRejectsExpiredPassword(t *testing.T) {
	withTestDB(t, &Database{
		Passwords: map[string]*PasswordEntry{
			"expired": {ExpiresAt: time.Now().Add(-time.Hour).Unix()},
		},
		Devices: map[string]*ClientDevice{},
	}, func() {
		rec := performDeviceNameRequest(t, DeviceNameRequest{
			Password:   "expired",
			DeviceID:   "device-1",
			DeviceName: "Phone",
		})
		if rec.Code != http.StatusUnauthorized {
			t.Fatalf("expected 401, got %d", rec.Code)
		}
	})
}

func TestDeviceNameHandlerRejectsForeignDevice(t *testing.T) {
	withTestDB(t, &Database{
		Passwords: map[string]*PasswordEntry{
			"owner-a": {DeviceIDs: []string{"device-a"}, MaxDevices: 1},
			"owner-b": {DeviceIDs: []string{"device-b"}, MaxDevices: 1},
		},
		Devices: map[string]*ClientDevice{
			"device-b": {DeviceID: "device-b", DeviceName: "Other"},
		},
	}, func() {
		rec := performDeviceNameRequest(t, DeviceNameRequest{
			Password:   "owner-a",
			DeviceID:   "device-b",
			DeviceName: "Hijack",
		})
		if rec.Code != http.StatusForbidden {
			t.Fatalf("expected 403, got %d", rec.Code)
		}
	})
}

func TestDeviceNameHandlerRejectsWhenDeviceLimitReached(t *testing.T) {
	withTestDB(t, &Database{
		Passwords: map[string]*PasswordEntry{
			"limited": {DeviceIDs: []string{"device-a"}, MaxDevices: 1},
		},
		Devices: map[string]*ClientDevice{
			"device-a": {DeviceID: "device-a", DeviceName: "Existing"},
		},
	}, func() {
		rec := performDeviceNameRequest(t, DeviceNameRequest{
			Password:   "limited",
			DeviceID:   "device-b",
			DeviceName: "New",
		})
		if rec.Code != http.StatusForbidden {
			t.Fatalf("expected 403, got %d", rec.Code)
		}
	})
}

func TestDeviceNameHandlerRegistersNewDeviceWhenAllowed(t *testing.T) {
	withTestDBFile(t, &Database{
		Passwords: map[string]*PasswordEntry{
			"good": {MaxDevices: 2},
		},
		Devices: map[string]*ClientDevice{},
	}, func(_ string) {
		rec := performDeviceNameRequest(t, DeviceNameRequest{
			Password:   "good",
			DeviceID:   "device-new",
			DeviceName: "Pixel",
		})
		asyncDBSave.wait()

		if rec.Code != http.StatusOK {
			t.Fatalf("expected 200, got %d", rec.Code)
		}
		entry := db.Passwords["good"]
		if !passwordEntryOwnsDeviceID(entry, "device-new") {
			t.Fatalf("expected device to be bound, entry=%+v", entry)
		}
		dev := db.Devices["device-new"]
		if dev == nil || dev.DeviceName != "Pixel" {
			t.Fatalf("expected device to be created with name, got %+v", dev)
		}
	})
}
