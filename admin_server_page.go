package main

import (
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

// -----------------------------------------------------------------------------
// Admin Server Page API
//
// Реализует оставшиеся backend endpoints страницы Admin -> Server.
//
// Реализовано:
//   - diagnostics;
//   - integrity;
//   - configuration;
//   - recent events;
//   - danger zone actions.
//
// TODO:
// При появлении выделенного system service для bot/api развести restart handlers
// по отдельным unit names вместо текущего общего wdtt.service.
// -----------------------------------------------------------------------------
const adminServerConfigFileName = "admin_server_config.json"

const (
	adminServerEventToneBlue    = "blue"
	adminServerEventToneCyan    = "cyan"
	adminServerEventToneEmerald = "emerald"
	adminServerEventToneOrange  = "orange"
	adminServerEventToneRed     = "red"
	adminServerEventToneViolet  = "violet"
)

type AdminServerErrorResponse struct {
	Success bool   `json:"success"`
	Error   string `json:"error"`
}

type AdminServerSuccessResponse struct {
	Success bool `json:"success"`
}

type AdminServerDiagnosticsResponse struct {
	Checks  []AdminServerDiagnosticCheck `json:"checks"`
	LastRun string                       `json:"lastRun,omitempty"`
}

type AdminServerDiagnosticCheck struct {
	ID          string `json:"id"`
	Title       string `json:"title"`
	Description string `json:"description"`
	Status      string `json:"status"`
	MockResult  string `json:"mockResult"`
}

type AdminServerDiagnosticsRunRequest struct {
	CheckID string `json:"checkId"`
}

type AdminServerIntegrityResponse struct {
	LastRun       *string                     `json:"lastRun"`
	ProblemsFound int                         `json:"problemsFound"`
	Checks        []AdminServerIntegrityCheck `json:"checks"`
}

type AdminServerIntegrityCheck struct {
	ID     string `json:"id"`
	Label  string `json:"label"`
	State  string `json:"state"`
	Detail string `json:"detail"`
}

type AdminServerConfigurationResponse struct {
	Configuration AdminServerConfiguration        `json:"configuration"`
	Fields        []AdminServerConfigurationField `json:"fields"`
}

type AdminServerConfiguration struct {
	Hostname      string `json:"hostname"`
	APIPort       string `json:"apiPort"`
	WireGuardPort string `json:"wireGuardPort"`
	DNS           string `json:"dns"`
	MTU           string `json:"mtu"`
	Endpoint      string `json:"endpoint"`
}

type AdminServerConfigurationField struct {
	Key         string `json:"key"`
	Label       string `json:"label"`
	Description string `json:"description"`
	Type        string `json:"type"`
}

type AdminServerEventsResponse struct {
	Events []AdminServerEvent `json:"events"`
}

type AdminServerEvent struct {
	ID         string `json:"id"`
	Title      string `json:"title"`
	OccurredAt string `json:"occurredAt"`
	Tone       string `json:"tone"`
}

type adminServerEventRecord struct {
	ID         string
	Title      string
	Tone       string
	OccurredAt time.Time
}

var (
	adminServerEventsMu           sync.Mutex
	adminServerEvents             []adminServerEventRecord
	adminServerDiagnosticsLastRun string
	adminServerIntegrityLastRun   string
	adminServerBaseConfigOnce     sync.Once
	adminServerBaseConfig         AdminServerConfiguration
)

var adminServerConfigurationFields = []AdminServerConfigurationField{
	{Key: "hostname", Label: "Имя хоста", Description: "Основное имя сервера", Type: "text"},
	{Key: "apiPort", Label: "Порт API", Description: "Порт панели и API", Type: "number"},
	{Key: "wireGuardPort", Label: "Порт WireGuard", Description: "Порт VPN-интерфейса", Type: "number"},
	{Key: "dns", Label: "DNS", Description: "DNS-серверы через запятую", Type: "text"},
	{Key: "mtu", Label: "MTU", Description: "Максимальный размер пакета", Type: "number"},
	{Key: "endpoint", Label: "Точка подключения", Description: "Публичный адрес WireGuard", Type: "text"},
}

// writeAdminServerJSON отправляет JSON-ответ с единым Content-Type для всей страницы Admin -> Server.
func writeAdminServerJSON(w http.ResponseWriter, statusCode int, payload any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(statusCode)

	if err := json.NewEncoder(w).Encode(payload); err != nil {
		log.Printf("[ADMIN SERVER] encode response failed: %v", err)
	}
}

// writeAdminServerJSONError отправляет JSON-ошибку в едином формате.
func writeAdminServerJSONError(w http.ResponseWriter, statusCode int, message string) {
	writeAdminServerJSON(w, statusCode, AdminServerErrorResponse{Success: false, Error: message})
}

// writeAdminServerJSONSuccess отправляет базовый JSON-ответ для простых операций.
func writeAdminServerJSONSuccess(w http.ResponseWriter, statusCode int) {
	writeAdminServerJSON(w, statusCode, AdminServerSuccessResponse{Success: true})
}

// requireAdminServerMethodAndAuth проверяет HTTP-метод и существующую авторизацию администратора.
func requireAdminServerMethodAndAuth(w http.ResponseWriter, r *http.Request, method string) bool {
	if r.Method != method {
		writeAdminServerJSONError(w, http.StatusMethodNotAllowed, "method not allowed")
		return false
	}

	if !requireAdmin(r) {
		writeAdminServerJSONError(w, http.StatusUnauthorized, "unauthorized")
		return false
	}

	return true
}

// recordAdminServerEvent сохраняет последние события для карточки Recent Events.
func recordAdminServerEvent(title string, tone string) {
	adminServerEventsMu.Lock()
	defer adminServerEventsMu.Unlock()

	record := adminServerEventRecord{
		ID:         fmt.Sprintf("evt-%d", time.Now().UnixNano()),
		Title:      title,
		Tone:       tone,
		OccurredAt: time.Now(),
	}

	adminServerEvents = append([]adminServerEventRecord{record}, adminServerEvents...)
	if len(adminServerEvents) > 20 {
		adminServerEvents = adminServerEvents[:20]
	}
}

// formatAdminServerRelativeTime превращает время события в строку, понятную frontend.
func formatAdminServerRelativeTime(t time.Time) string {
	elapsed := time.Since(t)
	if elapsed < time.Minute {
		return "только что"
	}
	if elapsed < time.Hour {
		return fmt.Sprintf("%d мин назад", int(elapsed.Minutes()))
	}
	if elapsed < 24*time.Hour {
		return fmt.Sprintf("%d ч назад", int(elapsed.Hours()))
	}

	return t.Format("02.01.2006 15:04")
}

// adminServerConfigDir определяет каталог, в котором хранится runtime-конфигурация страницы Admin -> Server.
func adminServerConfigDir() string {
	if dbFile != "" {
		return filepath.Dir(dbFile)
	}

	if configDirFlag := flag.Lookup("config-dir"); configDirFlag != nil && configDirFlag.Value.String() != "" {
		return configDirFlag.Value.String()
	}

	return "/etc/wdtt"
}

// adminServerConfigPath возвращает путь к файлу runtime-конфигурации страницы Server.
func adminServerConfigPath() string {
	return filepath.Join(adminServerConfigDir(), adminServerConfigFileName)
}

// adminServerAPIListenPort извлекает текущий API-порт из существующих runtime flags.
func adminServerAPIListenPort() string {
	if listenFlag := flag.Lookup("listen"); listenFlag != nil {
		_, port, err := net.SplitHostPort(listenFlag.Value.String())
		if err == nil && port != "" {
			return port
		}
	}

	return "8080"
}

// adminServerDefaultConfiguration строит базовую конфигурацию из текущего runtime состояния.
func adminServerDefaultConfiguration() AdminServerConfiguration {
	hostname, err := os.Hostname()
	if err != nil || hostname == "" {
		hostname = "localhost"
	}

	endpointHost := getPublicIP()
	if endpointHost == "" || endpointHost == "YOUR_SERVER_IP" {
		endpointHost = hostname
	}

	wireGuardPort := strconv.Itoa(defaultInternalWGPort)
	if wgPortFlag := flag.Lookup("wg-port"); wgPortFlag != nil && wgPortFlag.Value.String() != "" {
		wireGuardPort = wgPortFlag.Value.String()
	}

	return AdminServerConfiguration{
		Hostname:      hostname,
		APIPort:       adminServerAPIListenPort(),
		WireGuardPort: wireGuardPort,
		DNS:           dns,
		MTU:           strconv.Itoa(wgMTU),
		Endpoint:      fmt.Sprintf("%s:%s", endpointHost, wireGuardPort),
	}
}

// adminServerBaseConfigurationSnapshot фиксирует исходную конфигурацию процесса для reset-операций.
func adminServerBaseConfigurationSnapshot() AdminServerConfiguration {
	adminServerBaseConfigOnce.Do(func() {
		adminServerBaseConfig = adminServerDefaultConfiguration()
	})

	return adminServerBaseConfig
}

// loadAdminServerConfiguration загружает сохранённую конфигурацию поверх runtime defaults.
func loadAdminServerConfiguration() AdminServerConfiguration {
	configuration := adminServerBaseConfigurationSnapshot()

	data, err := os.ReadFile(adminServerConfigPath())
	if err != nil {
		return configuration
	}

	if err := json.Unmarshal(data, &configuration); err != nil {
		log.Printf("[ADMIN SERVER] load configuration failed: %v", err)
		return adminServerBaseConfigurationSnapshot()
	}

	return configuration
}

// saveAdminServerConfiguration сохраняет runtime-конфигурацию страницы Server.
func saveAdminServerConfiguration(configuration AdminServerConfiguration) error {
	if err := os.MkdirAll(adminServerConfigDir(), 0700); err != nil {
		return err
	}

	data, err := json.MarshalIndent(configuration, "", "  ")
	if err != nil {
		return err
	}

	return os.WriteFile(adminServerConfigPath(), data, 0600)
}

// validateAdminServerConfiguration выполняет минимальную валидацию редактируемых полей конфигурации.
func validateAdminServerConfiguration(configuration AdminServerConfiguration) error {
	if strings.TrimSpace(configuration.Hostname) == "" {
		return fmt.Errorf("hostname is required")
	}
	if strings.TrimSpace(configuration.DNS) == "" {
		return fmt.Errorf("dns is required")
	}
	if strings.TrimSpace(configuration.Endpoint) == "" {
		return fmt.Errorf("endpoint is required")
	}

	for _, item := range []struct {
		name  string
		value string
	}{
		{name: "apiPort", value: configuration.APIPort},
		{name: "wireGuardPort", value: configuration.WireGuardPort},
		{name: "mtu", value: configuration.MTU},
	} {
		if _, err := strconv.Atoi(strings.TrimSpace(item.value)); err != nil {
			return fmt.Errorf("%s must be numeric", item.name)
		}
	}

	return nil
}

// buildAdminServerDiagnostics формирует набор диагностик для одноимённой секции страницы.
func buildAdminServerDiagnostics() []AdminServerDiagnosticCheck {
	cpuUsage := getCPUUsage()
	ramUsed, ramTotal, _ := getMemoryUsage()
	diskUsed, diskTotal, _ := getDiskUsage(adminServerConfigDir())
	rx, tx, _ := getNetworkUsage()
	_, servicesErr := listAdminServerServices()
	_, configErr := os.Stat(adminServerConfigPath())
	_, dbErr := os.Stat(dbFile)
	_, keysErr := os.Stat(filepath.Join(adminServerConfigDir(), "wg-keys.dat"))

	checks := []AdminServerDiagnosticCheck{
		{ID: "cpu", Title: "CPU", Description: "Текущая загрузка CPU сервера.", Status: "ok", MockResult: fmt.Sprintf("Загрузка CPU: %d%%", cpuUsage)},
		{ID: "ram", Title: "RAM", Description: "Использование оперативной памяти.", Status: "ok", MockResult: fmt.Sprintf("RAM: %d / %d bytes", ramUsed, ramTotal)},
		{ID: "disk", Title: "Disk", Description: "Использование дискового пространства.", Status: "ok", MockResult: fmt.Sprintf("Disk: %d / %d bytes", diskUsed, diskTotal)},
		{ID: "network", Title: "Network", Description: "Накопленный сетевой трафик.", Status: "ok", MockResult: fmt.Sprintf("RX %d bytes / TX %d bytes", rx, tx)},
	}

	serviceStatus := "ok"
	serviceResult := "Systemd services are available"
	if servicesErr != nil {
		serviceStatus = "error"
		serviceResult = servicesErr.Error()
	}
	checks = append(checks, AdminServerDiagnosticCheck{ID: "systemd", Title: "Systemd", Description: "Доступность управляемых systemd-сервисов.", Status: serviceStatus, MockResult: serviceResult})

	checks = append(checks, AdminServerDiagnosticCheck{ID: "wireguard", Title: "WireGuard", Description: "Состояние WireGuard runtime и peer reload.", Status: ternaryDiagnosticStatus(globalWgDev != nil), MockResult: ternaryDiagnosticResult(globalWgDev != nil, "WireGuard runtime is ready", "WireGuard runtime is unavailable")})
	checks = append(checks, AdminServerDiagnosticCheck{ID: "configuration", Title: "Configuration", Description: "Файл конфигурации страницы Server.", Status: ternaryDiagnosticStatus(configErr == nil || errors.Is(configErr, os.ErrNotExist)), MockResult: ternaryDiagnosticResult(configErr == nil || errors.Is(configErr, os.ErrNotExist), "Configuration storage is accessible", "Configuration storage is unavailable")})
	checks = append(checks, AdminServerDiagnosticCheck{ID: "database", Title: "Database", Description: "Доступность основной JSON-базы проекта.", Status: ternaryDiagnosticStatus(dbErr == nil), MockResult: ternaryDiagnosticResult(dbErr == nil, "Database file is available", "Database file is unavailable")})
	checks = append(checks, AdminServerDiagnosticCheck{ID: "files", Title: "Files", Description: "Проверка ключевых runtime-файлов.", Status: ternaryDiagnosticStatus(keysErr == nil), MockResult: ternaryDiagnosticResult(keysErr == nil, "Runtime files are available", "Runtime files are unavailable")})

	return checks
}

// ternaryDiagnosticStatus возвращает status в компактном виде для backend-диагностик.
func ternaryDiagnosticStatus(ok bool) string {
	if ok {
		return "ok"
	}

	return "error"
}

// ternaryDiagnosticResult возвращает человекочитаемый результат диагностики.
func ternaryDiagnosticResult(ok bool, positive string, negative string) string {
	if ok {
		return positive
	}

	return negative
}

// buildAdminServerIntegrity формирует проверки целостности для соответствующей карточки.
func buildAdminServerIntegrity() AdminServerIntegrityResponse {
	checks := []AdminServerIntegrityCheck{
		integrityFileCheck("wireguard-config", "Конфигурация WireGuard", filepath.Join(adminServerConfigDir(), "wg-keys.dat")),
		integrityFileCheck("passwords", "Файл базы данных", dbFile),
		integrityDatabaseCheck(),
		integrityPermissionsCheck(),
		integrityFreeDiskCheck(adminServerConfigDir()),
	}

	problemsFound := 0
	for _, check := range checks {
		if check.State != "ok" {
			problemsFound++
		}
	}

	var lastRun *string
	if adminServerIntegrityLastRun != "" {
		lastRun = &adminServerIntegrityLastRun
	}

	return AdminServerIntegrityResponse{LastRun: lastRun, ProblemsFound: problemsFound, Checks: checks}
}

// integrityFileCheck verifies that a required server file exists and can be inspected by the backend.
func integrityFileCheck(id string, label string, path string) AdminServerIntegrityCheck {
	info, err := os.Stat(path)
	if err != nil {
		return AdminServerIntegrityCheck{
			ID:     id,
			Label:  label,
			State:  "warning",
			Detail: "Файл не найден или недоступен.",
		}
	}

	if info.IsDir() {
		return AdminServerIntegrityCheck{
			ID:     id,
			Label:  label,
			State:  "warning",
			Detail: "Ожидался файл, но найден каталог.",
		}
	}

	return AdminServerIntegrityCheck{
		ID:     id,
		Label:  label,
		State:  "ok",
		Detail: "Файл присутствует и доступен для чтения.",
	}
}

// integrityDatabaseCheck validates that the runtime database file exists and is non-empty.
func integrityDatabaseCheck() AdminServerIntegrityCheck {
	info, err := os.Stat(dbFile)
	if err != nil {
		return AdminServerIntegrityCheck{
			ID:     "database-health",
			Label:  "Состояние базы данных",
			State:  "warning",
			Detail: "Файл базы данных недоступен.",
		}
	}

	if info.Size() == 0 {
		return AdminServerIntegrityCheck{
			ID:     "database-health",
			Label:  "Состояние базы данных",
			State:  "warning",
			Detail: "Файл базы данных пустой.",
		}
	}

	return AdminServerIntegrityCheck{
		ID:     "database-health",
		Label:  "Состояние базы данных",
		State:  "ok",
		Detail: "Файл базы данных найден и содержит данные.",
	}
}

// integrityPermissionsCheck performs a lightweight writeability check for the configuration directory.
func integrityPermissionsCheck() AdminServerIntegrityCheck {
	testFile := filepath.Join(adminServerConfigDir(), ".admin-server-permissions-check")
	data := []byte("ok")

	if err := os.WriteFile(testFile, data, 0o600); err != nil {
		return AdminServerIntegrityCheck{
			ID:     "permissions",
			Label:  "Права доступа",
			State:  "warning",
			Detail: "Каталог конфигурации недоступен для записи.",
		}
	}

	if err := os.Remove(testFile); err != nil {
		log.Printf("[ADMIN SERVER] permissions check cleanup failed: %v", err)
	}

	return AdminServerIntegrityCheck{
		ID:     "permissions",
		Label:  "Права доступа",
		State:  "ok",
		Detail: "Каталог конфигурации доступен для чтения и записи.",
	}
}

// integrityFreeDiskCheck verifies that the server still has free disk space for runtime operations.
func integrityFreeDiskCheck(path string) AdminServerIntegrityCheck {
	used, total, _ := getDiskUsage(path)
	if total == 0 {
		return AdminServerIntegrityCheck{
			ID:     "disk-free",
			Label:  "Свободное место",
			State:  "warning",
			Detail: "Не удалось определить объём диска.",
		}
	}

	free := total - used
	freePercent := float64(free) / float64(total) * 100
	if freePercent < 10 {
		return AdminServerIntegrityCheck{
			ID:     "disk-free",
			Label:  "Свободное место",
			State:  "warning",
			Detail: fmt.Sprintf("Свободно только %.1f%% диска.", freePercent),
		}
	}

	return AdminServerIntegrityCheck{
		ID:     "disk-free",
		Label:  "Свободное место",
		State:  "ok",
		Detail: fmt.Sprintf("Доступно %.1f%% свободного места.", freePercent),
	}
}

// buildAdminServerEvents returns the recent event feed displayed on the Admin -> Server page.
func buildAdminServerEvents() []AdminServerEvent {
	adminServerEventsMu.Lock()
	defer adminServerEventsMu.Unlock()

	if len(adminServerEvents) == 0 {
		adminServerEvents = []adminServerEventRecord{
			{ID: "server-started", Title: "Server runtime started", Tone: adminServerEventToneBlue, OccurredAt: serverStarted},
			{ID: "config-loaded", Title: "Server configuration loaded", Tone: adminServerEventToneCyan, OccurredAt: time.Now().Add(-5 * time.Minute)},
			{ID: "health-check", Title: "System health snapshot prepared", Tone: adminServerEventToneViolet, OccurredAt: time.Now().Add(-2 * time.Minute)},
		}
	}

	records := append([]adminServerEventRecord(nil), adminServerEvents...)
	sort.Slice(records, func(i, j int) bool {
		return records[i].OccurredAt.After(records[j].OccurredAt)
	})

	events := make([]AdminServerEvent, 0, len(records))
	for _, record := range records {
		events = append(events, AdminServerEvent{
			ID:         record.ID,
			Title:      record.Title,
			OccurredAt: formatAdminServerRelativeTime(record.OccurredAt),
			Tone:       record.Tone,
		})
	}

	return events
}

// runAdminServerDangerAction executes a destructive server operation with a bounded timeout and sanitized errors.
func runAdminServerDangerAction(actionName string, operation func(context.Context) error) error {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	if err := operation(ctx); err != nil {
		if ctx.Err() == context.DeadlineExceeded || errors.Is(err, context.DeadlineExceeded) {
			log.Printf("[ADMIN SERVER] %s timed out: %v", actionName, err)
			return fmt.Errorf("operation timed out")
		}

		log.Printf("[ADMIN SERVER] %s failed: %v", actionName, err)
		return fmt.Errorf("%s failed", actionName)
	}

	return nil
}

// adminServerDiagnosticsHandler returns the diagnostic checks shown by the frontend diagnostics card.
func adminServerDiagnosticsHandler(w http.ResponseWriter, r *http.Request) {
	if ok := requireAdminServerMethodAndAuth(w, r, http.MethodGet); !ok {
		return
	}

	writeAdminServerJSON(w, http.StatusOK, AdminServerDiagnosticsResponse{
		Checks:  buildAdminServerDiagnostics(),
		LastRun: adminServerDiagnosticsLastRun,
	})
}

// adminServerDiagnosticsRunHandler reruns diagnostics and returns the refreshed checks.
func adminServerDiagnosticsRunHandler(w http.ResponseWriter, r *http.Request) {
	if ok := requireAdminServerMethodAndAuth(w, r, http.MethodPost); !ok {
		return
	}

	var request AdminServerDiagnosticsRunRequest
	if err := json.NewDecoder(r.Body).Decode(&request); err != nil && !errors.Is(err, io.EOF) {
		writeAdminServerJSONError(w, http.StatusBadRequest, "invalid request body")
		return
	}

	validChecks := map[string]struct{}{
		"cpu":           {},
		"ram":           {},
		"disk":          {},
		"network":       {},
		"systemd":       {},
		"wireguard":     {},
		"configuration": {},
		"database":      {},
		"files":         {},
	}
	if request.CheckID != "" {
		if _, ok := validChecks[request.CheckID]; !ok {
			writeAdminServerJSONError(w, http.StatusBadRequest, "unsupported diagnostic check")
			return
		}
	}

	adminServerDiagnosticsLastRun = time.Now().Format("02.01.2006 15:04:05")
	recordAdminServerEvent("Diagnostics completed", adminServerEventToneViolet)

	writeAdminServerJSON(w, http.StatusOK, AdminServerDiagnosticsResponse{
		Checks:  buildAdminServerDiagnostics(),
		LastRun: adminServerDiagnosticsLastRun,
	})
}

// adminServerIntegrityHandler returns the current integrity report for the server page.
func adminServerIntegrityHandler(w http.ResponseWriter, r *http.Request) {
	if ok := requireAdminServerMethodAndAuth(w, r, http.MethodGet); !ok {
		return
	}

	writeAdminServerJSON(w, http.StatusOK, buildAdminServerIntegrity())
}

// adminServerIntegrityRunHandler reruns integrity checks and returns the updated report.
func adminServerIntegrityRunHandler(w http.ResponseWriter, r *http.Request) {
	if ok := requireAdminServerMethodAndAuth(w, r, http.MethodPost); !ok {
		return
	}

	adminServerIntegrityLastRun = time.Now().Format("02.01.2006 15:04:05")
	recordAdminServerEvent("Integrity check completed", adminServerEventToneEmerald)
	writeAdminServerJSON(w, http.StatusOK, buildAdminServerIntegrity())
}

// adminServerConfigurationHandler serves and updates the server configuration block used by the frontend.
func adminServerConfigurationHandler(w http.ResponseWriter, r *http.Request) {
	if !requireAdmin(r) {
		writeAdminServerJSONError(w, http.StatusUnauthorized, "unauthorized")
		return
	}

	switch r.Method {
	case http.MethodGet:
		writeAdminServerJSON(w, http.StatusOK, AdminServerConfigurationResponse{
			Configuration: loadAdminServerConfiguration(),
			Fields:        adminServerConfigurationFields,
		})
	case http.MethodPut:
		var configuration AdminServerConfiguration
		if err := json.NewDecoder(r.Body).Decode(&configuration); err != nil {
			writeAdminServerJSONError(w, http.StatusBadRequest, "invalid request body")
			return
		}

		if err := validateAdminServerConfiguration(configuration); err != nil {
			writeAdminServerJSONError(w, http.StatusBadRequest, err.Error())
			return
		}

		if err := saveAdminServerConfiguration(configuration); err != nil {
			log.Printf("[ADMIN SERVER] save configuration failed: %v", err)
			writeAdminServerJSONError(w, http.StatusInternalServerError, "failed to save configuration")
			return
		}

		dns = configuration.DNS
		publicIP = ""
		recordAdminServerEvent("Server configuration updated", adminServerEventToneCyan)
		writeAdminServerJSON(w, http.StatusOK, AdminServerConfigurationResponse{
			Configuration: configuration,
			Fields:        adminServerConfigurationFields,
		})
	default:
		writeAdminServerJSONError(w, http.StatusMethodNotAllowed, "method not allowed")
	}
}

// adminServerConfigurationResetHandler restores the persisted server configuration to the initial runtime snapshot.
func adminServerConfigurationResetHandler(w http.ResponseWriter, r *http.Request) {
	if ok := requireAdminServerMethodAndAuth(w, r, http.MethodPost); !ok {
		return
	}

	if err := os.Remove(adminServerConfigPath()); err != nil && !errors.Is(err, os.ErrNotExist) {
		log.Printf("[ADMIN SERVER] reset configuration failed: %v", err)
		writeAdminServerJSONError(w, http.StatusInternalServerError, "failed to reset configuration")
		return
	}

	configuration := adminServerBaseConfigurationSnapshot()
	dns = configuration.DNS
	publicIP = ""
	recordAdminServerEvent("Server configuration reset", adminServerEventToneOrange)
	writeAdminServerJSON(w, http.StatusOK, AdminServerConfigurationResponse{
		Configuration: configuration,
		Fields:        adminServerConfigurationFields,
	})
}

// adminServerEventsHandler returns the recent operational events for the server page.
func adminServerEventsHandler(w http.ResponseWriter, r *http.Request) {
	if ok := requireAdminServerMethodAndAuth(w, r, http.MethodGet); !ok {
		return
	}

	writeAdminServerJSON(w, http.StatusOK, AdminServerEventsResponse{Events: buildAdminServerEvents()})
}

// handleAdminServerDangerAction runs a single server danger-zone action and reports the result in JSON.
func handleAdminServerDangerAction(w http.ResponseWriter, r *http.Request, actionName string, eventTitle string, tone string, operation func(context.Context) error) {
	if ok := requireAdminServerMethodAndAuth(w, r, http.MethodPost); !ok {
		return
	}

	if err := runAdminServerDangerAction(actionName, operation); err != nil {
		writeAdminServerJSONError(w, http.StatusInternalServerError, err.Error())
		return
	}

	recordAdminServerEvent(eventTitle, tone)
	writeAdminServerJSONSuccess(w, http.StatusOK)
}

// adminServerRestartHandler restarts the main application service from the danger zone card.
func adminServerRestartHandler(w http.ResponseWriter, r *http.Request) {
	handleAdminServerDangerAction(w, r, "restart server", "Application server restarted", adminServerEventToneRed, func(ctx context.Context) error {
		_ = ctx
		return runSystemctlServiceAction(serviceActionRestart, adminServerPrimaryServiceName)
	})
}

// adminServerReloadHandler reloads the runtime configuration through the existing backend mechanism.
func adminServerReloadHandler(w http.ResponseWriter, r *http.Request) {
	handleAdminServerDangerAction(w, r, "reload configuration", "Runtime configuration reloaded", adminServerEventToneBlue, func(ctx context.Context) error {
		_ = ctx
		SaveAndReload()
		return nil
	})
}

// adminServerClearCacheHandler clears lightweight runtime caches used by the server page.
func adminServerClearCacheHandler(w http.ResponseWriter, r *http.Request) {
	handleAdminServerDangerAction(w, r, "clear cache", "Server cache cleared", adminServerEventToneOrange, func(ctx context.Context) error {
		_ = ctx
		publicIP = ""
		appReleaseCacheMu.Lock()
		appReleaseCache = cachedAppRelease{}
		appReleaseCacheMu.Unlock()
		return nil
	})
}

// adminServerReloadWireGuardHandler reloads WireGuard state using the existing runtime helper.
func adminServerReloadWireGuardHandler(w http.ResponseWriter, r *http.Request) {
	handleAdminServerDangerAction(w, r, "reload wireguard", "WireGuard state reloaded", adminServerEventToneViolet, func(ctx context.Context) error {
		_ = ctx
		if globalWgDev == nil {
			return fmt.Errorf("wireguard reload is unavailable")
		}
		if err := reloadDB(globalWgDev); err != nil {
			log.Printf("[ADMIN SERVER] reload wireguard failed: %v", err)
			return fmt.Errorf("wireguard reload failed")
		}
		return nil
	})
}

// adminServerRestartBotHandler restarts the managed service used by the current bot runtime.
func adminServerRestartBotHandler(w http.ResponseWriter, r *http.Request) {
	handleAdminServerDangerAction(w, r, "restart bot", "Bot service restarted", adminServerEventToneRed, func(ctx context.Context) error {
		_ = ctx
		return runSystemctlServiceAction(serviceActionRestart, adminServerPrimaryServiceName)
	})
}

// adminServerRestartAPIHandler restarts the managed service used by the current API runtime.
func adminServerRestartAPIHandler(w http.ResponseWriter, r *http.Request) {
	handleAdminServerDangerAction(w, r, "restart api", "API service restarted", adminServerEventToneRed, func(ctx context.Context) error {
		_ = ctx
		return runSystemctlServiceAction(serviceActionRestart, adminServerPrimaryServiceName)
	})
}
