package main

import (
	"encoding/json"
	"net/http"
)

func telegramPaymentConfirmHandler(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		w.WriteHeader(http.StatusMethodNotAllowed)
		return
	}

	token := getTokenFromRequest(r)
	if token == "" {
		http.Error(w, "missing token", http.StatusUnauthorized)
		return
	}

	claims, err := ValidateJWT(token)
	if err != nil {
		http.Error(w, "invalid token", http.StatusUnauthorized)
		return
	}

	var req TelegramPaymentConfirmRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "invalid json", http.StatusBadRequest)
		return
	}

	order, _, alreadyPaid, err := ConfirmTelegramPaymentForUser(claims.Email, req)
	if err != nil {
		http.Error(
			w,
			err.Error(),
			orderServiceStatus(err, http.StatusInternalServerError),
		)
		return
	}

	json.NewEncoder(w).Encode(map[string]any{
		"success":     true,
		"alreadyPaid": alreadyPaid,
		"order":       order,
	})
}
