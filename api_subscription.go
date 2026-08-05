package main

import (
	"encoding/json"
	"net/http"
)

func subscriptionHandler(w http.ResponseWriter, r *http.Request) {

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

	user, ok := GetUser(claims.Email)
	if !ok {
		http.Error(w, "user not found", http.StatusNotFound)
		return
	}

	json.NewEncoder(w).Encode(map[string]any{
		"success": true,
		"subscription": map[string]any{
			"status":       user.SubscriptionStatus,
			"plan":         user.SubscriptionPlan,
			"expires":      user.SubscriptionExpires,
			"device_limit": user.DeviceLimit,
		},
	})
}
