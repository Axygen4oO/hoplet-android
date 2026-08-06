package main

import (
	"errors"
	"strings"
)

var (
	ErrSubscriptionNotFound          = errors.New("subscription not found")
	ErrSubscriptionAlreadyRegistered = errors.New("subscription already registered")
	ErrEmailAlreadyExists            = errors.New("email already exists")
	ErrInvalidRegistrationEmail      = errors.New("invalid email")
	ErrInvalidRegistrationPassword   = errors.New("password is empty")
)

func validateSubscriptionRegistrationEmail(email string) (string, error) {
	normalized := normalizeUserEmail(email)
	if normalized == "" || !strings.Contains(normalized, "@") {
		return "", ErrInvalidRegistrationEmail
	}

	return normalized, nil
}

func validateSubscriptionRegistrationPasswordValue(password string) error {
	if strings.TrimSpace(password) == "" {
		return ErrInvalidRegistrationPassword
	}

	return nil
}

func subscriptionStatusForEntry(entry *PasswordEntry) string {
	if entry == nil {
		return "inactive"
	}
	if entry.IsDeactivated {
		return "blocked"
	}
	if isPasswordExpired(entry) {
		return "expired"
	}
	return "active"
}

func applyLinkedSubscriptionLocked(user *UserAccount, subscriptionID string, entry *PasswordEntry) {
	if user == nil || entry == nil {
		return
	}

	user.SubscriptionID = subscriptionID

	if entry.MaxDevices > 0 {
		user.DeviceLimit = entry.MaxDevices
	}

	user.SubscriptionExpires = entry.ExpiresAt
	user.SubscriptionStatus = subscriptionStatusForEntry(entry)
}

func validateSubscriptionRegistrationPassword(subscriptionPassword string) error {
	dbMutex.Lock()
	defer dbMutex.Unlock()

	subscriptionPassword = strings.TrimSpace(subscriptionPassword)
	entry, ok := db.Passwords[subscriptionPassword]
	if !ok || entry == nil {
		return ErrSubscriptionNotFound
	}

	if _, linked := findUserBySubscriptionID(subscriptionPassword); linked {
		return ErrSubscriptionAlreadyRegistered
	}

	return nil
}

func validateSubscriptionRegistrationEmailAvailability(email string) error {
	dbMutex.Lock()
	defer dbMutex.Unlock()

	normalized, err := validateSubscriptionRegistrationEmail(email)
	if err != nil {
		return err
	}

	if _, exists := db.Users[normalized]; exists {
		return ErrEmailAlreadyExists
	}

	return nil
}

func RegisterUserBySubscriptionAndIssueToken(subscriptionPassword, email, password string) (*AuthResult, error) {
	dbMutex.Lock()
	defer dbMutex.Unlock()

	subscriptionPassword = strings.TrimSpace(subscriptionPassword)
	normalizedEmail, err := validateSubscriptionRegistrationEmail(email)
	if err != nil {
		return nil, err
	}

	if err := validateSubscriptionRegistrationPasswordValue(password); err != nil {
		return nil, err
	}

	entry, ok := db.Passwords[subscriptionPassword]
	if !ok || entry == nil {
		return nil, ErrSubscriptionNotFound
	}

	if _, linked := findUserBySubscriptionID(subscriptionPassword); linked {
		return nil, ErrSubscriptionAlreadyRegistered
	}

	if _, exists := db.Users[normalizedEmail]; exists {
		return nil, ErrEmailAlreadyExists
	}

	user, err := createUserLocked(normalizedEmail, password)
	if err != nil {
		return nil, err
	}

	applyLinkedSubscriptionLocked(user, subscriptionPassword, entry)

	result, err := authResultForUser(user)
	if err != nil {
		return nil, err
	}

	saveDBLocked()

	return result, nil
}
