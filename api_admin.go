package main

import (
	"encoding/json"
	"net/http"
	"runtime"
	"time"
)

var serverStarted = time.Now()

type AdminStats struct {
	Subscriptions int    `json:"subscriptions"`
	Users         int    `json:"users"`
	Devices       int    `json:"devices"`
	Revenue       int    `json:"revenue"`
	ServerOnline  bool   `json:"serverOnline"`
	Version       string `json:"version"`
	Uptime        string `json:"uptime"`
	GoVersion     string `json:"goVersion"`
}

func adminStatsHandler(w http.ResponseWriter, r *http.Request) {

	if !requireAdmin(r) {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}

	dbMutex.Lock()
	stats := AdminStats{
		ServerOnline: true,
		Version:      ServerVersion,
		Uptime:       time.Since(serverStarted).Round(time.Second).String(),
		GoVersion:    runtime.Version(),
	}

	stats.Users = len(db.Users)
	stats.Devices = len(db.Devices)

	for _, user := range db.Users {
		if user.SubscriptionStatus == "active" {
			stats.Subscriptions++
		}
	}

	for _, order := range db.Orders {
		if order.Status == "paid" {
			stats.Revenue += order.Price
		}
	}
	dbMutex.Unlock()

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(stats)
}
