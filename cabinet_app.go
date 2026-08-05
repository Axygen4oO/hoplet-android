package main

import (
	"fmt"
	"strings"
)

func showCabinetApp(token string, actor cabinetActor, messageID int, edit bool, force bool) {
	release, err := resolveLatestAppRelease(force)
	if err != nil {
		cabinetRender(
			token,
			actor.ChatID,
			messageID,
			fmt.Sprintf(
				"<b>📱 Скачать приложение</b>\n\nНе удалось получить информацию о последнем релизе: %s",
				cabinetSafe(err.Error()),
			),
			[][]map[string]interface{}{
				{cabinetButton("🔄 Проверить обновление", "cabinet_app_refresh")},
				{cabinetButton("⬅️ Назад", "cabinet_open")},
			},
			edit,
		)
		return
	}

	appInfo := release.toResponse()
	text := buildCabinetAppText(appInfo)
	keyboard := [][]map[string]interface{}{}
	if appInfo.DownloadURL != "" {
		keyboard = append(keyboard, []map[string]interface{}{
			cabinetURLButton("⬇ Скачать приложение", appInfo.DownloadURL),
		})
	}
	keyboard = append(keyboard,
		[]map[string]interface{}{cabinetButton("🔄 Проверить обновление", "cabinet_app_refresh")},
		[]map[string]interface{}{cabinetButton("⬅️ Назад", "cabinet_open")},
	)

	cabinetRender(token, actor.ChatID, messageID, text, keyboard, edit)
}

func buildCabinetAppText(release appLatestResponse) string {
	var parts []string
	parts = append(parts, "<b>📱 Скачать приложение</b>")
	parts = append(parts, "")
	parts = append(parts, fmt.Sprintf("<b>Доступная версия:</b> %s", cabinetSafe(valueOrDash(release.Version))))
	parts = append(parts, fmt.Sprintf("<b>Дата релиза:</b> %s", cabinetSafe(valueOrDash(release.ReleaseDate))))
	parts = append(parts, fmt.Sprintf("<b>Размер APK:</b> %s", cabinetSafe(formatCabinetByteSize(release.ApkSize))))
	parts = append(parts, "")
	parts = append(parts, "<b>Список изменений:</b>")
	if len(release.Changelog) > 0 {
		parts = append(parts, formatCabinetChangelog(release.Changelog))
	} else {
		parts = append(parts, "—")
	}
	return strings.Join(parts, "\n")
}

func formatCabinetChangelog(items []string) string {
	const limit = 8
	filtered := make([]string, 0, len(items))
	for _, item := range items {
		text := strings.TrimSpace(item)
		if text != "" {
			filtered = append(filtered, text)
		}
	}
	if len(filtered) == 0 {
		return "—"
	}

	displayed := filtered
	if len(displayed) > limit {
		displayed = displayed[:limit]
	}

	lines := make([]string, 0, len(displayed)+1)
	for _, item := range displayed {
		lines = append(lines, "• "+cabinetSafe(item))
	}
	if len(filtered) > limit {
		lines = append(lines, fmt.Sprintf("• и еще %d пунктов", len(filtered)-limit))
	}
	return strings.Join(lines, "\n")
}

func formatCabinetByteSize(size int64) string {
	if size <= 0 {
		return "—"
	}

	units := []string{"Б", "КБ", "МБ", "ГБ"}
	value := float64(size)
	unitIndex := 0
	for value >= 1024 && unitIndex < len(units)-1 {
		value /= 1024
		unitIndex++
	}
	if unitIndex == 0 {
		return fmt.Sprintf("%d %s", size, units[unitIndex])
	}
	return fmt.Sprintf("%.1f %s", value, units[unitIndex])
}

func valueOrDash(value string) string {
	if strings.TrimSpace(value) == "" {
		return "—"
	}
	return value
}
