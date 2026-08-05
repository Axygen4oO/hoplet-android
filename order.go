package main

import (
	"crypto/rand"
	"encoding/hex"
	"time"
)

type Order struct {
	ID    string `json:"id"`
	Email string `json:"email"`

	Plan    string `json:"plan"`
	Devices int    `json:"devices"`
	Price   int    `json:"price"`

	Type string `json:"type"` // new | renew | upgrade

	OldDevices int `json:"old_devices"`

	Status                  string `json:"status"`
	PaymentMethod           string `json:"payment_method,omitempty"`
	PaymentID               string `json:"payment_id"`
	TelegramPaymentChargeID string `json:"telegram_payment_charge_id,omitempty"`
	PaymentURL              string `json:"payment_url"`
	CreatedAt               int64  `json:"created_at"`
}

func newOrderID() string {
	b := make([]byte, 8)

	if _, err := rand.Read(b); err != nil {
		return hex.EncodeToString([]byte(time.Now().Format("150405")))
	}

	return "ORD-" + hex.EncodeToString(b)
}
