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

	dbMutex.Lock()
	statusCode := http.StatusOK
	resp := map[string]any{
		"success": true,
	}
	pass, ok := db.Passwords[req.Code]
	if !ok || pass == nil {
		statusCode = http.StatusNotFound
		resp = map[string]any{
			"success": false,
			"message": "subscription not found",
		}
	} else {
		for email, u := range db.Users {
			if email == claims.Email {
				continue
			}

			if u.SubscriptionID == req.Code {
				statusCode = http.StatusConflict
				resp = map[string]any{
					"success": false,
					"message": "subscription already linked",
				}
				break
			}
		}

		if statusCode == http.StatusOK {
			user := db.Users[claims.Email]
			if user == nil {
				statusCode = http.StatusNotFound
				resp = map[string]any{
					"success": false,
					"message": "user not found",
				}
			} else {
				user.SubscriptionID = req.Code

				if pass.MaxDevices > 0 {
					user.DeviceLimit = pass.MaxDevices
				}

				if pass.ExpiresAt > 0 {
					user.SubscriptionExpires = pass.ExpiresAt
				}

				user.SubscriptionStatus = "active"
				saveDBLocked()
			}
		}
	}
	dbMutex.Unlock()

	if statusCode != http.StatusOK {
		w.WriteHeader(statusCode)
	}
	json.NewEncoder(w).Encode(resp)
}
