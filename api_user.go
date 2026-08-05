package main

import (
	"encoding/json"
	"net/http"
	"strings"
)

func getTokenFromRequest(r *http.Request) string {

	header := r.Header.Get("Authorization")

	if header == "" {
		return ""
	}

	parts := strings.Split(header, " ")

	if len(parts) != 2 {
		return ""
	}

	if parts[0] != "Bearer" {
		return ""
	}

	return parts[1]
}

func userHandler(w http.ResponseWriter, r *http.Request) {

	token := getTokenFromRequest(r)

	if token == "" {
		w.WriteHeader(http.StatusUnauthorized)

		json.NewEncoder(w).Encode(map[string]interface{}{
			"success": false,
			"message": "missing token",
		})

		return
	}

	claims, err := ValidateJWT(token)

	if err != nil {

		w.WriteHeader(http.StatusUnauthorized)

		json.NewEncoder(w).Encode(map[string]interface{}{
			"success": false,
			"message": "invalid token",
		})

		return
	}

	json.NewEncoder(w).Encode(map[string]interface{}{
		"success": true,
		"user": map[string]interface{}{
			"email": claims.Email,
			"role":  claims.Role,
		},
	})
}
