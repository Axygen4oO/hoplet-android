package main

import (
	"encoding/json"
	"io"
	"net/http"
	"strings"
)

type passwordDeviceRequest struct {
	Password string `json:"password"`
	DeviceID string `json:"device_id"`
}

func isActivePasswordEntry(entry *PasswordEntry) bool {
	return entry != nil && !entry.IsDeactivated && !isPasswordExpired(entry)
}

func passwordEntryOwnsDeviceID(entry *PasswordEntry, deviceID string) bool {
	if entry == nil || deviceID == "" {
		return false
	}
	if entry.DeviceID == deviceID {
		return true
	}
	for _, id := range entry.DeviceIDs {
		if id == deviceID {
			return true
		}
	}
	return false
}

func findPasswordEntryByDeviceIDLocked(deviceID string) *PasswordEntry {
	for _, entry := range db.Passwords {
		if passwordEntryOwnsDeviceID(entry, deviceID) {
			return entry
		}
	}
	return nil
}

func readPasswordDeviceRequest(r *http.Request) (passwordDeviceRequest, error) {
	req := passwordDeviceRequest{}

	switch r.Method {
	case http.MethodGet:
		req.Password = strings.TrimSpace(r.FormValue("password"))
		req.DeviceID = strings.TrimSpace(r.FormValue("device_id"))
	case http.MethodPost:
		contentType := strings.ToLower(r.Header.Get("Content-Type"))
		if strings.Contains(contentType, "application/json") {
			if err := json.NewDecoder(r.Body).Decode(&req); err != nil && err != io.EOF {
				return passwordDeviceRequest{}, err
			}
		} else {
			req.Password = strings.TrimSpace(r.FormValue("password"))
			req.DeviceID = strings.TrimSpace(r.FormValue("device_id"))
		}

		if req.Password == "" {
			authHeader := strings.TrimSpace(r.Header.Get("Authorization"))
			if strings.HasPrefix(authHeader, "Password ") {
				req.Password = strings.TrimSpace(strings.TrimPrefix(authHeader, "Password "))
			}
		}
	default:
		req.Password = strings.TrimSpace(r.FormValue("password"))
		req.DeviceID = strings.TrimSpace(r.FormValue("device_id"))
	}

	req.Password = strings.TrimSpace(req.Password)
	req.DeviceID = strings.TrimSpace(req.DeviceID)
	return req, nil
}
