//go:build windows

package main

import (
	"fmt"
	"os"
	"runtime"
	"time"
)

func getCPUCores() int {
	return runtime.NumCPU()
}

func getCPUUsage() int {
	return 0
}

func getMemoryUsage() (used uint64, total uint64, err error) {
	return 0, 0, nil
}

func getDiskUsage(path string) (used uint64, total uint64, err error) {
	return 0, 0, nil
}

func getUptime() (string, error) {
	return "n/a", nil
}

func getNetworkUsage() (rx uint64, tx uint64, err error) {
	return 0, 0, fmt.Errorf("network usage is not supported on windows")
}

func getClientCount() int {
	dbMutex.Lock()
	defer dbMutex.Unlock()

	return len(db.Passwords)
}

func getDeviceCount() int {
	dbMutex.Lock()
	defer dbMutex.Unlock()

	return len(db.Devices)
}

func getLastUpdated() string {
	info, err := os.Stat(dbFile)
	if err != nil {
		return ""
	}

	return info.ModTime().UTC().Format(time.RFC3339)
}
