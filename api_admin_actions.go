package main

import (
	"encoding/json"
	"net/http"
	"strings"

	"golang.org/x/crypto/bcrypt"
)

type AdminUserUpdateRequest struct {
	Email       string `json:"email"`
	Plan        string `json:"plan"`
	Expires     int64  `json:"expires"`
	DeviceLimit int    `json:"device_limit"`
}

type AdminUserDeleteRequest struct {
	Email string `json:"email"`
}

type AdminUserBlockRequest struct {
	Email string `json:"email"`
}

type AdminUserPasswordRequest struct {
	Email    string `json:"email"`
	Password string `json:"password"`
}

type AdminUserRoleRequest struct {
	Email string `json:"email"`
	Role  string `json:"role"`
}

type AdminUserExtendRequest struct {
	Email string `json:"email"`
	Days  int64  `json:"days"`
}

type AdminUsersExtendAllRequest struct {
	Days int64 `json:"days"`

	IncludeActive  bool `json:"include_active"`
	IncludeBlocked bool `json:"include_blocked"`
	IncludeExpired bool `json:"include_expired"`
}

type AdminUsersExtendAllResponse struct {
	Success bool   `json:"success"`
	Updated int    `json:"updated"`
	Error   string `json:"error,omitempty"`
}

type AdminUserPlanRequest struct {
	Email string `json:"email"`
	Plan  string `json:"plan"`
}

type AdminUserResetRequest struct {
	Email string `json:"email"`
}

type AdminActionResponse struct {
	Success bool   `json:"success"`
	Error   string `json:"error,omitempty"`
}

type AdminDevice struct {
	Email      string `json:"email"`
	DeviceID   string `json:"device_id"`
	DeviceName string `json:"device_name"`
	IP         string `json:"ip"`
	DownBytes  int64  `json:"down_bytes"`
	UpBytes    int64  `json:"up_bytes"`
}

type AdminDevicesResponse struct {
	Success bool          `json:"success"`
	Devices []AdminDevice `json:"devices"`
}

type AdminDeviceRequest struct {
	DeviceID string `json:"device_id"`
}

type AdminRenameDeviceRequest struct {
	DeviceID string `json:"device_id"`
	Name     string `json:"name"`
}

func syncUserSubscription(user *UserAccount) {
	pass, ok := db.Passwords[user.SubscriptionID]
	if !ok || pass == nil {
		return
	}

	pass.ExpiresAt = user.SubscriptionExpires
	pass.MaxDevices = user.DeviceLimit
}

func adminUserUpdateHandler(w http.ResponseWriter, r *http.Request) {

	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}

	if !requireAdmin(r) {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}

	var req AdminUserUpdateRequest

	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "bad request", http.StatusBadRequest)
		return
	}

	dbMutex.Lock()
	resp := AdminActionResponse{Success: true}
	user, ok := db.Users[req.Email]
	if !ok {
		resp = AdminActionResponse{
			Success: false,
			Error:   "user not found",
		}
	} else {
		user.SubscriptionPlan = req.Plan
		user.SubscriptionExpires = req.Expires
		user.DeviceLimit = req.DeviceLimit

		syncUserSubscription(user)
		saveDBLocked()
	}
	dbMutex.Unlock()

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(resp)
}

func adminUserDeleteHandler(w http.ResponseWriter, r *http.Request) {

	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}

	if !requireAdmin(r) {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}

	var req AdminUserDeleteRequest

	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "bad request", http.StatusBadRequest)
		return
	}

	dbMutex.Lock()
	resp := AdminActionResponse{Success: true}
	removed := []*ClientDevice{}
	removedPassword := ""
	user, ok := db.Users[req.Email]
	if !ok {
		resp = AdminActionResponse{
			Success: false,
			Error:   "user not found",
		}
	} else if varErrRemoved, varRemovedPassword, err := deleteUserAccount(user); err != nil {
		resp = AdminActionResponse{
			Success: false,
			Error:   err.Error(),
		}
	} else {
		removed = varErrRemoved
		removedPassword = varRemovedPassword
	}
	dbMutex.Unlock()

	if removedPassword != "" {
		serverWrapKeys.RemovePassword(removedPassword)
	}
	applyRemovedDeviceRuntimeState(globalWgDev, removed)

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(resp)
}

func adminUserBlockHandler(w http.ResponseWriter, r *http.Request) {

	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}

	if !requireAdmin(r) {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}

	var req AdminUserBlockRequest

	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "bad request", http.StatusBadRequest)
		return
	}

	dbMutex.Lock()
	resp := AdminActionResponse{Success: true}
	user, ok := db.Users[req.Email]
	if !ok {
		resp = AdminActionResponse{
			Success: false,
			Error:   "user not found",
		}
	} else if pass, ok := db.Passwords[user.SubscriptionID]; !ok || pass == nil {
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

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(resp)
}

func adminUserUnblockHandler(w http.ResponseWriter, r *http.Request) {

	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}

	if !requireAdmin(r) {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}

	var req AdminUserBlockRequest

	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "bad request", http.StatusBadRequest)
		return
	}

	dbMutex.Lock()
	resp := AdminActionResponse{Success: true}
	user, ok := db.Users[req.Email]
	if !ok {
		resp = AdminActionResponse{
			Success: false,
			Error:   "user not found",
		}
	} else if pass, ok := db.Passwords[user.SubscriptionID]; !ok || pass == nil {
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

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(resp)
}
func adminUserPasswordHandler(w http.ResponseWriter, r *http.Request) {

	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}

	if !requireAdmin(r) {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}

	var req AdminUserPasswordRequest

	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "bad request", http.StatusBadRequest)
		return
	}

	if req.Password == "" {
		json.NewEncoder(w).Encode(AdminActionResponse{
			Success: false,
			Error:   "password is empty",
		})
		return
	}

	hash, err := bcrypt.GenerateFromPassword(
		[]byte(req.Password),
		bcrypt.DefaultCost,
	)
	if err != nil {
		json.NewEncoder(w).Encode(AdminActionResponse{
			Success: false,
			Error:   err.Error(),
		})
		return
	}

	dbMutex.Lock()
	resp := AdminActionResponse{Success: true}
	user, ok := db.Users[req.Email]
	if !ok {
		resp = AdminActionResponse{
			Success: false,
			Error:   "user not found",
		}
	} else {
		user.PasswordHash = string(hash)
		saveDBLocked()
	}
	dbMutex.Unlock()

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(resp)
}

func adminUserChangeRoleHandler(w http.ResponseWriter, r *http.Request) {

	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}

	if !requireAdmin(r) {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}

	var req AdminUserRoleRequest

	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "bad request", http.StatusBadRequest)
		return
	}

	if req.Role == "" {
		json.NewEncoder(w).Encode(AdminActionResponse{
			Success: false,
			Error:   "role is empty",
		})
		return
	}

	dbMutex.Lock()
	resp := AdminActionResponse{Success: true}
	user, ok := db.Users[req.Email]
	if !ok {
		resp = AdminActionResponse{
			Success: false,
			Error:   "user not found",
		}
	} else {
		user.Role = req.Role
		saveDBLocked()
	}
	dbMutex.Unlock()

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(resp)
}

func adminUserResetDevicesHandler(w http.ResponseWriter, r *http.Request) {

	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}

	if !requireAdmin(r) {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}

	w.Header().Set("Content-Type", "application/json")

	var req AdminUserResetRequest

	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		json.NewEncoder(w).Encode(AdminActionResponse{
			Success: false,
			Error:   "bad request",
		})
		return
	}

	dbMutex.Lock()
	resp := AdminActionResponse{Success: true}
	removed := []*ClientDevice{}
	user, ok := db.Users[req.Email]
	if !ok {
		resp = AdminActionResponse{
			Success: false,
			Error:   "user not found",
		}
	} else if removedDevices, err := resetSubscriptionDevices(user); err != nil {
		resp = AdminActionResponse{
			Success: false,
			Error:   err.Error(),
		}
	} else {
		removed = removedDevices
	}
	dbMutex.Unlock()

	applyRemovedDeviceRuntimeState(globalWgDev, removed)
	json.NewEncoder(w).Encode(resp)
}

func adminUserResetTrafficHandler(w http.ResponseWriter, r *http.Request) {

	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}

	if !requireAdmin(r) {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}

	w.Header().Set("Content-Type", "application/json")

	var req AdminUserResetRequest

	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		json.NewEncoder(w).Encode(AdminActionResponse{
			Success: false,
			Error:   "bad request",
		})
		return
	}

	dbMutex.Lock()
	resp := AdminActionResponse{Success: true}
	user, ok := db.Users[req.Email]
	if !ok {
		resp = AdminActionResponse{
			Success: false,
			Error:   "user not found",
		}
	} else if err := resetSubscriptionTraffic(user); err != nil {
		resp = AdminActionResponse{
			Success: false,
			Error:   err.Error(),
		}
	}
	dbMutex.Unlock()

	json.NewEncoder(w).Encode(resp)
}

func adminUserResetVKHashHandler(w http.ResponseWriter, r *http.Request) {

	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}

	if !requireAdmin(r) {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}

	w.Header().Set("Content-Type", "application/json")

	var req AdminUserResetRequest

	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		json.NewEncoder(w).Encode(AdminActionResponse{
			Success: false,
			Error:   "bad request",
		})
		return
	}

	dbMutex.Lock()
	resp := AdminActionResponse{Success: true}
	user, ok := db.Users[req.Email]
	if !ok {
		resp = AdminActionResponse{
			Success: false,
			Error:   "user not found",
		}
	} else if err := resetSubscriptionVKHash(user); err != nil {
		resp = AdminActionResponse{
			Success: false,
			Error:   err.Error(),
		}
	}
	dbMutex.Unlock()

	json.NewEncoder(w).Encode(resp)
}

func adminUserExtendHandler(w http.ResponseWriter, r *http.Request) {

	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}

	if !requireAdmin(r) {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}

	w.Header().Set("Content-Type", "application/json")

	var req AdminUserExtendRequest

	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		json.NewEncoder(w).Encode(AdminActionResponse{
			Success: false,
			Error:   "bad request",
		})
		return
	}

	dbMutex.Lock()
	resp := AdminActionResponse{Success: true}
	user, ok := db.Users[req.Email]
	if !ok {
		resp = AdminActionResponse{
			Success: false,
			Error:   "user not found",
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

func adminUsersExtendAllHandler(w http.ResponseWriter, r *http.Request) {

	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}

	if !requireAdmin(r) {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}

	w.Header().Set("Content-Type", "application/json")

	var req AdminUsersExtendAllRequest

	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		json.NewEncoder(w).Encode(AdminUsersExtendAllResponse{
			Success: false,
			Error:   "bad request",
		})
		return
	}

	if req.Days <= 0 {
		json.NewEncoder(w).Encode(AdminUsersExtendAllResponse{
			Success: false,
			Error:   "days must be greater than zero",
		})
		return
	}

	if !req.IncludeActive &&
		!req.IncludeBlocked &&
		!req.IncludeExpired {

		json.NewEncoder(w).Encode(AdminUsersExtendAllResponse{
			Success: false,
			Error:   "no user groups selected",
		})
		return
	}

	dbMutex.Lock()
	updated := 0

	for _, user := range db.Users {

		include := false

		switch user.SubscriptionStatus {

		case "active":
			include = req.IncludeActive

		case "blocked":
			include = req.IncludeBlocked

		case "expired":
			include = req.IncludeExpired
		}

		if !include {
			continue
		}

		if err := extendSubscription(user, req.Days); err != nil {
			continue
		}

		updated++
	}

	saveDBLocked()
	dbMutex.Unlock()

	json.NewEncoder(w).Encode(AdminUsersExtendAllResponse{
		Success: true,
		Updated: updated,
	})
}

func adminUserChangePlanHandler(w http.ResponseWriter, r *http.Request) {

	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}

	if !requireAdmin(r) {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}

	w.Header().Set("Content-Type", "application/json")

	var req AdminUserPlanRequest

	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		json.NewEncoder(w).Encode(AdminActionResponse{
			Success: false,
			Error:   "bad request",
		})
		return
	}

	dbMutex.Lock()
	resp := AdminActionResponse{Success: true}
	user, ok := db.Users[req.Email]
	if !ok {
		resp = AdminActionResponse{
			Success: false,
			Error:   "user not found",
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

func adminDevicesHandler(w http.ResponseWriter, r *http.Request) {

	if !requireAdmin(r) {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}

	dbMutex.Lock()
	result := make([]AdminDevice, 0)

	for _, user := range db.Users {

		if user.SubscriptionID == "" {
			continue
		}

		pass := db.Passwords[user.SubscriptionID]
		if pass == nil {
			continue
		}

		deviceIDs := pass.DeviceIDs

		if len(deviceIDs) == 0 && pass.DeviceID != "" {
			deviceIDs = []string{pass.DeviceID}
		}

		for _, id := range deviceIDs {

			dev := db.Devices[id]
			if dev == nil {
				continue
			}

			name := dev.DeviceName
			if name == "" {
				name = dev.DeviceID
			}

			result = append(result, AdminDevice{
				Email:      user.Email,
				DeviceID:   dev.DeviceID,
				DeviceName: name,
				IP:         dev.IP,
				DownBytes:  dev.DownBytes,
				UpBytes:    dev.UpBytes,
			})
		}
	}
	dbMutex.Unlock()

	w.Header().Set("Content-Type", "application/json")

	json.NewEncoder(w).Encode(AdminDevicesResponse{
		Success: true,
		Devices: result,
	})
}

func adminDeviceUnbindHandler(w http.ResponseWriter, r *http.Request) {

	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}

	if !requireAdmin(r) {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}

	w.Header().Set("Content-Type", "application/json")

	var req AdminDeviceRequest

	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		json.NewEncoder(w).Encode(AdminActionResponse{
			Success: false,
			Error:   "bad request",
		})
		return
	}

	dbMutex.Lock()
	resp := AdminActionResponse{
		Success: false,
		Error:   "device not found",
	}

	for _, user := range db.Users {

		if user.SubscriptionID == "" {
			continue
		}

		pass := db.Passwords[user.SubscriptionID]
		if pass == nil {
			continue
		}

		found := false

		if pass.DeviceID == req.DeviceID {
			found = true
		}

		if !found {
			for _, id := range pass.DeviceIDs {
				if id == req.DeviceID {
					found = true
					break
				}
			}
		}

		if !found {
			continue
		}

		removed := unbindDevices(pass, req.DeviceID)
		purgeRemovedDeviceStatsLocked(removed)
		saveDBLocked()
		resp = AdminActionResponse{
			Success: true,
		}
		break
	}
	dbMutex.Unlock()

	json.NewEncoder(w).Encode(resp)
}

func adminDeviceRenameHandler(w http.ResponseWriter, r *http.Request) {

	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}

	if !requireAdmin(r) {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}

	w.Header().Set("Content-Type", "application/json")

	var req AdminRenameDeviceRequest

	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		json.NewEncoder(w).Encode(AdminActionResponse{
			Success: false,
			Error:   "bad request",
		})
		return
	}

	dbMutex.Lock()
	resp := AdminActionResponse{Success: true}
	dev := db.Devices[req.DeviceID]

	if dev == nil {
		resp = AdminActionResponse{
			Success: false,
			Error:   "device not found",
		}
	} else {
		dev.DeviceName = strings.TrimSpace(req.Name)
		saveDBLocked()
	}
	dbMutex.Unlock()

	json.NewEncoder(w).Encode(resp)
}

func adminDeviceResetTrafficHandler(w http.ResponseWriter, r *http.Request) {

	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}

	if !requireAdmin(r) {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}

	w.Header().Set("Content-Type", "application/json")

	var req AdminDeviceRequest

	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		json.NewEncoder(w).Encode(AdminActionResponse{
			Success: false,
			Error:   "bad request",
		})
		return
	}

	dbMutex.Lock()
	resp := AdminActionResponse{Success: true}
	dev := db.Devices[req.DeviceID]

	if dev == nil {
		resp = AdminActionResponse{
			Success: false,
			Error:   "device not found",
		}
	} else {
		dev.DownBytes = 0
		dev.UpBytes = 0
		saveDBLocked()
	}
	dbMutex.Unlock()

	json.NewEncoder(w).Encode(resp)
}

func adminRemoveDevice(deviceID string) []*ClientDevice {
	removed := []*ClientDevice{}

	for _, user := range db.Users {

		if user.SubscriptionID == "" {
			continue
		}

		pass := db.Passwords[user.SubscriptionID]
		if pass == nil {
			continue
		}

		found := false

		if pass.DeviceID == deviceID {
			found = true
		}

		if !found {
			for _, id := range pass.DeviceIDs {
				if id == deviceID {
					found = true
					break
				}
			}
		}

		if !found {
			continue
		}

		removed = append(removed, unbindDevices(pass, deviceID)...)

		break
	}

	if dev := removeDeviceFromSystem(deviceID); dev != nil {
		removed = append(removed, dev)
	}

	purgeRemovedDeviceStatsLocked(removed)
	saveDBLocked()

	return normalizeRemovedDevices(removed)
}

func adminDeviceDeleteHandler(w http.ResponseWriter, r *http.Request) {

	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}

	if !requireAdmin(r) {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}

	w.Header().Set("Content-Type", "application/json")

	var req AdminDeviceRequest

	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {

		json.NewEncoder(w).Encode(AdminActionResponse{
			Success: false,
			Error:   "bad request",
		})

		return
	}

	dbMutex.Lock()

	if _, ok := db.Devices[req.DeviceID]; !ok {
		dbMutex.Unlock()

		json.NewEncoder(w).Encode(AdminActionResponse{
			Success: false,
			Error:   "device not found",
		})

		return
	}

	removed := adminRemoveDevice(req.DeviceID)
	dbMutex.Unlock()
	applyRemovedDeviceRuntimeState(globalWgDev, removed)

	json.NewEncoder(w).Encode(AdminActionResponse{
		Success: true,
	})
}
