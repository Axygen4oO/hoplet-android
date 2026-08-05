package main

import (
	"errors"
	"time"
)

func CreateSiteSubscription(order *Order) (string, error) {

	return createSubscription(
		order.Email,
		order.Plan,
		order.Devices,
	)
}

func CreateOrExtendSiteSubscription(user *UserAccount, order *Order) (string, error) {

	if user.SubscriptionID == "" {
		return CreateSiteSubscription(order)
	}

	entry, ok := db.Passwords[user.SubscriptionID]
	if !ok || entry == nil {
		return CreateSiteSubscription(order)
	}

	if order.Type == "upgrade" {
		entry.MaxDevices = order.Devices
		return user.SubscriptionID, nil
	}

	base := time.Now()

	if entry.ExpiresAt > time.Now().Unix() {
		base = time.Unix(entry.ExpiresAt, 0)
	}

	switch order.Plan {

	case "week":
		base = base.AddDate(0, 0, 7)

	case "month":
		base = base.AddDate(0, 0, 30)

	case "3months":
		base = base.AddDate(0, 0, 90)

	default:
		return "", errors.New("unknown plan")
	}

	entry.ExpiresAt = base.Unix()
	switch order.Type {

	case "new":
		entry.MaxDevices = order.Devices

	case "renew":
		entry.MaxDevices = order.Devices

	case "upgrade":
		entry.MaxDevices = order.Devices

	default:
		entry.MaxDevices = order.Devices
	}

	return user.SubscriptionID, nil
}
