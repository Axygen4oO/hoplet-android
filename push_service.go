package main

// Production FCM integration. Credentials are loaded only from the process
// environment (FCM_SERVICE_ACCOUNT_JSON) or a protected file path
// (FCM_SERVICE_ACCOUNT_FILE); they are never persisted in passwords.json.

import (
	"bytes"
	"context"
	"crypto/rsa"
	"crypto/sha256"
	"crypto/x509"
	"encoding/json"
	"encoding/pem"
	"errors"
	"fmt"
	"io"
	"log"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"time"

	"github.com/golang-jwt/jwt/v5"
)

const (
	pushPlatformAndroid = "android"
	pushDefaultTTL      = 24 * time.Hour
)

type PushPreferences struct {
	Enabled               bool `json:"enabled"`
	SubscriptionUpdates   bool `json:"subscription_updates"`
	SubscriptionReminders bool `json:"subscription_reminders"`
	SecurityAlerts        bool `json:"security_alerts"`
	ProductUpdates        bool `json:"product_updates"`
	Promotions            bool `json:"promotions"`
	SchemaVersion         int  `json:"schema_version,omitempty"`
}

type PushRegistration struct {
	ID string `json:"id"`
	// SubscriptionID and DeviceID are the real ownership tuple.  UserEmail is
	// only a derived compatibility label for the Telegram UI when a server-side
	// account happens to be linked to this subscription.
	SubscriptionID      string            `json:"subscription_id,omitempty"`
	DeviceID            string            `json:"device_id,omitempty"`
	UserEmail           string            `json:"user_email"`
	InstallationID      string            `json:"installation_id"`
	FID                 string            `json:"fid,omitempty"`
	Token               string            `json:"token"`
	Platform            string            `json:"platform"`
	AppVersion          string            `json:"app_version,omitempty"`
	CreatedAt           int64             `json:"created_at"`
	LastSeenAt          int64             `json:"last_seen_at"`
	Enabled             bool              `json:"enabled"`
	Preferences         PushPreferences   `json:"preferences"`
	Metadata            map[string]string `json:"metadata,omitempty"`
	PushCapabilityState string            `json:"push_capability_state,omitempty"`
}

type PushMetrics struct {
	RegistrationsActive    atomic.Int64
	PushAttempted          atomic.Int64
	PushSuccess            atomic.Int64
	PushFailed             atomic.Int64
	InvalidRegistrations   atomic.Int64
	NotificationsGenerated atomic.Int64
	DuplicatesSuppressed   atomic.Int64
}

var pushMetrics PushMetrics

type fcmServiceAccount struct {
	ProjectID   string `json:"project_id"`
	ClientEmail string `json:"client_email"`
	PrivateKey  string `json:"private_key"`
}

type fcmClient struct {
	account fcmServiceAccount
	key     *rsa.PrivateKey
	client  *http.Client
}

// fcmConfigurationStatus validates presence and shape of server-only credentials
// without contacting Google and without logging any secret material.
func fcmConfigurationStatus() bool {
	_, err := newFCMClient()
	return err == nil
}

func newFCMClient() (*fcmClient, error) {
	raw := strings.TrimSpace(os.Getenv("FCM_SERVICE_ACCOUNT_JSON"))
	if raw == "" {
		path := strings.TrimSpace(os.Getenv("FCM_SERVICE_ACCOUNT_FILE"))
		if path == "" {
			return nil, errors.New("FCM credentials are not configured")
		}
		data, err := os.ReadFile(filepath.Clean(path))
		if err != nil {
			return nil, fmt.Errorf("read FCM credentials: %w", err)
		}
		raw = string(data)
	}
	var account fcmServiceAccount
	if err := json.Unmarshal([]byte(raw), &account); err != nil {
		return nil, errors.New("invalid FCM credentials JSON")
	}
	if account.ProjectID == "" || account.ClientEmail == "" || account.PrivateKey == "" {
		return nil, errors.New("incomplete FCM credentials")
	}
	block, _ := pem.Decode([]byte(account.PrivateKey))
	if block == nil {
		return nil, errors.New("invalid FCM private key")
	}
	key, err := x509.ParsePKCS8PrivateKey(block.Bytes)
	if err != nil {
		return nil, errors.New("invalid FCM private key format")
	}
	rsaKey, ok := key.(*rsa.PrivateKey)
	if !ok {
		return nil, errors.New("FCM private key is not RSA")
	}
	return &fcmClient{account: account, key: rsaKey, client: &http.Client{Timeout: 15 * time.Second}}, nil
}

func (c *fcmClient) accessToken() (string, error) {
	now := time.Now()
	claims := jwt.MapClaims{"iss": c.account.ClientEmail, "scope": "https://www.googleapis.com/auth/firebase.messaging", "aud": "https://oauth2.googleapis.com/token", "iat": now.Unix(), "exp": now.Add(time.Hour).Unix()}
	assertion, err := jwt.NewWithClaims(jwt.SigningMethodRS256, claims).SignedString(c.key)
	if err != nil {
		return "", err
	}
	form := url.Values{"grant_type": {"urn:ietf:params:oauth:grant-type:jwt-bearer"}, "assertion": {assertion}}
	resp, err := c.client.PostForm("https://oauth2.googleapis.com/token", form)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	if resp.StatusCode/100 != 2 {
		return "", fmt.Errorf("oauth token HTTP %d", resp.StatusCode)
	}
	var out struct {
		AccessToken string `json:"access_token"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&out); err != nil || out.AccessToken == "" {
		return "", errors.New("oauth token response invalid")
	}
	return out.AccessToken, nil
}

func (c *fcmClient) send(reg *PushRegistration, n NotificationRecord) (bool, bool, error) {
	token, err := c.accessToken()
	if err != nil {
		return false, false, err
	}
	// Normalize transport fields at the boundary so malformed/admin input can
	// never produce an unsafe destination or unbounded payload.
	n.Type = normalizePushType(n.Type)
	n.DeepLink = normalizePushDeepLink(n.DeepLink)
	if len(n.Title) > 200 {
		n.Title = n.Title[:200]
	}
	if len(n.Message) > 4000 {
		n.Message = n.Message[:4000]
	}
	if n.Priority == "" {
		n.Priority = "normal"
	}
	// Data-only high-priority messages are intentional: our service owns
	// presentation in every process state, so foreground/background behavior,
	// channels, deduplication and tap extras are identical. Do not add an
	// android.notification block here: its presence enables Firebase's
	// automatic background renderer and creates a second, empty-looking card.
	payload := buildFCMPayload(reg.Token, n)
	body, _ := json.Marshal(payload)
	endpoint := "https://fcm.googleapis.com/v1/projects/" + url.PathEscape(c.account.ProjectID) + "/messages:send"
	req, _ := http.NewRequest(http.MethodPost, endpoint, bytes.NewReader(body))
	req.Header.Set("Authorization", "Bearer "+token)
	req.Header.Set("Content-Type", "application/json")
	resp, err := c.client.Do(req)
	if err != nil {
		return false, false, err
	}
	defer resp.Body.Close()
	responseBody, _ := io.ReadAll(io.LimitReader(resp.Body, 4096))
	if resp.StatusCode/100 == 2 {
		return true, false, nil
	}
	invalid := resp.StatusCode == http.StatusNotFound || bytes.Contains(bytes.ToUpper(responseBody), []byte("UNREGISTERED")) || bytes.Contains(bytes.ToUpper(responseBody), []byte("INVALID_ARGUMENT"))
	return false, invalid, fmt.Errorf("FCM HTTP %d", resp.StatusCode)
}

// buildFCMPayload is deliberately data-only. Keeping this construction in a
// pure helper makes the transport contract explicit and testable: all
// user-visible fields are delivered through RemoteMessage.data and are
// rendered by HopletFirebaseMessagingService.
func buildFCMPayload(token string, n NotificationRecord) map[string]any {
	revision := n.Revision
	if revision <= 0 {
		revision = n.ID
	}
	return map[string]any{
		"message": map[string]any{
			"token": token,
			"data": map[string]string{
				"notification_id": fmt.Sprint(n.ID),
				"title":           n.Title,
				"message":         n.Message,
				"type":            n.Type,
				"deep_link":       n.DeepLink,
				"priority":        n.Priority,
				"revision":        fmt.Sprint(revision),
			},
			"android": map[string]any{
				"priority": "HIGH",
				"ttl":      fmt.Sprintf("%ds", int64(pushDefaultTTL/time.Second)),
			},
		},
	}
}

func normalizePushType(value string) string {
	v := strings.ToUpper(strings.TrimSpace(value))
	if strings.HasPrefix(v, "SUBSCRIPTION_") {
		switch v {
		case SubscriptionActivated, SubscriptionRenewed, SubscriptionLimitChanged,
			SubscriptionExpiring7D, SubscriptionExpiring3D, SubscriptionExpiring1D,
			SubscriptionExpired, SubscriptionBlocked, SubscriptionRestored:
			return v
		}
	}
	switch v {
	case "SYSTEM", "SECURITY", "SUBSCRIPTION", "PAYMENT", "UPDATE", "MAINTENANCE", "PROMOTION", "SUPPORT", "CUSTOM":
		return v
	default:
		return "CUSTOM"
	}
}

func normalizePushDeepLink(value string) string {
	v := strings.TrimSpace(strings.TrimPrefix(value, "hoplet://"))
	if i := strings.IndexByte(v, '/'); i >= 0 {
		v = v[:i]
	}
	switch strings.ToLower(v) {
	case "none", "notifications", "subscription", "support", "updates":
		return "hoplet://" + strings.ToLower(v)
	default:
		return "hoplet://notifications"
	}
}

type PushDeliveryResult struct {
	Targeted int `json:"targeted"`
	Sent     int `json:"sent"`
	Failed   int `json:"failed"`
	Invalid  int `json:"invalid"`
	Removed  int `json:"removed"`
}

func (r *PushDeliveryResult) add(other PushDeliveryResult) {
	r.Targeted += other.Targeted
	r.Sent += other.Sent
	r.Failed += other.Failed
	r.Invalid += other.Invalid
	r.Removed += other.Removed
}

func sendPushToRegistrations(regs []*PushRegistration, n NotificationRecord) PushDeliveryResult {
	result := PushDeliveryResult{}
	if len(regs) == 0 {
		return result
	}
	client, err := newFCMClient()
	if err != nil {
		result.Targeted = len(regs)
		result.Failed = len(regs)
		pushMetrics.PushFailed.Add(int64(len(regs)))
		log.Printf("[PUSH] send failed: FCM is not configured")
		return result
	}
	for _, reg := range regs {
		if reg == nil || !reg.Enabled || reg.Token == "" || !pushPreferenceAllows(reg, n) {
			continue
		}
		result.Targeted++
		pushMetrics.PushAttempted.Add(1)
		sent, invalid, sendErr := client.send(reg, n)
		if sent {
			result.Sent++
			pushMetrics.PushSuccess.Add(1)
			continue
		}
		if invalid {
			result.Invalid++
			result.Removed++
			pushMetrics.InvalidRegistrations.Add(1)
			invalidatePushRegistration(reg.ID)
		} else {
			result.Failed++
			pushMetrics.PushFailed.Add(1)
		}
		if sendErr != nil {
			log.Printf("[PUSH] send failed registration=%s reason=%v", shortPushID(reg.ID), sendErr)
		}
	}
	return result
}

func pushPreferenceAllows(reg *PushRegistration, n NotificationRecord) bool {
	if reg == nil || !reg.Preferences.Enabled {
		return false
	}
	prefs := reg.Preferences
	if prefs.SchemaVersion == 0 && !prefs.SubscriptionReminders && !prefs.SecurityAlerts && !prefs.ProductUpdates && !prefs.Promotions {
		prefs.SubscriptionReminders, prefs.SecurityAlerts, prefs.ProductUpdates = true, true, true
	}
	t := strings.ToUpper(strings.TrimSpace(n.Type))
	switch {
	case strings.HasPrefix(t, "SECURITY"):
		return prefs.SecurityAlerts
	case t == SubscriptionExpiring7D || t == SubscriptionExpiring3D || t == SubscriptionExpiring1D || t == SubscriptionExpired || t == NotificationTypeSubscription:
		return prefs.SubscriptionReminders
	case t == SubscriptionActivated || t == SubscriptionRenewed || t == SubscriptionLimitChanged || t == SubscriptionBlocked || t == SubscriptionRestored:
		// Registrations written by pre-split clients had no updates field.
		if prefs.SchemaVersion < 2 {
			return true
		}
		return prefs.SubscriptionUpdates
	case strings.HasPrefix(t, "UPDATE"):
		return prefs.ProductUpdates
	case strings.HasPrefix(t, "PROMOTION"):
		return prefs.Promotions
	default:
		return true
	}
}

func shortPushID(value string) string {
	digest := sha256.Sum256([]byte(value))
	return fmt.Sprintf("%x", digest[:4])
}

type pushSubscriptionIdentity struct {
	ID       string
	DeviceID string
	Email    string
}

// Push authentication deliberately uses the same subscription password that
// authenticates /api/profile/status and the tunnel.  User JWTs and the server
// MainPassword are not accepted here.
func pushSubscriptionFromRequest(r *http.Request, deviceID string, bind bool) (pushSubscriptionIdentity, bool) {
	token := strings.TrimSpace(getTokenFromRequest(r))
	deviceID = strings.TrimSpace(deviceID)
	if token == "" || deviceID == "" {
		log.Printf("[PUSH_AUTH] AUTH_LOOKUP_FOUND=false AUTH_SUBSCRIPTION_FOUND=false AUTH_EXPIRED=false AUTH_BLOCKED=false DEVICE_BOUND=false DEVICE_LIMIT_OK=false PUSH_AUTH_RESULT=FAIL")
		return pushSubscriptionIdentity{}, false
	}
	dbMutex.Lock()
	defer dbMutex.Unlock()
	entry, ok := db.Passwords[token]
	authLookupFound := ok && entry != nil
	authExpired := entry != nil && isPasswordExpired(entry)
	authBlocked := entry != nil && entry.IsDeactivated
	deviceBound := false
	if entry != nil {
		if entry.DeviceID == deviceID {
			deviceBound = true
		} else {
			for _, id := range entry.DeviceIDs {
				if id == deviceID {
					deviceBound = true
					break
				}
			}
		}
	}
	if !authLookupFound || authBlocked || authExpired {
		log.Printf("[PUSH_AUTH] AUTH_LOOKUP_FOUND=%t AUTH_SUBSCRIPTION_FOUND=%t AUTH_EXPIRED=%t AUTH_BLOCKED=%t DEVICE_BOUND=%t DEVICE_LIMIT_OK=false PUSH_AUTH_RESULT=FAIL", authLookupFound, authLookupFound, authExpired, authBlocked, deviceBound)
		return pushSubscriptionIdentity{}, false
	}
	deviceLimitOK := true
	if bind {
		deviceLimitOK = entry.canConnectAndBind(deviceID)
	}
	if !deviceLimitOK {
		log.Printf("[PUSH_AUTH] AUTH_LOOKUP_FOUND=true AUTH_SUBSCRIPTION_FOUND=true AUTH_EXPIRED=false AUTH_BLOCKED=false DEVICE_BOUND=%t DEVICE_LIMIT_OK=false PUSH_AUTH_RESULT=FAIL", deviceBound)
		return pushSubscriptionIdentity{}, false
	}
	log.Printf("[PUSH_AUTH] AUTH_LOOKUP_FOUND=true AUTH_SUBSCRIPTION_FOUND=true AUTH_EXPIRED=false AUTH_BLOCKED=false DEVICE_BOUND=%t DEVICE_LIMIT_OK=true PUSH_AUTH_RESULT=PASS", deviceBound)
	if bind {
		saveDBLocked()
	}
	email := ""
	if linked, linkedOK := findUserBySubscriptionID(token); linkedOK && linked != nil {
		email = normalizeUserEmail(linked.Email)
	}
	return pushSubscriptionIdentity{ID: token, DeviceID: deviceID, Email: email}, true
}

func pushRegistrationID(subscriptionID, deviceID string) string {
	digest := sha256.Sum256([]byte(strings.TrimSpace(subscriptionID) + "\x00" + strings.TrimSpace(deviceID)))
	return fmt.Sprintf("sub_%x", digest[:])
}

func publicPushRegistration(reg *PushRegistration) *PushRegistration {
	if reg == nil {
		return nil
	}
	copy := *reg
	copy.SubscriptionID = ""
	copy.Token = ""
	copy.FID = ""
	return &copy
}

type pushRegisterRequest struct {
	InstallationID string            `json:"installation_id"`
	DeviceID       string            `json:"device_id"`
	FID            string            `json:"fid"`
	Token          string            `json:"token"`
	FCMToken       string            `json:"fcm_token"`
	AppVersion     string            `json:"app_version"`
	Platform       string            `json:"platform"`
	Enabled        *bool             `json:"enabled"`
	Preferences    *PushPreferences  `json:"preferences"`
	Metadata       map[string]string `json:"metadata"`
}

func pushRegisterHandler(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", 405)
		return
	}
	var req pushRegisterRequest
	if json.NewDecoder(io.LimitReader(r.Body, 64<<10)).Decode(&req) != nil {
		http.Error(w, "invalid JSON", 400)
		return
	}
	req.InstallationID = strings.TrimSpace(req.InstallationID)
	req.Token = strings.TrimSpace(req.Token)
	if req.Token == "" {
		req.Token = strings.TrimSpace(req.FCMToken)
	}
	req.DeviceID = strings.TrimSpace(req.DeviceID)
	if req.InstallationID == "" || req.DeviceID == "" || req.Token == "" || len(req.Token) > 4096 || (req.Platform != "" && req.Platform != pushPlatformAndroid) {
		http.Error(w, "installation_id, device_id, token and android platform are required", 400)
		return
	}
	identity, ok := pushSubscriptionFromRequest(r, req.DeviceID, true)
	if !ok {
		http.Error(w, "invalid subscription or device", http.StatusUnauthorized)
		return
	}
	if req.Platform == "" {
		req.Platform = pushPlatformAndroid
	}
	now := time.Now().Unix()
	id := pushRegistrationID(identity.ID, identity.DeviceID)
	dbMutex.Lock()
	if db.PushRegistrations == nil {
		db.PushRegistrations = map[string]*PushRegistration{}
	}
	existing := db.PushRegistrations[id]
	reg := &PushRegistration{ID: id, SubscriptionID: identity.ID, DeviceID: identity.DeviceID, UserEmail: identity.Email, InstallationID: req.InstallationID, FID: strings.TrimSpace(req.FID), Token: req.Token, Platform: req.Platform, AppVersion: strings.TrimSpace(req.AppVersion), CreatedAt: now, LastSeenAt: now, Enabled: true, Preferences: PushPreferences{Enabled: true, SubscriptionUpdates: true, SubscriptionReminders: true, SecurityAlerts: true, ProductUpdates: true, Promotions: false, SchemaVersion: 2}, Metadata: req.Metadata, PushCapabilityState: "available"}
	if existing != nil {
		reg.CreatedAt = existing.CreatedAt
		reg.Preferences = existing.Preferences
	}
	if req.Enabled != nil {
		reg.Enabled = *req.Enabled
		reg.Preferences.Enabled = *req.Enabled
	}
	if req.Preferences != nil {
		reg.Preferences = *req.Preferences
		reg.Enabled = reg.Preferences.Enabled
	}
	// A device can move to a different imported subscription. Disable the old
	// tuple so one physical installation cannot remain attached to two owners.
	for oldID, candidate := range db.PushRegistrations {
		if oldID != id && candidate != nil && (candidate.DeviceID == identity.DeviceID || candidate.InstallationID == req.InstallationID) && candidate.Enabled {
			candidate.Enabled = false
			candidate.Preferences.Enabled = false
			candidate.PushCapabilityState = "disabled"
		}
	}
	db.PushRegistrations[id] = reg
	active := int64(0)
	for _, candidate := range db.PushRegistrations {
		if candidate != nil && candidate.Enabled && candidate.Preferences.Enabled {
			active++
		}
	}
	pushMetrics.RegistrationsActive.Store(active)
	saveDBLocked()
	dbMutex.Unlock()
	log.Printf("[PUSH] registration %s id=%s", map[bool]string{true: "refreshed", false: "added"}[existing != nil], shortPushID(id))
	writeJSON(w, map[string]any{"success": true, "registration": publicPushRegistration(reg)})
}

func pushUnregisterHandler(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", 405)
		return
	}
	var req struct {
		InstallationID string `json:"installation_id"`
		DeviceID       string `json:"device_id"`
	}
	_ = json.NewDecoder(r.Body).Decode(&req)
	identity, ok := pushSubscriptionFromRequest(r, req.DeviceID, false)
	if !ok {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}
	id := pushRegistrationID(identity.ID, req.DeviceID)
	dbMutex.Lock()
	if reg := db.PushRegistrations[id]; reg != nil {
		reg.Enabled = false
		reg.Preferences.Enabled = false
		reg.LastSeenAt = time.Now().Unix()
		saveDBLocked()
	}
	dbMutex.Unlock()
	writeJSON(w, map[string]any{"success": true})
}

func pushPreferencesHandler(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", 405)
		return
	}
	var req struct {
		InstallationID string          `json:"installation_id"`
		DeviceID       string          `json:"device_id"`
		Preferences    PushPreferences `json:"preferences"`
	}
	if json.NewDecoder(r.Body).Decode(&req) != nil {
		http.Error(w, "invalid JSON", 400)
		return
	}
	identity, ok := pushSubscriptionFromRequest(r, req.DeviceID, false)
	if !ok {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}
	dbMutex.Lock()
	reg := db.PushRegistrations[pushRegistrationID(identity.ID, req.DeviceID)]
	if reg == nil {
		dbMutex.Unlock()
		http.Error(w, "registration not found", 404)
		return
	}
	reg.Preferences = req.Preferences
	reg.Enabled = req.Preferences.Enabled
	reg.LastSeenAt = time.Now().Unix()
	saveDBLocked()
	dbMutex.Unlock()
	writeJSON(w, map[string]any{"success": true})
}

func pushStatusHandler(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed", 405)
		return
	}
	deviceID := strings.TrimSpace(r.URL.Query().Get("device_id"))
	identity, ok := pushSubscriptionFromRequest(r, deviceID, false)
	if !ok {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}
	dbMutex.Lock()
	regs := make([]*PushRegistration, 0)
	for _, reg := range db.PushRegistrations {
		if reg != nil && reg.SubscriptionID == identity.ID && reg.DeviceID == identity.DeviceID {
			regs = append(regs, publicPushRegistration(reg))
		}
	}
	dbMutex.Unlock()
	active := 0
	for _, reg := range regs {
		if reg.Enabled && reg.Preferences.Enabled {
			active++
		}
	}
	writeJSON(w, map[string]any{"registrations": regs, "active": active})
}

func writeJSON(w http.ResponseWriter, value any) {
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(value)
}

func invalidatePushRegistration(id string) {
	dbMutex.Lock()
	defer dbMutex.Unlock()
	if reg := db.PushRegistrations[id]; reg != nil {
		wasActive := reg.Enabled && reg.Preferences.Enabled
		reg.Enabled = false
		reg.Preferences.Enabled = false
		reg.PushCapabilityState = "invalid"
		if wasActive {
			pushMetrics.RegistrationsActive.Add(-1)
		}
		saveDBLocked()
		log.Printf("[PUSH] registration invalidated id=%s", shortPushID(id))
	}
}

func sendPushToUser(email string, n NotificationRecord) PushDeliveryResult {
	dbMutex.Lock()
	regs := make([]*PushRegistration, 0)
	for _, reg := range db.PushRegistrations {
		if reg != nil && reg.UserEmail == normalizeUserEmail(email) && reg.Enabled && reg.Preferences.Enabled {
			copy := *reg
			regs = append(regs, &copy)
		}
	}
	dbMutex.Unlock()
	return sendPushToRegistrations(regs, n)
}

// Exported service surface used by notification engines and admin tooling.
func SendPush(n NotificationRecord) PushDeliveryResult { return sendPushToAudience(n) }
func SendPushToUser(email string, n NotificationRecord) PushDeliveryResult {
	return sendPushToUser(email, n)
}
func SendPushToDevice(registrationID string, n NotificationRecord) PushDeliveryResult {
	dbMutex.Lock()
	reg := db.PushRegistrations[registrationID]
	var copy *PushRegistration
	if reg != nil {
		value := *reg
		copy = &value
	}
	dbMutex.Unlock()
	if copy == nil {
		return PushDeliveryResult{}
	}
	return sendPushToRegistrations([]*PushRegistration{copy}, n)
}
func SendPushToMultiple(registrationIDs []string, n NotificationRecord) PushDeliveryResult {
	dbMutex.Lock()
	regs := make([]*PushRegistration, 0, len(registrationIDs))
	for _, id := range registrationIDs {
		if reg := db.PushRegistrations[id]; reg != nil {
			value := *reg
			regs = append(regs, &value)
		}
	}
	dbMutex.Unlock()
	return sendPushToRegistrations(regs, n)
}
func InvalidatePushRegistration(id string) { invalidatePushRegistration(id) }

func sendPushToAudience(n NotificationRecord) PushDeliveryResult {
	regs := snapshotActivePushRegistrations()
	if len(regs) == 0 {
		return PushDeliveryResult{}
	}
	log.Printf("[PUSH] send started audience=%s targeted=%d", n.Audience, len(regs))
	result := sendPushToRegistrations(regs, n)
	log.Printf("[PUSH] send result targeted=%d sent=%d failed=%d invalid=%d removed=%d", result.Targeted, result.Sent, result.Failed, result.Invalid, result.Removed)
	return result
}

func snapshotActivePushRegistrations() []*PushRegistration {
	dbMutex.Lock()
	defer dbMutex.Unlock()
	if db == nil {
		return nil
	}
	regs := make([]*PushRegistration, 0, len(db.PushRegistrations))
	for _, reg := range db.PushRegistrations {
		if reg != nil && reg.Enabled && reg.Preferences.Enabled {
			copy := *reg
			regs = append(regs, &copy)
		}
	}
	return regs
}

func pushReminderScheduler(ctx context.Context) {
	ticker := time.NewTicker(30 * time.Minute)
	defer ticker.Stop()
	run := func() {
		now := time.Now()
		for _, days := range []int{7, 3, 1} {
			generateSubscriptionReminders(now, days)
		}
		generateSubscriptionReminders(now, 0)
	}
	run()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			run()
		}
	}
}

func generateSubscriptionReminders(now time.Time, days int) {
	dbMutex.Lock()
	defer dbMutex.Unlock()
	for subscriptionID, entry := range db.Passwords {
		if entry == nil || entry.ExpiresAt <= 0 {
			continue
		}
		expiry := time.Unix(entry.ExpiresAt, 0)
		localExpiry := expiry.In(time.Local)
		localNow := now.In(time.Local)
		targetDate := localNow.AddDate(0, 0, days)
		if days == 0 {
			if !expiry.Before(now) {
				continue
			}
		} else {
			if entry.IsDeactivated || localExpiry.Year() != targetDate.Year() || localExpiry.YearDay() != targetDate.YearDay() {
				continue
			}
		}
		typ := SubscriptionExpired
		title, message := "Подписка закончилась", "Срок действия подписки истёк.\nДоступ к VPN приостановлен."
		if days > 0 {
			typ = fmt.Sprintf("SUBSCRIPTION_EXPIRING_%dD", days)
			title = "Подписка скоро истекает"
			message = fmt.Sprintf("Срок действия подписки истекает через %d дн.\nДействительна до %s.", days, subscriptionDate(entry.ExpiresAt))
		}
		key := fmt.Sprintf("%s|%s|%s", subscriptionIdentityKey(subscriptionID), typ, localExpiry.Format("2006-01-02"))
		_, created := createSubscriptionNotificationLocked(subscriptionID, subscriptionEvent{typ: typ, title: title, message: message, key: key}, "subscription_scheduler")
		if created {
			log.Printf("[PUSH] reminder generated type=%s identity=%s", typ, shortPushID(subscriptionID))
		}
	}
}
