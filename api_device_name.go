package main

import (
	"encoding/json"
	"log"
	"net/http"
)

type DeviceNameRequest struct {
	Password   string `json:"password"`
	DeviceID   string `json:"device_id"`
	DeviceName string `json:"device_name"`
}

func deviceNameHandler(w http.ResponseWriter, r *http.Request) {
	log.Println("deviceNameHandler called")

	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}

	var req DeviceNameRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "bad request", http.StatusBadRequest)
		return
	}

	if req.Password == "" || req.DeviceID == "" || req.DeviceName == "" {
		http.Error(w, "missing fields", http.StatusBadRequest)
		return
	}

	dbMutex.Lock()
	entry := db.Passwords[req.Password]
	if entry == nil {
		dbMutex.Unlock()
		http.Error(w, "subscription not found", http.StatusNotFound)
		return
	}

	dev := db.Devices[req.DeviceID]
	if dev == nil {
		dev = &ClientDevice{
			DeviceID: req.DeviceID,
		}
		db.Devices[req.DeviceID] = dev
	}

	dev.DeviceName = req.DeviceName

	log.Printf("Saving device name: %s -> %s", req.DeviceID, req.DeviceName)

	saveDBLocked()
	dbMutex.Unlock()

	json.NewEncoder(w).Encode(map[string]any{
		"success": true,
	})
}
