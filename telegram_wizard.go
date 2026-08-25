package main

import (
	"fmt"
	"log"
	"strconv"
	"strings"

	"golang.zx2c4.com/wireguard/device"
)

func handleTelegramInput(
	token string,
	adminID int64,
	cmd string,
	wgDev *device.Device,
) bool {
	if handleNotificationInput(token, adminID, cmd) {
		return true
	}

	if handleAdminBulkExtendInput(token, adminID, cmd) {
		return true
	}

	if tgState.WaitingUserExtendDays {
		days, err := strconv.Atoi(strings.TrimSpace(cmd))
		if err != nil || days <= 0 {
			sendTelegram(
				token,
				adminID,
				"❌ Введите положительное число дней.",
				nil,
			)
			return true
		}

		subscriptionID := tgState.TargetUserSubscriptionID
		resetAdminUserCardState()

		if err := adminExtendUserSubscriptionByID(subscriptionID, int64(days)); err != nil {
			sendTelegram(token, adminID, "❌ "+err.Error(), nil)
			return true
		}

		sendTelegram(
			token,
			adminID,
			fmt.Sprintf("✅ Подписка пользователя продлена на %d дней.", days),
			nil,
		)
		renderAdminUserCard(token, adminID, 0, false, subscriptionID)
		return true
	}

	if tgState.WaitingUserMessage {
		message := strings.TrimSpace(cmd)
		if message == "" {
			sendTelegram(
				token,
				adminID,
				"❌ Сообщение не может быть пустым.",
				nil,
			)
			return true
		}

		subscriptionID := tgState.TargetUserSubscriptionID
		resetAdminUserCardState()

		if err := adminSendUserMessageBySubscriptionID(token, subscriptionID, message); err != nil {
			sendTelegram(token, adminID, "❌ "+err.Error(), nil)
			return true
		}

		sendTelegram(token, adminID, "✅ Сообщение отправлено пользователю.", nil)
		renderAdminUserCard(token, adminID, 0, false, subscriptionID)
		return true
	}

	if tgState.WaitingForLabel {
		log.Printf("DEBUG: WaitingForLabel=true, cmd='%s'", cmd)
		tgState.WaitingForLabel = false

		tgState.TempLabel = strings.TrimSpace(cmd)

		if tgState.TempLabel == "" {
			sendTelegram(
				token,
				adminID,
				"❌ Имя подписки не может быть пустым.",
				nil,
			)
			return true
		}

		tgState.WaitingForDevices = true
		log.Printf("DEBUG: TempLabel='%s', WaitingForDevices=true", tgState.TempLabel)

		sendTelegram(
			token,
			adminID,
			"📱 Введите количество устройств.\n\nПример:\n`1`\nили\n`3`",
			nil,
		)

		return true
	}

	if tgState.WaitingForDevices {
		tgState.WaitingForDevices = false

		maxDevs, err := strconv.Atoi(strings.TrimSpace(cmd))
		if err != nil || maxDevs < 1 {
			sendTelegram(
				token,
				adminID,
				"❌ Введите количество устройств (1, 2, 3...).",
				nil,
			)
			return true
		}

		tgState.TempMaxDevs = maxDevs
		tgState.WaitingForPlan = true

		var keyboard [][]map[string]interface{}

		keyboard = append(keyboard, []map[string]interface{}{
			{"text": "📅 Неделя", "callback_data": "plan_week"},
		})

		keyboard = append(keyboard, []map[string]interface{}{
			{"text": "📅 Месяц", "callback_data": "plan_month"},
		})

		keyboard = append(keyboard, []map[string]interface{}{
			{"text": "📅 3 месяца", "callback_data": "plan_3months"},
		})

		sendTelegram(
			token,
			adminID,
			"💳 Выберите тариф:",
			map[string]interface{}{
				"inline_keyboard": keyboard,
			},
		)

		return true
	}

	if tgState.WaitingForPorts {

		// Если выбраны стандартные порты, используем их
		if tgState.TempPorts != "" {
			cmd = tgState.TempPorts
		}

		parts := strings.Split(cmd, ",")

		if len(parts) != 3 {
			sendTelegram(
				token,
				adminID,
				"❌ Укажите 3 порта через запятую.\nПример:\n`56000,56001,9000`",
				nil,
			)
			return true
		}

		p1 := strings.TrimSpace(parts[0])
		p2 := strings.TrimSpace(parts[1])
		p3 := strings.TrimSpace(parts[2])

		if _, err := strconv.Atoi(p1); err != nil {
			sendTelegram(token, adminID, "❌ Неверный первый порт.", nil)
			return true
		}

		if _, err := strconv.Atoi(p2); err != nil {
			sendTelegram(token, adminID, "❌ Неверный второй порт.", nil)
			return true
		}

		if _, err := strconv.Atoi(p3); err != nil {
			sendTelegram(token, adminID, "❌ Неверный третий порт.", nil)
			return true
		}

		// Если порты были введены вручную — сохраняем их
		if tgState.TempPorts == "" {
			tgState.TempPorts = cmd
		}

		tgState.WaitingForPorts = false

		if len(db.VKHashes) == 0 {
			sendTelegram(
				token,
				adminID,
				"❌ На сервере не настроены VK Hash.",
				nil,
			)
			return true
		}

		pass, err := createSubscription(
			tgState.TempLabel,
			tgState.TempPlan,
			tgState.TempMaxDevs,
		)

		if err != nil {
			sendTelegram(token, adminID, "❌ "+err.Error(), nil)
			return true
		}

		dbMutex.Lock()
		entry := db.Passwords[pass]
		dbMutex.Unlock()

		srvIP := getPublicIP()
		pts := strings.Split(entry.Ports, ",")

		link := fmt.Sprintf(
			"wdtt://%s:%s:%s:%s:%s:%s",
			srvIP,
			pts[0],
			pts[1],
			pts[2],
			pass,
			entry.VkHash,
		)

		sendTelegram(
			token,
			adminID,
			fmt.Sprintf(
				"✅ Подписка создана.\n\nПароль:\n`%s`\n\nСсылка:\n`%s`",
				pass,
				link,
			),
			nil,
		)

		// Сброс состояния мастера
		tgState.WaitingForLabel = false
		tgState.WaitingForDevices = false
		tgState.WaitingForPlan = false
		tgState.WaitingForPorts = false

		tgState.TempLabel = ""
		tgState.TempPlan = ""
		tgState.TempMaxDevs = 0
		tgState.TempPorts = ""

		return true
	}

	return false
}
