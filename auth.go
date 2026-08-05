package main

import (
	"errors"
	"log"
	"strings"
	"time"

	"golang.org/x/crypto/bcrypt"
)

var ErrInvalidUserCredentials = errors.New("invalid email or password")

type AuthResult struct {
	User           *UserAccount
	Token          string
	TokenExpiresAt int64
}

func normalizeUserEmail(email string) string {
	return strings.TrimSpace(strings.ToLower(email))
}

func createUserLocked(email, password string) (*UserAccount, error) {
	email = normalizeUserEmail(email)

	if email == "" {
		return nil, errors.New("email is empty")
	}

	if password == "" {
		return nil, errors.New("password is empty")
	}

	if _, ok := db.Users[email]; ok {
		return nil, errors.New("user already exists")
	}

	hash, err := bcrypt.GenerateFromPassword(
		[]byte(password),
		bcrypt.DefaultCost,
	)
	if err != nil {
		return nil, err
	}

	user := &UserAccount{
		Email:        email,
		PasswordHash: string(hash),

		CreatedAt: time.Now().Unix(),

		Role: "user",

		SubscriptionStatus:  "inactive",
		SubscriptionPlan:    "Неделя",
		SubscriptionExpires: 0,

		DeviceLimit: 5,
		Language:    "ru",
	}

	db.Users[email] = user

	return user, nil
}

func authResultForUser(user *UserAccount) (*AuthResult, error) {
	token, expiresAt, err := GenerateJWTWithExpiry(user)
	if err != nil {
		return nil, err
	}

	return &AuthResult{
		User:           user,
		Token:          token,
		TokenExpiresAt: expiresAt,
	}, nil
}

func CreateUser(email, password string) error {
	dbMutex.Lock()
	defer dbMutex.Unlock()

	if _, err := createUserLocked(email, password); err != nil {
		return err
	}

	saveDBLocked()

	return nil
}

func RegisterUserAndIssueToken(email, password string) (*AuthResult, error) {
	dbMutex.Lock()
	defer dbMutex.Unlock()

	user, err := createUserLocked(email, password)
	if err != nil {
		return nil, err
	}

	result, err := authResultForUser(user)
	if err != nil {
		return nil, err
	}

	saveDBLocked()

	return result, nil
}

func GetUser(email string) (*UserAccount, bool) {
	dbMutex.Lock()
	defer dbMutex.Unlock()

	key := normalizeUserEmail(email)

	user, ok := db.Users[key]

	if ok {
		if migrateUser(user) {
			saveDBLocked()
		}
	}

	return user, ok
}

func CheckPassword(email, password string) bool {

	user, ok := GetUser(email)

	if !ok {
		return false
	}

	return bcrypt.CompareHashAndPassword(
		[]byte(user.PasswordHash),
		[]byte(password),
	) == nil
}

func AuthenticateUser(email, password string) (*AuthResult, error) {
	dbMutex.Lock()
	defer dbMutex.Unlock()

	key := normalizeUserEmail(email)
	user, ok := db.Users[key]
	if !ok || user == nil {
		return nil, ErrInvalidUserCredentials
	}

	changed := migrateUser(user)

	if bcrypt.CompareHashAndPassword(
		[]byte(user.PasswordHash),
		[]byte(password),
	) != nil {
		if changed {
			saveDBLocked()
		}
		return nil, ErrInvalidUserCredentials
	}

	result, err := authResultForUser(user)
	if err != nil {
		return nil, err
	}

	if changed {
		saveDBLocked()
	}

	return result, nil
}

func IssueTokenForUser(email string) (*AuthResult, error) {
	dbMutex.Lock()
	defer dbMutex.Unlock()

	key := normalizeUserEmail(email)
	user, ok := db.Users[key]
	if !ok || user == nil {
		return nil, errors.New("user not found")
	}

	changed := migrateUser(user)
	result, err := authResultForUser(user)
	if err != nil {
		return nil, err
	}

	if changed {
		saveDBLocked()
	}

	return result, nil
}

func migrateUser(user *UserAccount) bool {
	changed := false

	if user.SubscriptionStatus == "" {
		log.Println("[MIGRATE] subscription_status")
		user.SubscriptionStatus = "inactive"
		changed = true
	}

	if user.SubscriptionPlan == "" {
		log.Println("[MIGRATE] subscription_plan")
		user.SubscriptionPlan = "Неделя"
		changed = true
	}

	if user.DeviceLimit == 0 {
		log.Println("[MIGRATE] device_limit")
		user.DeviceLimit = 5
		changed = true
	}

	if user.Language == "" {
		log.Println("[MIGRATE] language")
		user.Language = "ru"
		changed = true
	}

	if user.TelegramAuthExpiresAt < 0 {
		user.TelegramAuthExpiresAt = 0
		changed = true
	}

	if changed {
		log.Printf("[MIGRATE] changed=%v", changed)
	}

	return changed
}
