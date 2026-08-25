package main

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func TestLinkSubscriptionHandlerLinksSubscriptionToExistingUser(t *testing.T) {
	withTestDB(t, &Database{
		Passwords: map[string]*PasswordEntry{
			"sub-pass": {
				MaxDevices: 2,
				ExpiresAt:  time.Now().Add(24 * time.Hour).Unix(),
			},
		},
		Devices: map[string]*ClientDevice{},
		Users: map[string]*UserAccount{
			"user@example.com": {
				Email:        "user@example.com",
				Role:         "user",
				CreatedAt:    time.Now().Unix(),
				PasswordHash: "hash",
			},
		},
		Orders:         map[string]*Order{},
		SupportTickets: map[string]*SupportTicket{},
	}, func() {
		token, err := GenerateJWT(&UserAccount{
			Email: "user@example.com",
			Role:  "user",
		})
		if err != nil {
			t.Fatalf("failed to issue jwt: %v", err)
		}

		body, err := json.Marshal(LinkSubscriptionRequest{Code: "sub-pass"})
		if err != nil {
			t.Fatalf("failed to encode request: %v", err)
		}

		req := httptest.NewRequest(http.MethodPost, "/api/subscription/link", bytes.NewReader(body))
		req.Header.Set("Authorization", "Bearer "+token)
		recorder := httptest.NewRecorder()

		linkSubscriptionHandler(recorder, req)

		if recorder.Code != http.StatusOK {
			t.Fatalf("expected 200, got %d: %s", recorder.Code, recorder.Body.String())
		}

		user := db.Users["user@example.com"]
		if user.SubscriptionID != "sub-pass" {
			t.Fatalf("expected linked subscription, got %q", user.SubscriptionID)
		}
	})
}

func TestLinkSubscriptionHandlerReturnsBlockedError(t *testing.T) {
	withTestDB(t, &Database{
		Passwords: map[string]*PasswordEntry{
			"sub-pass": {
				IsDeactivated: true,
			},
		},
		Devices: map[string]*ClientDevice{},
		Users: map[string]*UserAccount{
			"user@example.com": {
				Email:        "user@example.com",
				Role:         "user",
				CreatedAt:    time.Now().Unix(),
				PasswordHash: "hash",
			},
		},
		Orders:         map[string]*Order{},
		SupportTickets: map[string]*SupportTicket{},
	}, func() {
		token, err := GenerateJWT(&UserAccount{
			Email: "user@example.com",
			Role:  "user",
		})
		if err != nil {
			t.Fatalf("failed to issue jwt: %v", err)
		}

		body, err := json.Marshal(LinkSubscriptionRequest{Code: "sub-pass"})
		if err != nil {
			t.Fatalf("failed to encode request: %v", err)
		}

		req := httptest.NewRequest(http.MethodPost, "/api/subscription/link", bytes.NewReader(body))
		req.Header.Set("Authorization", "Bearer "+token)
		recorder := httptest.NewRecorder()

		linkSubscriptionHandler(recorder, req)

		if recorder.Code != http.StatusForbidden {
			t.Fatalf("expected 403, got %d: %s", recorder.Code, recorder.Body.String())
		}
		if recorder.Body.String() == "" {
			t.Fatalf("expected error payload")
		}
	})
}
