package main

import (
	"errors"
	"log"
	"math"
	"sort"
	"strings"
	"time"
)

const (
	orderPaymentMethodYooKassa = "yookassa"
	orderPaymentMethodTelegram = "telegram"
)

type orderServiceError struct {
	status  int
	message string
}

func (e *orderServiceError) Error() string {
	return e.message
}

func newOrderServiceError(status int, message string) error {
	return &orderServiceError{
		status:  status,
		message: message,
	}
}

func orderServiceStatus(err error, fallback int) int {
	var serviceErr *orderServiceError
	if errors.As(err, &serviceErr) {
		return serviceErr.status
	}

	return fallback
}

type OrderQuote struct {
	Action     string
	Plan       string
	Devices    int
	OldDevices int
	Price      int
}

type OrderCreateResult struct {
	Order      *Order
	PaymentURL string
	Existing   bool
}

type TelegramPaymentCheckRequest struct {
	OrderID     string
	Payload     string
	Currency    string
	TotalAmount int
}

type TelegramPaymentConfirmRequest struct {
	OrderID                 string `json:"orderId"`
	Payload                 string `json:"payload"`
	Currency                string `json:"currency"`
	TotalAmount             int    `json:"total_amount"`
	TelegramPaymentChargeID string `json:"telegram_payment_charge_id"`
	ProviderPaymentChargeID string `json:"provider_payment_charge_id"`
}

func normalizeOrderPlan(plan string) string {
	switch strings.ToLower(strings.TrimSpace(plan)) {
	case "week", "неделя":
		return "week"
	case "month", "месяц", "1 месяц":
		return "month"
	case "3months", "3 месяца":
		return "3months"
	default:
		return ""
	}
}

func normalizeOrderAction(action string) string {
	return strings.ToLower(strings.TrimSpace(action))
}

func normalizeOrderPaymentMethod(method string) string {
	switch strings.ToLower(strings.TrimSpace(method)) {
	case "", "yookassa", "web", "redirect":
		return orderPaymentMethodYooKassa
	case "telegram", "telegram_payments":
		return orderPaymentMethodTelegram
	default:
		return ""
	}
}

func calculateOrderPrice(plan string, devices int) int {
	if devices < 1 {
		return 0
	}

	switch normalizeOrderPlan(plan) {
	case "week":
		return devices * 40
	case "month":
		return devices * 100
	case "3months":
		return devices * 270
	default:
		return 0
	}
}

func currentOrderDeviceLimitLocked(user *UserAccount) int {
	currentDevices := 1

	if user != nil && user.DeviceLimit > 0 {
		currentDevices = user.DeviceLimit
	}

	if user != nil && user.SubscriptionID != "" {
		entry := db.Passwords[user.SubscriptionID]
		if entry != nil && entry.MaxDevices > currentDevices {
			currentDevices = entry.MaxDevices
		}
	}

	return currentDevices
}

func getOrderUserLocked(email string) (*UserAccount, error) {
	key := normalizeUserEmail(email)
	user, ok := db.Users[key]
	if !ok || user == nil {
		return nil, newOrderServiceError(404, "user not found")
	}

	if migrateUser(user) {
		saveDBLocked()
	}

	return user, nil
}

func calculateOrderQuoteLocked(user *UserAccount, req CreateOrderRequest, clampUpgradeMin bool) (*OrderQuote, error) {
	if user == nil {
		return nil, newOrderServiceError(404, "user not found")
	}

	req.Action = normalizeOrderAction(req.Action)
	req.Plan = normalizeOrderPlan(req.Plan)

	currentDevices := currentOrderDeviceLimitLocked(user)
	if req.Devices < 1 || req.Devices > 10 {
		return nil, newOrderServiceError(400, "invalid devices")
	}

	if user.SubscriptionExpires > time.Now().Unix() && req.Devices < currentDevices {
		return nil, newOrderServiceError(
			400,
			"cannot reduce device count during active subscription",
		)
	}

	switch req.Action {
	case "new", "renew", "upgrade":
	default:
		return nil, newOrderServiceError(400, "invalid action")
	}

	quote := &OrderQuote{
		Action:     req.Action,
		Plan:       req.Plan,
		Devices:    req.Devices,
		OldDevices: currentDevices,
	}

	switch req.Action {
	case "new", "renew":
		quote.Price = calculateOrderPrice(req.Plan, req.Devices)

	case "upgrade":
		if req.Devices <= currentDevices {
			return nil, newOrderServiceError(
				400,
				"new device limit must be greater than current",
			)
		}

		additionalDevices := req.Devices - currentDevices
		plan := normalizeOrderPlan(user.SubscriptionPlan)

		var tariffDays float64
		var pricePerDevice float64

		switch plan {
		case "week":
			tariffDays = 7
			pricePerDevice = 40
		case "month":
			tariffDays = 30
			pricePerDevice = 100
		case "3months":
			tariffDays = 90
			pricePerDevice = 270
		default:
			return nil, newOrderServiceError(400, "invalid plan")
		}

		remainingDays := math.Ceil(
			float64(user.SubscriptionExpires-time.Now().Unix()) / 86400,
		)
		if remainingDays <= 0 {
			return nil, newOrderServiceError(400, "subscription expired")
		}

		quote.Plan = plan
		quote.Price = int(math.Ceil(
			pricePerDevice *
				float64(additionalDevices) *
				remainingDays /
				tariffDays,
		))

		if clampUpgradeMin && quote.Price < 1 {
			quote.Price = 1
		}
	}

	if quote.Price == 0 {
		return nil, newOrderServiceError(400, "invalid plan")
	}

	return quote, nil
}

func CalculatePriceForUser(email string, req CreateOrderRequest) (*OrderQuote, error) {
	dbMutex.Lock()
	defer dbMutex.Unlock()

	user, err := getOrderUserLocked(email)
	if err != nil {
		return nil, err
	}

	return calculateOrderQuoteLocked(user, req, false)
}

func findPendingOrderLocked(email string, quote *OrderQuote, paymentMethod string) *Order {
	paymentMethod = normalizeOrderPaymentMethod(paymentMethod)

	for _, existing := range db.Orders {
		if existing == nil {
			continue
		}
		if existing.Email != email {
			continue
		}
		if existing.Status != "pending" {
			continue
		}
		if existing.Type != quote.Action {
			continue
		}
		if existing.Devices != quote.Devices {
			continue
		}
		if quote.Action != "upgrade" && existing.Plan != quote.Plan {
			continue
		}
		if normalizeOrderPaymentMethod(existing.PaymentMethod) != paymentMethod {
			continue
		}

		return existing
	}

	return nil
}

func createOrderRecordLocked(user *UserAccount, quote *OrderQuote, paymentMethod string) *Order {
	return &Order{
		ID:            newOrderID(),
		Email:         user.Email,
		Plan:          quote.Plan,
		Devices:       quote.Devices,
		OldDevices:    quote.OldDevices,
		Type:          quote.Action,
		Price:         quote.Price,
		Status:        "pending",
		PaymentMethod: paymentMethod,
		CreatedAt:     time.Now().Unix(),
	}
}

func createOrderForUserWithMethod(email string, req CreateOrderRequest, paymentMethod string) (*OrderCreateResult, error) {
	paymentMethod = normalizeOrderPaymentMethod(paymentMethod)
	if paymentMethod == "" {
		return nil, newOrderServiceError(400, "invalid payment method")
	}

	dbMutex.Lock()

	user, err := getOrderUserLocked(email)
	if err != nil {
		dbMutex.Unlock()
		return nil, err
	}

	quote, err := calculateOrderQuoteLocked(user, req, true)
	if err != nil {
		dbMutex.Unlock()
		return nil, err
	}

	if existing := findPendingOrderLocked(user.Email, quote, paymentMethod); existing != nil {
		paymentURL := existing.PaymentURL
		dbMutex.Unlock()

		return &OrderCreateResult{
			Order:      existing,
			PaymentURL: paymentURL,
			Existing:   true,
		}, nil
	}

	order := createOrderRecordLocked(user, quote, paymentMethod)

	db.Orders[order.ID] = order
	saveDBLocked()
	dbMutex.Unlock()

	result := &OrderCreateResult{
		Order:      order,
		PaymentURL: "",
		Existing:   false,
	}

	if paymentMethod == orderPaymentMethodTelegram {
		return result, nil
	}

	paymentURL, paymentID, err := createYooPayment(order)
	if err != nil {
		return result, err
	}

	order.PaymentID = paymentID
	order.PaymentURL = paymentURL

	dbMutex.Lock()
	db.Orders[order.ID] = order
	saveDBLocked()
	dbMutex.Unlock()

	result.PaymentURL = paymentURL
	return result, nil
}

func CreateOrderForUser(email string, req CreateOrderRequest) (*OrderCreateResult, error) {
	return createOrderForUserWithMethod(email, req, orderPaymentMethodYooKassa)
}

func CreateTelegramOrderForUser(email string, req CreateOrderRequest) (*OrderCreateResult, error) {
	return createOrderForUserWithMethod(email, req, orderPaymentMethodTelegram)
}

func RetryOrderForUser(email, orderID string) (*Order, string, error) {
	dbMutex.Lock()

	order, ok := db.Orders[orderID]
	if !ok || order == nil {
		dbMutex.Unlock()
		return nil, "", newOrderServiceError(404, "order not found")
	}

	if order.Email != normalizeUserEmail(email) {
		dbMutex.Unlock()
		return nil, "", newOrderServiceError(403, "access denied")
	}

	if order.Status != "pending" {
		dbMutex.Unlock()
		return nil, "", newOrderServiceError(400, "order is not pending")
	}

	if normalizeOrderPaymentMethod(order.PaymentMethod) == orderPaymentMethodTelegram {
		dbMutex.Unlock()
		return order, "", nil
	}

	if order.PaymentURL != "" {
		paymentURL := order.PaymentURL
		dbMutex.Unlock()
		return order, paymentURL, nil
	}

	dbMutex.Unlock()

	paymentURL, paymentID, err := createYooPayment(order)
	if err != nil {
		return order, "", err
	}

	dbMutex.Lock()
	order.PaymentID = paymentID
	order.PaymentURL = paymentURL
	order.Status = "pending"
	saveDBLocked()
	dbMutex.Unlock()

	return order, paymentURL, nil
}

func CancelOrderForUser(email, orderID string) error {
	dbMutex.Lock()

	order, ok := db.Orders[orderID]
	if !ok || order == nil {
		dbMutex.Unlock()
		return newOrderServiceError(404, "order not found")
	}

	if order.Email != normalizeUserEmail(email) {
		dbMutex.Unlock()
		return newOrderServiceError(403, "access denied")
	}

	if order.Status != "pending" {
		dbMutex.Unlock()
		return newOrderServiceError(400, "order is not pending")
	}

	paymentID := order.PaymentID
	dbMutex.Unlock()

	if normalizeOrderPaymentMethod(order.PaymentMethod) == orderPaymentMethodYooKassa &&
		paymentID != "" {
		if err := cancelYooPayment(paymentID); err != nil {
			log.Printf(
				"failed to cancel YooKassa payment %s: %v",
				paymentID,
				err,
			)
		}
	}

	dbMutex.Lock()
	order.Status = "cancelled"
	order.PaymentURL = ""
	order.PaymentID = ""
	saveDBLocked()
	dbMutex.Unlock()

	return nil
}

func GetOrderForUser(email, orderID string) (*Order, error) {
	email = normalizeUserEmail(email)
	orderID = strings.TrimSpace(orderID)

	dbMutex.Lock()
	defer dbMutex.Unlock()

	order, ok := db.Orders[orderID]
	if !ok || order == nil {
		return nil, newOrderServiceError(404, "order not found")
	}

	if order.Email != email {
		return nil, newOrderServiceError(403, "access denied")
	}

	return order, nil
}

func resolveTelegramOrderID(payload, orderID string) string {
	orderID = strings.TrimSpace(orderID)
	if orderID != "" {
		return orderID
	}

	return strings.TrimSpace(payload)
}

func validateTelegramPaymentLocked(email string, req TelegramPaymentCheckRequest, allowPaid bool) (*Order, error) {
	email = normalizeUserEmail(email)
	orderID := resolveTelegramOrderID(req.Payload, req.OrderID)
	if orderID == "" {
		return nil, newOrderServiceError(400, "missing order id")
	}

	order, ok := db.Orders[orderID]
	if !ok || order == nil {
		return nil, newOrderServiceError(404, "order not found")
	}

	if order.Email != email {
		return nil, newOrderServiceError(403, "access denied")
	}

	if normalizeOrderPaymentMethod(order.PaymentMethod) != orderPaymentMethodTelegram {
		return nil, newOrderServiceError(400, "order is not a telegram payment")
	}

	if order.Status == "cancelled" {
		return nil, newOrderServiceError(400, "order is cancelled")
	}
	if order.Status == "paid" && !allowPaid {
		return nil, newOrderServiceError(400, "order already paid")
	}
	if order.Status != "pending" && !(allowPaid && order.Status == "paid") {
		return nil, newOrderServiceError(400, "order is not pending")
	}

	currency := strings.ToUpper(strings.TrimSpace(req.Currency))
	if currency == "" || currency != "RUB" {
		return nil, newOrderServiceError(400, "invalid currency")
	}

	expectedAmount := order.Price * 100
	if req.TotalAmount != expectedAmount {
		return nil, newOrderServiceError(400, "invalid payment amount")
	}

	if payload := strings.TrimSpace(req.Payload); payload != "" && payload != order.ID {
		return nil, newOrderServiceError(400, "invalid payment payload")
	}

	return order, nil
}

func ValidateTelegramPaymentForUser(email string, req TelegramPaymentCheckRequest) (*Order, error) {
	dbMutex.Lock()
	defer dbMutex.Unlock()

	return validateTelegramPaymentLocked(email, req, false)
}

func ConfirmTelegramPaymentForUser(email string, req TelegramPaymentConfirmRequest) (*Order, *UserAccount, bool, error) {
	var createdPassword string

retry:
	dbMutex.Lock()

	order, err := validateTelegramPaymentLocked(email, TelegramPaymentCheckRequest{
		OrderID:     req.OrderID,
		Payload:     req.Payload,
		Currency:    req.Currency,
		TotalAmount: req.TotalAmount,
	}, true)
	if err != nil {
		dbMutex.Unlock()
		if createdPassword != "" {
			rollbackCreatedSubscription(createdPassword)
		}
		return nil, nil, false, err
	}

	user, alreadyPaid, err := finalizeOrderPaymentLocked(
		order,
		orderPaymentMethodTelegram,
		strings.TrimSpace(req.ProviderPaymentChargeID),
		strings.TrimSpace(req.TelegramPaymentChargeID),
		createdPassword,
	)
	if errors.Is(err, errOrderRequiresSubscriptionCreation) {
		orderEmail := order.Email
		orderPlan := order.Plan
		orderDevices := order.Devices
		dbMutex.Unlock()

		createdPassword, err = createSubscription(orderEmail, orderPlan, orderDevices)
		if err != nil {
			return order, nil, false, err
		}

		goto retry
	}

	createdPasswordLinked := createdPassword != "" &&
		user != nil &&
		user.SubscriptionID == createdPassword

	if err != nil {
		dbMutex.Unlock()
		if createdPassword != "" && !createdPasswordLinked {
			rollbackCreatedSubscription(createdPassword)
		}
		return order, nil, alreadyPaid, err
	}

	saveDBLocked()
	dbMutex.Unlock()

	if createdPassword != "" && !createdPasswordLinked {
		rollbackCreatedSubscription(createdPassword)
	}

	return order, user, alreadyPaid, nil
}

func ListOrdersForUser(email string) []*Order {
	key := normalizeUserEmail(email)

	dbMutex.Lock()
	defer dbMutex.Unlock()

	orders := make([]*Order, 0)
	for _, order := range db.Orders {
		if order == nil {
			continue
		}
		if order.Email == key {
			orders = append(orders, order)
		}
	}

	sort.Slice(orders, func(i, j int) bool {
		return orders[i].CreatedAt > orders[j].CreatedAt
	})

	return orders
}
