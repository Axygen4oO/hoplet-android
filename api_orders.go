package main

import (
	"encoding/json"
	"net/http"
)

func ordersHandler(w http.ResponseWriter, r *http.Request) {

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

	json.NewEncoder(w).Encode(map[string]any{
		"success": true,
		"orders":  ListOrdersForUser(claims.Email),
	})
}
