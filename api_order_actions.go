package main

import (
	"encoding/json"
	"net/http"
)

type OrderActionRequest struct {
	OrderID string `json:"orderId"`
}

func retryOrderHandler(w http.ResponseWriter, r *http.Request) {
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

	var req OrderActionRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "invalid json", http.StatusBadRequest)
		return
	}

	_, paymentURL, err := RetryOrderForUser(claims.Email, req.OrderID)
	if err != nil {
		http.Error(
			w,
			err.Error(),
			orderServiceStatus(err, http.StatusInternalServerError),
		)
		return
	}

	json.NewEncoder(w).Encode(map[string]any{
		"success":    true,
		"paymentUrl": paymentURL,
	})
}

func cancelOrderHandler(w http.ResponseWriter, r *http.Request) {
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

	var req OrderActionRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "invalid json", http.StatusBadRequest)
		return
	}

	if err := CancelOrderForUser(claims.Email, req.OrderID); err != nil {
		http.Error(
			w,
			err.Error(),
			orderServiceStatus(err, http.StatusInternalServerError),
		)
		return
	}

	json.NewEncoder(w).Encode(map[string]any{
		"success": true,
	})
}
