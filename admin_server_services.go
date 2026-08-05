package main

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"os/exec"
	"sort"
	"strconv"
	"strings"
	"time"
)

// -----------------------------------------------------------------------------
// Admin Server Services API
//
// Реализует backend для секции Services страницы Admin -> Server.
//
// Реализовано:
//   - получение состояния реальных systemd-сервисов;
//   - запуск;
//   - остановка;
//   - перезапуск с ожиданием перехода в running;
//   - единый JSON-формат ошибок.
//
// TODO:
// После финального согласования инфраструктуры сократить список candidate services
// до точного production-набора.
// -----------------------------------------------------------------------------
const adminServerPrimaryServiceName = "wdtt.service"

const (
	serviceActionStart   = "start"
	serviceActionStop    = "stop"
	serviceActionRestart = "restart"
)

var adminServerManagedServiceNames = []string{
	"wdtt.service",
	"wg-quick@wdtt0.service",
	"nginx.service",
	"caddy.service",
	"apache2.service",
}

type AdminServerServicesResponse struct {
	Services []AdminServerService `json:"services"`
}

type AdminServerService struct {
	Name        string   `json:"name"`
	Description string   `json:"description"`
	State       string   `json:"state"`
	SubState    string   `json:"subState"`
	MainPID     int      `json:"mainPid"`
	StartedAt   string   `json:"startedAt"`
	Uptime      string   `json:"uptime"`
	Memory      uint64   `json:"memory"`
	CPU         string   `json:"cpu"`
	IsRunning   bool     `json:"isRunning"`
	Actions     []string `json:"actions"`
}

type AdminServerServiceActionRequest struct {
	Service string `json:"service"`
}

type AdminServerServiceActionResponse struct {
	Success bool               `json:"success"`
	Error   string             `json:"error,omitempty"`
	Service AdminServerService `json:"service"`
}

// validateAdminServerServiceName ограничивает API только поддерживаемыми сервисами.
func validateAdminServerServiceName(serviceName string) error {
	for _, allowed := range adminServerManagedServiceNames {
		if serviceName == allowed {
			return nil
		}
	}

	return fmt.Errorf("unsupported service")
}

// writeAdminServerServiceActionJSON отправляет JSON-ответ в едином формате для service endpoints.
func writeAdminServerServiceActionJSON(w http.ResponseWriter, statusCode int, response AdminServerServiceActionResponse) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(statusCode)

	if err := json.NewEncoder(w).Encode(response); err != nil {
		log.Printf("[ADMIN SERVER] encode service response failed: %v", err)
	}
}

// writeAdminServerServicesJSONError отправляет JSON-ошибку для секции Services.
func writeAdminServerServicesJSONError(w http.ResponseWriter, statusCode int, message string) {
	writeAdminServerServiceActionJSON(w, statusCode, AdminServerServiceActionResponse{
		Success: false,
		Error:   message,
	})
}

// systemctlCommandError скрывает внутренние детали исполнения и пишет их только в логи.
func systemctlCommandError(ctx context.Context, action string, serviceName string, err error, output []byte) error {
	if ctx.Err() == context.DeadlineExceeded {
		log.Printf("[ADMIN SERVER] systemctl %s %s timed out: %v | %s", action, serviceName, err, strings.TrimSpace(string(output)))
		return fmt.Errorf("systemctl command timed out")
	}

	if len(output) > 0 {
		log.Printf("[ADMIN SERVER] systemctl %s %s failed: %v | %s", action, serviceName, err, strings.TrimSpace(string(output)))
	} else {
		log.Printf("[ADMIN SERVER] systemctl %s %s failed: %v", action, serviceName, err)
	}

	if action == "show" {
		return fmt.Errorf("failed to read system service status")
	}

	return fmt.Errorf("systemctl %s failed", action)
}

// getServiceActions возвращает допустимые действия для текущего состояния systemd-сервиса.
func getServiceActions(state string, subState string) []string {
	if state == "active" && subState == "running" {
		return []string{serviceActionStop, serviceActionRestart}
	}

	if state == "activating" || state == "deactivating" || subState == "auto-restart" {
		return []string{serviceActionRestart}
	}

	return []string{serviceActionStart}
}

// formatServiceCPUUsage переводит CPUUsageNSec в строку для frontend.
func formatServiceCPUUsage(value uint64) string {
	if value == 0 {
		return "0s"
	}

	duration := time.Duration(value)
	if duration >= time.Hour {
		return duration.Truncate(time.Second).String()
	}
	if duration >= time.Minute {
		return duration.Truncate(time.Second).String()
	}
	if duration >= time.Second {
		return duration.Truncate(100 * time.Millisecond).String()
	}

	return duration.String()
}

// formatServiceUptime вычисляет относительный uptime из systemd timestamp.
func formatServiceUptime(startedAt string) string {
	if startedAt == "" || strings.EqualFold(startedAt, "n/a") {
		return "Недоступно"
	}

	started, err := time.Parse("Mon 2006-01-02 15:04:05 MST", startedAt)
	if err != nil {
		return startedAt
	}

	elapsed := time.Since(started)
	if elapsed < time.Minute {
		return "только что"
	}

	days := int(elapsed.Hours()) / 24
	hours := int(elapsed.Hours()) % 24
	minutes := int(elapsed.Minutes()) % 60

	if days > 0 {
		return fmt.Sprintf("%dд %dч %dм", days, hours, minutes)
	}
	if hours > 0 {
		return fmt.Sprintf("%dч %dм", hours, minutes)
	}

	return fmt.Sprintf("%dм", minutes)
}

// getServiceInfo получает актуальную информацию о состоянии systemd-сервиса.
func getServiceInfo(serviceName string) (AdminServerService, error) {
	if err := validateAdminServerServiceName(serviceName); err != nil {
		return AdminServerService{}, err
	}

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	cmd := exec.CommandContext(
		ctx,
		"systemctl",
		"show",
		serviceName,
		"--no-pager",
		"--property=Id,Description,ActiveState,SubState,MainPID,ActiveEnterTimestamp,MemoryCurrent,CPUUsageNSec",
	)

	output, err := cmd.CombinedOutput()
	if err != nil {
		return AdminServerService{}, systemctlCommandError(ctx, "show", serviceName, err, output)
	}

	properties := make(map[string]string)
	for _, line := range strings.Split(strings.TrimSpace(string(output)), "\n") {
		key, value, ok := strings.Cut(line, "=")
		if !ok {
			continue
		}

		properties[key] = value
	}

	mainPID, _ := strconv.Atoi(properties["MainPID"])
	memoryValue, _ := strconv.ParseUint(properties["MemoryCurrent"], 10, 64)
	cpuValue, _ := strconv.ParseUint(properties["CPUUsageNSec"], 10, 64)

	service := AdminServerService{
		Name:        properties["Id"],
		Description: properties["Description"],
		State:       properties["ActiveState"],
		SubState:    properties["SubState"],
		MainPID:     mainPID,
		StartedAt:   properties["ActiveEnterTimestamp"],
		Uptime:      formatServiceUptime(properties["ActiveEnterTimestamp"]),
		Memory:      memoryValue,
		CPU:         formatServiceCPUUsage(cpuValue),
	}

	service.IsRunning = service.State == "active" && service.SubState == "running"
	service.Actions = getServiceActions(service.State, service.SubState)

	return service, nil
}

// listAdminServerServices возвращает все реальные systemd-сервисы, доступные для страницы Admin -> Server.
func listAdminServerServices() ([]AdminServerService, error) {
	services := make([]AdminServerService, 0, len(adminServerManagedServiceNames))

	for _, serviceName := range adminServerManagedServiceNames {
		service, err := getServiceInfo(serviceName)
		if err != nil {
			if serviceName == adminServerPrimaryServiceName {
				return nil, err
			}

			continue
		}

		services = append(services, service)
	}

	if len(services) == 0 {
		return nil, fmt.Errorf("failed to read system service status")
	}

	sort.Slice(services, func(i int, j int) bool {
		return services[i].Name < services[j].Name
	})

	return services, nil
}

// runSystemctlServiceAction выполняет start/stop/restart через systemctl с фиксированным timeout.
func runSystemctlServiceAction(action string, serviceName string) error {
	if err := validateAdminServerServiceName(serviceName); err != nil {
		return err
	}

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	cmd := exec.CommandContext(ctx, "systemctl", action, serviceName)
	output, err := cmd.CombinedOutput()
	if err != nil {
		return systemctlCommandError(ctx, action, serviceName, err, output)
	}

	return nil
}

// waitForRestartedServiceState несколько раз читает состояние сервиса после restart.
func waitForRestartedServiceState(serviceName string) (AdminServerService, error) {
	var lastService AdminServerService

	for attempt := 0; attempt < 7; attempt++ {
		service, err := getServiceInfo(serviceName)
		if err != nil {
			return AdminServerService{}, err
		}

		lastService = service
		if service.State == "active" && service.SubState == "running" {
			return service, nil
		}

		if attempt < 6 {
			time.Sleep(300 * time.Millisecond)
		}
	}

	return lastService, nil
}

// adminServerServicesHandler возвращает список systemd-сервисов для секции Services.
func adminServerServicesHandler(w http.ResponseWriter, r *http.Request) {
	if !requireAdminServerMethodAndAuth(w, r, http.MethodGet) {
		return
	}

	services, err := listAdminServerServices()
	if err != nil {
		log.Printf("[ADMIN SERVER] get services failed: %v", err)
		writeAdminServerServicesJSONError(w, http.StatusInternalServerError, err.Error())
		return
	}

	w.Header().Set("Content-Type", "application/json")
	if err := json.NewEncoder(w).Encode(AdminServerServicesResponse{Services: services}); err != nil {
		log.Printf("[ADMIN SERVER] encode services response failed: %v", err)
	}
}

// handleAdminServerServiceAction выполняет действие над сервисом и возвращает его актуальное состояние.
func handleAdminServerServiceAction(w http.ResponseWriter, r *http.Request, action string) {
	if !requireAdminServerMethodAndAuth(w, r, http.MethodPost) {
		return
	}

	var request AdminServerServiceActionRequest
	if err := json.NewDecoder(r.Body).Decode(&request); err != nil {
		writeAdminServerServicesJSONError(w, http.StatusBadRequest, "invalid request body")
		return
	}

	if err := validateAdminServerServiceName(request.Service); err != nil {
		writeAdminServerServicesJSONError(w, http.StatusBadRequest, err.Error())
		return
	}

	if err := runSystemctlServiceAction(action, request.Service); err != nil {
		writeAdminServerServicesJSONError(w, http.StatusInternalServerError, err.Error())
		return
	}

	service := AdminServerService{}
	var err error
	if action == serviceActionRestart {
		service, err = waitForRestartedServiceState(request.Service)
	} else {
		service, err = getServiceInfo(request.Service)
	}
	if err != nil {
		log.Printf("[ADMIN SERVER] refresh service info after %s failed: %v", action, err)
		writeAdminServerServicesJSONError(w, http.StatusInternalServerError, err.Error())
		return
	}

	recordAdminServerEvent(fmt.Sprintf("Service %s: %s", action, request.Service), adminServerEventToneBlue)
	writeAdminServerServiceActionJSON(w, http.StatusOK, AdminServerServiceActionResponse{
		Success: true,
		Service: service,
	})
}

// adminServerServiceStartHandler запускает поддерживаемый systemd-сервис.
func adminServerServiceStartHandler(w http.ResponseWriter, r *http.Request) {
	handleAdminServerServiceAction(w, r, serviceActionStart)
}

// adminServerServiceStopHandler останавливает поддерживаемый systemd-сервис.
func adminServerServiceStopHandler(w http.ResponseWriter, r *http.Request) {
	handleAdminServerServiceAction(w, r, serviceActionStop)
}

// adminServerServiceRestartHandler перезапускает сервис и дожидается обновления его состояния.
func adminServerServiceRestartHandler(w http.ResponseWriter, r *http.Request) {
	handleAdminServerServiceAction(w, r, serviceActionRestart)
}
