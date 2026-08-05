package main

import (
	"bytes"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
)

type YooCreatePaymentRequest struct {
	Amount struct {
		Value    string `json:"value"`
		Currency string `json:"currency"`
	} `json:"amount"`

	Capture bool `json:"capture"`

	Confirmation struct {
		Type      string `json:"type"`
		ReturnURL string `json:"return_url"`
	} `json:"confirmation"`

	Description string `json:"description"`
}

type YooCreatePaymentResponse struct {
	ID string `json:"id"`

	Status string `json:"status"`

	Confirmation struct {
		ConfirmationURL string `json:"confirmation_url"`
	} `json:"confirmation"`
}

func newIdempotenceKey() string {
	b := make([]byte, 16)

	_, _ = rand.Read(b)

	return hex.EncodeToString(b)
}

func cancelYooPayment(paymentID string) error {
	req, err := http.NewRequest(
		"POST",
		"https://api.yookassa.ru/v3/payments/"+paymentID+"/cancel",
		nil,
	)

	if err != nil {
		return err
	}

	req.Header.Set("Idempotence-Key", newIdempotenceKey())
	req.Header.Set("Content-Type", "application/json")

	req.SetBasicAuth(
		yookassaShopID,
		yookassaSecretKey,
	)

	client := &http.Client{}

	resp, err := client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK &&
		resp.StatusCode != http.StatusCreated {

		data, _ := io.ReadAll(resp.Body)
		return fmt.Errorf(string(data))
	}

	return nil
}

func createYooPayment(order *Order) (string, string, error) {

	reqBody := YooCreatePaymentRequest{}

	reqBody.Amount.Value = fmt.Sprintf("%.2f", float64(order.Price))
	reqBody.Amount.Currency = "RUB"

	reqBody.Capture = true

	reqBody.Confirmation.Type = "redirect"

	reqBody.Confirmation.ReturnURL =
		"https://hoplet.ru/payment/success"

	reqBody.Description =
		fmt.Sprintf("Hoplet %s", order.ID)

	body, _ := json.Marshal(reqBody)

	req, err := http.NewRequest(
		"POST",
		"https://api.yookassa.ru/v3/payments",
		bytes.NewBuffer(body),
	)

	if err != nil {
		return "", "", err
	}

	req.Header.Set(
		"Idempotence-Key",
		newIdempotenceKey(),
	)

	req.Header.Set(
		"Content-Type",
		"application/json",
	)

	req.SetBasicAuth(
		yookassaShopID,
		yookassaSecretKey,
	)

	client := &http.Client{}

	resp, err := client.Do(req)

	if err != nil {
		return "", "", err
	}

	defer resp.Body.Close()

	data, _ := io.ReadAll(resp.Body)

	if resp.StatusCode != http.StatusOK &&
		resp.StatusCode != http.StatusCreated {

		return "", "", fmt.Errorf(string(data))
	}

	var payment YooCreatePaymentResponse

	if err := json.Unmarshal(data, &payment); err != nil {
		return "", "", err
	}

	order.PaymentID = payment.ID

	return payment.Confirmation.ConfirmationURL, payment.ID, nil
}
