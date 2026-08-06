package main

import (
	"errors"
	"testing"
	"time"
)

func TestRegisterUserBySubscriptionAndIssueTokenSuccess(t *testing.T) {
	withTestDB(t, &Database{
		Passwords: map[string]*PasswordEntry{
			"sub-pass": {
				MaxDevices: 3,
				ExpiresAt:  time.Now().Add(-2 * time.Hour).Unix(),
			},
		},
		Devices:        map[string]*ClientDevice{},
		Users:          map[string]*UserAccount{},
		Orders:         map[string]*Order{},
		SupportTickets: map[string]*SupportTicket{},
	}, func() {
		result, err := RegisterUserBySubscriptionAndIssueToken("sub-pass", "User@Example.com", "secret")
		if err != nil {
			t.Fatalf("expected success, got %v", err)
		}
		if result == nil || result.User == nil {
			t.Fatalf("expected auth result with user")
		}
		if result.Token == "" || result.TokenExpiresAt == 0 {
			t.Fatalf("expected auth token to be issued: %+v", result)
		}

		user, ok := db.Users["user@example.com"]
		if !ok || user == nil {
			t.Fatalf("expected normalized user to be stored in db")
		}
		if user.SubscriptionID != "sub-pass" {
			t.Fatalf("expected linked subscription, got %q", user.SubscriptionID)
		}
		if user.SubscriptionStatus != "expired" {
			t.Fatalf("expected expired subscription status, got %q", user.SubscriptionStatus)
		}
		if user.DeviceLimit != 3 {
			t.Fatalf("expected device limit from subscription, got %d", user.DeviceLimit)
		}
		if user.SubscriptionExpires == 0 {
			t.Fatalf("expected subscription expiry to be copied")
		}
	})
}

func TestRegisterUserBySubscriptionAndIssueTokenRejectsRegisteredSubscription(t *testing.T) {
	withTestDB(t, &Database{
		Passwords: map[string]*PasswordEntry{
			"sub-pass": {},
		},
		Devices: map[string]*ClientDevice{},
		Users: map[string]*UserAccount{
			"existing@example.com": {
				Email:          "existing@example.com",
				SubscriptionID: "sub-pass",
			},
		},
		Orders:         map[string]*Order{},
		SupportTickets: map[string]*SupportTicket{},
	}, func() {
		err := validateSubscriptionRegistrationPassword("sub-pass")
		if !errors.Is(err, ErrSubscriptionAlreadyRegistered) {
			t.Fatalf("expected already registered error, got %v", err)
		}

		_, err = RegisterUserBySubscriptionAndIssueToken("sub-pass", "new@example.com", "secret")
		if !errors.Is(err, ErrSubscriptionAlreadyRegistered) {
			t.Fatalf("expected already registered error, got %v", err)
		}
	})
}

func TestRegisterUserBySubscriptionAndIssueTokenRejectsExistingEmail(t *testing.T) {
	withTestDB(t, &Database{
		Passwords: map[string]*PasswordEntry{
			"sub-pass": {},
		},
		Devices: map[string]*ClientDevice{},
		Users: map[string]*UserAccount{
			"user@example.com": {
				Email: "user@example.com",
			},
		},
		Orders:         map[string]*Order{},
		SupportTickets: map[string]*SupportTicket{},
	}, func() {
		err := validateSubscriptionRegistrationEmailAvailability("user@example.com")
		if !errors.Is(err, ErrEmailAlreadyExists) {
			t.Fatalf("expected email exists error, got %v", err)
		}

		_, err = RegisterUserBySubscriptionAndIssueToken("sub-pass", "user@example.com", "secret")
		if !errors.Is(err, ErrEmailAlreadyExists) {
			t.Fatalf("expected email exists error, got %v", err)
		}
	})
}
