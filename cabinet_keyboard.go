package main

func cabinetButton(text, data string) map[string]interface{} {
	return map[string]interface{}{
		"text":          text,
		"callback_data": data,
	}
}

func cabinetURLButton(text, url string) map[string]interface{} {
	return map[string]interface{}{
		"text": text,
		"url":  url,
	}
}

func cabinetKeyboard(rows ...[]map[string]interface{}) map[string]interface{} {
	return map[string]interface{}{
		"inline_keyboard": rows,
	}
}
