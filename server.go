package main

import (
	"bytes"
	"context"
	"crypto/cipher"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"log"
	"mime/multipart"
	"net"
	"net/http"
	"os"
	"os/exec"
	"os/signal"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"time"

	"github.com/pion/dtls/v3"
	"github.com/pion/dtls/v3/pkg/crypto/selfsign"
	"golang.org/x/crypto/chacha20poly1305"
	"golang.org/x/crypto/curve25519"
	"golang.org/x/crypto/hkdf"

	"golang.zx2c4.com/wireguard/conn"
	"golang.zx2c4.com/wireguard/device"
	"golang.zx2c4.com/wireguard/tun"

	dtlsnet "github.com/pion/dtls/v3/pkg/net"
	pionudp "github.com/pion/transport/v4/udp"
)

const (
	wgIfaceName           = "wdtt0"
	wgServerAddr          = "10.66.66.1"
	wgClientAddr          = "10.66.66.2"
	wgClientCIDR          = wgClientAddr + "/32"
	wgServerCIDR          = wgServerAddr + "/16"
	defaultInternalWGPort = 56001
	wgMTU                 = 1280
	keepalive             = 25
)

var dns = "8.8.8.8"

// ==================== База данных и Бот ====================

type ClientDevice struct {
	DeviceID   string `json:"device_id"`
	DeviceName string `json:"device_name,omitempty"`
	IP         string `json:"ip"`
	PrivKey    string `json:"priv_key"`
	PubKey     string `json:"pub_key"`
	DownBytes  int64  `json:"down_bytes"`
	UpBytes    int64  `json:"up_bytes"`
	LastSeenAt int64  `json:"last_seen_at,omitempty"`
}

type PasswordEntry struct {
	Label         string   `json:"label,omitempty"` // понятное имя в боте
	DeviceID      string   `json:"device_id"`       // Для обратной совместимости, если нужно
	DeviceIDs     []string `json:"device_ids"`      // Список привязанных deviceID
	MaxDevices    int      `json:"max_devices"`     // Максимальное кол-во устройств (0 или 1 = 1 устройство)
	ExpiresAt     int64    `json:"expires_at"`      // unix timestamp
	DownBytes     int64    `json:"down_bytes"`      // скачано клиентом
	UpBytes       int64    `json:"up_bytes"`        // отдано клиентом
	VkHash        string   `json:"vk_hash,omitempty"`
	Ports         string   `json:"ports,omitempty"` // "dtls,wg,tun"
	IsDeactivated bool     `json:"is_deactivated,omitempty"`
}

func (entry *PasswordEntry) canConnectAndBind(deviceID string) bool {
	limit := entry.MaxDevices
	if limit <= 0 {
		limit = 1
	}

	// Сначала проверяем, привязано ли уже это устройство
	for _, id := range entry.DeviceIDs {
		if id == deviceID {
			return true
		}
	}

	// Для обратной совместимости
	if len(entry.DeviceIDs) == 0 && entry.DeviceID != "" {
		if entry.DeviceID == deviceID {
			entry.DeviceIDs = []string{deviceID}
			return true
		}
		if limit == 1 {
			return false
		}
		entry.DeviceIDs = append(entry.DeviceIDs, entry.DeviceID)
	}

	// Если есть свободное место под новое устройство
	if len(entry.DeviceIDs) < limit {
		entry.DeviceIDs = append(entry.DeviceIDs, deviceID)
		if len(entry.DeviceIDs) == 1 {
			entry.DeviceID = deviceID
		} else {
			entry.DeviceID = "multi"
		}
		return true
	}

	return false
}

// Трафик главного пароля (владельца)
var (
	mainPassDown int64
	mainPassUp   int64
)

// Онлайн-статус устройств
var (
	activeDevices   = make(map[string]int32) // deviceID -> кол-во активных коннектов
	activeDevicesMu sync.Mutex
)

type UserAccount struct {
	Email        string `json:"email"`
	PasswordHash string `json:"password_hash"`

	CreatedAt int64 `json:"created_at"`

	Role string `json:"role,omitempty"`

	TelegramID int64 `json:"telegram_id,omitempty"`

	TelegramAuthToken     string `json:"telegram_auth_token,omitempty"`
	TelegramAuthExpiresAt int64  `json:"telegram_auth_expires_at,omitempty"`

	SubscriptionStatus string `json:"subscription_status"`

	SubscriptionPlan string `json:"subscription_plan"`

	SubscriptionExpires int64 `json:"subscription_expires"`

	DeviceLimit int `json:"device_limit"`

	SubscriptionID string `json:"subscription_id"`

	Notify7Days bool   `json:"notify_7_days,omitempty"`
	Notify3Days bool   `json:"notify_3_days,omitempty"`
	NotifyNews  bool   `json:"notify_news,omitempty"`
	Language    string `json:"language,omitempty"`
}

type Database struct {
	MainPassword string `json:"main_password"`
	JWTSecret    string `json:"jwt_secret"`
	AdminID      string `json:"admin_id"`
	BotToken     string `json:"bot_token"`

	Passwords map[string]*PasswordEntry `json:"passwords"`
	Devices   map[string]*ClientDevice  `json:"devices"`

	Users            map[string]*UserAccount   `json:"users"`
	Orders           map[string]*Order         `json:"orders"`
	SupportTickets   map[string]*SupportTicket `json:"support_tickets"`
	VKHashes         []string                  `json:"vk_hashes"`
	LastNotification AppNotification           `json:"last_notification"`
}

type dbSaveState struct {
	mu              sync.Mutex
	cond            *sync.Cond
	started         bool
	pending         *Database
	pendingVersion  uint64
	inFlightVersion uint64
	writtenVersion  uint64
}

var (
	db          *Database
	dbMutex     sync.Mutex
	dbFile      string
	globalWgDev *device.Device
	asyncDBSave = newDBSaveState()
)
var yookassaShopID string
var yookassaSecretKey string
var telegramPaymentsProviderToken string
var botTokenGlobal string
var botAdminIDGlobal int64
var expiredSubscriptions []string

var serverWrapKeys = newWrapKeyStore()

func newDBSaveState() *dbSaveState {
	state := &dbSaveState{}
	state.cond = sync.NewCond(&state.mu)
	return state
}

func (s *dbSaveState) ensureWorker() {
	s.mu.Lock()
	if s.started {
		s.mu.Unlock()
		return
	}
	s.started = true
	s.mu.Unlock()

	go s.loop()
}

func (s *dbSaveState) loop() {
	for {
		s.mu.Lock()
		for s.pending == nil {
			s.cond.Wait()
		}

		snapshot := s.pending
		version := s.pendingVersion
		s.pending = nil
		s.inFlightVersion = version
		s.mu.Unlock()

		if err := writeDBSnapshot(snapshot); err != nil {
			log.Printf("[DB] async save failed: %v", err)
		}

		s.mu.Lock()
		if version > s.writtenVersion {
			s.writtenVersion = version
		}
		if s.inFlightVersion == version {
			s.inFlightVersion = 0
		}
		s.cond.Broadcast()
		s.mu.Unlock()
	}
}

func (s *dbSaveState) queue(snapshot *Database) {
	if snapshot == nil {
		return
	}

	s.ensureWorker()

	s.mu.Lock()
	s.pending = snapshot
	s.pendingVersion++
	s.cond.Signal()
	s.mu.Unlock()
}

func (s *dbSaveState) wait() {
	s.mu.Lock()
	target := s.pendingVersion
	if s.inFlightVersion > target {
		target = s.inFlightVersion
	}
	for s.writtenVersion < target {
		s.cond.Wait()
	}
	s.mu.Unlock()
}

const (
	passChars             = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789"
	generatedPasswordLen  = 16
	maxGeneratedPasswords = 99999
)

func generatePassword() string {
	b := make([]byte, generatedPasswordLen)
	randomBytes := make([]byte, len(b))
	if _, err := rand.Read(randomBytes); err != nil {
		now := time.Now().UnixNano()
		for i := range b {
			b[i] = passChars[int(now+int64(i))%len(passChars)]
		}
		return string(b)
	}
	for i, raw := range randomBytes {
		b[i] = passChars[int(raw)%len(passChars)]
	}
	return string(b)
}

func passwordEntryLabel(entry *PasswordEntry, pass string, index int) string {
	if entry != nil {
		if label := strings.TrimSpace(entry.Label); label != "" {
			return label
		}
	}
	if len(pass) >= 4 {
		return fmt.Sprintf("Доступ …%s", pass[len(pass)-4:])
	}
	return fmt.Sprintf("Доступ #%d", index)
}

func nextPasswordLabel() string {
	return fmt.Sprintf("Доступ %d", len(db.Passwords)+1)
}

var publicIP string = ""

func getPublicIP() string {
	if publicIP != "" {
		return publicIP
	}
	client := &http.Client{Timeout: 5 * time.Second}
	resp, err := client.Get("https://api.ipify.org")
	if err != nil {
		return "YOUR_SERVER_IP"
	}
	defer resp.Body.Close()
	ipBytes, err := io.ReadAll(resp.Body)
	if err != nil {
		return "YOUR_SERVER_IP"
	}
	publicIP = string(bytes.TrimSpace(ipBytes))
	return publicIP
}

func stripVkUrl(url string) string {
	url = strings.TrimSpace(url)
	if idx := strings.LastIndex(url, "/"); idx != -1 {
		url = url[idx+1:]
	}
	if idx := strings.Index(url, "?"); idx != -1 {
		url = url[:idx]
	}
	return strings.TrimSpace(url)
}

type wrapKeyEntry struct {
	id  string
	key []byte
}

type wrapKeyStore struct {
	mu      sync.RWMutex
	entries []wrapKeyEntry
}

func newWrapKeyStore() *wrapKeyStore {
	return &wrapKeyStore{}
}

func deriveWrapKey(password string) ([]byte, error) {
	if password == "" {
		return nil, errors.New("empty password")
	}
	key := make([]byte, wrapKeyLen)
	reader := hkdf.New(
		sha256.New,
		[]byte(password),
		[]byte("WDTT-WRAP-v1"),
		[]byte("rtp-obfs/chacha20poly1305"),
	)
	if _, err := io.ReadFull(reader, key); err != nil {
		return nil, fmt.Errorf("derive wrap key: %w", err)
	}
	return key, nil
}

func wrapKeyID(password string) string {
	sum := sha256.Sum256([]byte("WDTT-WRAP-ID-v1\x00" + password))
	return hex.EncodeToString(sum[:8])
}

func zeroBytes(b []byte) {
	for i := range b {
		b[i] = 0
	}
}

func (s *wrapKeyStore) SetPasswords(mainPassword string, generated []string) error {
	next := make([]wrapKeyEntry, 0, len(generated)+1)
	seen := make(map[string]struct{}, len(generated)+1)

	if mainPassword != "" {
		key, err := deriveWrapKey(mainPassword)
		if err != nil {
			return err
		}
		next = append(next, wrapKeyEntry{id: "main", key: key})
		seen["main"] = struct{}{}
	}

	for _, password := range generated {
		if password == "" {
			continue
		}
		id := "pass:" + wrapKeyID(password)
		if _, exists := seen[id]; exists {
			continue
		}
		key, err := deriveWrapKey(password)
		if err != nil {
			for _, entry := range next {
				zeroBytes(entry.key)
			}
			return err
		}
		next = append(next, wrapKeyEntry{id: id, key: key})
		seen[id] = struct{}{}
	}

	s.mu.Lock()
	old := s.entries
	s.entries = next
	s.mu.Unlock()

	log.Printf(
		"[WRAP] Loaded keys: %d (generated=%d, main=%t)",
		len(next),
		len(generated),
		mainPassword != "",
	)

	for _, entry := range old {
		zeroBytes(entry.key)
	}

	return nil
}

func (s *wrapKeyStore) AddPassword(password string) error {
	key, err := deriveWrapKey(password)
	if err != nil {
		return err
	}
	id := "pass:" + wrapKeyID(password)

	s.mu.Lock()
	defer s.mu.Unlock()
	for _, entry := range s.entries {
		if entry.id == id {
			zeroBytes(key)
			return nil
		}
	}
	s.entries = append(s.entries, wrapKeyEntry{id: id, key: key})
	return nil
}

func (s *wrapKeyStore) RemovePassword(password string) {
	id := "pass:" + wrapKeyID(password)

	s.mu.Lock()
	defer s.mu.Unlock()
	for i, entry := range s.entries {
		if entry.id != id {
			continue
		}
		zeroBytes(entry.key)
		copy(s.entries[i:], s.entries[i+1:])
		s.entries[len(s.entries)-1] = wrapKeyEntry{}
		s.entries = s.entries[:len(s.entries)-1]
		return
	}
}

func (s *wrapKeyStore) Count() int {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return len(s.entries)
}

func (s *wrapKeyStore) Unwrap(raw, dst []byte) ([]byte, int, error) {
	if !obfsIsRTPPacket(raw) {
		return nil, 0, errors.New("wrap: non-obfs packet")
	}

	s.mu.RLock()
	defer s.mu.RUnlock()
	if len(s.entries) == 0 {
		return nil, 0, errors.New("wrap: no active keys")
	}
	log.Printf("[WRAP] Active keys: %d", len(s.entries))

	for _, entry := range s.entries {
		log.Printf("[WRAP] Trying key: %s", entry.id)

		m, err := obfsUnwrapPacket(entry.key, raw, dst)
		if err == nil {
			log.Printf("[WRAP] SUCCESS with key: %s", entry.id)
			return append([]byte(nil), entry.key...), m, nil
		}
	}
	return nil, 0, errors.New("wrap: auth failed")
}

func snapshotWrapPasswordsLocked() (string, []string) {
	log.Printf("[WRAP] Refreshing keys, passwords in DB: %d", len(db.Passwords))

	passwords := make([]string, 0, len(db.Passwords))

	for password, entry := range db.Passwords {
		if !isPasswordExpired(entry) {
			passwords = append(passwords, password)
		}
	}

	log.Printf("[WRAP] Active passwords after filter: %d", len(passwords))

	return db.MainPassword, passwords
}

func refreshWrapKeys(mainPassword string, passwords []string) error {
	return serverWrapKeys.SetPasswords(mainPassword, passwords)
}

func refreshWrapKeysFromDB() error {
	dbMutex.Lock()
	mainPassword, passwords := snapshotWrapPasswordsLocked()
	dbMutex.Unlock()
	return refreshWrapKeys(mainPassword, passwords)
}

func reloadDB(wgDev *device.Device) error {
	asyncDBSave.wait()
	data, err := os.ReadFile(dbFile)
	if err != nil {
		return fmt.Errorf("read db file: %w", err)
	}

	newDB := &Database{
		Passwords:      make(map[string]*PasswordEntry),
		Devices:        make(map[string]*ClientDevice),
		Users:          make(map[string]*UserAccount),
		Orders:         make(map[string]*Order),
		SupportTickets: make(map[string]*SupportTicket),
	}
	if err := json.Unmarshal(data, newDB); err != nil {
		return fmt.Errorf("parse db json: %w", err)
	}

	if newDB.Passwords == nil {
		newDB.Passwords = make(map[string]*PasswordEntry)
	}
	if newDB.Devices == nil {
		newDB.Devices = make(map[string]*ClientDevice)
	}
	if newDB.Users == nil {
		newDB.Users = make(map[string]*UserAccount)
	}
	if newDB.Orders == nil {
		newDB.Orders = make(map[string]*Order)
	}
	if newDB.SupportTickets == nil {
		newDB.SupportTickets = make(map[string]*SupportTicket)
	}
	if newDB.VKHashes == nil {
		newDB.VKHashes = []string{}
	}

	dbMutex.Lock()
	oldDB := db

	newDB.MainPassword = oldDB.MainPassword
	if newDB.JWTSecret == "" {
		newDB.JWTSecret = oldDB.JWTSecret
	}
	newDB.AdminID = oldDB.AdminID
	newDB.BotToken = oldDB.BotToken

	db = newDB
	if ensureJWTSecretLocked() {
		saveDBLocked()
	}

	expiredRemovedCount, expiredRemovedDevices := cleanupExpiredPasswordsLocked()
	removedDevices := make([]*ClientDevice, 0, len(oldDB.Devices))
	for devID, oldDev := range oldDB.Devices {
		if _, exists := db.Devices[devID]; !exists {
			removedDevices = append(removedDevices, oldDev)
		}
	}
	purgeRemovedDeviceStatsLocked(append(expiredRemovedDevices, removedDevices...))
	if expiredRemovedCount > 0 {
		saveDBLocked()
	}

	persistedDevices := collectActiveWGDevicesLocked()
	wrapMainPassword, wrapPasswords := snapshotWrapPasswordsLocked()
	dbMutex.Unlock()

	if err := refreshWrapKeys(wrapMainPassword, wrapPasswords); err != nil {
		return fmt.Errorf("refresh wrap keys: %w", err)
	}

	applyRemovedDeviceRuntimeState(wgDev, append(expiredRemovedDevices, removedDevices...))
	for _, dev := range persistedDevices {
		upsertPeerInWG(wgDev, dev)
	}

	return nil
}

func initDB(dir, mainPass, adminID, botToken string) {
	dbFile = filepath.Join(dir, "passwords.json")
	db = &Database{
		Passwords:      make(map[string]*PasswordEntry),
		Devices:        make(map[string]*ClientDevice),
		Users:          make(map[string]*UserAccount),
		Orders:         make(map[string]*Order),
		SupportTickets: make(map[string]*SupportTicket),
	}

	data, err := os.ReadFile(dbFile)
	if err == nil {
		json.Unmarshal(data, db)
	}

	if db.Passwords == nil {
		db.Passwords = make(map[string]*PasswordEntry)
	}

	if db.Devices == nil {
		db.Devices = make(map[string]*ClientDevice)
	}

	if db.Users == nil {
		db.Users = make(map[string]*UserAccount)
	}

	if db.Orders == nil {
		db.Orders = make(map[string]*Order)
	}

	if db.SupportTickets == nil {
		db.SupportTickets = make(map[string]*SupportTicket)
	}

	if db.VKHashes == nil {
		db.VKHashes = []string{}
	}

	db.MainPassword = mainPass
	db.AdminID = adminID
	db.BotToken = botToken
	ensureJWTSecretLocked()

	saveDB()
	if err := refreshWrapKeysFromDB(); err != nil {
		log.Fatalf("[WRAP] init keys: %v", err)
	}
}

func clonePasswordEntry(entry *PasswordEntry) *PasswordEntry {
	if entry == nil {
		return nil
	}

	cloned := *entry
	cloned.DeviceIDs = append([]string(nil), entry.DeviceIDs...)
	return &cloned
}

func cloneClientDevice(dev *ClientDevice) *ClientDevice {
	if dev == nil {
		return nil
	}

	cloned := *dev
	return &cloned
}

func cloneUserAccount(user *UserAccount) *UserAccount {
	if user == nil {
		return nil
	}

	cloned := *user
	return &cloned
}

func cloneOrder(order *Order) *Order {
	if order == nil {
		return nil
	}

	cloned := *order
	return &cloned
}

func cloneSupportAIHint(hint *SupportAIHint) *SupportAIHint {
	if hint == nil {
		return nil
	}

	cloned := *hint
	cloned.SimilarTicketIDs = append([]string(nil), hint.SimilarTicketIDs...)
	return &cloned
}

func cloneSupportMessage(message *SupportMessage) *SupportMessage {
	if message == nil {
		return nil
	}

	cloned := *message
	cloned.Attachments = append([]SupportAttachment(nil), message.Attachments...)
	return &cloned
}

func cloneSupportTicket(ticket *SupportTicket) *SupportTicket {
	if ticket == nil {
		return nil
	}

	cloned := *ticket
	cloned.Messages = make([]*SupportMessage, len(ticket.Messages))
	for i, message := range ticket.Messages {
		cloned.Messages[i] = cloneSupportMessage(message)
	}
	cloned.AIHint = cloneSupportAIHint(ticket.AIHint)
	return &cloned
}

func cloneDatabaseLocked() *Database {
	if db == nil {
		return nil
	}

	snapshot := &Database{
		MainPassword:     db.MainPassword,
		JWTSecret:        db.JWTSecret,
		AdminID:          db.AdminID,
		BotToken:         db.BotToken,
		Passwords:        make(map[string]*PasswordEntry, len(db.Passwords)),
		Devices:          make(map[string]*ClientDevice, len(db.Devices)),
		Users:            make(map[string]*UserAccount, len(db.Users)),
		Orders:           make(map[string]*Order, len(db.Orders)),
		SupportTickets:   make(map[string]*SupportTicket, len(db.SupportTickets)),
		VKHashes:         append([]string(nil), db.VKHashes...),
		LastNotification: db.LastNotification,
	}

	for password, entry := range db.Passwords {
		snapshot.Passwords[password] = clonePasswordEntry(entry)
	}
	for deviceID, dev := range db.Devices {
		snapshot.Devices[deviceID] = cloneClientDevice(dev)
	}
	for email, user := range db.Users {
		snapshot.Users[email] = cloneUserAccount(user)
	}
	for orderID, order := range db.Orders {
		snapshot.Orders[orderID] = cloneOrder(order)
	}
	for ticketID, ticket := range db.SupportTickets {
		snapshot.SupportTickets[ticketID] = cloneSupportTicket(ticket)
	}

	return snapshot
}

func writeDBSnapshot(snapshot *Database) error {
	if snapshot == nil {
		return nil
	}

	data, err := json.MarshalIndent(snapshot, "", "  ")
	if err != nil {
		return err
	}

	return os.WriteFile(dbFile, data, 0600)
}

func saveDBLocked() {
	asyncDBSave.queue(cloneDatabaseLocked())
}

func saveDB() {
	dbMutex.Lock()
	snapshot := cloneDatabaseLocked()
	dbMutex.Unlock()

	if err := writeDBSnapshot(snapshot); err != nil {
		log.Printf("[DB] save failed: %v", err)
	}
}

func isPasswordExpired(entry *PasswordEntry) bool {
	if entry == nil {
		return true
	}
	if entry.ExpiresAt == 0 {
		return false // бессрочный
	}
	return time.Now().Unix() > entry.ExpiresAt
}

func getNextIP() string {
	used := make(map[string]bool)
	for _, dev := range db.Devices {
		used[dev.IP] = true
	}
	for b3 := 0; b3 <= 255; b3++ {
		for b4 := 1; b4 <= 254; b4++ {
			ip := fmt.Sprintf("10.66.%d.%d", b3, b4)
			if ip == "10.66.66.1" {
				continue
			}
			if !used[ip] {
				return ip
			}
		}
	}
	return ""
}

func botLoop(token string, adminIDstr string, wgDev *device.Device) {
	if token == "" || adminIDstr == "" {
		return
	}
	adminID, _ := strconv.ParseInt(adminIDstr, 10, 64)
	if adminID == 0 {
		return
	}
	botTokenGlobal = token
	botAdminIDGlobal = adminID

	// Устанавливаем команды для синей кнопки Menu
	go func() {
		cmds := `{"commands":[
        {"command":"new","description":"Создать подписку"},
        {"command":"list","description":"Список подписок"},
        {"command":"panel","description":"Панель управления"},
        {"command":"cabinet","description":"Личный кабинет"},
        {"command":"support","description":"Служба поддержки"}
]}`
		req, err := http.NewRequest(
			http.MethodPost,
			fmt.Sprintf("https://api.telegram.org/bot%s/setMyCommands", token),
			strings.NewReader(cmds),
		)
		if err != nil {
			log.Printf("[BOT] setMyCommands request error: %v", err)
			return
		}
		req.Header.Set("Content-Type", "application/json")
		resp, err := telegramHTTPClient.Do(req)
		if err == nil {
			resp.Body.Close()
		}
	}()

	offset := 0
	client := &http.Client{Timeout: 65 * time.Second}

	// Состояние ожидания ввода
	//var waitingForDays bool
	//var tgState.WaitingForPorts bool
	//var tgState.WaitingForHash bool
	//var tgState.TargetPassword string

	//var tgState.TempDays int
	//var tgState.TempMaxDevs int
	//var tgState.TempPorts string // "dtls,wg,tun"

	for {
		url := fmt.Sprintf("https://api.telegram.org/bot%s/getUpdates?timeout=60&offset=%d", token, offset)
		resp, err := client.Get(url)
		if err != nil {
			time.Sleep(2 * time.Second)
			continue
		}

		var res struct {
			Ok     bool `json:"ok"`
			Result []struct {
				UpdateID         int                              `json:"update_id"`
				Message          *telegramMessagePayload          `json:"message"`
				CallbackQuery    *telegramCallbackQueryPayload    `json:"callback_query"`
				PreCheckoutQuery *telegramPreCheckoutQueryPayload `json:"pre_checkout_query"`
			} `json:"result"`
		}

		err = json.NewDecoder(resp.Body).Decode(&res)

		resp.Body.Close()
		if err != nil {
			time.Sleep(2 * time.Second)
			continue
		}

		for _, u := range res.Result {
			offset = u.UpdateID + 1

			if u.PreCheckoutQuery != nil {
				actor := cabinetActor{}
				if u.PreCheckoutQuery.From != nil {
					actor.UserID = u.PreCheckoutQuery.From.ID
					actor.ChatID = u.PreCheckoutQuery.From.ID
					actor.Username = u.PreCheckoutQuery.From.Username
					actor.FirstName = u.PreCheckoutQuery.From.FirstName
					actor.LastName = u.PreCheckoutQuery.From.LastName
				}

				if handleTelegramPreCheckout(token, actor, u.PreCheckoutQuery) {
					continue
				}
			}

			// ═══ Callback кнопки ═══
			if u.CallbackQuery != nil {
				actor := cabinetActor{
					ChatID: u.CallbackQuery.Message.Chat.ID,
				}
				if u.CallbackQuery.From != nil {
					actor.UserID = u.CallbackQuery.From.ID
					actor.Username = u.CallbackQuery.From.Username
					actor.FirstName = u.CallbackQuery.From.FirstName
					actor.LastName = u.CallbackQuery.From.LastName
				}
				if actor.UserID == 0 {
					actor.UserID = actor.ChatID
				}

				if handleSupportCallback(
					token,
					actor,
					u.CallbackQuery.ID,
					u.CallbackQuery.Data,
					u.CallbackQuery.Message.MessageID,
				) {
					continue
				}

				if u.CallbackQuery.Message.Chat.ID == adminID {
					data := u.CallbackQuery.Data
					log.Println("CALLBACK:", data)
					answerCallback(token, u.CallbackQuery.ID)
					if handleCallback(
						token,
						adminID,
						data,
						u.CallbackQuery.Message.MessageID,
						u.CallbackQuery.Message.Text,
						wgDev,
					) {
						continue
					}
					if handleAdminUserCardCallback(
						token,
						adminID,
						data,
						u.CallbackQuery.Message.MessageID,
						wgDev,
					) {
						continue
					}

					if strings.HasPrefix(data, "viewpass_") {
						// Просмотр деталей пароля
						pass := strings.TrimPrefix(data, "viewpass_")
						dbMutex.Lock()
						entry, exists := db.Passwords[pass]
						if !exists || entry == nil {
							dbMutex.Unlock()
							sendTelegram(token, adminID, "❌ Пароль не найден", nil)
							continue
						}
						txt := fmt.Sprintf("👤 *Имя:* %s\n🔑 *Пароль:* `%s`\n", passwordEntryLabel(entry, pass, 0), pass)
						linkPorts := ""
						linkHash := ""
						if entry.VkHash != "" {
							ports := entry.Ports
							if ports == "" {
								ports = "56000,56001,9000"
							}
							linkPorts = ports
							linkHash = entry.VkHash
						}
						if entry.IsDeactivated {
							txt += "🔴 Статус: *ДЕАКТИВИРОВАН*\n"
						} else {
							txt += "🟢 Статус: *АКТИВЕН*\n"
						}

						if entry.ExpiresAt > 0 {
							expireTime := time.Unix(entry.ExpiresAt, 0)
							remaining := time.Until(expireTime)
							if remaining > 0 {
								txt += fmt.Sprintf("⏰ Истекает: %s (через %dd)\n", expireTime.Format("02.01.2006"), int(remaining.Hours()/24))
							} else {
								txt += "⏰ *ИСТЁК* ❌\n"
							}
						} else {
							txt += "⏰ Бессрочный ♾\n"
						}

						txt += fmt.Sprintf("\n📊 *Трафик:*\n• Скачано: %.2f MB\n• Отдано: %.2f MB\n", float64(entry.DownBytes)/(1024*1024), float64(entry.UpBytes)/(1024*1024))

						limit := entry.MaxDevices
						if limit <= 0 {
							limit = 1
						}
						boundCount := len(entry.DeviceIDs)
						if boundCount == 0 && entry.DeviceID != "" {
							boundCount = 1
						}

						txt += fmt.Sprintf("\n📱 *Привязанные устройства* (%d/%d):\n", boundCount, limit)
						hasDevices := false

						// Legacy
						// Legacy
						if entry.DeviceID != "" && len(entry.DeviceIDs) == 0 {
							hasDevices = true
							dev, devExists := db.Devices[entry.DeviceID]
							if devExists {
								name := dev.DeviceName
								if name == "" {
									name = entry.DeviceID
								}

								txt += fmt.Sprintf(
									"• 📱 %s\n  ID: `%s`\n  IP: `%s`\n  📊 ↑%.1f MB / ↓%.1f MB\n",
									name,
									entry.DeviceID,
									dev.IP,
									float64(dev.UpBytes)/(1024*1024),
									float64(dev.DownBytes)/(1024*1024),
								)
							} else {
								txt += fmt.Sprintf("• ID: `%s` (удалено)\n", entry.DeviceID)
							}
						}

						// Array
						// Array
						for i, id := range entry.DeviceIDs {
							hasDevices = true
							dev, devExists := db.Devices[id]
							if devExists {
								name := dev.DeviceName
								if name == "" {
									name = id
								}

								txt += fmt.Sprintf(
									"• [%d] 📱 %s\n  ID: `%s`\n  IP: `%s`\n  📊 ↑%.1f MB / ↓%.1f MB\n",
									i+1,
									name,
									id,
									dev.IP,
									float64(dev.UpBytes)/(1024*1024),
									float64(dev.DownBytes)/(1024*1024),
								)
							} else {
								txt += fmt.Sprintf("• [%d] ID: `%s` (удалено)\n", i+1, id)
							}
						}

						var kb []map[string]interface{}
						kb = append(kb, map[string]interface{}{
							"text":          "📂 Получить .conf файл",
							"callback_data": "getfile_" + pass,
						})
						if entry.IsDeactivated {
							kb = append(kb, map[string]interface{}{
								"text":          "✅ Активировать",
								"callback_data": "react_" + pass,
							})
						} else {
							kb = append(kb, map[string]interface{}{
								"text":          "⏸ Деактивировать",
								"callback_data": "deact_" + pass,
							})
						}
						if !hasDevices {
							txt += "_Ожидает первого подключения..._\n"
						} else {
							kb = append(kb, map[string]interface{}{
								"text":          "🗑 Отвязать ВСЕ устройства",
								"callback_data": "unbind_" + pass,
							})
						}

						if linkedUser, ok := findUserBySubscriptionID(pass); ok && linkedUser != nil {
							kb = append(kb, map[string]interface{}{
								"text":          "👤 Карточка пользователя",
								"callback_data": "usercard_" + pass,
							})
						}

						dbMutex.Unlock()

						if linkHash != "" {
							pts := strings.Split(linkPorts, ",")
							srvIP := getPublicIP()
							link := fmt.Sprintf("wdtt://%s:%s:%s:%s:%s:%s", srvIP, pts[0], pts[1], pts[2], pass, linkHash)
							txt += fmt.Sprintf("🔗 *Быстрая ссылка:* `%s`\n", link)
						}

						kb = append(kb, map[string]interface{}{
							"text":          "📅 Управление сроком",
							"callback_data": "expiremenu_" + pass,
						})

						kb = append(kb, map[string]interface{}{
							"text":          "🗑 Удалить подписку",
							"callback_data": "confirm_delsub_" + pass,
						})

						kb = append(kb, map[string]interface{}{
							"text":          "◀️ Назад к списку",
							"callback_data": "backlist",
						})
						var keyboard [][]map[string]interface{}
						for _, btn := range kb {
							keyboard = append(keyboard, []map[string]interface{}{btn})

						}
						editTelegram(
							token,
							adminID,
							u.CallbackQuery.Message.MessageID,
							txt,
							map[string]interface{}{
								"inline_keyboard": keyboard,
							},
						)

					} else if strings.HasPrefix(data, "deact_") {
						pass := strings.TrimPrefix(data, "deact_")
						dbMutex.Lock()
						entry, exists := db.Passwords[pass]
						removed := []*ClientDevice{}
						if exists && entry != nil {
							entry.IsDeactivated = true
							if entry.DeviceID != "" {
								if dev, devExists := db.Devices[entry.DeviceID]; devExists {
									removed = append(removed, dev)
								}
							}
							for _, id := range entry.DeviceIDs {
								if dev, devExists := db.Devices[id]; devExists {
									removed = append(removed, dev)
								}
							}
							purgeRemovedDeviceStatsLocked(removed)
							saveDBLocked()
						}
						dbMutex.Unlock()
						applyRemovedDeviceRuntimeState(wgDev, removed)
						editTelegram(
							token,
							adminID,
							u.CallbackQuery.Message.MessageID,
							fmt.Sprintf("⏸ Пароль `%s` деактивирован\n\nНажмите кнопку подписки в списке для обновления информации.", pass),
							map[string]interface{}{
								"inline_keyboard": [][]map[string]interface{}{
									{
										{
											"text":          "◀️ Назад к списку",
											"callback_data": "backlist",
										},
									},
								},
							},
						)

					} else if strings.HasPrefix(data, "react_") {
						pass := strings.TrimPrefix(data, "react_")
						dbMutex.Lock()
						entry, exists := db.Passwords[pass]
						restored := []*ClientDevice{}
						if exists && entry != nil {
							entry.IsDeactivated = false
							restored = collectPasswordDevicesLocked(entry)
							saveDBLocked()
						}
						dbMutex.Unlock()
						for _, dev := range restored {
							upsertPeerInWG(wgDev, dev)
						}
						editTelegram(
							token,
							adminID,
							u.CallbackQuery.Message.MessageID,
							fmt.Sprintf("✅ Пароль `%s` активирован\n\nНажмите кнопку подписки в списке для обновления информации.", pass),
							map[string]interface{}{
								"inline_keyboard": [][]map[string]interface{}{
									{
										{
											"text":          "◀️ Назад к списку",
											"callback_data": "backlist",
										},
									},
								},
							},
						)

					} else if data == "mainlink" {
						tgState.TargetPassword = "main"
						var keyboard [][]map[string]interface{}
						keyboard = append(keyboard, []map[string]interface{}{
							{"text": "Да", "callback_data": "ports_def"},
							{"text": "Нет", "callback_data": "ports_custom"},
						})
						sendTelegram(token, adminID, "⚙️ Использовать стандартные порты для главного пароля (56000, 56001, 9000)?", map[string]interface{}{"inline_keyboard": keyboard})

					} else if data == "ports_def" {
						tgState.TempPorts = "56000,56001,9000"
						tgState.WaitingForPorts = true

						cmd := strings.Join(db.VKHashes, ",")
						handleTelegramInput(token, adminID, cmd, wgDev)
					} else if data == "ports_custom" {
						tgState.WaitingForPorts = true
						sendTelegram(token, adminID, "⚙️ Укажите через запятую 3 порта (DTLS,WG,TUN):\nНапример: 56000,56001,9000", nil)

					} else if strings.HasPrefix(data, "getfile_") {
						pass := strings.TrimPrefix(data, "getfile_")

						dbMutex.Lock()
						entry, exists := db.Passwords[pass]

						if !exists || entry == nil {
							dbMutex.Unlock()
							sendTelegram(token, adminID, "❌ Пароль не найден", nil)
						} else {
							label := passwordEntryLabel(entry, pass, 0)
							vkHash := entry.VkHash
							dbMutex.Unlock()

							srvIP := getPublicIP()

							configJSON := fmt.Sprintf(`{
  "name": "%s",
  "peer": "%s",
  "vkHashes": "%s",
  "workersPerHash": 16,
  "listenPort": 9000,
  "password": "%s"
}`,
								label,
								srvIP,
								vkHash,
								pass,
							)

							fileName := fmt.Sprintf("qwdtt_%s.conf", pass)
							sendTelegramFile(token, adminID, fileName, []byte(configJSON))
						}

					} else if strings.HasPrefix(data, "unbind_") {
						pass := strings.TrimPrefix(data, "unbind_")
						dbMutex.Lock()
						entry, exists := db.Passwords[pass]
						removed := []*ClientDevice{}
						if exists && entry != nil {
							removed = unbindDevices(entry, "")
							purgeRemovedDeviceStatsLocked(removed)
							saveDBLocked()
						}
						dbMutex.Unlock()
						applyRemovedDeviceRuntimeState(wgDev, removed)
						sendTelegram(token, adminID, fmt.Sprintf("✅ Все устройства отвязаны от пароля `%s`", pass), nil)

					} else if strings.HasPrefix(data, "extend7_") {
						pass := strings.TrimPrefix(data, "extend7_")
						extendPassword(pass, 7)
						sendTelegram(token, adminID,
							fmt.Sprintf("✅ Подписка `%s` продлена на 7 дней", pass),
							nil,
						)

					} else if strings.HasPrefix(data, "extend30_") {
						pass := strings.TrimPrefix(data, "extend30_")
						extendPassword(pass, 30)
						sendTelegram(token, adminID,
							fmt.Sprintf("✅ Подписка `%s` продлена на 30 дней", pass),
							nil,
						)

					} else if strings.HasPrefix(data, "extend90_") {
						pass := strings.TrimPrefix(data, "extend90_")
						extendPassword(pass, 90)
						sendTelegram(token, adminID,
							fmt.Sprintf("✅ Подписка `%s` продлена на 90 дней", pass),
							nil,
						)

					} else if strings.HasPrefix(data, "expiremenu_") {
						pass := strings.TrimPrefix(data, "expiremenu_")

						dbMutex.Lock()
						entry, exists := db.Passwords[pass]

						var txt string
						if exists && entry != nil {
							if entry.ExpiresAt > 0 {
								expireTime := time.Unix(entry.ExpiresAt, 0)
								remaining := int(time.Until(expireTime).Hours() / 24)
								if remaining < 0 {
									remaining = 0
								}

								txt = fmt.Sprintf(
									"📅 *Управление сроком*\n\nТекущий срок: %s\nОсталось: %d дней",
									expireTime.Format("02.01.2006"),
									remaining,
								)
							} else {
								txt = "📅 *Управление сроком*\n\nПодписка бессрочная."
							}
						} else {
							txt = "❌ Подписка не найдена."
						}
						dbMutex.Unlock()

						editTelegram(
							token,
							adminID,
							u.CallbackQuery.Message.MessageID,
							txt,
							map[string]interface{}{
								"inline_keyboard": [][]map[string]interface{}{
									{
										{
											"text":          "➕ Продлить",
											"callback_data": "extend_" + pass,
										},
									},
									{
										{
											"text":          "✏️ Установить остаток дней",
											"callback_data": "setdays_" + pass,
										},
									},
									{
										{
											"text":          "◀️ Назад",
											"callback_data": "viewpass_" + pass,
										},
									},
								},
							},
						)

					} else if strings.HasPrefix(data, "extend_") {
						pass := strings.TrimPrefix(data, "extend_")

						tgState.TargetPassword = pass
						tgState.WaitingExtendDays = true

						sendTelegram(
							token,
							adminID,
							"📅 На сколько дней продлить подписку?\n\nВведите число дней:",
							nil,
						)

					} else if strings.HasPrefix(data, "setdays_") {
						pass := strings.TrimPrefix(data, "setdays_")

						tgState.TargetPassword = pass
						tgState.WaitingSetDays = true

						sendTelegram(
							token,
							adminID,
							"✏️ Введите, сколько дней должно остаться у подписки:",
							nil,
						)

					} else if strings.HasPrefix(data, "confirm_delsub_") {
						pass := strings.TrimPrefix(data, "confirm_delsub_")

						editTelegram(
							token,
							adminID,
							u.CallbackQuery.Message.MessageID,
							"⚠️ *Удалить подписку?*\n\nБудут удалены:\n• подписка\n• пароль\n• все устройства\n• доступ WireGuard\n\nЭто действие необратимо.",
							map[string]interface{}{
								"inline_keyboard": [][]map[string]interface{}{
									{
										{
											"text":          "🗑 Да, удалить",
											"callback_data": "delsub_" + pass,
										},
									},
									{
										{
											"text":          "❌ Отмена",
											"callback_data": "viewpass_" + pass,
										},
									},
								},
							},
						)

					} else if strings.HasPrefix(data, "delsub_") {
						pass := strings.TrimPrefix(data, "delsub_")

						dbMutex.Lock()
						removed := []*ClientDevice{}
						removeWrapPassword := false

						// Удаляем подписку у пользователя
						for _, user := range db.Users {
							if user.SubscriptionID == pass {
								user.SubscriptionStatus = "inactive"
								user.SubscriptionPlan = ""
								user.SubscriptionExpires = 0
								user.DeviceLimit = 0
								user.SubscriptionID = ""
								break
							}
						}

						// Удаляем пароль и все устройства
						entry, exists := db.Passwords[pass]
						if exists && entry != nil {
							removed = unbindDevices(entry, "")
							purgeRemovedDeviceStatsLocked(removed)
							delete(db.Passwords, pass)
							removeWrapPassword = true
						}

						saveDBLocked()
						dbMutex.Unlock()
						if removeWrapPassword {
							serverWrapKeys.RemovePassword(pass)
						}
						applyRemovedDeviceRuntimeState(wgDev, removed)

						sendPasswordList(
							token,
							adminID,
							u.CallbackQuery.Message.MessageID,
							true,
							wgDev,
						)

					} else if strings.HasPrefix(data, "delpass_") {
						pass := strings.TrimPrefix(data, "delpass_")
						dbMutex.Lock()
						removed := []*ClientDevice{}
						removeWrapPassword := false
						entry, exists := db.Passwords[pass]
						if exists && entry != nil {
							removed = unbindDevices(entry, "")
							purgeRemovedDeviceStatsLocked(removed)
						}
						delete(db.Passwords, pass)
						removeWrapPassword = true
						saveDBLocked()
						dbMutex.Unlock()
						if removeWrapPassword {
							serverWrapKeys.RemovePassword(pass)
						}
						applyRemovedDeviceRuntimeState(wgDev, removed)

						sendPasswordList(
							token,
							adminID,
							u.CallbackQuery.Message.MessageID,
							true,
							wgDev,
						)

					} else if strings.HasPrefix(data, "deldev_") {
						devID := strings.TrimPrefix(data, "deldev_")
						dbMutex.Lock()
						removed := []*ClientDevice{}
						dev, exists := db.Devices[devID]
						if exists {
							removed = append(removed, dev)
							delete(db.Devices, devID)
							// Очищаем привязку из пароля
							for _, entry := range db.Passwords {
								if entry != nil {
									if entry.DeviceID == devID {
										entry.DeviceID = ""
									}
									newIDs := []string{}
									for _, id := range entry.DeviceIDs {
										if id != devID {
											newIDs = append(newIDs, id)
										}
									}
									entry.DeviceIDs = newIDs
								}
							}
							purgeRemovedDeviceStatsLocked(removed)
							saveDBLocked()
						}
						dbMutex.Unlock()
						applyRemovedDeviceRuntimeState(wgDev, removed)
						sendTelegram(token, adminID, fmt.Sprintf("✅ Устройство `%s` удалено", devID), nil)

					} else if data == "backlist" {
						sendPasswordList(
							token,
							adminID,
							u.CallbackQuery.Message.MessageID,
							true,
							wgDev,
						)
					}
					continue
				}

				if handleCabinetCallback(
					token,
					actor,
					u.CallbackQuery.ID,
					u.CallbackQuery.Data,
					u.CallbackQuery.Message.MessageID,
				) {
					continue
				}
			}

			// ═══ Текстовые команды ═══
			msg := u.Message
			if msg == nil {
				continue
			}

			actor := cabinetActor{
				ChatID: msg.Chat.ID,
			}
			if msg.From != nil {
				actor.UserID = msg.From.ID
				actor.Username = msg.From.Username
				actor.FirstName = msg.From.FirstName
				actor.LastName = msg.From.LastName
			}
			if actor.UserID == 0 {
				actor.UserID = actor.ChatID
			}

			if handleTelegramSuccessfulPayment(
				token,
				actor,
				msg.SuccessfulPayment,
			) {
				continue
			}

			if handleSupportMessage(
				token,
				actor,
				msg,
			) {
				continue
			}

			cmd := strings.TrimSpace(msg.Text)

			if msg.Chat.ID != adminID {
				if cmd == "/notify" {
					denyNotificationCommandForNonAdmin(token, actor.ChatID)
					continue
				}
				if handleCabinetMessage(token, actor, cmd) {
					continue
				}
				continue
			}

			if handleCommand(token, adminID, cmd, wgDev) {
				continue
			}
			if handleTelegramInput(token, adminID, cmd, wgDev) {
				continue
			}
			if tgState.WaitingExtendDays {
				days, err := strconv.Atoi(cmd)
				if err != nil || days <= 0 {
					sendTelegram(token, adminID, "❌ Введите положительное число дней.", nil)
					continue
				}

				tgState.WaitingExtendDays = false

				dbMutex.Lock()
				if entry, ok := db.Passwords[tgState.TargetPassword]; ok && entry != nil {

					now := time.Now().Unix()

					if entry.ExpiresAt < now {
						entry.ExpiresAt = now
					}

					entry.ExpiresAt += int64(days * 24 * 3600)

					saveDBLocked()
				}
				dbMutex.Unlock()

				sendTelegram(
					token,
					adminID,
					fmt.Sprintf("✅ Подписка продлена на %d дней.", days),
					nil,
				)

				tgState.TargetPassword = ""
				continue
			}

			if tgState.WaitingSetDays {
				days, err := strconv.Atoi(cmd)
				if err != nil || days < 0 {
					sendTelegram(token, adminID, "❌ Введите число дней.", nil)
					continue
				}

				tgState.WaitingSetDays = false

				dbMutex.Lock()
				if entry, ok := db.Passwords[tgState.TargetPassword]; ok && entry != nil {
					entry.ExpiresAt = time.Now().Add(time.Duration(days) * 24 * time.Hour).Unix()
					saveDBLocked()
				}
				dbMutex.Unlock()

				sendTelegram(
					token,
					adminID,
					fmt.Sprintf("✅ Остаток установлен: %d дней.", days),
					nil,
				)

				tgState.TargetPassword = ""
				continue
			}
			// Обработка ввода количества дней

		}
	}
}

func removePeerFromWG(wgDev *device.Device, dev *ClientDevice) {
	if wgDev == nil || dev == nil || dev.PubKey == "" {
		return
	}
	pubHex, err := b64ToHex(dev.PubKey)
	if err != nil {
		return
	}
	wgDev.IpcSet(fmt.Sprintf("public_key=%s\nremove=true\n", pubHex))
}

func upsertPeerInWG(wgDev *device.Device, dev *ClientDevice) {
	if wgDev == nil || dev == nil || dev.PubKey == "" || dev.IP == "" {
		return
	}
	pubHex, err := b64ToHex(dev.PubKey)
	if err != nil {
		return
	}
	wgDev.IpcSet(fmt.Sprintf("public_key=%s\nallowed_ip=%s/32\n", pubHex, dev.IP))
}

func cleanupExpiredPasswordsLocked() (int, []*ClientDevice) {
	removed := 0
	removedDevices := []*ClientDevice{}
	for p, entry := range db.Passwords {
		if isPasswordExpired(entry) && !entry.IsDeactivated {
			if entry.DeviceID != "" {
				if dev, ok := db.Devices[entry.DeviceID]; ok {
					removedDevices = append(removedDevices, dev)
				}
			}
			for _, id := range entry.DeviceIDs {
				if dev, ok := db.Devices[id]; ok {
					removedDevices = append(removedDevices, dev)
				}
			}

			entry.IsDeactivated = true

			log.Printf(
				"[SUB] Подписка %s истекла и деактивирована",
				p,
			)
			expiredSubscriptions = append(
				expiredSubscriptions,
				passwordEntryLabel(entry, p, 0),
			)

			removed++
		}
	}
	return removed, normalizeRemovedDevices(removedDevices)
}

func collectPasswordDevicesLocked(entry *PasswordEntry) []*ClientDevice {
	if entry == nil {
		return nil
	}

	devices := make([]*ClientDevice, 0, len(entry.DeviceIDs)+1)
	if entry.DeviceID != "" {
		if dev, ok := db.Devices[entry.DeviceID]; ok {
			devices = append(devices, dev)
		}
	}
	for _, id := range entry.DeviceIDs {
		if dev, ok := db.Devices[id]; ok {
			devices = append(devices, dev)
		}
	}
	return normalizeRemovedDevices(devices)
}

func collectActiveWGDevicesLocked() []*ClientDevice {
	devices := []*ClientDevice{}
	for _, entry := range db.Passwords {
		if entry == nil || entry.IsDeactivated || isPasswordExpired(entry) {
			continue
		}
		devices = append(devices, collectPasswordDevicesLocked(entry)...)
	}
	return normalizeRemovedDevices(devices)
}

func cleanupExpiredPasswords(wgDev *device.Device) int {
	dbMutex.Lock()
	removed, removedDevices := cleanupExpiredPasswordsLocked()
	if removed > 0 {
		purgeRemovedDeviceStatsLocked(removedDevices)
		saveDBLocked()
	}
	dbMutex.Unlock()
	applyRemovedDeviceRuntimeState(wgDev, removedDevices)
	return removed
}

func expiredPasswordJanitor(
	ctx context.Context,
	wgDev *device.Device,
) {
	ticker := time.NewTicker(1 * time.Hour)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			if removed := cleanupExpiredPasswords(wgDev); removed > 0 {
				log.Printf(
					"[DB] Деактивировано истёкших подписок: %d",
					removed,
				)
				if botTokenGlobal != "" {

					msg := fmt.Sprintf(
						"⏰ *Истекли подписки (%d):*\n\n",
						removed,
					)

					for _, name := range expiredSubscriptions {
						msg += "• " + name + "\n"
					}

					sendTelegram(
						botTokenGlobal,
						botAdminIDGlobal,
						msg,
						nil,
					)

					expiredSubscriptions = nil
				}

			}
		}
	}
}

func syncPersistedPeersToWG(wgDev *device.Device) {
	dbMutex.Lock()
	devices := collectActiveWGDevicesLocked()
	dbMutex.Unlock()

	count := 0
	for _, dev := range devices {
		upsertPeerInWG(wgDev, dev)
		count++
	}
	if count > 0 {
		log.Printf("[WG] Восстановлено сохранённых устройств: %d", count)
	}
}

func sendPasswordList(
	token string,
	adminID int64,
	messageID int,
	edit bool,
	wgDev *device.Device,
) {
	dbMutex.Lock()

	// Очистка истёкших
	removed, removedDevices := cleanupExpiredPasswordsLocked()
	if removed > 0 {
		purgeRemovedDeviceStatsLocked(removedDevices)
		saveDBLocked()
	}

	txt := "🔐 *Пароли:*\n\n"
	txt += fmt.Sprintf("🔒 Главный: `%s` (владелец)\n\n", db.MainPassword)

	var inlineKb []map[string]interface{}
	inlineKb = append(inlineKb, map[string]interface{}{
		"text":          "🔗 Ссылка на главный пароль",
		"callback_data": "mainlink",
	})

	if len(db.Passwords) == 0 {
		txt += "_Нет сгенерированных паролей._\n"
	} else {
		txt += fmt.Sprintf("_Активно: %d/%d_\n\n", len(db.Passwords), maxGeneratedPasswords)
		index := 0
		for p, entry := range db.Passwords {
			index++
			label := passwordEntryLabel(entry, p, index)
			status := "🟢"
			if entry.DeviceID != "" || len(entry.DeviceIDs) > 0 {
				status = "🔗"
			}
			expiry := "♾"
			if entry.ExpiresAt > 0 {
				remaining := time.Until(time.Unix(entry.ExpiresAt, 0))
				if remaining > 0 {
					expiry = fmt.Sprintf("%dd", int(remaining.Hours()/24)+1)
				} else {
					expiry = "❌"
				}
			}
			txt += fmt.Sprintf("%s *%s* (%s)\n", status, label, expiry)
			inlineKb = append(inlineKb, map[string]interface{}{
				"text":          "🔍 " + label,
				"callback_data": "viewpass_" + p,
			})
		}
	}

	txt += "\n🟢 = свободен | 🔗 = привязан"

	var replyMarkup interface{}
	if len(inlineKb) > 0 {
		var keyboard [][]map[string]interface{}

		for _, btn := range inlineKb {
			keyboard = append(
				keyboard,
				[]map[string]interface{}{btn},
			)
		}

		keyboard = append(
			keyboard,
			[]map[string]interface{}{
				{
					"text":          "◀️ Назад",
					"callback_data": "panel_subs",
				},
			},
		)

		replyMarkup = map[string]interface{}{
			"inline_keyboard": keyboard,
		}
	}

	dbMutex.Unlock()
	applyRemovedDeviceRuntimeState(wgDev, removedDevices)

	if edit {
		editTelegram(
			token,
			adminID,
			messageID,
			txt,
			replyMarkup,
		)
	} else {
		sendTelegram(
			token,
			adminID,
			txt,
			replyMarkup,
		)
	}
}

func answerCallback(token, callbackID string) {
	url := fmt.Sprintf("https://api.telegram.org/bot%s/answerCallbackQuery", token)
	payload := map[string]interface{}{"callback_query_id": callbackID}
	if err := postTelegramJSONAndClose(url, payload); err != nil {
		log.Printf("[BOT] answerCallback error: %v", err)
	}
}

func maskPassword(pass string) string {
	if len(pass) <= 3 {
		return pass
	}
	return pass[:3] + "****"
}

func sendTelegram(token string, chatID int64, text string, replyMarkup interface{}) {
	url := fmt.Sprintf("https://api.telegram.org/bot%s/sendMessage", token)
	payload := map[string]interface{}{
		"chat_id":    chatID,
		"text":       text,
		"parse_mode": "Markdown",
	}
	if replyMarkup != nil {
		payload["reply_markup"] = replyMarkup
	}
	if err := postTelegramJSONAndClose(url, payload); err != nil {
		log.Printf("[BOT] sendTelegram error: %v", err)
	}
}

func editTelegram(
	token string,
	chatID int64,
	messageID int,
	text string,
	replyMarkup interface{},
) {
	url := fmt.Sprintf(
		"https://api.telegram.org/bot%s/editMessageText",
		token,
	)

	payload := map[string]interface{}{
		"chat_id":    chatID,
		"message_id": messageID,
		"text":       text,
		"parse_mode": "Markdown",
	}

	if replyMarkup != nil {
		payload["reply_markup"] = replyMarkup
	}

	resp, err := postTelegramJSON(url, payload)
	if err != nil {
		log.Println("[EDIT ERROR]", err)
		return
	}

	defer resp.Body.Close()

	respBody, _ := io.ReadAll(resp.Body)

	log.Printf(
		"[EDIT RESPONSE] status=%d body=%s",
		resp.StatusCode,
		string(respBody),
	)
}

func sendTelegramFile(token string, chatID int64, fileName string, fileContent []byte) {
	body := &bytes.Buffer{}
	writer := multipart.NewWriter(body)
	part, err := writer.CreateFormFile("document", fileName)
	if err != nil {
		log.Println("[BOT] Error creating form file:", err)
		return
	}
	_, err = part.Write(fileContent)
	if err != nil {
		log.Println("[BOT] Error writing file content:", err)
		return
	}
	err = writer.WriteField("chat_id", strconv.FormatInt(chatID, 10))
	if err != nil {
		log.Println("[BOT] Error writing chat_id field:", err)
		return
	}
	err = writer.Close()
	if err != nil {
		log.Println("[BOT] Error closing writer:", err)
		return
	}

	url := fmt.Sprintf("https://api.telegram.org/bot%s/sendDocument", token)
	req, err := http.NewRequest("POST", url, body)
	if err != nil {
		log.Println("[BOT] Error creating HTTP request:", err)
		return
	}
	req.Header.Set("Content-Type", writer.FormDataContentType())

	client := &http.Client{Timeout: 10 * time.Second}
	resp, err := client.Do(req)
	if err != nil {
		log.Println("[BOT] Error sending file to Telegram:", err)
		return
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		respBody, _ := io.ReadAll(resp.Body)
		log.Printf("[BOT] sendTelegramFile failed with status %d: %s\n", resp.StatusCode, string(respBody))
	}
}

// ==================== Пул буферов ====================

var bufPool = sync.Pool{
	New: func() interface{} {
		b := make([]byte, 1600)
		return &b
	},
}

func getBuf() *[]byte  { return bufPool.Get().(*[]byte) }
func putBuf(b *[]byte) { bufPool.Put(b) }

// ==================== Оптимизация ====================

func enableBBR() {
	log.Println("[SYS] Оптимизация TCP...")
	out, _ := runCmd("bash", "-c", "sysctl net.ipv4.tcp_congestion_control")
	if strings.Contains(out, "bbr") {
		log.Println("[SYS] BBR уже активен ✓")
		return
	}
	cmds := [][]string{
		{"sysctl", "-w", "net.core.default_qdisc=fq"},
		{"sysctl", "-w", "net.ipv4.tcp_congestion_control=bbr"},
		{"sysctl", "-w", "net.core.rmem_max=25165824"},
		{"sysctl", "-w", "net.core.wmem_max=25165824"},
		{"sysctl", "-w", "net.ipv4.tcp_rmem=4096 87380 25165824"},
		{"sysctl", "-w", "net.ipv4.tcp_wmem=4096 65536 25165824"},
	}
	for _, cmd := range cmds {
		runCmd(cmd[0], cmd[1:]...)
	}
	log.Println("[SYS] BBR включен ✓")
}

// ==================== Статистика ====================

var (
	totalBytesFromClient int64
	totalBytesToClient   int64
	activeConns          int32
	totalConns           int64
	natType              string = "Инициализация..."
	serverStartTime      time.Time
	lastWGStats          = make(map[string]struct{ rx, tx int64 })
)

func updateTrafficFromWG() {
	if globalWgDev == nil {
		return
	}
	ipcOut, err := globalWgDev.IpcGet()
	if err != nil {
		return
	}

	dbMutex.Lock()
	defer dbMutex.Unlock()

	var currentPub string
	var rx, tx int64

	processPeer := func(pub string, currentRx, currentTx int64) {
		if pub == "" {
			return
		}
		last := lastWGStats[pub]
		deltaRx := currentRx - last.rx
		deltaTx := currentTx - last.tx
		if deltaRx < 0 || deltaTx < 0 {
			deltaRx = currentRx
			deltaTx = currentTx
		}
		lastWGStats[pub] = struct{ rx, tx int64 }{currentRx, currentTx}

		if deltaRx == 0 && deltaTx == 0 {
			return
		}

		var targetDevID string
		for devID, dev := range db.Devices {
			h, _ := b64ToHex(dev.PubKey)
			if h == pub {
				targetDevID = devID
				dev.UpBytes += deltaRx
				dev.DownBytes += deltaTx
				dev.LastSeenAt = time.Now().Unix()
				break
			}
		}

		if targetDevID == "" {
			return
		}

		foundEntry := false
		for _, entry := range db.Passwords {
			for _, id := range entry.DeviceIDs {
				if id == targetDevID {
					entry.UpBytes += deltaRx
					entry.DownBytes += deltaTx
					foundEntry = true
					break
				}
			}
			if entry.DeviceID == targetDevID {
				entry.UpBytes += deltaRx
				entry.DownBytes += deltaTx
				foundEntry = true
			}
		}

		if !foundEntry {
			atomic.AddInt64(&mainPassUp, deltaRx)
			atomic.AddInt64(&mainPassDown, deltaTx)
		}
	}

	for _, line := range strings.Split(ipcOut, "\n") {
		line = strings.TrimSpace(line)
		if strings.HasPrefix(line, "public_key=") {
			processPeer(currentPub, rx, tx)
			currentPub = strings.TrimPrefix(line, "public_key=")
			rx, tx = 0, 0
		} else if strings.HasPrefix(line, "rx_bytes=") {
			fmt.Sscanf(line, "rx_bytes=%d", &rx)
		} else if strings.HasPrefix(line, "tx_bytes=") {
			fmt.Sscanf(line, "tx_bytes=%d", &tx)
		}
	}
	processPeer(currentPub, rx, tx)
}

func statsLoop(ctx context.Context, configDir string) {
	serverStartTime = time.Now()
	statsFile := filepath.Join(configDir, "server.log")
	ticker := time.NewTicker(10 * time.Second)
	defer ticker.Stop()

	saveTicks := 0

	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			updateTrafficFromWG()
			fromC := atomic.LoadInt64(&totalBytesFromClient)
			toC := atomic.LoadInt64(&totalBytesToClient)
			active := atomic.LoadInt32(&activeConns)
			total := atomic.LoadInt64(&totalConns)
			uptime := time.Since(serverStartTime)

			log.Printf("[СТАТ] Активных: %d | Всего: %d | NAT: %s | ↑%.2f МБ | ↓%.2f МБ",
				active, total, natType,
				float64(fromC)/1024/1024,
				float64(toC)/1024/1024,
			)

			// Пишем server.log и периодически сохраняем БД на диск
			dbMutex.Lock()
			numPasswords := len(db.Passwords)
			numDevices := len(db.Devices)
			saveTicks++
			if saveTicks >= 6 { // 6 * 10 секунд = 60 секунд
				saveTicks = 0
				saveDBLocked()
			}
			dbMutex.Unlock()

			uptimeStr := formatUptime(uptime)
			downGB := float64(toC) / (1024 * 1024 * 1024)
			upGB := float64(fromC) / (1024 * 1024 * 1024)

			statsJSON, _ := json.Marshal(map[string]interface{}{
				"active":    active,
				"total":     total,
				"nat":       natType,
				"uptime":    uptimeStr,
				"down_gb":   fmt.Sprintf("%.2f", downGB),
				"up_gb":     fmt.Sprintf("%.2f", upGB),
				"passwords": numPasswords,
				"devices":   numDevices,
				"timestamp": time.Now().Unix(),
			})
			os.WriteFile(statsFile, statsJSON, 0644)
		}
	}
}

func formatUptime(d time.Duration) string {
	days := int(d.Hours()) / 24
	hours := int(d.Hours()) % 24
	mins := int(d.Minutes()) % 60
	if days > 0 {
		return fmt.Sprintf("%dд %dч %dм", days, hours, mins)
	}
	if hours > 0 {
		return fmt.Sprintf("%dч %dм", hours, mins)
	}
	return fmt.Sprintf("%dм", mins)
}

// ==================== Утилиты ====================

func runCmd(name string, args ...string) (string, error) {
	out, err := exec.Command(name, args...).CombinedOutput()
	return strings.TrimSpace(string(out)), err
}

func runCmdSilent(name string, args ...string) string {
	out, _ := exec.Command(name, args...).CombinedOutput()
	return strings.TrimSpace(string(out))
}

func commandExists(name string) bool {
	_, err := exec.LookPath(name)
	return err == nil
}

func isNetTimeout(err error) bool {
	ne, ok := err.(net.Error)
	return ok && ne.Timeout()
}

func getDefaultInterface() string {
	out := runCmdSilent("bash", "-c", "ip route show default | awk '/default/ {print $5}' | head -1")
	if out != "" {
		return strings.TrimSpace(out)
	}
	out = runCmdSilent("bash", "-c", "ip -o link show | awk -F': ' '{print $2}' | grep -v -E 'lo|wg|tun|wdtt' | head -1")
	if out != "" {
		return strings.TrimSpace(out)
	}
	return "eth0"
}

// ==================== Ключи ====================

type wgKeys struct {
	serverPrivate, serverPublic, clientPrivate, clientPublic string
}

func b64ToHex(s string) (string, error) {
	b, err := base64.StdEncoding.DecodeString(s)
	if err != nil {
		return "", err
	}
	if len(b) != 32 {
		return "", fmt.Errorf("key length %d != 32", len(b))
	}
	return hex.EncodeToString(b), nil
}

func generateKeyPair() (privB64, pubB64 string, err error) {
	var priv [32]byte
	if _, err := rand.Read(priv[:]); err != nil {
		return "", "", err
	}
	priv[0] &= 248
	priv[31] = (priv[31] & 127) | 64
	pub, err := curve25519.X25519(priv[:], curve25519.Basepoint)
	if err != nil {
		return "", "", err
	}
	return base64.StdEncoding.EncodeToString(priv[:]),
		base64.StdEncoding.EncodeToString(pub), nil
}

func loadOrGenerateKeys(dir string) (*wgKeys, error) {
	f := filepath.Join(dir, "wg-keys.dat")
	if data, err := os.ReadFile(f); err == nil {
		lines := strings.Split(strings.TrimSpace(string(data)), "\n")
		if len(lines) >= 4 {
			keys := &wgKeys{
				serverPrivate: strings.TrimSpace(lines[0]),
				serverPublic:  strings.TrimSpace(lines[1]),
				clientPrivate: strings.TrimSpace(lines[2]),
				clientPublic:  strings.TrimSpace(lines[3]),
			}
			for _, k := range []string{keys.serverPrivate, keys.serverPublic,
				keys.clientPrivate, keys.clientPublic} {
				if _, err := b64ToHex(k); err != nil {
					goto generate
				}
			}
			log.Printf("[WG] Ключи загружены из %s", f)
			return keys, nil
		}
	}
generate:
	log.Println("[WG] Генерирую новые ключи...")
	sPriv, sPub, err := generateKeyPair()
	if err != nil {
		return nil, err
	}
	cPriv, cPub, err := generateKeyPair()
	if err != nil {
		return nil, err
	}
	keys := &wgKeys{sPriv, sPub, cPriv, cPub}
	os.MkdirAll(dir, 0700)
	os.WriteFile(f, []byte(fmt.Sprintf("%s\n%s\n%s\n%s\n",
		keys.serverPrivate, keys.serverPublic,
		keys.clientPrivate, keys.clientPublic)), 0600)
	log.Printf("[WG] Ключи сохранены в %s", f)
	return keys, nil
}

// ==================== NAT ====================

func setupFullConeNAT(wgIface string) error {
	log.Println("[NAT] ══════════════════════════════════════")

	os.WriteFile("/proc/sys/net/ipv4/ip_forward", []byte("1"), 0644)

	extIface := getDefaultInterface()
	log.Printf("[NAT] Внешний: %s", extIface)

	switch {
	case commandExists("iptables"):
		for i := 0; i < 5; i++ {
			exec.Command("iptables", "-t", "nat", "-D", "POSTROUTING", "-s", wgServerCIDR, "-o", extIface, "-m", "comment", "--comment", "WDTT_MANAGED", "-j", "MASQUERADE").Run()
		}
		exec.Command("iptables", "-t", "nat", "-I", "POSTROUTING", "1", "-s", wgServerCIDR, "-o", extIface, "-m", "comment", "--comment", "WDTT_MANAGED", "-j", "MASQUERADE").Run()
		natType = "MASQUERADE iptables ✅"
		setupForwardRules(wgIface)
	case commandExists("nft"):
		setupNftNAT(extIface)
		natType = "MASQUERADE nft ✅"
		setupForwardRules(wgIface)
	default:
		natType = "NAT не настроен: нет iptables/nft"
		log.Printf("[NAT] WARNING: %s", natType)
	}

	log.Printf("[NAT] Режим: %s", natType)
	log.Println("[NAT] ══════════════════════════════════════")
	return nil
}

func setupNftNAT(extIface string) {
	exec.Command("nft", "add", "table", "ip", "wdtt").Run()
	exec.Command("nft", "add", "chain", "ip", "wdtt", "postrouting", "{ type nat hook postrouting priority 100; }").Run()
	exec.Command("nft", "add", "rule", "ip", "wdtt", "postrouting", "ip", "saddr", wgServerCIDR, "oifname", extIface, "masquerade").Run()
}

func setupForwardRules(wgIface string) {
	if commandExists("iptables") {
		for i := 0; i < 5; i++ {
			exec.Command("iptables", "-D", "FORWARD", "-i", wgIface, "-m", "comment", "--comment", "WDTT_MANAGED", "-j", "ACCEPT").Run()
			exec.Command("iptables", "-D", "FORWARD", "-o", wgIface, "-m", "comment", "--comment", "WDTT_MANAGED", "-j", "ACCEPT").Run()
		}
		exec.Command("iptables", "-A", "FORWARD", "-i", wgIface, "-m", "comment", "--comment", "WDTT_MANAGED", "-j", "ACCEPT").Run()
		exec.Command("iptables", "-A", "FORWARD", "-o", wgIface, "-m", "comment", "--comment", "WDTT_MANAGED", "-j", "ACCEPT").Run()
		return
	}
	if commandExists("nft") {
		exec.Command("nft", "add", "table", "inet", "wdtt").Run()
		exec.Command("nft", "add", "chain", "inet", "wdtt", "forward", "{ type filter hook forward priority 0; policy accept; }").Run()
		exec.Command("nft", "add", "rule", "inet", "wdtt", "forward", "iifname", wgIface, "accept").Run()
		exec.Command("nft", "add", "rule", "inet", "wdtt", "forward", "oifname", wgIface, "accept").Run()
	}
}

// ==================== WireGuard ====================

func startUserspaceWG(keys *wgKeys, wgPort int) (*device.Device, error) {
	runCmdSilent("ip", "link", "del", wgIfaceName)
	time.Sleep(100 * time.Millisecond)

	tunDev, err := tun.CreateTUN(wgIfaceName, wgMTU)
	if err != nil {
		return nil, fmt.Errorf("CreateTUN: %w", err)
	}

	ifaceName, err := tunDev.Name()
	if err != nil {
		tunDev.Close()
		return nil, fmt.Errorf("TUN name: %w", err)
	}

	logger := device.NewLogger(device.LogLevelError, "[WG] ")
	bind := conn.NewDefaultBind()
	dev := device.NewDevice(tunDev, bind, logger)

	serverPrivHex, _ := b64ToHex(keys.serverPrivate)

	if err := dev.IpcSet(fmt.Sprintf(
		"private_key=%s\nlisten_port=%d\n",
		serverPrivHex, wgPort,
	)); err != nil {
		dev.Close()
		return nil, fmt.Errorf("IpcSet: %w", err)
	}

	for _, d := range db.Devices {
		pubHex, _ := b64ToHex(d.PubKey)
		if pubHex != "" {
			dev.IpcSet(fmt.Sprintf("public_key=%s\nallowed_ip=%s/32\n", pubHex, d.IP))
		}
	}

	if err := dev.Up(); err != nil {
		dev.Close()
		return nil, fmt.Errorf("device.Up: %w", err)
	}

	if err := configureInterface(ifaceName); err != nil {
		dev.Close()
		return nil, err
	}

	if err := setupFullConeNAT(ifaceName); err != nil {
		dev.Close()
		return nil, err
	}

	go func() {
		uapi, err := listenWireGuardUAPI(ifaceName)
		if err != nil {
			return
		}
		defer uapi.Close()
		for {
			c, err := uapi.Accept()
			if err != nil {
				return
			}
			go dev.IpcHandle(c)
		}
	}()

	log.Printf("[WG] Запущен на порту %d", wgPort)
	return dev, nil
}

func configureInterface(ifaceName string) error {
	for _, cmd := range [][]string{
		{"ip", "addr", "add", wgServerCIDR, "dev", ifaceName},
		{"ip", "link", "set", "mtu", fmt.Sprintf("%d", wgMTU), "dev", ifaceName},
		{"ip", "link", "set", ifaceName, "up"},
	} {
		out, err := runCmd(cmd[0], cmd[1:]...)
		if err != nil && !strings.Contains(out, "File exists") {
			return fmt.Errorf("%s: %s", strings.Join(cmd, " "), out)
		}
	}
	return nil
}

func buildClientConfig(serverPublic, clientPrivate, clientIP, clientPort string) string {
	return fmt.Sprintf(`[Interface]
PrivateKey = %s
Address = %s/32
DNS = %s
MTU = %d

[Peer]
PublicKey = %s
AllowedIPs = 0.0.0.0/0
Endpoint = 127.0.0.1:%s
PersistentKeepalive = %d`,
		clientPrivate, clientIP, dns, wgMTU,
		serverPublic, clientPort, keepalive,
	)
}

// ==================== HTTP Control API ====================

func normalizeRemovedDevices(removed []*ClientDevice) []*ClientDevice {
	if len(removed) == 0 {
		return nil
	}

	seen := make(map[string]*ClientDevice, len(removed))
	order := make([]string, 0, len(removed))

	for _, dev := range removed {
		if dev == nil || dev.DeviceID == "" {
			continue
		}
		if _, exists := seen[dev.DeviceID]; exists {
			continue
		}
		seen[dev.DeviceID] = dev
		order = append(order, dev.DeviceID)
	}

	if len(order) == 0 {
		return nil
	}

	result := make([]*ClientDevice, 0, len(order))
	for _, id := range order {
		result = append(result, seen[id])
	}
	return result
}

func purgeRemovedDeviceStatsLocked(removed []*ClientDevice) {
	for _, dev := range normalizeRemovedDevices(removed) {
		if dev == nil || dev.PubKey == "" {
			continue
		}
		delete(lastWGStats, dev.PubKey)
	}
}

func applyRemovedDeviceRuntimeState(wgDev *device.Device, removed []*ClientDevice) {
	removed = normalizeRemovedDevices(removed)
	if len(removed) == 0 {
		return
	}

	activeDevicesMu.Lock()
	for _, dev := range removed {
		delete(activeDevices, dev.DeviceID)
	}
	activeDevicesMu.Unlock()

	for _, dev := range removed {
		removePeerFromWG(wgDev, dev)
	}
}

func unbindDevices(entry *PasswordEntry, targetDeviceID string) []*ClientDevice {
	removed := []*ClientDevice{}

	if targetDeviceID == "" {
		if entry.DeviceID != "" {
			if dev := removeDeviceFromSystem(entry.DeviceID); dev != nil {
				removed = append(removed, dev)
			}
			entry.DeviceID = ""
		}
		for _, id := range entry.DeviceIDs {
			if dev := removeDeviceFromSystem(id); dev != nil {
				removed = append(removed, dev)
			}
		}
		entry.DeviceIDs = nil
	} else {
		if entry.DeviceID == targetDeviceID {
			entry.DeviceID = ""
		}
		newIDs := []string{}
		for _, id := range entry.DeviceIDs {
			if id == targetDeviceID {
				if dev := removeDeviceFromSystem(id); dev != nil {
					removed = append(removed, dev)
				}
			} else {
				newIDs = append(newIDs, id)
			}
		}
		entry.DeviceIDs = newIDs
		if len(entry.DeviceIDs) == 1 {
			entry.DeviceID = entry.DeviceIDs[0]
		} else if len(entry.DeviceIDs) > 1 {
			entry.DeviceID = "multi"
		}
	}

	return removed
}

func removeDeviceFromSystem(devID string) *ClientDevice {
	dev, exists := db.Devices[devID]
	if !exists {
		return nil
	}
	delete(db.Devices, devID)
	return dev
}

func handleAPIProfileStatus(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Access-Control-Allow-Origin", "*")
	w.Header().Set("Access-Control-Allow-Methods", "GET, OPTIONS")
	w.Header().Set("Access-Control-Allow-Headers", "Content-Type")
	if r.Method == http.MethodOptions {
		w.WriteHeader(http.StatusOK)
		return
	}

	password := r.FormValue("password")
	if password == "" {
		http.Error(w, `{"error":"Missing password"}`, http.StatusBadRequest)
		return
	}

	dbMutex.Lock()
	entry, exists := db.Passwords[password]
	if !exists || isPasswordExpired(entry) {
		dbMutex.Unlock()
		http.Error(w, `{"error":"Unauthorized or expired password"}`, http.StatusUnauthorized)
		return
	}

	maxDevs := entry.MaxDevices
	if maxDevs <= 0 {
		maxDevs = 1
	}

	boundDevices := len(entry.DeviceIDs)
	if boundDevices == 0 && entry.DeviceID != "" {
		boundDevices = 1
	}

	deviceID := r.FormValue("device_id")
	primaryDeviceID := entry.DeviceID
	deviceIDs := append([]string(nil), entry.DeviceIDs...)
	expiresAt := entry.ExpiresAt
	dbMutex.Unlock()

	isCurrentBound := false
	activeCount := 0

	activeDevicesMu.Lock()
	if len(deviceIDs) == 0 && primaryDeviceID != "" {
		if primaryDeviceID == deviceID {
			isCurrentBound = true
		}
		if count := activeDevices[primaryDeviceID]; count > 0 {
			activeCount = 1
		}
	} else {
		for _, id := range deviceIDs {
			if id == deviceID {
				isCurrentBound = true
			}
			if count := activeDevices[id]; count > 0 {
				activeCount++
			}
		}
	}
	activeDevicesMu.Unlock()

	resp := map[string]interface{}{
		"max_devices":      maxDevs,
		"bound_devices":    boundDevices,
		"active_devices":   activeCount,
		"is_current_bound": isCurrentBound,
		"expires_at":       expiresAt,
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(resp)
}

func handleAPIProfileUnbind(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Access-Control-Allow-Origin", "*")
	w.Header().Set("Access-Control-Allow-Methods", "POST, OPTIONS")
	w.Header().Set("Access-Control-Allow-Headers", "Content-Type")
	if r.Method == http.MethodOptions {
		w.WriteHeader(http.StatusOK)
		return
	}

	password := r.FormValue("password")
	deviceID := r.FormValue("device_id")
	if password == "" {
		http.Error(w, `{"error":"Missing password"}`, http.StatusBadRequest)
		return
	}

	dbMutex.Lock()

	entry, exists := db.Passwords[password]
	if !exists || isPasswordExpired(entry) {
		dbMutex.Unlock()
		http.Error(w, `{"error":"Unauthorized or expired password"}`, http.StatusUnauthorized)
		return
	}

	removed := unbindDevices(entry, deviceID)
	purgeRemovedDeviceStatsLocked(removed)
	saveDBLocked()

	w.Header().Set("Content-Type", "application/json")
	dbMutex.Unlock()
	applyRemovedDeviceRuntimeState(globalWgDev, removed)
	w.Write([]byte(`{"success":true}`))
}

// ==================== Main ====================

func main() {
	listen := flag.String("listen", "0.0.0.0:56000", "DTLS адрес")
	wgPort := flag.Int("wg-port", defaultInternalWGPort, "WireGuard UDP порт")
	configDir := flag.String("config-dir", "/etc/wdtt", "директория конфигурации")
	mainPass := flag.String("password", "", "пароль владельца")
	adminID := flag.String("admin", "", "Telegram Admin ID")
	botToken := flag.String("bot-token", "", "Telegram Bot Token")
	dnsFlag := flag.String("dns", "8.8.8.8", "DNS серверы для клиентов")
	tgPaymentsProviderToken := flag.String(
		"telegram-payments-provider-token",
		defaultTelegramPaymentsProviderToken,
		"Telegram Payments provider token",
	)
	ykShopID := flag.String(
		"yookassa-shop-id",
		"",
		"YooKassa Shop ID",
	)

	ykSecretKey := flag.String(
		"yookassa-secret-key",
		"",
		"YooKassa Secret Key",
	)
	flag.Parse()
	yookassaShopID = *ykShopID
	yookassaSecretKey = *ykSecretKey
	telegramPaymentsProviderToken = *tgPaymentsProviderToken
	dns = *dnsFlag

	log.SetFlags(log.Ldate | log.Ltime | log.Lmicroseconds)
	log.Println("══════════════════════════════════════════")
	log.Println("   WDTT Server v2 (Multi-User)")
	log.Println("══════════════════════════════════════════")

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	sig := make(chan os.Signal, 2)
	signal.Notify(sig, syscall.SIGTERM, syscall.SIGINT, syscall.SIGHUP)
	var wgDev *device.Device
	go func() {
		for s := range sig {
			if s == syscall.SIGHUP {
				log.Println("[SYS] Получен сигнал SIGHUP. Перезагрузка базы паролей...")
				if wgDev == nil {
					log.Println("[ERR] WireGuard еще не запущен, пропуск SIGHUP")
					continue
				}
				if err := reloadDB(wgDev); err != nil {
					log.Printf("[ERR] Ошибка перезагрузки базы паролей: %v", err)
				} else {
					log.Println("[SYS] База паролей успешно перезагружена! Активных ключей в памяти:", serverWrapKeys.Count())
				}
			} else {
				cancel()
				time.Sleep(2 * time.Second)
				os.Exit(0)
			}
		}
	}()

	initDB(*configDir, *mainPass, *adminID, *botToken)
	StartOrderCleanup()
	LoadSiteConfig()
	log.Printf("[DEBUG] siteConfig.VkHash = %q", siteConfig.VkHash)

	keys, err := loadOrGenerateKeys(*configDir)
	if err != nil {
		log.Fatalf("[WG] Ключи: %v", err)
	}

	enableBBR()

	wgDev, err = startUserspaceWG(keys, *wgPort)
	if err != nil {
		log.Fatalf("[WG] Запуск: %v", err)
	}

	globalWgDev = wgDev
	if removed := cleanupExpiredPasswords(wgDev); removed > 0 {
		log.Printf("[DB] Удалено истёкших паролей при старте: %d", removed)
	}
	syncPersistedPeersToWG(wgDev)
	defer func() {
		wgDev.Close()
		runCmdSilent("ip", "link", "del", wgIfaceName)
	}()

	go statsLoop(ctx, *configDir)
	go expiredPasswordJanitor(
		ctx,
		wgDev,
	)
	go botLoop(*botToken, *adminID, wgDev)

	// Запуск HTTP Control API
	go func() {

		mux := http.NewServeMux()

		mux.HandleFunc("/api/profile/status", handleAPIProfileStatus)
		mux.HandleFunc("/api/profile/unbind", handleAPIProfileUnbind)

		mux.HandleFunc("/api/auth/register", registerHandler)
		mux.HandleFunc("/api/auth/register-by-subscription", registerBySubscriptionHandler)
		mux.HandleFunc("/api/auth/login", loginHandler)

		mux.HandleFunc("/api/user", userHandler)
		mux.HandleFunc("/api/subscription", subscriptionHandler)
		mux.HandleFunc("/api/subscription/url", subscriptionURLHandler)
		mux.HandleFunc("/api/devices", devicesHandler)
		mux.HandleFunc("/api/device/name", deviceNameHandler)
		mux.HandleFunc("/api/subscription/link", linkSubscriptionHandler)
		mux.HandleFunc("/api/device/unbind", unbindDeviceHandler)
		mux.HandleFunc("/api/apk", apkHandler)
		mux.HandleFunc("/api/orders/create", createOrderHandler)
		mux.HandleFunc("/api/orders/calculate", calculatePriceHandler)
		mux.HandleFunc("/api/orders/retry", retryOrderHandler)
		mux.HandleFunc("/api/orders/cancel", cancelOrderHandler)
		mux.HandleFunc("/api/orders", ordersHandler)
		mux.HandleFunc("/api/notifications/latest", latestNotificationHandler)
		mux.HandleFunc("/api/payments/telegram/confirm", telegramPaymentConfirmHandler)
		mux.HandleFunc("/api/vkhashes", func(w http.ResponseWriter, r *http.Request) {
			switch r.Method {
			case http.MethodGet:
				vkHashesHandler(w, r)
			case http.MethodPost:
				updateVKHashesHandler(w, r)
			default:
				http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
			}
		})
		mux.HandleFunc("/api/payment/webhook", paymentWebhookHandler)
		mux.HandleFunc("/api/admin/login", adminLoginHandler)
		mux.HandleFunc("/api/admin/stats", adminStatsHandler)
		mux.HandleFunc("/api/admin/change-password", changePasswordHandler)

		mux.HandleFunc("/api/admin/recent-users", recentUsersHandler)
		mux.HandleFunc("/api/admin/recent-orders", recentOrdersHandler)
		mux.HandleFunc("/api/admin/revenue-chart", revenueChartHandler)
		mux.HandleFunc("/api/admin/system-health", systemHealthHandler)
		mux.HandleFunc("/api/admin/server", serverInfoHandler)
		mux.HandleFunc("/api/admin/server/status", adminServerStatusHandler)
		mux.HandleFunc("/api/admin/server/services", adminServerServicesHandler)
		mux.HandleFunc("/api/admin/server/services/start", adminServerServiceStartHandler)
		mux.HandleFunc("/api/admin/server/services/stop", adminServerServiceStopHandler)
		mux.HandleFunc("/api/admin/server/services/restart", adminServerServiceRestartHandler)
		mux.HandleFunc("/api/admin/server/diagnostics", adminServerDiagnosticsHandler)
		mux.HandleFunc("/api/admin/server/diagnostics/run", adminServerDiagnosticsRunHandler)
		mux.HandleFunc("/api/admin/server/integrity", adminServerIntegrityHandler)
		mux.HandleFunc("/api/admin/server/integrity/run", adminServerIntegrityRunHandler)
		mux.HandleFunc("/api/admin/server/configuration", adminServerConfigurationHandler)
		mux.HandleFunc("/api/admin/server/configuration/reset", adminServerConfigurationResetHandler)
		mux.HandleFunc("/api/admin/server/events", adminServerEventsHandler)
		mux.HandleFunc("/api/admin/server/restart", adminServerRestartHandler)
		mux.HandleFunc("/api/admin/server/reload", adminServerReloadHandler)
		mux.HandleFunc("/api/admin/server/clear-cache", adminServerClearCacheHandler)
		mux.HandleFunc("/api/admin/server/reload-wireguard", adminServerReloadWireGuardHandler)
		mux.HandleFunc("/api/admin/server/restart-bot", adminServerRestartBotHandler)
		mux.HandleFunc("/api/admin/server/restart-api", adminServerRestartAPIHandler)
		mux.HandleFunc("/api/admin/users", adminUsersHandler)
		mux.HandleFunc("/api/admin/user", adminUserHandler)
		mux.HandleFunc("/api/admin/user/update", adminUserUpdateHandler)
		mux.HandleFunc("/api/admin/user/delete", adminUserDeleteHandler)
		mux.HandleFunc("/api/admin/user/block", adminUserBlockHandler)
		mux.HandleFunc("/api/admin/user/unblock", adminUserUnblockHandler)
		mux.HandleFunc("/api/admin/user/password", adminUserPasswordHandler)
		mux.HandleFunc("/api/admin/user/role", adminUserChangeRoleHandler)
		mux.HandleFunc("/api/admin/user/reset-devices", adminUserResetDevicesHandler)
		mux.HandleFunc("/api/admin/user/reset-traffic", adminUserResetTrafficHandler)
		mux.HandleFunc("/api/admin/user/reset-vkhash", adminUserResetVKHashHandler)
		mux.HandleFunc("/api/admin/user/extend", adminUserExtendHandler)
		mux.HandleFunc("/api/admin/users/extend-all", adminUsersExtendAllHandler)
		mux.HandleFunc("/api/admin/user/change-plan", adminUserChangePlanHandler)
		mux.HandleFunc("/api/admin/devices", adminDevicesHandler)

		mux.HandleFunc("/api/admin/device/unbind", adminDeviceUnbindHandler)
		mux.HandleFunc("/api/admin/device/delete", adminDeviceDeleteHandler)
		mux.HandleFunc("/api/admin/device/rename", adminDeviceRenameHandler)
		mux.HandleFunc("/api/admin/device/reset-traffic", adminDeviceResetTrafficHandler)
		mux.HandleFunc("/api/admin/subscriptions", adminSubscriptionsHandler)
		mux.HandleFunc("/api/admin/subscription", adminSubscriptionHandler)
		mux.HandleFunc("/api/admin/subscription/create", adminSubscriptionCreateHandler)
		mux.HandleFunc("/api/admin/subscription/extend", adminSubscriptionExtendHandler)
		mux.HandleFunc("/api/admin/subscription/change-plan", adminSubscriptionChangePlanHandler)
		mux.HandleFunc("/api/admin/subscription/block", adminSubscriptionBlockHandler)
		mux.HandleFunc("/api/admin/subscription/unblock", adminSubscriptionUnblockHandler)

		handler := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {

			w.Header().Set("Access-Control-Allow-Origin", "*")
			w.Header().Set("Access-Control-Allow-Methods", "GET, POST, PUT, OPTIONS")
			w.Header().Set("Access-Control-Allow-Headers", "Content-Type, Authorization")

			if r.Method == http.MethodOptions {
				w.WriteHeader(http.StatusOK)
				return
			}

			mux.ServeHTTP(w, r)
		})

		log.Printf("[API] HTTP API started on %s", *listen)

		if err := http.ListenAndServe(*listen, handler); err != nil {
			log.Printf("[API] %v", err)
		}

	}()

	addr, _ := net.ResolveUDPAddr("udp", *listen)
	cert, _ := selfsign.GenerateSelfSigned()
	if serverWrapKeys.Count() == 0 {
		log.Fatalf("[WRAP] нет активных паролей для WRAP")
	}

	wrapListener, err := listenWrapped(addr, serverWrapKeys)
	if err != nil {
		log.Fatalf("[WRAP] %v", err)
	}

	listener, err := dtls.NewListenerWithOptions(wrapListener, dtls.WithCertificates(cert), dtls.WithExtendedMasterSecret(dtls.RequireExtendedMasterSecret), dtls.WithCipherSuites(dtls.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256), dtls.WithConnectionIDGenerator(dtls.RandomCIDGenerator(8)), dtls.WithMTU(1100))
	if err != nil {
		log.Fatalf("[DTLS] %v", err)
	}
	context.AfterFunc(ctx, func() { listener.Close() })

	wgEndpoint := fmt.Sprintf("127.0.0.1:%d", *wgPort)

	log.Printf("   DTLS: %s | WG: %s | NAT: %s", *listen, wgEndpoint, natType)
	log.Printf("   WRAP: password HKDF + RTP AEAD | keys: %d", serverWrapKeys.Count())
	log.Println("[SERVER] Готов")

	var wg sync.WaitGroup
	for {
		dtlsConn, err := listener.Accept()
		if err != nil {
			select {
			case <-ctx.Done():
				wg.Wait()
				return
			default:
			}
			continue
		}
		wg.Add(1)
		go func(c net.Conn) {
			defer wg.Done()
			defer c.Close()
			handleConn(ctx, c, wgEndpoint, wgDev, keys)
		}(dtlsConn)
	}
}

// ==================== Обработка соединений ====================

func handleConn(ctx context.Context, clientConn net.Conn, wgEndpoint string, wgDev *device.Device, keys *wgKeys) {
	atomic.AddInt64(&totalConns, 1)

	var connDeviceID string

	dtlsConn, ok := clientConn.(*dtls.Conn)
	if !ok {
		return
	}

	hctx, hcancel := context.WithTimeout(ctx, 60*time.Second)
	if err := dtlsConn.HandshakeContext(hctx); err != nil {
		hcancel()
		log.Printf("[DTLS] [ERR] Handshake failed from %s: %v", clientConn.RemoteAddr().String(), err)
		return
	}
	hcancel()

	atomic.AddInt32(&activeConns, 1)
	defer atomic.AddInt32(&activeConns, -1)

	buf := make([]byte, 1600)
	clientConn.SetReadDeadline(time.Now().Add(30 * time.Second))
	n, err := clientConn.Read(buf)
	if err != nil {
		return
	}
	clientConn.SetReadDeadline(time.Time{})

	firstPacket := buf[:n]
	firstStr := string(firstPacket)

	if strings.HasPrefix(firstStr, "GETCONF:") {
		parts := strings.Split(strings.TrimSpace(strings.TrimPrefix(firstStr, "GETCONF:")), "|")
		clientPort := "9000"
		deviceID := "unknown"
		password := ""
		if len(parts) > 0 {
			clientPort = parts[0]
		}
		if len(parts) > 1 {
			deviceID = parts[1]
		}
		if len(parts) > 2 {
			password = parts[2]
		}

		dbMutex.Lock()

		// Проверяем пароль
		isMainPass := password != "" && password == db.MainPassword
		entry, isGenPass := db.Passwords[password]
		valid := isMainPass || (isGenPass && !isPasswordExpired(entry))

		// Для сгенерированных паролей — проверяем привязку к устройству
		if valid && isGenPass && entry.IsDeactivated {
			clientConn.Write([]byte("DENIED:deactivated"))
			log.Printf("[WG] Отказ: пароль %s деактивирован, запрос от %s", maskPassword(password), deviceID)
			dbMutex.Unlock()
		} else if valid && isGenPass && !entry.canConnectAndBind(deviceID) {
			// Достигнут лимит устройств или привязано к другому устройству
			clientConn.Write([]byte("DENIED:device_mismatch"))
			log.Printf("[WG] Отказ: пароль %s достиг лимита устройств (%d), запрос от %s", maskPassword(password), entry.MaxDevices, deviceID)
			dbMutex.Unlock()
		} else if valid {
			connDeviceID = deviceID

			// Сохраняем БД, так как canConnectAndBind мог внести привязку нового устройства
			if isGenPass {
				saveDBLocked()
			}

			dev, exists := db.Devices[deviceID]
			if !exists {
				dev = &ClientDevice{
					DeviceID: deviceID,
					IP:       getNextIP(),
				}
				db.Devices[deviceID] = dev
			}

			if dev.PrivKey == "" || dev.PubKey == "" || dev.IP == "" {
				if dev.IP == "" {
					dev.IP = getNextIP()
				}

				privB64, pubB64, keyErr := generateKeyPair()
				if keyErr == nil {
					dev.PrivKey = privB64
					dev.PubKey = pubB64
					saveDBLocked()
					log.Printf("[WG] Инициализировано устройство %s (IP: %s)", deviceID, dev.IP)
				} else {
					dev = nil
				}
			}
			if dev != nil {
				dev.LastSeenAt = time.Now().Unix()
				upsertPeerInWG(wgDev, dev)
				clientConn.Write([]byte(buildClientConfig(keys.serverPublic, dev.PrivKey, dev.IP, clientPort)))
			} else {
				clientConn.Write([]byte("NOCONF"))
			}
			dbMutex.Unlock()
		} else {
			if isGenPass && isPasswordExpired(entry) {
				clientConn.Write([]byte("DENIED:expired"))
				log.Printf("[WG] Отказ: пароль %s истёк, от %s", maskPassword(password), deviceID)
			} else {
				clientConn.Write([]byte("DENIED:wrong_password"))
				log.Printf("[WG] Отказ (неверный пароль) от %s", deviceID)
			}
			dbMutex.Unlock()
		}

		clientConn.SetReadDeadline(time.Now().Add(5 * time.Minute))
		n, err = clientConn.Read(buf)
		if err != nil {
			return
		}
		clientConn.SetReadDeadline(time.Time{})
		firstPacket = buf[:n]
		firstStr = string(firstPacket)
	} else if strings.HasPrefix(firstStr, "AUTH:") {
		parts := strings.Split(strings.TrimSpace(strings.TrimPrefix(firstStr, "AUTH:")), "|")
		deviceID := "unknown"
		if len(parts) > 0 {
			deviceID = parts[0]
		}

		connDeviceID = deviceID

		clientConn.SetReadDeadline(time.Now().Add(5 * time.Minute))
		n, err = clientConn.Read(buf)
		if err != nil {
			return
		}
		clientConn.SetReadDeadline(time.Time{})
		firstPacket = buf[:n]
		firstStr = string(firstPacket)
	}

	if firstStr == "READY" {
		clientConn.Write([]byte("READY_OK"))
		clientConn.SetReadDeadline(time.Now().Add(10 * time.Minute))
		n, err = clientConn.Read(buf)
		if err != nil {
			return
		}
		clientConn.SetReadDeadline(time.Time{})
		firstPacket = buf[:n]
	}

	// WG прокси
	wgConn, err := net.Dial("udp", wgEndpoint)
	if err != nil {
		return
	}
	defer wgConn.Close()

	if uc, ok := wgConn.(*net.UDPConn); ok {
		uc.SetReadBuffer(2 * 1024 * 1024)
		uc.SetWriteBuffer(2 * 1024 * 1024)
	}

	if _, err := wgConn.Write(firstPacket); err != nil {
		return
	}
	atomic.AddInt64(&totalBytesFromClient, int64(len(firstPacket)))

	// Трекинг онлайн-статуса
	if connDeviceID != "" {
		activeDevicesMu.Lock()
		activeDevices[connDeviceID]++
		activeDevicesMu.Unlock()
		defer func() {
			activeDevicesMu.Lock()
			activeDevices[connDeviceID]--
			if activeDevices[connDeviceID] <= 0 {
				delete(activeDevices, connDeviceID)
			}
			activeDevicesMu.Unlock()
		}()
	}

	pctx, pcancel := context.WithCancel(ctx)
	defer pcancel()

	context.AfterFunc(pctx, func() {
		clientConn.SetDeadline(time.Now())
		wgConn.SetDeadline(time.Now())
	})

	var proxyWg sync.WaitGroup
	proxyWg.Add(2)

	// Клиент → WG
	go func() {
		defer proxyWg.Done()
		defer pcancel()
		b := getBuf()
		defer putBuf(b)
		for {
			select {
			case <-pctx.Done():
				return
			default:
			}
			clientConn.SetReadDeadline(time.Now().Add(30 * time.Minute))
			nn, err := clientConn.Read(*b)
			if err != nil {
				return
			}
			// Skip DTLS keepalive packets (1-byte 0xFF ping from client)
			if nn == 1 && (*b)[0] == 0xFF {
				continue
			}
			atomic.AddInt64(&totalBytesFromClient, int64(nn))
			// Учёт трафика теперь происходит через IpcGet()

			if _, err := wgConn.Write((*b)[:nn]); err != nil {
				return
			}
		}
	}()

	// WG → Клиент
	go func() {
		defer proxyWg.Done()
		defer pcancel()
		b := getBuf()
		defer putBuf(b)
		for {
			select {
			case <-pctx.Done():
				return
			default:
			}
			wgConn.SetReadDeadline(time.Now().Add(30 * time.Minute))
			nn, err := wgConn.Read(*b)
			if err != nil {
				if isNetTimeout(err) {
					if pctx.Err() != nil {
						return
					}
					continue
				}
				return
			}
			atomic.AddInt64(&totalBytesToClient, int64(nn))
			// Учёт трафика теперь происходит через IpcGet()

			if _, err := clientConn.Write((*b)[:nn]); err != nil {
				return
			}
		}
	}()

	proxyWg.Wait()
}

const (
	wrapNonceLen = 12
	wrapKeyLen   = 32
)

// ==================== RTP Обфускация ====================

type ObfsConfig struct {
	SSRC        uint32
	PayloadType uint8
	PaddingMax  int
}

var aeadCache sync.Map

func getAEAD(key []byte) (cipher.AEAD, error) {
	if len(key) != wrapKeyLen {
		return nil, fmt.Errorf("obfs: key must be %d bytes", wrapKeyLen)
	}
	keyStr := string(key)
	if val, ok := aeadCache.Load(keyStr); ok {
		return val.(cipher.AEAD), nil
	}
	aead, err := chacha20poly1305.New(key)
	if err != nil {
		return nil, err
	}
	aeadCache.Store(keyStr, aead)
	return aead, nil
}

type ObfsState struct {
	mu      sync.Mutex
	initSeq uint16
	initTs  uint32
	count   uint64
}

func NewObfsConfig() *ObfsConfig {
	var buf [4]byte
	rand.Read(buf[:])
	return &ObfsConfig{
		SSRC:        binary.BigEndian.Uint32(buf[:]),
		PayloadType: 111,
		PaddingMax:  24,
	}
}

func NewObfsState() *ObfsState {
	var buf [6]byte
	rand.Read(buf[:])
	return &ObfsState{
		initSeq: binary.BigEndian.Uint16(buf[0:2]),
		initTs:  binary.BigEndian.Uint32(buf[2:6]),
		count:   0,
	}
}

func obfsBuildNonce(ssrc uint32, seq uint16, ts uint32) []byte {
	n := make([]byte, 12)
	binary.BigEndian.PutUint32(n[0:4], ssrc)
	binary.BigEndian.PutUint16(n[4:6], seq)
	binary.BigEndian.PutUint32(n[8:12], ts)
	return n
}

func obfsWrapPacket(key, payload []byte, cfg *ObfsConfig, state *ObfsState) ([]byte, error) {
	if len(key) != wrapKeyLen {
		return nil, fmt.Errorf("obfs: key must be %d bytes (got %d)", wrapKeyLen, len(key))
	}
	if len(payload) == 0 {
		return nil, errors.New("obfs: empty payload")
	}
	state.mu.Lock()
	c := state.count
	state.count++
	state.mu.Unlock()

	seq := state.initSeq + uint16(c)
	ts := state.initTs + uint32(c)*960 + uint32(c>>16)

	nonce := obfsBuildNonce(cfg.SSRC, seq, ts)
	padRand := 0
	if cfg.PaddingMax > 0 {
		var rndBuf [1]byte
		rand.Read(rndBuf[:])
		padRand = int(rndBuf[0]) % cfg.PaddingMax
	}
	padTotal := padRand + 1
	outLen := 12 + len(payload) + chacha20poly1305.Overhead + padTotal
	out := make([]byte, outLen)

	out[0] = 0x80 | 0x20
	out[1] = cfg.PayloadType & 0x7F
	binary.BigEndian.PutUint16(out[2:4], seq)
	binary.BigEndian.PutUint32(out[4:8], ts)
	binary.BigEndian.PutUint32(out[8:12], cfg.SSRC)

	aead, err := getAEAD(key)
	if err != nil {
		return nil, fmt.Errorf("obfs: cipher init: %w", err)
	}
	sealed := aead.Seal(out[12:12], nonce, payload, out[:12])
	padStart := 12 + len(sealed)
	if padRand > 0 {
		rand.Read(out[padStart : padStart+padRand])
	}
	out[outLen-1] = byte(padTotal)
	return out, nil
}

func obfsUnwrapPacket(key, wire, dst []byte) (int, error) {
	if len(key) != wrapKeyLen {
		return 0, fmt.Errorf("obfs: key must be %d bytes (got %d)", wrapKeyLen, len(key))
	}
	if len(wire) < 13 {
		return 0, errors.New("obfs: packet too short")
	}
	if (wire[0] >> 6) != 2 {
		return 0, errors.New("obfs: not RTP v2")
	}
	seq := binary.BigEndian.Uint16(wire[2:4])
	ts := binary.BigEndian.Uint32(wire[4:8])
	ssrc := binary.BigEndian.Uint32(wire[8:12])

	payloadEnd := len(wire)
	if wire[0]&0x20 != 0 {
		padLen := int(wire[len(wire)-1])
		if padLen == 0 || padLen > payloadEnd-12 {
			return 0, fmt.Errorf("obfs: invalid padding length %d", padLen)
		}
		payloadEnd -= padLen
	}
	ciphertextLen := payloadEnd - 12
	if ciphertextLen <= chacha20poly1305.Overhead {
		return 0, errors.New("obfs: no payload")
	}
	if ciphertextLen-chacha20poly1305.Overhead > len(dst) {
		return 0, errors.New("obfs: dst buffer too small")
	}
	nonce := obfsBuildNonce(ssrc, seq, ts)
	aead, err := getAEAD(key)
	if err != nil {
		return 0, fmt.Errorf("obfs: cipher init: %w", err)
	}
	plain, err := aead.Open(dst[:0], nonce, wire[12:payloadEnd], wire[:12])
	if err != nil {
		return 0, fmt.Errorf("obfs: auth: %w", err)
	}
	return len(plain), nil
}
func obfsIsRTPPacket(wire []byte) bool {
	if len(wire) < 13 {
		return false
	}
	if (wire[0] >> 6) != 2 {
		return false
	}
	pt := wire[1] & 0x7F
	return pt == 111
}

func listenWrapped(addr *net.UDPAddr, keys *wrapKeyStore) (dtlsnet.PacketListener, error) {
	if keys == nil || keys.Count() == 0 {
		return nil, errors.New("wrap: no active keys")
	}
	inner, err := pionudp.Listen("udp", addr)
	if err != nil {
		return nil, fmt.Errorf("wrap: udp listen: %w", err)
	}
	return &wrapPacketListener{
		inner: dtlsnet.PacketListenerFromListener(inner),
		keys:  keys,
	}, nil
}

type wrapPacketListener struct {
	inner dtlsnet.PacketListener
	keys  *wrapKeyStore
}

func (l *wrapPacketListener) Accept() (net.PacketConn, net.Addr, error) {
	pc, addr, err := l.inner.Accept()
	if err != nil {
		return pc, addr, err
	}
	return &wrapPacketConn{inner: pc, keys: l.keys}, addr, nil
}

func (l *wrapPacketListener) Close() error   { return l.inner.Close() }
func (l *wrapPacketListener) Addr() net.Addr { return l.inner.Addr() }

type wrapPacketConn struct {
	inner     net.PacketConn
	keys      *wrapKeyStore
	key       []byte
	selected  int32
	authLog   int32
	obfsCfg   *ObfsConfig
	obfsWrite *ObfsState
}

func (c *wrapPacketConn) ReadFrom(p []byte) (int, net.Addr, error) {
	// Extra space for RTP header (12) + AEAD tag (16) + padding.
	buf := make([]byte, len(p)+80)
	n, addr, err := c.inner.ReadFrom(buf)
	if err != nil {
		return 0, addr, err
	}
	raw := buf[:n]

	if atomic.LoadInt32(&c.selected) == 0 {
		key, m, uErr := c.keys.Unwrap(raw, p)
		if uErr != nil {
			if atomic.CompareAndSwapInt32(&c.authLog, 0, 1) {
				log.Printf("[WRAP] Отказ: RTP AEAD auth failed from %s (keys=%d)", addr.String(), c.keys.Count())
			}
			return 0, addr, uErr
		}
		c.key = append([]byte(nil), key...) // Клонируем ключ в независимую память!
		c.obfsCfg = NewObfsConfig()
		c.obfsWrite = NewObfsState()
		atomic.StoreInt32(&c.selected, 1)
		if atomic.CompareAndSwapInt32(&c.authLog, 0, 1) {
			log.Printf("[WRAP] OK: ключ выбран для %s (keys=%d)", addr.String(), c.keys.Count())
		}
		return m, addr, nil
	}

	m, uErr := obfsUnwrapPacket(c.key, raw, p)
	if uErr != nil {
		// Если расшифровка старым ключом провалилась — возможно, пароль обновился!
		// Пробуем пере-верифицировать пакет по всем активным ключам
		key, m2, uErr2 := c.keys.Unwrap(raw, p)
		if uErr2 == nil {
			c.key = append([]byte(nil), key...) // На лету обновляем ключ сессии!
			c.obfsCfg = NewObfsConfig()
			c.obfsWrite = NewObfsState()
			log.Printf("[WRAP] Обновлен ключ на лету для %s (пароль изменился/обновился)", addr.String())
			return m2, addr, nil
		}
		return 0, addr, fmt.Errorf("obfs unwrap: %w", uErr)
	}
	return m, addr, nil
}

func (c *wrapPacketConn) WriteTo(p []byte, addr net.Addr) (int, error) {
	if atomic.LoadInt32(&c.selected) == 0 || len(c.key) != wrapKeyLen {
		return 0, errors.New("wrap: key not selected")
	}
	if c.obfsCfg == nil || c.obfsWrite == nil {
		c.obfsCfg = NewObfsConfig()
		c.obfsWrite = NewObfsState()
	}
	wrapped, wErr := obfsWrapPacket(c.key, p, c.obfsCfg, c.obfsWrite)
	if wErr != nil {
		return 0, fmt.Errorf("obfs wrap: %w", wErr)
	}
	if _, err := c.inner.WriteTo(wrapped, addr); err != nil {
		return 0, err
	}
	return len(p), nil
}

func (c *wrapPacketConn) Close() error                       { return c.inner.Close() }
func (c *wrapPacketConn) LocalAddr() net.Addr                { return c.inner.LocalAddr() }
func (c *wrapPacketConn) SetDeadline(t time.Time) error      { return c.inner.SetDeadline(t) }
func (c *wrapPacketConn) SetReadDeadline(t time.Time) error  { return c.inner.SetReadDeadline(t) }
func (c *wrapPacketConn) SetWriteDeadline(t time.Time) error { return c.inner.SetWriteDeadline(t) }
func extendPassword(pass string, days int) {
	dbMutex.Lock()
	defer dbMutex.Unlock()

	entry, exists := db.Passwords[pass]
	if !exists || entry == nil {
		return
	}

	now := time.Now().Unix()

	if entry.ExpiresAt < now {
		entry.ExpiresAt = now
	}

	entry.ExpiresAt += int64(days * 24 * 60 * 60)
	entry.IsDeactivated = false

	saveDBLocked()
}
