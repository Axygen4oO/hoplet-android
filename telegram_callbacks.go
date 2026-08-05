package main

import (
	"fmt"
	"os/exec"
	"strings"
	"time"

	"golang.zx2c4.com/wireguard/device"
)

func handleCallback(
	token string,
	adminID int64,
	data string,
	messageID int,
	wgDev *device.Device,
) bool {

	switch data {

	case "panel_main":
		showMainPanel(
			token,
			adminID,
			messageID,
			true,
		)
		return true

	case "panel_subs":
		showSubscriptionsPanel(
			token,
			adminID,
			messageID,
			true,
		)
		return true
	case "subs_list":
		sendPasswordList(
			token,
			adminID,
			messageID,
			true,
			wgDev,
		)
		return true
	case "subs_new":
		tgState.WaitingForLabel = true

		sendTelegram(
			token,
			adminID,
			"👤 Введите имя подписки:",
			nil,
		)

		return true

	case "plan_week":
		tgState.WaitingForPlan = false
		tgState.TempPlan = "week"

		var keyboard [][]map[string]interface{}
		keyboard = append(keyboard, []map[string]interface{}{
			{"text": "Да", "callback_data": "ports_def"},
			{"text": "Нет", "callback_data": "ports_custom"},
		})

		sendTelegram(
			token,
			adminID,
			"⚙️ Использовать стандартные порты (56000, 56001, 9000)?",
			map[string]interface{}{"inline_keyboard": keyboard},
		)

		return true

	case "plan_month":
		tgState.WaitingForPlan = false
		tgState.TempPlan = "month"

		var keyboard [][]map[string]interface{}
		keyboard = append(keyboard, []map[string]interface{}{
			{"text": "Да", "callback_data": "ports_def"},
			{"text": "Нет", "callback_data": "ports_custom"},
		})

		sendTelegram(
			token,
			adminID,
			"⚙️ Использовать стандартные порты (56000, 56001, 9000)?",
			map[string]interface{}{"inline_keyboard": keyboard},
		)

		return true

	case "plan_3months":
		tgState.WaitingForPlan = false
		tgState.TempPlan = "3months"

		var keyboard [][]map[string]interface{}
		keyboard = append(keyboard, []map[string]interface{}{
			{"text": "Да", "callback_data": "ports_def"},
			{"text": "Нет", "callback_data": "ports_custom"},
		})

		sendTelegram(
			token,
			adminID,
			"⚙️ Использовать стандартные порты (56000, 56001, 9000)?",
			map[string]interface{}{"inline_keyboard": keyboard},
		)

		return true

	case "panel_devices":
		dbMutex.Lock()

		if len(db.Devices) == 0 {
			dbMutex.Unlock()

			editTelegram(
				token,
				adminID,
				messageID,
				"📱 Устройства отсутствуют.",
				map[string]interface{}{
					"inline_keyboard": [][]map[string]interface{}{
						{
							{
								"text":          "◀️ Назад",
								"callback_data": "panel_main",
							},
						},
					},
				},
			)

			return true
		}

		msg := "📱 *Список устройств*\n\n"

		for id, dev := range db.Devices {
			shortID := id
			if len(shortID) > 12 {
				shortID = shortID[:12]
			}

			owner := "Неизвестно"

			for pass, entry := range db.Passwords {
				if entry == nil {
					continue
				}

				if entry.DeviceID == id {
					if entry.Label != "" {
						owner = entry.Label
					} else {
						owner = pass
					}
					break
				}

				for _, devID := range entry.DeviceIDs {
					if devID == id {
						if entry.Label != "" {
							owner = entry.Label
						} else {
							owner = pass
						}
						break
					}
				}
			}

			msg += fmt.Sprintf(
				"🖥 `%s`\n"+
					"👤 `%s`\n"+
					"🌐 IP: `%s`\n"+
					"⬆️ %.2f МБ\n"+
					"⬇️ %.2f МБ\n\n",
				shortID,
				owner,
				dev.IP,
				float64(dev.UpBytes)/1024/1024,
				float64(dev.DownBytes)/1024/1024,
			)
		}

		keyboard := [][]map[string]interface{}{}

		for id := range db.Devices {
			owner := "Неизвестно"

			for pass, entry := range db.Passwords {
				if entry == nil {
					continue
				}

				if entry.DeviceID == id {
					if entry.Label != "" {
						owner = entry.Label
					} else {
						owner = pass
					}
					break
				}

				for _, devID := range entry.DeviceIDs {
					if devID == id {
						if entry.Label != "" {
							owner = entry.Label
						} else {
							owner = pass
						}
						break
					}
				}
			}

			keyboard = append(
				keyboard,
				[]map[string]interface{}{
					{
						"text":          fmt.Sprintf("❌ %s", owner),
						"callback_data": "deldev_" + id,
					},
				},
			)
		}

		keyboard = append(
			keyboard,
			[]map[string]interface{}{
				{
					"text":          "◀️ Назад",
					"callback_data": "panel_main",
				},
			},
		)

		dbMutex.Unlock()

		editTelegram(
			token,
			adminID,
			messageID,
			msg,
			map[string]interface{}{
				"inline_keyboard": keyboard,
			},
		)

		return true

	case "panel_stats":
		dbMutex.Lock()

		totalSubs := len(db.Passwords)
		totalDevices := len(db.Devices)

		activeSubs := 0
		disabledSubs := 0

		var up int64
		var down int64

		for _, entry := range db.Passwords {
			if entry == nil {
				continue
			}

			if entry.IsDeactivated {
				disabledSubs++
			} else {
				activeSubs++
			}

			up += entry.UpBytes
			down += entry.DownBytes
		}

		dbMutex.Unlock()

		msg := fmt.Sprintf(
			"📊 *Статистика сервера*\n\n"+
				"👥 Подписок всего: *%d*\n"+
				"🟢 Активных: *%d*\n"+
				"🔴 Отключенных: *%d*\n\n"+
				"📱 Устройств: *%d*\n\n"+
				"⬆️ Отдано: *%.2f МБ*\n"+
				"⬇️ Получено: *%.2f МБ*",
			totalSubs,
			activeSubs,
			disabledSubs,
			totalDevices,
			float64(up)/1024/1024,
			float64(down)/1024/1024,
		)

		keyboard := [][]map[string]interface{}{
			{{
				"text":          "◀️ Назад",
				"callback_data": "panel_main",
			}},
		}

		editTelegram(
			token,
			adminID,
			messageID,
			msg,
			map[string]interface{}{
				"inline_keyboard": keyboard,
			},
		)

		return true

	case "panel_server":
		dbMutex.Lock()

		totalSubs := len(db.Passwords)
		totalDevices := len(db.Devices)

		activeSubs := 0
		disabledSubs := 0

		for _, entry := range db.Passwords {
			if entry == nil {
				continue
			}

			if entry.IsDeactivated {
				disabledSubs++
			} else {
				activeSubs++
			}
		}

		dbMutex.Unlock()

		msg := fmt.Sprintf(
			"⚙️ *Сервер*\n\n"+
				"🟢 Активных подписок: *%d*\n"+
				"🔴 Отключенных подписок: *%d*\n"+
				"📦 Всего подписок: *%d*\n"+
				"📱 Зарегистрировано устройств: *%d*",
			activeSubs,
			disabledSubs,
			totalSubs,
			totalDevices,
		)

		keyboard := [][]map[string]interface{}{
			{
				{
					"text":          "🔄 Перезапустить WDTT",
					"callback_data": "server_restart",
				},
			},
			{
				{
					"text":          "📊 Ресурсы VPS",
					"callback_data": "server_resources",
				},
			},
			{
				{
					"text":          "📜 Последние логи",
					"callback_data": "server_logs",
				},
			},
			{
				{
					"text":          "◀️ Назад",
					"callback_data": "panel_main",
				},
			},
		}

		editTelegram(
			token,
			adminID,
			messageID,
			msg,
			map[string]interface{}{
				"inline_keyboard": keyboard,
			},
		)

		return true

	case "server_restart":
		sendTelegram(
			token,
			adminID,
			"♻️ Перезапускаю WDTT...",
			nil,
		)

		go func() {
			time.Sleep(2 * time.Second)
			exec.Command(
				"systemctl",
				"restart",
				"wdtt",
			).Run()
		}()

		return true
	case "server_logs":
		out, err := exec.Command(
			"journalctl",
			"-u",
			"wdtt",
			"-n",
			"20",
			"--no-pager",
		).CombinedOutput()

		if err != nil {
			sendTelegram(
				token,
				adminID,
				fmt.Sprintf(
					"❌ Не удалось получить логи:\n`%v`",
					err,
				),
				nil,
			)
			return true
		}

		sendTelegram(
			token,
			adminID,
			"```"+string(out)+"```",
			nil,
		)

		return true
	case "server_resources":
		uptime, _ := exec.Command(
			"bash",
			"-c",
			"uptime -p",
		).Output()

		load, _ := exec.Command(
			"bash",
			"-c",
			"uptime | awk -F'load average:' '{print $2}'",
		).Output()

		cpu, _ := exec.Command(
			"bash",
			"-c",
			"nproc",
		).Output()

		ram, _ := exec.Command(
			"bash",
			"-c",
			"free -m | awk '/Mem:/ {print $3\" MB / \"$2\" MB\"}'",
		).Output()

		disk, _ := exec.Command(
			"bash",
			"-c",
			"df -h / | awk 'NR==2 {print $3\" / \"$2\" (\"$5\")\"}'",
		).Output()

		msg := fmt.Sprintf(
			"📊 *Ресурсы VPS*\n\n"+
				"⏱ Uptime: `%s`\n"+
				"🖥 CPU ядер: `%s`\n"+
				"📈 Load Average:%s\n"+
				"💾 RAM: `%s`\n"+
				"💽 Диск: `%s`",
			strings.TrimSpace(string(uptime)),
			strings.TrimSpace(string(cpu)),
			strings.TrimSpace(string(load)),
			strings.TrimSpace(string(ram)),
			strings.TrimSpace(string(disk)),
		)

		sendTelegram(
			token,
			adminID,
			msg,
			nil,
		)

		return true
	}

	return false
}
