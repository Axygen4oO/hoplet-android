package main

import (
	"encoding/json"
	"net/http"
)

type UpdateVKHashesRequest struct {
	Hashes []string `json:"hashes"`
}

func vkHashesHandler(w http.ResponseWriter, r *http.Request) {

    if r.Method != http.MethodGet {
        http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
        return
    }

    // Получение VK Hash доступно всем клиентам

    dbMutex.Lock()
    hashes := append([]string(nil), db.VKHashes...)
    dbMutex.Unlock()

    w.Header().Set("Content-Type", "application/json")

    json.NewEncoder(w).Encode(map[string]any{
        "success": true,
        "hashes":  hashes,
    })
}

func updateVKHashesHandler(w http.ResponseWriter, r *http.Request) {

	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}

	// Доступ только для администратора
	if !requireAdmin(r) {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}

	var req UpdateVKHashesRequest

	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "invalid json", http.StatusBadRequest)
		return
	}

	dbMutex.Lock()
	db.VKHashes = append([]string(nil), req.Hashes...)
	saveDBLocked()
	dbMutex.Unlock()

	w.Header().Set("Content-Type", "application/json")

	json.NewEncoder(w).Encode(map[string]any{
		"success": true,
	})
}
