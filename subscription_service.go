package main

import (
	"errors"
	"log"
	"strings"
	"time"
)

func createSubscription(email, plan string, devices int) (string, error) {
	expires := time.Now()

	switch plan {

	case "week":
		expires = expires.AddDate(0, 0, 7)

	case "month":
		expires = expires.AddDate(0, 0, 30)

	case "3months":
		expires = expires.AddDate(0, 0, 90)

	default:
		return "", errors.New("unknown plan")
	}

	dbMutex.Lock()

	password := generatePassword()
	for {
		if _, ok := db.Passwords[password]; !ok {
			break
		}
		password = generatePassword()
	}

	entry := &PasswordEntry{
		Label:      email,
		MaxDevices: devices,
		ExpiresAt:  expires.Unix(),
		Ports:      "56000,56001,9000",
		VkHash:     strings.Join(db.VKHashes, ","),
	}

	log.Printf(
		"[SUBSCRIPTION] Create: email=%s vk_hashes=%q ports=%q",
		email,
		entry.VkHash,
		entry.Ports,
	)

	db.Passwords[password] = entry
	saveDBLocked()
	dbMutex.Unlock()

	if err := serverWrapKeys.AddPassword(password); err != nil {
		dbMutex.Lock()
		if current := db.Passwords[password]; current == entry {
			delete(db.Passwords, password)
			saveDBLocked()
		}
		dbMutex.Unlock()
		return "", err
	}

	return password, nil
}

func rollbackCreatedSubscription(password string) {
	if password == "" {
		return
	}

	dbMutex.Lock()
	delete(db.Passwords, password)
	saveDBLocked()
	dbMutex.Unlock()

	serverWrapKeys.RemovePassword(password)
}

func extendSubscription(user *UserAccount, days int64) error {

	if user.SubscriptionID == "" {
		return errors.New("subscription not linked")
	}

	entry, ok := db.Passwords[user.SubscriptionID]
	if !ok || entry == nil {
		return errors.New("subscription not found")
	}

	base := time.Now()

	if entry.ExpiresAt > base.Unix() {
		base = time.Unix(entry.ExpiresAt, 0)
	}

	entry.ExpiresAt = base.AddDate(0, 0, int(days)).Unix()

	user.SubscriptionExpires = entry.ExpiresAt
	user.SubscriptionStatus = "active"

	syncUserSubscription(user)

	saveDBLocked()

	return nil
}

func changeSubscriptionPlan(user *UserAccount, plan string) error {

	if user.SubscriptionID == "" {
		return errors.New("subscription not linked")
	}

	user.SubscriptionPlan = plan

	saveDBLocked()

	return nil
}

func changeSubscriptionDeviceLimit(user *UserAccount, limit int) error {

	if user.SubscriptionID == "" {
		return errors.New("subscription not linked")
	}

	entry, ok := db.Passwords[user.SubscriptionID]
	if !ok || entry == nil {
		return errors.New("subscription not found")
	}

	entry.MaxDevices = limit
	user.DeviceLimit = limit

	syncUserSubscription(user)

	saveDBLocked()

	return nil
}

func blockSubscription(user *UserAccount) error {

	if user.SubscriptionID == "" {
		return errors.New("subscription not linked")
	}

	entry := db.Passwords[user.SubscriptionID]
	if entry == nil {
		return errors.New("subscription not found")
	}

	entry.IsDeactivated = true
	user.SubscriptionStatus = "blocked"

	saveDBLocked()

	return nil
}

func unblockSubscription(user *UserAccount) error {

	if user.SubscriptionID == "" {
		return errors.New("subscription not linked")
	}

	entry := db.Passwords[user.SubscriptionID]
	if entry == nil {
		return errors.New("subscription not found")
	}

	entry.IsDeactivated = false
	user.SubscriptionStatus = "active"

	saveDBLocked()

	return nil
}

func resetSubscriptionDevices(user *UserAccount) ([]*ClientDevice, error) {
	if user.SubscriptionID == "" {
		return nil, errors.New("subscription not linked")
	}

	pass, ok := db.Passwords[user.SubscriptionID]
	if !ok || pass == nil {
		return nil, errors.New("subscription not found")
	}

	removed := unbindDevices(pass, "")
	purgeRemovedDeviceStatsLocked(removed)
	saveDBLocked()

	return normalizeRemovedDevices(removed), nil
}

func resetSubscriptionTraffic(user *UserAccount) error {

	if user.SubscriptionID == "" {
		return errors.New("subscription not linked")
	}

	pass, ok := db.Passwords[user.SubscriptionID]
	if !ok || pass == nil {
		return errors.New("subscription not found")
	}

	pass.DownBytes = 0
	pass.UpBytes = 0

	saveDBLocked()

	return nil
}

func resetSubscriptionVKHash(user *UserAccount) error {

	if user.SubscriptionID == "" {
		return errors.New("subscription not linked")
	}

	pass, ok := db.Passwords[user.SubscriptionID]
	if !ok || pass == nil {
		return errors.New("subscription not found")
	}

	pass.VkHash = ""

	saveDBLocked()

	return nil
}

func deleteUserAccount(user *UserAccount) ([]*ClientDevice, string, error) {
	if user == nil {
		return nil, "", errors.New("user not found")
	}

	removed := []*ClientDevice{}
	removedPassword := ""

	if pass, ok := db.Passwords[user.SubscriptionID]; ok && pass != nil {
		removed = unbindDevices(pass, "")
		purgeRemovedDeviceStatsLocked(removed)
		delete(db.Passwords, user.SubscriptionID)
		removedPassword = user.SubscriptionID
	}

	for id, order := range db.Orders {
		if order == nil {
			continue
		}

		if order.Email == user.Email {
			delete(db.Orders, id)
		}
	}

	delete(db.Users, user.Email)

	saveDBLocked()

	return normalizeRemovedDevices(removed), removedPassword, nil
}
