package main

import (
	"encoding/json"
	"net/http"
	"sort"
	"strings"
)

type AdminUser struct {
	Email               string `json:"email"`
	CreatedAt           int64  `json:"created_at"`
	SubscriptionStatus  string `json:"subscription_status"`
	SubscriptionPlan    string `json:"subscription_plan"`
	SubscriptionExpires int64  `json:"subscription_expires"`
	DeviceLimit         int    `json:"device_limit"`
	ConnectedDevices    int    `json:"connected_devices"`
	SubscriptionID      string `json:"subscription_id"`
}

func adminUsersHandler(w http.ResponseWriter, r *http.Request) {
	if !requireAdmin(r) {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}

	search := strings.TrimSpace(strings.ToLower(r.URL.Query().Get("search")))

	dbMutex.Lock()
	defer dbMutex.Unlock()

	users := make([]AdminUser, 0, len(db.Users))

	for _, user := range db.Users {

		if search != "" && !strings.Contains(strings.ToLower(user.Email), search) {
			continue
		}

		devices := 0

		if user.SubscriptionID != "" {
			if pass, ok := db.Passwords[user.SubscriptionID]; ok && pass != nil {
				devices = len(pass.DeviceIDs)

				// Обратная совместимость
				if devices == 0 && pass.DeviceID != "" {
					devices = 1
				}
			}
		}

		users = append(users, AdminUser{
			Email:               user.Email,
			CreatedAt:           user.CreatedAt,
			SubscriptionStatus:  user.SubscriptionStatus,
			SubscriptionPlan:    user.SubscriptionPlan,
			SubscriptionExpires: user.SubscriptionExpires,
			DeviceLimit:         user.DeviceLimit,
			ConnectedDevices:    devices,
			SubscriptionID:      user.SubscriptionID,
		})
	}

	sort.Slice(users, func(i, j int) bool {
		return users[i].CreatedAt > users[j].CreatedAt
	})

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(users)
}
