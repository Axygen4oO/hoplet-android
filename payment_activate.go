package main

import (
	"errors"
	"fmt"
	"log"
)

var errOrderRequiresSubscriptionCreation = errors.New("order requires subscription creation")

func createOrExtendOrderSubscriptionLocked(user *UserAccount, order *Order, createdPassword string) (string, error) {
	if user.SubscriptionID == "" {
		if createdPassword == "" {
			return "", errOrderRequiresSubscriptionCreation
		}
		return createdPassword, nil
	}

	if entry, ok := db.Passwords[user.SubscriptionID]; !ok || entry == nil {
		if createdPassword == "" {
			return "", errOrderRequiresSubscriptionCreation
		}
		return createdPassword, nil
	}

	return CreateOrExtendSiteSubscription(user, order)
}

func finalizeOrderPaymentLocked(order *Order, paymentMethod, paymentID, telegramChargeID, createdPassword string) (*UserAccount, bool, error) {
	if order == nil {
		return nil, false, errors.New("order not found")
	}

	user, ok := db.Users[order.Email]
	if !ok || user == nil {
		return nil, false, errors.New("user not found")
	}

	if order.Status == "paid" {
		return user, true, nil
	}

	if order.Status != "pending" {
		return nil, false, errors.New("order is not pending")
	}

	password, err := createOrExtendOrderSubscriptionLocked(user, order, createdPassword)
	if err != nil {
		return nil, false, err
	}

	user.SubscriptionStatus = "active"
	user.SubscriptionPlan = order.Plan
	user.DeviceLimit = order.Devices

	entry := db.Passwords[password]
	if entry != nil {
		user.SubscriptionExpires = entry.ExpiresAt
	}

	user.SubscriptionID = password

	order.Status = "paid"
	if paymentMethod != "" {
		order.PaymentMethod = paymentMethod
	}
	if paymentID != "" {
		order.PaymentID = paymentID
	}
	if telegramChargeID != "" {
		order.TelegramPaymentChargeID = telegramChargeID
	}
	order.PaymentURL = ""

	return user, false, nil
}

func buildOrderPaymentNotification(order *Order, user *UserAccount) (int64, string, interface{}) {
	if order == nil || user == nil || user.TelegramID == 0 || botTokenGlobal == "" {
		return 0, "", nil
	}

	text := fmt.Sprintf(
		"<b>✅ Оплата подтверждена</b>\n\n"+
			"<b>Заказ:</b> <code>%s</code>\n"+
			"<b>Тип:</b> %s\n"+
			"<b>Тариф:</b> %s\n"+
			"<b>Устройств:</b> %d\n"+
			"<b>Подписка активна до:</b> %s\n\n"+
			"Данные в личном кабинете уже обновлены.",
		cabinetSafe(order.ID),
		cabinetSafe(cabinetOrderTypeLabel(order.Type)),
		cabinetSafe(cabinetPlanLabel(order.Plan)),
		user.DeviceLimit,
		cabinetFormatDate(user.SubscriptionExpires),
	)

	keyboard := cabinetKeyboard(
		[]map[string]interface{}{
			cabinetButton("📦 Открыть подписку", "cabinet_subscription"),
		},
	)

	return user.TelegramID, text, keyboard
}

func ActivateOrder(paymentID string) {
	log.Println("[PAYMENT] ActivateOrder:", paymentID)

	var notifyChatID int64
	var notifyText string
	var notifyKeyboard interface{}
	var createdPassword string

retry:
	dbMutex.Lock()

	for _, order := range db.Orders {
		if order.PaymentID != paymentID {
			continue
		}
		user, alreadyPaid, err := finalizeOrderPaymentLocked(
			order,
			orderPaymentMethodYooKassa,
			paymentID,
			"",
			createdPassword,
		)
		if errors.Is(err, errOrderRequiresSubscriptionCreation) {
			email := order.Email
			plan := order.Plan
			devices := order.Devices
			dbMutex.Unlock()

			createdPassword, err = createSubscription(email, plan, devices)
			if err != nil {
				log.Println("[PAYMENT] create subscription:", err)
				return
			}

			goto retry
		}

		createdPasswordLinked := createdPassword != "" &&
			user != nil &&
			user.SubscriptionID == createdPassword

		if err != nil {
			saveDBLocked()
			dbMutex.Unlock()
			if createdPassword != "" && !createdPasswordLinked {
				rollbackCreatedSubscription(createdPassword)
			}
			return
		}

		if !alreadyPaid {
			notifyChatID, notifyText, notifyKeyboard =
				buildOrderPaymentNotification(order, user)
		}

		saveDBLocked()
		dbMutex.Unlock()
		if createdPassword != "" && !createdPasswordLinked {
			rollbackCreatedSubscription(createdPassword)
		}

		if notifyChatID != 0 && notifyText != "" {
			sendCabinetTelegram(
				botTokenGlobal,
				notifyChatID,
				notifyText,
				notifyKeyboard,
			)
		}

		return
	}

	dbMutex.Unlock()
	if createdPassword != "" {
		rollbackCreatedSubscription(createdPassword)
	}
}
