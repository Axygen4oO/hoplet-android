package main

import (
	"encoding/json"
	"net/http"
)

// ServerStatusResponse — общий ответ API статуса сервера.
type ServerStatusResponse struct {
	Online      bool   `json:"online"`
	Version     string `json:"version"`
	Uptime      string `json:"uptime"`
	LastUpdated string `json:"lastUpdated"`

	Resources ResourcesResponse `json:"resources"`

	Clients int `json:"clients"`
	Devices int `json:"devices"`
}

// ResourcesResponse содержит информацию о ресурсах сервера.
type ResourcesResponse struct {
	CPU     CPUInfo     `json:"cpu"`
	RAM     RAMInfo     `json:"ram"`
	Disk    DiskInfo    `json:"disk"`
	Network NetworkInfo `json:"network"`
}

// CPUInfo — информация о процессоре.
type CPUInfo struct {
	Usage int `json:"usage"`
	Cores int `json:"cores"`
}

// RAMInfo — использование оперативной памяти.
type RAMInfo struct {
	Used  uint64 `json:"used"`
	Total uint64 `json:"total"`
}

// DiskInfo — использование диска.
type DiskInfo struct {
	Used  uint64 `json:"used"`
	Total uint64 `json:"total"`
}

// NetworkInfo — сетевой трафик.
type NetworkInfo struct {
	RX uint64 `json:"rx"`
	TX uint64 `json:"tx"`
}

// getServerStatus собирает информацию о состоянии сервера.
// Пока возвращает тестовые данные.
func getServerStatus() (*ServerStatusResponse, error) {
	ramUsed, ramTotal, err := getMemoryUsage()
	diskUsed, diskTotal, err := getDiskUsage("/")
	uptime, err := getUptime()
	rx, tx, err := getNetworkUsage()
	clients := getClientCount()
	devices := getDeviceCount()
	lastUpdated := getLastUpdated()
	if err != nil {
		clients = 0
	}
	if err != nil {
		rx = 0
		tx = 0
	}

	if err != nil {
		uptime = "unknown"
	}
	if err != nil {
		diskUsed = 0
		diskTotal = 0
	}
	if err != nil {
		ramUsed = 0
		ramTotal = 0
	}
	return &ServerStatusResponse{
		Online:      true,
		Version:     ServerVersion,
		Uptime:      uptime,
		LastUpdated: lastUpdated,

		Resources: ResourcesResponse{
			CPU: CPUInfo{
				Usage: getCPUUsage(),
				Cores: getCPUCores(),
			},
			RAM: RAMInfo{
				Used:  ramUsed,
				Total: ramTotal,
			},
			Disk: DiskInfo{
				Used:  diskUsed,
				Total: diskTotal,
			},
			Network: NetworkInfo{
				RX: rx,
				TX: tx,
			},
		},

		Clients: clients,
		Devices: devices,
	}, nil
}

func adminServerStatusHandler(w http.ResponseWriter, r *http.Request) {
	status, err := getServerStatus()
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}

	w.Header().Set("Content-Type", "application/json")

	if err := json.NewEncoder(w).Encode(status); err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
}
