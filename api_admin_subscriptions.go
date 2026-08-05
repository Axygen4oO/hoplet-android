package main

import (
	"encoding/json"
	"net/http"
	"time"
)

type AdminSubscription struct {
	ID         string `json:"id"`
	Email      string `json:"email"`
	Plan       string `json:"plan"`
	Status     string `json:"status"`
	Expires    int64  `json:"expires"`
	DaysLeft   int64  `json:"days_left"`
	Devices    int    `json:"devices"`
	DeviceUsed int    `json:"device_used"`
}

type AdminSubscriptionsResponse struct {
	Success       bool                `json:"success"`
	Subscriptions []AdminSubscription `json:"subscriptions"`
}

type AdminSubscriptionRequest struct {
	SubscriptionID string `json:"subscription_id"`
}

type AdminCreateSubscriptionRequest struct {
	Email   string `json:"email"`
	Plan    string `json:"plan"`
	Devices int    `json:"devices"`
	Days    int64  `json:"days"`
}

type AdminSubscriptionIDRequest struct {
	ID string `json:"id"`
}

type AdminSubscriptionExtendRequest struct {
	ID   string `json:"id"`
	Days int64  `json:"days"`
}

type AdminSubscriptionPlanRequest struct {
	ID   string `json:"id"`
	Plan string `json:"plan"`
}

func adminSubscriptionsHandler(w http.ResponseWriter, r *http.Request) {

	if !requireAdmin(r) {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}

	dbMutex.Lock()
	now := time.Now().Unix()

	result := make([]AdminSubscription, 0)

	for _, user := range db.Users {

		if user.SubscriptionID == "" {
			continue
		}

		pass := db.Passwords[user.SubscriptionID]
		if pass == nil {
			continue
		}

		days := int64(0)

		if user.SubscriptionExpires > now {
			days = (user.SubscriptionExpires - now) / 86400
		}

		result = append(result, AdminSubscription{
			ID:         user.SubscriptionID,
			Email:      user.Email,
			Plan:       user.SubscriptionPlan,
			Status:     user.SubscriptionStatus,
			Expires:    user.SubscriptionExpires,
			DaysLeft:   days,
			Devices:    user.DeviceLimit,
			DeviceUsed: len(pass.DeviceIDs),
		})
	}
	dbMutex.Unlock()

	w.Header().Set("Content-Type", "application/json")

	json.NewEncoder(w).Encode(AdminSubscriptionsResponse{
		Success:       true,
		Subscriptions: result,
	})
}

func adminSubscriptionHandler(w http.ResponseWriter, r *http.Request) {

	if !requireAdmin(r) {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}

	id := r.URL.Query().Get("id")
	if id == "" {
		http.Error(w, "subscription id required", http.StatusBadRequest)
		return
	}

	dbMutex.Lock()
	var user *UserAccount

	for _, u := range db.Users {
		if u.SubscriptionID == id {
			user = u
			break
		}
	}

	if user == nil {
		dbMutex.Unlock()
		http.Error(w, "subscription not found", http.StatusNotFound)
		return
	}

	pass := db.Passwords[user.SubscriptionID]
	if pass == nil {
		dbMutex.Unlock()
		http.Error(w, "subscription not found", http.StatusNotFound)
		return
	}

	now := time.Now().Unix()

	var days int64
	if user.SubscriptionExpires > now {
		days = (user.SubscriptionExpires - now) / 86400
	}
	resp := AdminSubscription{
		ID:         user.SubscriptionID,
		Email:      user.Email,
		Plan:       user.SubscriptionPlan,
		Status:     user.SubscriptionStatus,
		Expires:    user.SubscriptionExpires,
		DaysLeft:   days,
		Devices:    user.DeviceLimit,
		DeviceUsed: len(pass.DeviceIDs),
	}
	dbMutex.Unlock()

	w.Header().Set("Content-Type", "application/json")

	json.NewEncoder(w).Encode(resp)
}

func adminSubscriptionCreateHandler(w http.ResponseWriter, r *http.Request) {

	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}

	if !requireAdmin(r) {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}

	var req AdminCreateSubscriptionRequest

	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "bad request", http.StatusBadRequest)
		return
	}

	dbMutex.Lock()
	_, ok := db.Users[req.Email]
	if !ok {
		dbMutex.Unlock()
		http.Error(w, "user not found", http.StatusNotFound)
		return
	}
	dbMutex.Unlock()

	password, err := createSubscription(
		req.Email,
		req.Plan,
		req.Devices,
	)

	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}

	dbMutex.Lock()
	user, ok := db.Users[req.Email]
	entry := db.Passwords[password]
	if !ok || user == nil || entry == nil {
		dbMutex.Unlock()
		rollbackCreatedSubscription(password)
		http.Error(w, "user not found", http.StatusNotFound)
		return
	}

	user.SubscriptionID = password
	user.SubscriptionStatus = "active"
	user.SubscriptionPlan = req.Plan
	user.SubscriptionExpires = entry.ExpiresAt
	user.DeviceLimit = req.Devices

	syncUserSubscription(user)

	saveDBLocked()
	dbMutex.Unlock()

	w.Header().Set("Content-Type", "application/json")

	json.NewEncoder(w).Encode(AdminActionResponse{
		Success: true,
	})
}

func findUserBySubscriptionID(subscriptionID string) (*UserAccount, bool) {
	for _, user := range db.Users {
		if user.SubscriptionID == subscriptionID {
			return user, true
		}
	}
	return nil, false
}

func adminSubscriptionExtendHandler(w http.ResponseWriter, r *http.Request) {

	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}

	if !requireAdmin(r) {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}

	w.Header().Set("Content-Type", "application/json")

	var req AdminSubscriptionExtendRequest

	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		json.NewEncoder(w).Encode(AdminActionResponse{
			Success: false,
			Error:   "bad request",
		})
		return
	}

	dbMutex.Lock()
	resp := AdminActionResponse{Success: true}
	user, ok := findUserBySubscriptionID(req.ID)
	if !ok {
		resp = AdminActionResponse{
			Success: false,
			Error:   "subscription not found",
		}
	} else if err := extendSubscription(user, req.Days); err != nil {
		resp = AdminActionResponse{
			Success: false,
			Error:   err.Error(),
		}
	}
	dbMutex.Unlock()

	json.NewEncoder(w).Encode(resp)
}

func adminSubscriptionBlockHandler(w http.ResponseWriter, r *http.Request) {

	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}

	if !requireAdmin(r) {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}

	w.Header().Set("Content-Type", "application/json")

	var req AdminSubscriptionIDRequest

	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		json.NewEncoder(w).Encode(AdminActionResponse{
			Success: false,
			Error:   "bad request",
		})
		return
	}

	dbMutex.Lock()
	resp := AdminActionResponse{Success: true}
	user, ok := findUserBySubscriptionID(req.ID)
	if !ok {
		resp = AdminActionResponse{
			Success: false,
			Error:   "subscription not found",
		}
	} else if err := blockSubscription(user); err != nil {
		resp = AdminActionResponse{
			Success: false,
			Error:   err.Error(),
		}
	}
	dbMutex.Unlock()

	json.NewEncoder(w).Encode(resp)
}

func adminSubscriptionUnblockHandler(w http.ResponseWriter, r *http.Request) {

	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}

	if !requireAdmin(r) {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}

	w.Header().Set("Content-Type", "application/json")

	var req AdminSubscriptionIDRequest

	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		json.NewEncoder(w).Encode(AdminActionResponse{
			Success: false,
			Error:   "bad request",
		})
		return
	}

	dbMutex.Lock()
	resp := AdminActionResponse{Success: true}
	user, ok := findUserBySubscriptionID(req.ID)
	if !ok {
		resp = AdminActionResponse{
			Success: false,
			Error:   "subscription not found",
		}
	} else if err := unblockSubscription(user); err != nil {
		resp = AdminActionResponse{
			Success: false,
			Error:   err.Error(),
		}
	}
	dbMutex.Unlock()

	json.NewEncoder(w).Encode(resp)
}

func adminSubscriptionChangePlanHandler(w http.ResponseWriter, r *http.Request) {

	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}

	if !requireAdmin(r) {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}

	w.Header().Set("Content-Type", "application/json")

	var req AdminSubscriptionPlanRequest

	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		json.NewEncoder(w).Encode(AdminActionResponse{
			Success: false,
			Error:   "bad request",
		})
		return
	}

	dbMutex.Lock()
	resp := AdminActionResponse{Success: true}
	user, ok := findUserBySubscriptionID(req.ID)
	if !ok {
		resp = AdminActionResponse{
			Success: false,
			Error:   "subscription not found",
		}
	} else if err := changeSubscriptionPlan(user, req.Plan); err != nil {
		resp = AdminActionResponse{
			Success: false,
			Error:   err.Error(),
		}
	}
	dbMutex.Unlock()

	json.NewEncoder(w).Encode(resp)
}
