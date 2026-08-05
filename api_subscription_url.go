package main

import (
	"encoding/json"
	"fmt"
	"net/http"
)

func subscriptionURLHandler(w http.ResponseWriter, r *http.Request) {

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

	if user.SubscriptionID == "" {
		http.Error(w, "subscription not found", http.StatusNotFound)
		return
	}

	dbMutex.Lock()
	entry, ok := db.Passwords[user.SubscriptionID]
	dbMutex.Unlock()

	if !ok {
		http.Error(w, "subscription not found", http.StatusNotFound)
		return
	}

	link := fmt.Sprintf(
		"wdtt://138.124.96.127:56000:56001:9000:%s:%s",
		user.SubscriptionID,
		entry.VkHash,
	)

	json.NewEncoder(w).Encode(map[string]any{
		"success": true,
		"link":    link,
	})
}
