package main

import (
	"fmt"
	"sort"
)

func cabinetOrderTypeLabel(orderType string) string {
	switch orderType {
	case "new":
		return "Покупка"
	case "renew":
		return "Продление"
	case "upgrade":
		return "Расширение"
	default:
		return "Заказ"
	}
}

func cabinetOrderStatusLabel(status string) string {
	switch status {
	case "paid":
		return "Оплачен"
	case "pending":
		return "Ожидает оплаты"
	case "cancelled":
		return "Отменён"
	default:
		return status
	}
}

func showCabinetHistory(token string, actor cabinetActor, messageID int, edit bool) {
	telegramID := actor.effectiveUserID()

	dbMutex.Lock()
	_, user, ok := cabinetFindUserByTelegramIDLocked(telegramID)
	if !ok || user == nil {
		dbMutex.Unlock()
		showCabinetLoginPrompt(token, actor, messageID, edit)
		return
	}

	changed := cabinetEnsureUserDefaults(user)
	if changed {
		saveDBLocked()
	}

	orders := make([]*Order, 0)
	for _, order := range db.Orders {
		if order == nil {
			continue
		}
		if order.Email == user.Email {
			orders = append(orders, order)
		}
	}
	dbMutex.Unlock()

	sort.Slice(orders, func(i, j int) bool {
		return orders[i].CreatedAt > orders[j].CreatedAt
	})

	text := "<b>📜 История</b>\n\n"
	if len(orders) == 0 {
		text += "История покупок и продлений пока пуста.\nПромокоды в истории ещё не отображаются отдельно."
	} else {
		limit := len(orders)
		if limit > 10 {
			limit = 10
		}

		for i := 0; i < limit; i++ {
			order := orders[i]
			text += fmt.Sprintf(
				"<b>%s</b>\n<b>Тип:</b> %s\n<b>Тариф:</b> %s\n<b>Устройств:</b> %d\n<b>Сумма:</b> %d RUB\n<b>Статус:</b> %s\n\n",
				cabinetFormatDate(order.CreatedAt),
				cabinetSafe(cabinetOrderTypeLabel(order.Type)),
				cabinetSafe(cabinetPlanLabel(order.Plan)),
				order.Devices,
				order.Price,
				cabinetSafe(cabinetOrderStatusLabel(order.Status)),
			)
		}

		if len(orders) > 10 {
			text += "Показаны последние 10 операций."
		}
	}

	cabinetRender(
		token,
		actor.ChatID,
		messageID,
		text,
		[][]map[string]interface{}{
			{cabinetButton("⬅️ Назад", "cabinet_open")},
		},
		edit,
	)
}
