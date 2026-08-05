package main

import (
	"encoding/json"
	"net/http"
)

type AdminUserDevice struct {
	DeviceID   string `json:"device_id"`
	DeviceName string `json:"device_name"`
	IP         string `json:"ip"`
	DownBytes  int64  `json:"down_bytes"`
	UpBytes    int64  `json:"up_bytes"`
}

type AdminUserDetails struct {
	Email               string            `json:"email"`
	CreatedAt           int64             `json:"created_at"`
	SubscriptionStatus  string            `json:"subscription_status"`
	SubscriptionPlan    string            `json:"subscription_plan"`
	SubscriptionExpires int64             `json:"subscription_expires"`
	DeviceLimit         int               `json:"device_limit"`
	ConnectedDevices    int               `json:"connected_devices"`
	SubscriptionID      string            `json:"subscription_id"`
	Devices             []AdminUserDevice `json:"devices"`
}

func adminUserHandler(w http.ResponseWriter, r *http.Request) {
	if !requireAdmin(r) {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}

	email := r.URL.Query().Get("email")
	if email == "" {
		http.Error(w, "email required", http.StatusBadRequest)
		return
	}

	dbMutex.Lock()
	user, ok := db.Users[email]
	if !ok {
		dbMutex.Unlock()
		http.Error(w, "user not found", http.StatusNotFound)
		return
	}

	result := AdminUserDetails{
		Email:               user.Email,
		CreatedAt:           user.CreatedAt,
		SubscriptionStatus:  user.SubscriptionStatus,
		SubscriptionPlan:    user.SubscriptionPlan,
		SubscriptionExpires: user.SubscriptionExpires,
		DeviceLimit:         user.DeviceLimit,
		SubscriptionID:      user.SubscriptionID,
		Devices:             []AdminUserDevice{},
	}

	if pass, ok := db.Passwords[user.SubscriptionID]; ok && pass != nil {

		result.ConnectedDevices = len(pass.DeviceIDs)

		if result.ConnectedDevices == 0 && pass.DeviceID != "" {
			result.ConnectedDevices = 1
		}

		deviceIDs := pass.DeviceIDs
		if len(deviceIDs) == 0 && pass.DeviceID != "" {
			deviceIDs = []string{pass.DeviceID}
		}

		for _, id := range deviceIDs {
			dev, exists := db.Devices[id]
			if !exists || dev == nil {
				continue
			}

			result.Devices = append(result.Devices, AdminUserDevice{
				DeviceID:   dev.DeviceID,
				DeviceName: dev.DeviceName,
				IP:         dev.IP,
				DownBytes:  dev.DownBytes,
				UpBytes:    dev.UpBytes,
			})
		}
	}
	dbMutex.Unlock()

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(result)
}
