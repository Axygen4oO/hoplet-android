package main

import (
	"strings"
	"testing"
	"time"
)

func TestAdminExtendAllUsersFilters(t *testing.T) {
	now := time.Now()
	activeExpiry := now.Add(24 * time.Hour).Unix()
	blockedExpiry := now.Add(48 * time.Hour).Unix()
	expiredExpiry := now.Add(-24 * time.Hour).Unix()

	withNotificationTestDB(t, &Database{
		Passwords: map[string]*PasswordEntry{
			"active-sub":  {ExpiresAt: activeExpiry},
			"blocked-sub": {ExpiresAt: blockedExpiry, IsDeactivated: true},
			"expired-sub": {ExpiresAt: expiredExpiry},
		},
		Devices: map[string]*ClientDevice{},
		Users: map[string]*UserAccount{
			"active@example.com": {
				Email:               "active@example.com",
				SubscriptionID:      "active-sub",
				SubscriptionStatus:  "active",
				SubscriptionExpires: activeExpiry,
			},
			"blocked@example.com": {
				Email:               "blocked@example.com",
				SubscriptionID:      "blocked-sub",
				SubscriptionStatus:  "blocked",
				SubscriptionExpires: blockedExpiry,
			},
			"expired@example.com": {
				Email:               "expired@example.com",
				SubscriptionID:      "expired-sub",
				SubscriptionStatus:  "expired",
				SubscriptionExpires: expiredExpiry,
			},
		},
		Orders:         map[string]*Order{},
		SupportTickets: map[string]*SupportTicket{},
	}, func(_ string) {
		updated, err := adminExtendAllUsers(AdminUsersExtendAllRequest{
			Days:           10,
			IncludeActive:  true,
			IncludeBlocked: false,
			IncludeExpired: true,
		})
		if err != nil {
			t.Fatalf("expected no error, got %v", err)
		}
		if updated != 2 {
			t.Fatalf("expected 2 updated users, got %d", updated)
		}

		if db.Users["active@example.com"].SubscriptionExpires <= activeExpiry {
			t.Fatalf("expected active subscription to be extended")
		}
		if db.Users["expired@example.com"].SubscriptionExpires <= now.Unix() {
			t.Fatalf("expected expired subscription to be extended from current time")
		}
		if db.Users["blocked@example.com"].SubscriptionExpires != blockedExpiry {
			t.Fatalf("expected blocked subscription to remain unchanged")
		}
	})
}

func TestTelegramBulkExtendFlow(t *testing.T) {
	now := time.Now()
	activeExpiry := now.Add(24 * time.Hour).Unix()
	blockedExpiry := now.Add(48 * time.Hour).Unix()

	withNotificationTestDB(t, &Database{
		Passwords: map[string]*PasswordEntry{
			"active-sub":  {ExpiresAt: activeExpiry},
			"blocked-sub": {ExpiresAt: blockedExpiry, IsDeactivated: true},
		},
		Devices: map[string]*ClientDevice{},
		Users: map[string]*UserAccount{
			"active@example.com": {
				Email:               "active@example.com",
				SubscriptionID:      "active-sub",
				SubscriptionStatus:  "active",
				SubscriptionExpires: activeExpiry,
			},
			"blocked@example.com": {
				Email:               "blocked@example.com",
				SubscriptionID:      "blocked-sub",
				SubscriptionStatus:  "blocked",
				SubscriptionExpires: blockedExpiry,
			},
		},
		Orders:         map[string]*Order{},
		SupportTickets: map[string]*SupportTicket{},
	}, func(_ string) {
		withTelegramRecorder(t, func(recorder *telegramRecorderTransport) {
			if !handleAdminBulkExtendCallback("token", 1, adminBulkExtendStartCallback, 55) {
				t.Fatalf("expected start callback to be handled")
			}
			if !strings.Contains(recorder.lastText(), "Массовое продление") {
				t.Fatalf("expected bulk extend wizard to be rendered")
			}

			if !handleAdminBulkExtendCallback("token", 1, adminBulkExtendContinueCallback, 55) {
				t.Fatalf("expected continue callback to be handled")
			}
			if !handleAdminBulkExtendInput("token", 1, "30") {
				t.Fatalf("expected days input to be handled")
			}
			if !strings.Contains(recorder.lastText(), "Продолжить?") {
				t.Fatalf("expected confirmation screen, got %q", recorder.lastText())
			}

			if !handleAdminBulkExtendCallback("token", 1, adminBulkExtendConfirmCallback, 55) {
				t.Fatalf("expected confirm callback to be handled")
			}
			if !strings.Contains(recorder.lastText(), "Продлено подписок: *1*") {
				t.Fatalf("expected success message, got %q", recorder.lastText())
			}

			if db.Users["active@example.com"].SubscriptionExpires <= activeExpiry {
				t.Fatalf("expected active subscription to be extended")
			}
			if db.Users["blocked@example.com"].SubscriptionExpires != blockedExpiry {
				t.Fatalf("expected blocked subscription to remain unchanged")
			}
		})
	})
}
