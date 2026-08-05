package main

import (
	"encoding/json"
	"net/http"
)

type DeviceResponse struct {
	DeviceID   string `json:"device_id"`
	DeviceName string `json:"device_name,omitempty"`
	IP         string `json:"ip"`
}

func devicesHandler(w http.ResponseWriter, r *http.Request) {

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

	user, ok := GetUser(claims.Email)
	if !ok {

		w.WriteHeader(http.StatusNotFound)

		json.NewEncoder(w).Encode(map[string]any{
			"success": false,
			"message": "user not found",
		})

		return
	}

	if user.SubscriptionID == "" {

		json.NewEncoder(w).Encode(map[string]any{
			"success": true,
			"devices": []DeviceResponse{},
		})

		return
	}

	dbMutex.Lock()
	pass := db.Passwords[user.SubscriptionID]

	if pass == nil {
		dbMutex.Unlock()

		json.NewEncoder(w).Encode(map[string]any{
			"success": true,
			"devices": []DeviceResponse{},
		})

		return
	}

	result := make([]DeviceResponse, 0)

	for _, id := range pass.DeviceIDs {

		dev := db.Devices[id]

		if dev == nil {
			continue
		}

		name := dev.DeviceName
		if name == "" {
			name = dev.DeviceID // для старых устройств, у которых имя ещё не сохранено
		}

		result = append(result, DeviceResponse{
			DeviceID:   dev.DeviceID,
			DeviceName: name,
			IP:         dev.IP,
		})
	}
	dbMutex.Unlock()

	json.NewEncoder(w).Encode(map[string]any{
		"success": true,
		"devices": result,
	})
}
