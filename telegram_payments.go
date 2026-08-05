package main

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
)

const defaultTelegramPaymentsProviderToken = "381764678:TEST:185872"

type telegramSuccessfulPaymentPayload struct {
	Currency                string `json:"currency"`
	TotalAmount             int    `json:"total_amount"`
	InvoicePayload          string `json:"invoice_payload"`
	TelegramPaymentChargeID string `json:"telegram_payment_charge_id"`
	ProviderPaymentChargeID string `json:"provider_payment_charge_id"`
}

type telegramPreCheckoutQueryPayload struct {
	ID             string               `json:"id"`
	From           *telegramUserPayload `json:"from"`
	Currency       string               `json:"currency"`
	TotalAmount    int                  `json:"total_amount"`
	InvoicePayload string               `json:"invoice_payload"`
}

type telegramAPIResponse struct {
	OK          bool   `json:"ok"`
	Description string `json:"description"`
}

func telegramPaymentsEnabled() bool {
	return strings.TrimSpace(telegramPaymentsProviderToken) != ""
}

func telegramInvoiceTitle(order *Order) string {
	if order == nil {
		return "WDTT"
	}

	switch order.Type {
	case "renew":
		return "WDTT: продление подписки"
	case "upgrade":
		return "WDTT: дополнительные устройства"
	default:
		return "WDTT: покупка подписки"
	}
}

func telegramInvoiceDescription(order *Order) string {
	if order == nil {
		return "Оплата подписки WDTT"
	}

	text := fmt.Sprintf(
		"%s, тариф %s, %d устройств",
		strings.ToLower(cabinetOrderTypeLabel(order.Type)),
		cabinetPlanLabel(order.Plan),
		order.Devices,
	)

	if order.Type == "upgrade" {
		text += fmt.Sprintf(", было %d", order.OldDevices)
	}

	return text
}

func telegramInvoicePrices(order *Order) []map[string]interface{} {
	label := fmt.Sprintf(
		"%s / %s / %d устройств",
		cabinetOrderTypeLabel(order.Type),
		cabinetPlanLabel(order.Plan),
		order.Devices,
	)

	return []map[string]interface{}{
		{
			"label":  label,
			"amount": order.Price * 100,
		},
	}
}

func sendTelegramInvoice(token string, chatID int64, order *Order) error {
	if order == nil {
		return fmt.Errorf("order not found")
	}

	if !telegramPaymentsEnabled() {
		return fmt.Errorf("telegram payments provider token is not configured")
	}

	payload := map[string]interface{}{
		"chat_id":         chatID,
		"title":           telegramInvoiceTitle(order),
		"description":     telegramInvoiceDescription(order),
		"payload":         order.ID,
		"provider_token":  telegramPaymentsProviderToken,
		"currency":        "RUB",
		"prices":          telegramInvoicePrices(order),
		"start_parameter": order.ID,
	}

	url := fmt.Sprintf("https://api.telegram.org/bot%s/sendInvoice", token)

	resp, err := postTelegramJSON(url, payload)
	if err != nil {
		return err
	}
	defer resp.Body.Close()

	data, _ := io.ReadAll(resp.Body)

	var apiResp telegramAPIResponse
	_ = json.Unmarshal(data, &apiResp)

	if resp.StatusCode != http.StatusOK {
		if strings.TrimSpace(apiResp.Description) != "" {
			return fmt.Errorf("telegram sendInvoice failed: %s", strings.TrimSpace(apiResp.Description))
		}
		return fmt.Errorf("telegram sendInvoice failed: %s", strings.TrimSpace(string(data)))
	}

	if !apiResp.OK {
		if strings.TrimSpace(apiResp.Description) != "" {
			return fmt.Errorf("telegram sendInvoice failed: %s", strings.TrimSpace(apiResp.Description))
		}
		return fmt.Errorf("telegram sendInvoice failed")
	}

	return nil
}

func answerPreCheckoutQuery(token, queryID string, ok bool, errorMessage string) {
	payload := map[string]interface{}{
		"pre_checkout_query_id": queryID,
		"ok":                    ok,
	}
	if !ok && strings.TrimSpace(errorMessage) != "" {
		payload["error_message"] = errorMessage
	}

	url := fmt.Sprintf("https://api.telegram.org/bot%s/answerPreCheckoutQuery", token)
	if err := postTelegramJSONAndClose(url, payload); err != nil {
		return
	}
}

func sendCabinetInvoiceForOrder(token string, actor cabinetActor, order *Order) error {
	if order == nil {
		return fmt.Errorf("order not found")
	}

	if normalizeOrderPaymentMethod(order.PaymentMethod) != orderPaymentMethodTelegram {
		return fmt.Errorf("order does not use telegram payments")
	}

	if order.Status != "pending" {
		return fmt.Errorf("order is not pending")
	}

	return sendTelegramInvoice(token, actor.ChatID, order)
}

func handleTelegramPreCheckout(
	token string,
	actor cabinetActor,
	query *telegramPreCheckoutQueryPayload,
) bool {
	if query == nil {
		return false
	}

	if !actor.isPrivateChat() {
		answerPreCheckoutQuery(
			token,
			query.ID,
			false,
			"Оплата доступна только в личном чате с ботом.",
		)
		return true
	}

	_, user, _, ok := cabinetEnsureAuthorizedUser(actor.effectiveUserID())
	if !ok || user == nil {
		answerPreCheckoutQuery(
			token,
			query.ID,
			false,
			"Сначала войдите в личный кабинет и создайте заказ заново.",
		)
		return true
	}

	_, err := ValidateTelegramPaymentForUser(user.Email, TelegramPaymentCheckRequest{
		Payload:     query.InvoicePayload,
		Currency:    query.Currency,
		TotalAmount: query.TotalAmount,
	})
	if err != nil {
		answerPreCheckoutQuery(
			token,
			query.ID,
			false,
			"Счёт больше недействителен. Откройте кабинет и сформируйте новый заказ.",
		)
		return true
	}

	answerPreCheckoutQuery(token, query.ID, true, "")
	return true
}

func handleTelegramSuccessfulPayment(
	token string,
	actor cabinetActor,
	payment *telegramSuccessfulPaymentPayload,
) bool {
	if payment == nil {
		return false
	}

	_, user, _, ok := cabinetEnsureAuthorizedUser(actor.effectiveUserID())
	if !ok || user == nil {
		sendCabinetTelegram(
			token,
			actor.ChatID,
			"<b>Оплата получена</b>\n\nСервер не смог связать платёж с вашим аккаунтом. Напишите в поддержку и укажите charge id из Telegram.",
			nil,
		)
		return true
	}

	order, confirmedUser, _, err := ConfirmTelegramPaymentForUser(
		user.Email,
		TelegramPaymentConfirmRequest{
			Payload:                 payment.InvoicePayload,
			Currency:                payment.Currency,
			TotalAmount:             payment.TotalAmount,
			TelegramPaymentChargeID: payment.TelegramPaymentChargeID,
			ProviderPaymentChargeID: payment.ProviderPaymentChargeID,
		},
	)
	if err != nil {
		sendCabinetTelegram(
			token,
			actor.ChatID,
			fmt.Sprintf(
				"<b>Оплата получена</b>\n\nПлатёж прошёл в Telegram, но сервер не смог подтвердить заказ: %s",
				cabinetSafe(strings.TrimSpace(err.Error())),
			),
			cabinetKeyboard(
				[]map[string]interface{}{
					cabinetButton("📦 Открыть подписку", "cabinet_subscription"),
				},
			),
		)
		return true
	}

	_, text, keyboard := buildOrderPaymentNotification(order, confirmedUser)
	if strings.TrimSpace(text) == "" {
		text = "<b>✅ Оплата подтверждена</b>\n\nПодписка уже обновлена на сервере."
	}

	sendCabinetTelegram(
		token,
		actor.ChatID,
		text,
		keyboard,
	)

	return true
}
