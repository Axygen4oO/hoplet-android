package main

import "fmt"

func showCabinetSubscription(token string, actor cabinetActor, messageID int, edit bool) {
	ctx, ok := loadCabinetPurchaseContextOrPrompt(token, actor, messageID, edit)
	if !ok {
		return
	}

	text := fmt.Sprintf(
		"<b>📦 Подписка</b>\n\n"+
			"<b>Статус:</b> %s\n"+
			"<b>Тариф:</b> %s\n"+
			"<b>Истекает:</b> %s\n"+
			"<b>Устройств:</b> %d\n"+
			"<b>Использовано:</b> %d\n"+
			"<b>Осталось времени:</b> %s",
		cabinetSafe(ctx.Status),
		cabinetSafe(ctx.PlanLabel),
		cabinetFormatDate(ctx.ExpiresAt),
		ctx.DeviceLimit,
		ctx.UsedDevices,
		cabinetFormatRemaining(ctx.ExpiresAt),
	)

	if !ctx.HasSubscription {
		text = "<b>📦 Подписка</b>\n\nАктивной подписки пока нет. Оформить её можно прямо в Telegram через единый checkout сервера."
	}

	keyboard := [][]map[string]interface{}{
		{cabinetButton("🛒 Купить подписку", "cabinet_purchase")},
	}

	if !ctx.HasSubscription {
		keyboard = append(keyboard, []map[string]interface{}{
			cabinetButton("➕ У меня есть подписка", "cabinet_subscription_link_existing"),
		})
	}

	keyboard = append(keyboard,
		[]map[string]interface{}{cabinetButton("🎁 Активировать промокод", "cabinet_subscription_promo")},
		[]map[string]interface{}{cabinetButton("⬅️ Назад", "cabinet_open")},
	)

	cabinetRender(
		token,
		actor.ChatID,
		messageID,
		text,
		keyboard,
		edit,
	)
}

func handleCabinetRenew(token string, actor cabinetActor, messageID int) {
	showCabinetRenewPlans(token, actor, messageID, true)
}

func startCabinetPromoFlow(token string, actor cabinetActor, messageID int) {
	cabinetSetState(actor.effectiveUserID(), CabinetState{
		Mode: "awaiting_promo",
	})

	cabinetRender(
		token,
		actor.ChatID,
		messageID,
		"<b>🎁 Промокод</b>\n\nОтправьте промокод следующим сообщением. Архитектура уже подготовлена, но сама активация пока ещё не подключена.",
		[][]map[string]interface{}{
			{cabinetButton("⬅️ Назад к подписке", "cabinet_subscription")},
		},
		true,
	)
}

func startCabinetLinkExistingSubscriptionFlow(token string, actor cabinetActor, messageID int) {
	ctx, ok := loadCabinetPurchaseContextOrPrompt(token, actor, messageID, true)
	if !ok {
		return
	}

	if ctx.HasSubscription {
		showCabinetSubscription(token, actor, messageID, true)
		return
	}

	cabinetSetState(actor.effectiveUserID(), CabinetState{
		Mode: "awaiting_subscription_link_password",
	})

	cabinetRender(
		token,
		actor.ChatID,
		messageID,
		"<b>📦 Привязка подписки</b>\n\nВведите пароль от вашей подписки.",
		[][]map[string]interface{}{
			{cabinetButton("⬅️ Назад к подписке", "cabinet_subscription")},
		},
		true,
	)
}
