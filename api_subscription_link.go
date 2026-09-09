package main

import (
	"encoding/json"
	"net/http"
	"strings"
)

type LinkSubscriptionRequest struct {
	Code string `json:"code"`
}

func linkSubscriptionHandler(w http.ResponseWriter, r *http.Request) {

	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}

	token := getTokenFromRequest(r)

	if token == "" {
		w.WriteHeader(http.StatusUnauthorized)

		json.NewEncoder(w).Encode(map[string]any{
			"success": false,
			"message": "missing token",
		})
		return
	}

	claims, err := ValidateJWT(token)

	if err != nil {
		w.WriteHeader(http.StatusUnauthorized)

		json.NewEncoder(w).Encode(map[string]any{
			"success": false,
			"message": "invalid token",
		})
		return
	}

	var req LinkSubscriptionRequest

	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		w.WriteHeader(http.StatusBadRequest)

		json.NewEncoder(w).Encode(map[string]any{
			"success": false,
			"message": "invalid request",
		})
		return
	}

	req.Code = strings.TrimSpace(req.Code)

	if err := LinkExistingSubscriptionToUser(req.Code, claims.Email); err != nil {
		statusCode := http.StatusBadRequest
		message := err.Error()

		switch err {
		case ErrSubscriptionNotFound:
			statusCode = http.StatusNotFound
		case ErrUserNotFound:
			statusCode = http.StatusNotFound
		case ErrSubscriptionAlreadyLinked, ErrUserAlreadyHasSubscription:
			statusCode = http.StatusConflict
		case ErrSubscriptionBlocked:
			statusCode = http.StatusForbidden
		}

		w.WriteHeader(statusCode)
		json.NewEncoder(w).Encode(map[string]any{
			"success": false,
			"message": message,
		})
		return
	}

	json.NewEncoder(w).Encode(map[string]any{
		"success": true,
	})
}
