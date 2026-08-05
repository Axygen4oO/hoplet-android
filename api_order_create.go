package main

import (
	"encoding/json"
	"net/http"
)

type CreateOrderRequest struct {
	Plan    string `json:"plan"`
	Devices int    `json:"devices"`
	Action  string `json:"action"`
}

type CalculatePriceResponse struct {
	Success bool `json:"success"`
	Price   int  `json:"price"`
}

func calculatePriceHandler(w http.ResponseWriter, r *http.Request) {
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

	var req CreateOrderRequest

	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "invalid json", http.StatusBadRequest)
		return
	}

	quote, err := CalculatePriceForUser(claims.Email, req)
	if err != nil {
		http.Error(w, err.Error(), orderServiceStatus(err, http.StatusInternalServerError))
		return
	}

	w.Header().Set("Content-Type", "application/json")

	json.NewEncoder(w).Encode(CalculatePriceResponse{
		Success: true,
		Price:   quote.Price,
	})
}

func createOrderHandler(w http.ResponseWriter, r *http.Request) {
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

	var req CreateOrderRequest

	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "invalid json", http.StatusBadRequest)
		return
	}

	result, err := CreateOrderForUser(claims.Email, req)
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
		"existing":   result.Existing,
		"order":      result.Order,
		"paymentUrl": result.PaymentURL,
	})
}
