package main

import (
	"encoding/json"
	"net/http"
	"strconv"
	"time"
)

type AppNotification struct {
	ID        int64  `json:"id"`
	Title     string `json:"title"`
	Message   string `json:"message"`
	CreatedAt int64  `json:"created_at"`
}

func publishNotification(title, message string) AppNotification {
	dbMutex.Lock()
	defer dbMutex.Unlock()

	nextID := db.LastNotification.ID + 1
	if nextID <= 0 {
		nextID = 1
	}

	notification := AppNotification{
		ID:        nextID,
		Title:     title,
		Message:   message,
		CreatedAt: time.Now().Unix(),
	}

	db.LastNotification = notification
	saveDBLocked()

	return notification
}

func latestNotificationHandler(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}

	afterID := int64(0)
	afterRaw := r.URL.Query().Get("after")
	if afterRaw != "" {
		parsed, err := strconv.ParseInt(afterRaw, 10, 64)
		if err != nil || parsed < 0 {
			http.Error(w, "invalid after", http.StatusBadRequest)
			return
		}
		afterID = parsed
	}

	dbMutex.Lock()
	notification := db.LastNotification
	dbMutex.Unlock()

	if notification.ID <= 0 || notification.ID <= afterID {
		w.WriteHeader(http.StatusNoContent)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(notification)
}
