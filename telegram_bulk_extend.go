package main

import (
	"fmt"
	"strconv"
	"strings"
)

const (
	adminBulkExtendStartCallback         = "subs_extend_all"
	adminBulkExtendToggleActiveCallback  = "bulk_extend_toggle_active"
	adminBulkExtendToggleBlockedCallback = "bulk_extend_toggle_blocked"
	adminBulkExtendToggleExpiredCallback = "bulk_extend_toggle_expired"
	adminBulkExtendContinueCallback      = "bulk_extend_continue"
	adminBulkExtendConfirmCallback       = "bulk_extend_confirm"
	adminBulkExtendCancelCallback        = "bulk_extend_cancel"
)

func resetAdminBulkExtendState() {
	tgState.WaitingBulkExtendDays = false
	tgState.BulkExtendMessageID = 0
	tgState.BulkExtendDays = 0
	tgState.BulkExtendIncludeActive = false
	tgState.BulkExtendIncludeBlocked = false
	tgState.BulkExtendIncludeExpired = false
}

func startAdminBulkExtendWizard(token string, adminID int64, messageID int) {
	resetAdminBulkExtendState()
	tgState.BulkExtendMessageID = messageID
	tgState.BulkExtendIncludeActive = true
	renderAdminBulkExtendFilters(token, adminID, messageID, true, "")
}

func handleAdminBulkExtendCallback(token string, adminID int64, data string, messageID int) bool {
	switch data {
	case adminBulkExtendStartCallback:
		startAdminBulkExtendWizard(token, adminID, messageID)
		return true

	case adminBulkExtendToggleActiveCallback:
		tgState.BulkExtendIncludeActive = !tgState.BulkExtendIncludeActive
		tgState.BulkExtendMessageID = messageID
		renderAdminBulkExtendFilters(token, adminID, messageID, true, "")
		return true

	case adminBulkExtendToggleBlockedCallback:
		tgState.BulkExtendIncludeBlocked = !tgState.BulkExtendIncludeBlocked
		tgState.BulkExtendMessageID = messageID
		renderAdminBulkExtendFilters(token, adminID, messageID, true, "")
		return true

	case adminBulkExtendToggleExpiredCallback:
		tgState.BulkExtendIncludeExpired = !tgState.BulkExtendIncludeExpired
		tgState.BulkExtendMessageID = messageID
		renderAdminBulkExtendFilters(token, adminID, messageID, true, "")
		return true

	case adminBulkExtendContinueCallback:
		tgState.BulkExtendMessageID = messageID
		if !adminBulkExtendHasSelectedGroups() {
			renderAdminBulkExtendFilters(token, adminID, messageID, true, "❌ Выберите хотя бы один фильтр.")
			return true
		}

		tgState.WaitingBulkExtendDays = true
		tgState.BulkExtendDays = 0
		editTelegram(
			token,
			adminID,
			messageID,
			buildAdminBulkExtendDaysPromptText(),
			cabinetKeyboard(
				[]map[string]interface{}{cabinetButton("❌ Отмена", adminBulkExtendCancelCallback)},
			),
		)
		return true

	case adminBulkExtendConfirmCallback:
		req := AdminUsersExtendAllRequest{
			Days:           tgState.BulkExtendDays,
			IncludeActive:  tgState.BulkExtendIncludeActive,
			IncludeBlocked: tgState.BulkExtendIncludeBlocked,
			IncludeExpired: tgState.BulkExtendIncludeExpired,
		}

		updated, err := adminExtendAllUsers(req)
		if err != nil {
			renderAdminBulkExtendFilters(token, adminID, messageID, true, "❌ "+err.Error())
			return true
		}

		days := tgState.BulkExtendDays
		resetAdminBulkExtendState()

		editTelegram(
			token,
			adminID,
			messageID,
			fmt.Sprintf(
				"✅ *Массовое продление выполнено*\n\nПродлено подписок: *%d*\nКоличество дней: *+%d*",
				updated,
				days,
			),
			cabinetKeyboard(
				[]map[string]interface{}{cabinetButton("◀️ К подпискам", "panel_subs")},
			),
		)
		return true

	case adminBulkExtendCancelCallback:
		resetAdminBulkExtendState()
		showSubscriptionsPanel(token, adminID, messageID, true)
		return true
	}

	return false
}

func handleAdminBulkExtendInput(token string, adminID int64, cmd string) bool {
	if !tgState.WaitingBulkExtendDays {
		return false
	}

	days, err := strconv.Atoi(strings.TrimSpace(cmd))
	if err != nil || days <= 0 {
		sendTelegram(token, adminID, "❌ Введите положительное число дней.", nil)
		return true
	}

	tgState.WaitingBulkExtendDays = false
	tgState.BulkExtendDays = int64(days)

	if tgState.BulkExtendMessageID > 0 {
		renderAdminBulkExtendConfirmation(token, adminID, tgState.BulkExtendMessageID, true)
		return true
	}

	renderAdminBulkExtendConfirmation(token, adminID, 0, false)
	return true
}

func renderAdminBulkExtendFilters(token string, adminID int64, messageID int, edit bool, notice string) {
	text := buildAdminBulkExtendFiltersText(notice)
	keyboard := cabinetKeyboard(
		[]map[string]interface{}{cabinetButton(adminBulkExtendToggleLabel(tgState.BulkExtendIncludeActive, "Только активным"), adminBulkExtendToggleActiveCallback)},
		[]map[string]interface{}{cabinetButton(adminBulkExtendToggleLabel(tgState.BulkExtendIncludeBlocked, "Включая заблокированных"), adminBulkExtendToggleBlockedCallback)},
		[]map[string]interface{}{cabinetButton(adminBulkExtendToggleLabel(tgState.BulkExtendIncludeExpired, "Включая истекшие"), adminBulkExtendToggleExpiredCallback)},
		[]map[string]interface{}{cabinetButton("➡️ Продолжить", adminBulkExtendContinueCallback)},
		[]map[string]interface{}{cabinetButton("◀️ Назад", adminBulkExtendCancelCallback)},
	)

	if edit {
		editTelegram(token, adminID, messageID, text, keyboard)
		return
	}

	sendTelegram(token, adminID, text, keyboard)
}

func renderAdminBulkExtendConfirmation(token string, adminID int64, messageID int, edit bool) {
	text := buildAdminBulkExtendConfirmationText()
	keyboard := cabinetKeyboard(
		[]map[string]interface{}{cabinetButton("✅ Подтвердить", adminBulkExtendConfirmCallback)},
		[]map[string]interface{}{cabinetButton("❌ Отмена", adminBulkExtendCancelCallback)},
	)

	if edit {
		editTelegram(token, adminID, messageID, text, keyboard)
		return
	}

	sendTelegram(token, adminID, text, keyboard)
}

func buildAdminBulkExtendFiltersText(notice string) string {
	var builder strings.Builder
	builder.WriteString("⚠️ *Массовое продление*\n\n")

	if notice != "" {
		builder.WriteString(notice)
		builder.WriteString("\n\n")
	}

	builder.WriteString("Выберите параметры продления:\n\n")
	builder.WriteString(adminBulkExtendToggleLabel(tgState.BulkExtendIncludeActive, "Только активным"))
	builder.WriteString("\n")
	builder.WriteString(adminBulkExtendToggleLabel(tgState.BulkExtendIncludeBlocked, "Включая заблокированных"))
	builder.WriteString("\n")
	builder.WriteString(adminBulkExtendToggleLabel(tgState.BulkExtendIncludeExpired, "Включая истекшие"))

	return builder.String()
}

func buildAdminBulkExtendDaysPromptText() string {
	return fmt.Sprintf(
		"⚠️ *Массовое продление*\n\nВведите количество дней.\n\nФильтры:\n%s\n%s\n%s",
		adminBulkExtendSummaryLabel(tgState.BulkExtendIncludeActive, "Только активным"),
		adminBulkExtendSummaryLabel(tgState.BulkExtendIncludeBlocked, "Включая заблокированных"),
		adminBulkExtendSummaryLabel(tgState.BulkExtendIncludeExpired, "Включая истекшие"),
	)
}

func buildAdminBulkExtendConfirmationText() string {
	return fmt.Sprintf(
		"━━━━━━━━━━━━━━\n\n⚠️ *Массовое продление*\n\nКоличество дней:\n\n+%d\n\nФильтры:\n\n%s\n%s\n%s\n\nПродолжить?\n\n━━━━━━━━━━━━━━",
		tgState.BulkExtendDays,
		adminBulkExtendSummaryLabel(tgState.BulkExtendIncludeActive, "Только активным"),
		adminBulkExtendSummaryLabel(tgState.BulkExtendIncludeBlocked, "Включая заблокированных"),
		adminBulkExtendSummaryLabel(tgState.BulkExtendIncludeExpired, "Включая истекшие"),
	)
}

func adminBulkExtendHasSelectedGroups() bool {
	return tgState.BulkExtendIncludeActive ||
		tgState.BulkExtendIncludeBlocked ||
		tgState.BulkExtendIncludeExpired
}

func adminBulkExtendToggleLabel(enabled bool, label string) string {
	if enabled {
		return "✅ " + label
	}
	return "☑ " + label
}

func adminBulkExtendSummaryLabel(enabled bool, label string) string {
	if enabled {
		return "✅ " + label
	}
	return "❌ " + label
}
