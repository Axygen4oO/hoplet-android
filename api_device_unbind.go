package main

import (
	"encoding/json"
	"net/http"
)

type UnbindDeviceRequest struct {
	DeviceID string `json:"device_id"`
}

func unbindDeviceHandler(w http.ResponseWriter, r *http.Request) {

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

	var req UnbindDeviceRequest

	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		w.WriteHeader(http.StatusBadRequest)

		json.NewEncoder(w).Encode(map[string]any{
			"success": false,
			"message": "invalid request",
		})
		return
	}

	if req.DeviceID == "" {
		w.WriteHeader(http.StatusBadRequest)

		json.NewEncoder(w).Encode(map[string]any{
			"success": false,
			"message": "device_id is required",
		})
		return
	}

	removed, statusCode, payload := func() ([]*ClientDevice, int, map[string]any) {
		dbMutex.Lock()
		defer dbMutex.Unlock()

		user, ok := db.Users[claims.Email]
		if !ok {
			return nil, http.StatusNotFound, map[string]any{
				"success": false,
				"message": "user not found",
			}
		}

		if user.SubscriptionID == "" {
			return nil, http.StatusBadRequest, map[string]any{
				"success": false,
				"message": "subscription not linked",
			}
		}

		entry, ok := db.Passwords[user.SubscriptionID]
		if !ok || entry == nil {
			return nil, http.StatusNotFound, map[string]any{
				"success": false,
				"message": "subscription not found",
			}
		}

		// Проверяем, что устройство принадлежит подписке пользователя.
		found := false

		if entry.DeviceID == req.DeviceID {
			found = true
		}

		if !found {
			for _, id := range entry.DeviceIDs {
				if id == req.DeviceID {
					found = true
					break
				}
			}
		}

		if !found {
			return nil, http.StatusNotFound, map[string]any{
				"success": false,
				"message": "device not found",
			}
		}

		removed := unbindDevices(entry, req.DeviceID)
		purgeRemovedDeviceStatsLocked(removed)
		saveDBLocked()

		return removed, 0, nil
	}()

	if statusCode != 0 {
		w.WriteHeader(statusCode)
		json.NewEncoder(w).Encode(payload)
		return
	}

	applyRemovedDeviceRuntimeState(globalWgDev, removed)

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]any{
		"success": true,
	})
}
