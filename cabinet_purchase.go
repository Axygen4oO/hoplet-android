package main

import (
	"fmt"
	"strconv"
	"strings"
)

type cabinetPurchaseContext struct {
	Email                 string
	Status                string
	PlanCode              string
	PlanLabel             string
	ExpiresAt             int64
	DeviceLimit           int
	UsedDevices           int
	HasSubscription       bool
	HasActiveSubscription bool
}

func loadCabinetPurchaseContext(telegramID int64) (cabinetPurchaseContext, bool) {
	dbMutex.Lock()
	defer dbMutex.Unlock()

	_, user, ok := cabinetFindUserByTelegramIDLocked(telegramID)
	if !ok || user == nil {
		return cabinetPurchaseContext{}, false
	}

	changed := cabinetEnsureUserDefaults(user)
	entry := db.Passwords[user.SubscriptionID]
	if changed {
		saveDBLocked()
	}

	expiresAt := user.SubscriptionExpires
	if expiresAt <= 0 && entry != nil {
		expiresAt = entry.ExpiresAt
	}

	deviceLimit := user.DeviceLimit
	if deviceLimit <= 0 && entry != nil && entry.MaxDevices > 0 {
		deviceLimit = entry.MaxDevices
	}
	if deviceLimit <= 0 {
		deviceLimit = 1
	}

	status := cabinetSubscriptionStatusLabel(user, entry)
	planCode := normalizeOrderPlan(user.SubscriptionPlan)

	return cabinetPurchaseContext{
		Email:                 user.Email,
		Status:                status,
		PlanCode:              planCode,
		PlanLabel:             cabinetPlanLabel(planCode),
		ExpiresAt:             expiresAt,
		DeviceLimit:           deviceLimit,
		UsedDevices:           cabinetBoundDeviceCount(entry),
		HasSubscription:       user.SubscriptionID != "" && entry != nil,
		HasActiveSubscription: status == "Активна",
	}, true
}

func loadCabinetPurchaseContextOrPrompt(token string, actor cabinetActor, messageID int, edit bool) (cabinetPurchaseContext, bool) {
	ctx, ok := loadCabinetPurchaseContext(actor.effectiveUserID())
	if !ok {
		showCabinetLoginPrompt(token, actor, messageID, edit)
		return cabinetPurchaseContext{}, false
	}

	return ctx, true
}

func cabinetPlanSelectionKeyboard(prefix, back string) [][]map[string]interface{} {
	return [][]map[string]interface{}{
		{cabinetButton("📅 Неделя", prefix+"week")},
		{cabinetButton("🗓 1 месяц", prefix+"month")},
		{cabinetButton("📦 3 месяца", prefix+"3months")},
		{cabinetButton("⬅️ Назад", back)},
	}
}

func cabinetDeviceSelectionKeyboard(prefix string, from, to int, back string) [][]map[string]interface{} {
	keyboard := make([][]map[string]interface{}, 0)
	row := make([]map[string]interface{}, 0, 2)

	for devices := from; devices <= to; devices++ {
		row = append(row, cabinetButton(
			fmt.Sprintf("%d устройств", devices),
			fmt.Sprintf("%s%d", prefix, devices),
		))
		if len(row) == 2 {
			keyboard = append(keyboard, row)
			row = make([]map[string]interface{}, 0, 2)
		}
	}

	if len(row) > 0 {
		keyboard = append(keyboard, row)
	}

	keyboard = append(keyboard, []map[string]interface{}{
		cabinetButton("⬅️ Назад", back),
	})

	return keyboard
}

func cabinetPaymentUnavailableText(title string) string {
	return fmt.Sprintf(
		"<b>%s</b>\n\nTelegram Payments сейчас не настроен на сервере. Попробуйте позже.",
		cabinetSafe(title),
	)
}

func cabinetOrderPaymentMethodLabel(method string) string {
	switch normalizeOrderPaymentMethod(method) {
	case orderPaymentMethodTelegram:
		return "Telegram Payments"
	default:
		return "YooKassa"
	}
}

func showCabinetPurchase(token string, actor cabinetActor, messageID int, edit bool) {
	ctx, ok := loadCabinetPurchaseContextOrPrompt(token, actor, messageID, edit)
	if !ok {
		return
	}

	text := "<b>🛒 Купить подписку</b>\n\n"
	keyboard := [][]map[string]interface{}{}

	if ctx.HasActiveSubscription {
		text += fmt.Sprintf(
			"У вас уже есть активная подписка.\n\n"+
				"<b>Тариф:</b> %s\n"+
				"<b>Устройств:</b> %d\n"+
				"<b>Использовано:</b> %d\n"+
				"<b>Истекает:</b> %s\n\n"+
				"Можно продлить подписку или докупить дополнительные устройства по существующей серверной логике.",
			cabinetSafe(ctx.PlanLabel),
			ctx.DeviceLimit,
			ctx.UsedDevices,
			cabinetFormatDate(ctx.ExpiresAt),
		)

		keyboard = append(keyboard,
			[]map[string]interface{}{cabinetButton("🔄 Продлить подписку", "cabinet_purchase_renew")},
			[]map[string]interface{}{cabinetButton("➕ Купить дополнительные устройства", "cabinet_purchase_upgrade")},
		)
	} else {
		text += fmt.Sprintf(
			"Активной подписки сейчас нет.\n\n"+
				"<b>Текущий статус:</b> %s\n\n"+
				"Выберите тариф. Стоимость и создание заказа будут рассчитаны на сервере тем же checkout-механизмом, что и на сайте.",
			cabinetSafe(ctx.Status),
		)

		keyboard = append(keyboard,
			[]map[string]interface{}{cabinetButton("📅 Неделя", "cabinet_purchase_new_plan_week")},
			[]map[string]interface{}{cabinetButton("🗓 1 месяц", "cabinet_purchase_new_plan_month")},
			[]map[string]interface{}{cabinetButton("📦 3 месяца", "cabinet_purchase_new_plan_3months")},
		)
	}

	if !telegramPaymentsEnabled() {
		text += "\n\n<i>Оплата временно недоступна: Telegram Payments provider token не настроен.</i>"
	}

	keyboard = append(keyboard, []map[string]interface{}{
		cabinetButton("⬅️ Назад", "cabinet_open"),
	})

	cabinetRender(token, actor.ChatID, messageID, text, keyboard, edit)
}

func showCabinetNewPurchaseDevices(token string, actor cabinetActor, messageID int, edit bool, plan string) {
	ctx, ok := loadCabinetPurchaseContextOrPrompt(token, actor, messageID, edit)
	if !ok {
		return
	}

	plan = normalizeOrderPlan(plan)
	if plan == "" {
		showCabinetPurchase(token, actor, messageID, edit)
		return
	}

	if ctx.HasActiveSubscription {
		showCabinetPurchase(token, actor, messageID, edit)
		return
	}

	cabinetRender(
		token,
		actor.ChatID,
		messageID,
		fmt.Sprintf(
			"<b>🛒 Новая подписка</b>\n\nВы выбрали тариф <b>%s</b>.\nТеперь укажите количество устройств.",
			cabinetSafe(cabinetPlanLabel(plan)),
		),
		cabinetDeviceSelectionKeyboard(
			"cabinet_purchase_new_devices_"+plan+"_",
			1,
			10,
			"cabinet_purchase",
		),
		edit,
	)
}

func showCabinetRenewPlans(token string, actor cabinetActor, messageID int, edit bool) {
	ctx, ok := loadCabinetPurchaseContextOrPrompt(token, actor, messageID, edit)
	if !ok {
		return
	}

	if !ctx.HasActiveSubscription {
		showCabinetPurchase(token, actor, messageID, edit)
		return
	}

	cabinetRender(
		token,
		actor.ChatID,
		messageID,
		fmt.Sprintf(
			"<b>🔄 Продлить подписку</b>\n\nТекущий лимит устройств: <b>%d</b>.\nВыберите тариф продления.",
			ctx.DeviceLimit,
		),
		cabinetPlanSelectionKeyboard("cabinet_purchase_renew_plan_", "cabinet_purchase"),
		edit,
	)
}

func showCabinetUpgradeDevices(token string, actor cabinetActor, messageID int, edit bool) {
	ctx, ok := loadCabinetPurchaseContextOrPrompt(token, actor, messageID, edit)
	if !ok {
		return
	}

	if !ctx.HasActiveSubscription {
		showCabinetPurchase(token, actor, messageID, edit)
		return
	}

	if ctx.PlanCode == "" {
		cabinetRender(
			token,
			actor.ChatID,
			messageID,
			"<b>➕ Дополнительные устройства</b>\n\nНе удалось определить текущий тариф. Откройте подписку позже или обратитесь в поддержку.",
			[][]map[string]interface{}{
				{cabinetButton("⬅️ Назад", "cabinet_purchase")},
			},
			edit,
		)
		return
	}

	if ctx.DeviceLimit >= 10 {
		cabinetRender(
			token,
			actor.ChatID,
			messageID,
			"<b>➕ Дополнительные устройства</b>\n\nУ вас уже подключён максимальный лимит в 10 устройств.",
			[][]map[string]interface{}{
				{cabinetButton("⬅️ Назад", "cabinet_purchase")},
			},
			edit,
		)
		return
	}

	cabinetRender(
		token,
		actor.ChatID,
		messageID,
		fmt.Sprintf(
			"<b>➕ Дополнительные устройства</b>\n\nТекущий лимит: <b>%d</b>.\nВыберите новый общий лимит устройств.",
			ctx.DeviceLimit,
		),
		cabinetDeviceSelectionKeyboard(
			"cabinet_purchase_upgrade_devices_",
			ctx.DeviceLimit+1,
			10,
			"cabinet_purchase",
		),
		edit,
	)
}

func showCabinetOrderQuote(token string, actor cabinetActor, messageID int, edit bool, action, plan string, devices int, back string) {
	ctx, ok := loadCabinetPurchaseContextOrPrompt(token, actor, messageID, edit)
	if !ok {
		return
	}

	if action == "new" && ctx.HasActiveSubscription {
		showCabinetPurchase(token, actor, messageID, edit)
		return
	}

	if action != "new" && !ctx.HasActiveSubscription {
		showCabinetPurchase(token, actor, messageID, edit)
		return
	}

	quote, err := CalculatePriceForUser(ctx.Email, CreateOrderRequest{
		Plan:    plan,
		Devices: devices,
		Action:  action,
	})
	if err != nil {
		cabinetRender(
			token,
			actor.ChatID,
			messageID,
			fmt.Sprintf(
				"<b>🧾 Расчёт заказа</b>\n\nНе удалось рассчитать стоимость: %s",
				cabinetSafe(strings.TrimSpace(err.Error())),
			),
			[][]map[string]interface{}{
				{cabinetButton("⬅️ Назад", back)},
			},
			edit,
		)
		return
	}

	title := "<b>🧾 Новый заказ</b>"
	description := "Проверьте параметры и создайте заказ."
	if action == "renew" {
		title = "<b>🔄 Продление подписки</b>"
		description = "Продление будет оформлено через существующий order API сайта."
	}
	if action == "upgrade" {
		title = "<b>➕ Дополнительные устройства</b>"
		description = "Стоимость рассчитана сервером по существующей формуле апгрейда."
	}

	text := fmt.Sprintf(
		"%s\n\n%s\n\n"+
			"<b>Тип:</b> %s\n"+
			"<b>Тариф:</b> %s\n"+
			"<b>Устройств:</b> %d\n"+
			"<b>Сумма:</b> %d RUB",
		title,
		description,
		cabinetSafe(cabinetOrderTypeLabel(quote.Action)),
		cabinetSafe(cabinetPlanLabel(quote.Plan)),
		quote.Devices,
		quote.Price,
	)

	if action == "upgrade" {
		text += fmt.Sprintf(
			"\n<b>Было устройств:</b> %d",
			quote.OldDevices,
		)
	}

	cabinetRender(
		token,
		actor.ChatID,
		messageID,
		text,
		[][]map[string]interface{}{
			{cabinetButton(
				"💳 Создать счёт в Telegram",
				fmt.Sprintf(
					"cabinet_purchase_confirm_%s_%s_%d",
					quote.Action,
					quote.Plan,
					quote.Devices,
				),
			)},
			{cabinetButton("⬅️ Назад", back)},
		},
		edit,
	)
}

func renderCabinetOrderResult(token string, actor cabinetActor, messageID int, edit bool, title, intro string, order *Order, paymentURL string) {
	if order == nil {
		cabinetRender(
			token,
			actor.ChatID,
			messageID,
			fmt.Sprintf(
				"<b>%s</b>\n\n%s",
				cabinetSafe(title),
				cabinetSafe(intro),
			),
			[][]map[string]interface{}{
				{cabinetButton("⬅️ Назад", "cabinet_purchase")},
			},
			edit,
		)
		return
	}

	text := fmt.Sprintf(
		"<b>%s</b>\n\n%s\n\n"+
			"<b>ID:</b> <code>%s</code>\n"+
			"<b>Тип:</b> %s\n"+
			"<b>Тариф:</b> %s\n"+
			"<b>Устройств:</b> %d\n"+
			"<b>Сумма:</b> %d RUB\n"+
			"<b>Оплата:</b> %s\n"+
			"<b>Статус:</b> %s",
		cabinetSafe(title),
		intro,
		cabinetSafe(order.ID),
		cabinetSafe(cabinetOrderTypeLabel(order.Type)),
		cabinetSafe(cabinetPlanLabel(order.Plan)),
		order.Devices,
		order.Price,
		cabinetSafe(cabinetOrderPaymentMethodLabel(order.PaymentMethod)),
		cabinetSafe(cabinetOrderStatusLabel(order.Status)),
	)

	if paymentURL != "" {
		text += fmt.Sprintf(
			"\n<b>Ссылка:</b> <a href=\"%s\">оплатить заказ</a>",
			cabinetSafe(paymentURL),
		)
	}

	keyboard := make([][]map[string]interface{}, 0)
	if paymentURL != "" {
		keyboard = append(keyboard, []map[string]interface{}{
			cabinetURLButton("💳 Открыть оплату", paymentURL),
		})
	}

	if order.Status == "pending" {
		if normalizeOrderPaymentMethod(order.PaymentMethod) == orderPaymentMethodTelegram {
			keyboard = append(keyboard, []map[string]interface{}{
				cabinetButton("💳 Отправить счёт в Telegram", "cabinet_order_pay_"+order.ID),
			})
		} else if paymentURL == "" {
			keyboard = append(keyboard, []map[string]interface{}{
				cabinetButton("🔁 Получить ссылку на оплату", "cabinet_order_retry_"+order.ID),
			})
		}

		keyboard = append(keyboard, []map[string]interface{}{
			cabinetButton("❌ Отменить заказ", "cabinet_order_cancel_"+order.ID),
		})
	}

	keyboard = append(keyboard, []map[string]interface{}{
		cabinetButton("⬅️ Назад", "cabinet_purchase"),
	})

	cabinetRender(token, actor.ChatID, messageID, text, keyboard, edit)
}

func createCabinetOrder(token string, actor cabinetActor, messageID int, action, plan string, devices int) {
	ctx, ok := loadCabinetPurchaseContextOrPrompt(token, actor, messageID, true)
	if !ok {
		return
	}

	if !telegramPaymentsEnabled() {
		cabinetRender(
			token,
			actor.ChatID,
			messageID,
			cabinetPaymentUnavailableText("🛒 Купить подписку"),
			[][]map[string]interface{}{
				{cabinetButton("⬅️ Назад", "cabinet_purchase")},
			},
			true,
		)
		return
	}

	result, err := CreateTelegramOrderForUser(ctx.Email, CreateOrderRequest{
		Plan:    plan,
		Devices: devices,
		Action:  action,
	})
	if err != nil {
		if result != nil && result.Order != nil {
			renderCabinetOrderResult(
				token,
				actor,
				messageID,
				true,
				"🧾 Заказ создан",
				fmt.Sprintf(
					"Заказ сохранён, но отправить счёт в Telegram не удалось.\n\n<b>Ошибка:</b> %s",
					cabinetSafe(strings.TrimSpace(err.Error())),
				),
				result.Order,
				result.PaymentURL,
			)
			return
		}

		cabinetRender(
			token,
			actor.ChatID,
			messageID,
			fmt.Sprintf(
				"<b>🛒 Купить подписку</b>\n\nНе удалось создать заказ: %s",
				cabinetSafe(strings.TrimSpace(err.Error())),
			),
			[][]map[string]interface{}{
				{cabinetButton("⬅️ Назад", "cabinet_purchase")},
			},
			true,
		)
		return
	}

	title := "🧾 Заказ создан"
	intro := "Заказ создан в общей серверной системе."
	if result.Existing {
		title = "🧾 Ожидающий заказ найден"
		intro = "У вас уже есть ожидающий заказ с такими параметрами."
	}

	if err := sendCabinetInvoiceForOrder(token, actor, result.Order); err != nil {
		renderCabinetOrderResult(
			token,
			actor,
			messageID,
			true,
			title,
			fmt.Sprintf(
				"%s\n\nСчёт пока не отправлен: %s",
				intro,
				cabinetSafe(strings.TrimSpace(err.Error())),
			),
			result.Order,
			result.PaymentURL,
		)
		return
	}

	renderCabinetOrderResult(
		token,
		actor,
		messageID,
		true,
		title,
		intro+"\n\nСчёт отправлен отдельным сообщением в этот чат. После успешной оплаты сервер сам подтвердит заказ и активирует подписку.",
		result.Order,
		result.PaymentURL,
	)
}

func sendCabinetOrderInvoice(token string, actor cabinetActor, messageID int, orderID string) {
	ctx, ok := loadCabinetPurchaseContextOrPrompt(token, actor, messageID, true)
	if !ok {
		return
	}

	if !telegramPaymentsEnabled() {
		cabinetRender(
			token,
			actor.ChatID,
			messageID,
			cabinetPaymentUnavailableText("🧾 Заказ"),
			[][]map[string]interface{}{
				{cabinetButton("⬅️ Назад", "cabinet_purchase")},
			},
			true,
		)
		return
	}

	order, err := GetOrderForUser(ctx.Email, orderID)
	if err != nil {
		cabinetRender(
			token,
			actor.ChatID,
			messageID,
			fmt.Sprintf(
				"<b>🧾 Заказ</b>\n\nНе удалось открыть заказ: %s",
				cabinetSafe(strings.TrimSpace(err.Error())),
			),
			[][]map[string]interface{}{
				{cabinetButton("⬅️ Назад", "cabinet_purchase")},
			},
			true,
		)
		return
	}

	if err := sendCabinetInvoiceForOrder(token, actor, order); err != nil {
		renderCabinetOrderResult(
			token,
			actor,
			messageID,
			true,
			"🧾 Заказ",
			fmt.Sprintf(
				"Не удалось отправить счёт: %s",
				cabinetSafe(strings.TrimSpace(err.Error())),
			),
			order,
			order.PaymentURL,
		)
		return
	}

	renderCabinetOrderResult(
		token,
		actor,
		messageID,
		true,
		"🧾 Заказ",
		"Счёт отправлен отдельным сообщением в этот чат. После успешной оплаты сервер сам подтвердит заказ.",
		order,
		order.PaymentURL,
	)
}

func retryCabinetOrder(token string, actor cabinetActor, messageID int, orderID string) {
	ctx, ok := loadCabinetPurchaseContextOrPrompt(token, actor, messageID, true)
	if !ok {
		return
	}

	order, err := GetOrderForUser(ctx.Email, orderID)
	if err == nil &&
		order != nil &&
		normalizeOrderPaymentMethod(order.PaymentMethod) == orderPaymentMethodTelegram {
		sendCabinetOrderInvoice(token, actor, messageID, orderID)
		return
	}

	if yookassaShopID == "" || yookassaSecretKey == "" {
		cabinetRender(
			token,
			actor.ChatID,
			messageID,
			"<b>🧾 Заказ</b>\n\nДля этого заказа недоступно повторное получение ссылки: YooKassa не настроена на сервере.",
			[][]map[string]interface{}{
				{cabinetButton("⬅️ Назад", "cabinet_purchase")},
			},
			true,
		)
		return
	}

	order, paymentURL, err := RetryOrderForUser(ctx.Email, orderID)
	if err != nil {
		if order != nil {
			renderCabinetOrderResult(
				token,
				actor,
				messageID,
				true,
				"🧾 Заказ",
				fmt.Sprintf(
					"Не удалось получить ссылку на оплату.\n\n<b>Ошибка:</b> %s",
					cabinetSafe(strings.TrimSpace(err.Error())),
				),
				order,
				paymentURL,
			)
			return
		}

		cabinetRender(
			token,
			actor.ChatID,
			messageID,
			fmt.Sprintf(
				"<b>🧾 Заказ</b>\n\nНе удалось обновить заказ: %s",
				cabinetSafe(strings.TrimSpace(err.Error())),
			),
			[][]map[string]interface{}{
				{cabinetButton("⬅️ Назад", "cabinet_purchase")},
			},
			true,
		)
		return
	}

	renderCabinetOrderResult(
		token,
		actor,
		messageID,
		true,
		"🧾 Заказ",
		"Ссылка на оплату обновлена через существующий order API сайта.",
		order,
		paymentURL,
	)
}

func cancelCabinetOrder(token string, actor cabinetActor, messageID int, orderID string) {
	ctx, ok := loadCabinetPurchaseContextOrPrompt(token, actor, messageID, true)
	if !ok {
		return
	}

	if err := CancelOrderForUser(ctx.Email, orderID); err != nil {
		cabinetRender(
			token,
			actor.ChatID,
			messageID,
			fmt.Sprintf(
				"<b>🧾 Заказ</b>\n\nНе удалось отменить заказ: %s",
				cabinetSafe(strings.TrimSpace(err.Error())),
			),
			[][]map[string]interface{}{
				{cabinetButton("⬅️ Назад", "cabinet_purchase")},
			},
			true,
		)
		return
	}

	cabinetRender(
		token,
		actor.ChatID,
		messageID,
		fmt.Sprintf(
			"<b>🧾 Заказ</b>\n\nЗаказ <code>%s</code> отменён.",
			cabinetSafe(orderID),
		),
		[][]map[string]interface{}{
			{cabinetButton("⬅️ Назад", "cabinet_purchase")},
		},
		true,
	)
}

func handleCabinetPurchaseCallback(token string, actor cabinetActor, messageID int, data string) bool {
	switch {
	case data == "cabinet_purchase":
		showCabinetPurchase(token, actor, messageID, true)
		return true

	case data == "cabinet_subscription_renew" || data == "cabinet_purchase_renew":
		showCabinetRenewPlans(token, actor, messageID, true)
		return true

	case data == "cabinet_purchase_upgrade":
		showCabinetUpgradeDevices(token, actor, messageID, true)
		return true

	case strings.HasPrefix(data, "cabinet_purchase_new_plan_"):
		plan := strings.TrimPrefix(data, "cabinet_purchase_new_plan_")
		showCabinetNewPurchaseDevices(token, actor, messageID, true, plan)
		return true

	case strings.HasPrefix(data, "cabinet_purchase_new_devices_"):
		suffix := strings.TrimPrefix(data, "cabinet_purchase_new_devices_")
		parts := strings.Split(suffix, "_")
		if len(parts) != 2 {
			return false
		}

		devices, err := strconv.Atoi(parts[1])
		if err != nil {
			return false
		}

		showCabinetOrderQuote(
			token,
			actor,
			messageID,
			true,
			"new",
			parts[0],
			devices,
			"cabinet_purchase_new_plan_"+parts[0],
		)
		return true

	case strings.HasPrefix(data, "cabinet_purchase_renew_plan_"):
		ctx, ok := loadCabinetPurchaseContextOrPrompt(token, actor, messageID, true)
		if !ok {
			return true
		}

		plan := strings.TrimPrefix(data, "cabinet_purchase_renew_plan_")
		showCabinetOrderQuote(
			token,
			actor,
			messageID,
			true,
			"renew",
			plan,
			ctx.DeviceLimit,
			"cabinet_purchase_renew",
		)
		return true

	case strings.HasPrefix(data, "cabinet_purchase_upgrade_devices_"):
		ctx, ok := loadCabinetPurchaseContextOrPrompt(token, actor, messageID, true)
		if !ok {
			return true
		}

		devices, err := strconv.Atoi(strings.TrimPrefix(data, "cabinet_purchase_upgrade_devices_"))
		if err != nil {
			return false
		}

		showCabinetOrderQuote(
			token,
			actor,
			messageID,
			true,
			"upgrade",
			ctx.PlanCode,
			devices,
			"cabinet_purchase_upgrade",
		)
		return true

	case strings.HasPrefix(data, "cabinet_purchase_confirm_"):
		suffix := strings.TrimPrefix(data, "cabinet_purchase_confirm_")
		parts := strings.Split(suffix, "_")
		if len(parts) != 3 {
			return false
		}

		devices, err := strconv.Atoi(parts[2])
		if err != nil {
			return false
		}

		createCabinetOrder(token, actor, messageID, parts[0], parts[1], devices)
		return true

	case strings.HasPrefix(data, "cabinet_order_retry_"):
		orderID := strings.TrimPrefix(data, "cabinet_order_retry_")
		if strings.TrimSpace(orderID) == "" {
			return false
		}

		retryCabinetOrder(token, actor, messageID, orderID)
		return true

	case strings.HasPrefix(data, "cabinet_order_pay_"):
		orderID := strings.TrimPrefix(data, "cabinet_order_pay_")
		if strings.TrimSpace(orderID) == "" {
			return false
		}

		sendCabinetOrderInvoice(token, actor, messageID, orderID)
		return true

	case strings.HasPrefix(data, "cabinet_order_cancel_"):
		orderID := strings.TrimPrefix(data, "cabinet_order_cancel_")
		if strings.TrimSpace(orderID) == "" {
			return false
		}

		cancelCabinetOrder(token, actor, messageID, orderID)
		return true
	}

	return false
}
