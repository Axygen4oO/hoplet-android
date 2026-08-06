//go:build !windows

package main

import (
	"bufio"
	"fmt"
	"math"
	"os"
	"runtime"
	"strconv"
	"strings"
	"syscall"
	"time"
)

// getCPUCores возвращает количество логических ядер процессора.
func getCPUCores() int {
	return runtime.NumCPU()
}

// getCPUUsage пока является заглушкой.
// На следующем шаге заменим её на получение реальной загрузки CPU.
func getCPUUsage() int {
	first, err := readCPUStat()
	if err != nil {
		return 0
	}

	time.Sleep(200 * time.Millisecond)

	second, err := readCPUStat()
	if err != nil {
		return 0
	}

	totalDiff := second.Total - first.Total
	idleDiff := second.Idle - first.Idle

	if totalDiff == 0 {
		return 0
	}

	usage := float64(totalDiff-idleDiff) / float64(totalDiff) * 100.0

	return int(math.Round(usage))
}

// getMemInfo читает /proc/meminfo.
func getMemInfo() (total uint64, available uint64, err error) {
	data, err := os.ReadFile("/proc/meminfo")
	if err != nil {
		return
	}

	lines := strings.Split(string(data), "\n")

	for _, line := range lines {
		fields := strings.Fields(line)

		if len(fields) < 2 {
			continue
		}

		switch fields[0] {
		case "MemTotal:":
			total, _ = strconv.ParseUint(fields[1], 10, 64)
		case "MemAvailable:":
			available, _ = strconv.ParseUint(fields[1], 10, 64)
		}
	}

	total *= 1024
	available *= 1024

	return
}

// getMemoryUsage возвращает использованную и общую память.
func getMemoryUsage() (used uint64, total uint64, err error) {
	total, available, err := getMemInfo()
	if err != nil {
		return
	}

	used = total - available

	return used, total, nil
}

// getDiskUsage возвращает использованное и общее место на диске.
func getDiskUsage(path string) (used uint64, total uint64, err error) {
	var stat syscall.Statfs_t

	if err = syscall.Statfs(path, &stat); err != nil {
		return
	}

	total = stat.Blocks * uint64(stat.Bsize)
	free := stat.Bavail * uint64(stat.Bsize)
	used = total - free

	return
}

// getUptime возвращает время работы системы в формате "Xd Xh Xm".
func getUptime() (string, error) {
	file, err := os.Open("/proc/uptime")
	if err != nil {
		return "", err
	}
	defer file.Close()

	var uptimeSeconds float64

	scanner := bufio.NewScanner(file)
	if scanner.Scan() {
		_, err = fmt.Sscanf(scanner.Text(), "%f", &uptimeSeconds)
		if err != nil {
			return "", err
		}
	}

	totalMinutes := int(uptimeSeconds) / 60

	days := totalMinutes / (24 * 60)
	hours := (totalMinutes % (24 * 60)) / 60
	minutes := totalMinutes % 60

	switch {
	case days > 0:
		return fmt.Sprintf("%dd %dh", days, hours), nil
	case hours > 0:
		return fmt.Sprintf("%dh %dm", hours, minutes), nil
	default:
		return fmt.Sprintf("%dm", minutes), nil
	}
}

// getNetworkUsage возвращает количество принятых и переданных байт
// по всем сетевым интерфейсам, кроме loopback.
func getNetworkUsage() (rx uint64, tx uint64, err error) {
	file, err := os.Open("/proc/net/dev")
	if err != nil {
		return
	}
	defer file.Close()

	scanner := bufio.NewScanner(file)

	// пропускаем первые две строки
	for i := 0; i < 2 && scanner.Scan(); i++ {
	}

	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())

		parts := strings.Split(line, ":")
		if len(parts) != 2 {
			continue
		}

		iface := strings.TrimSpace(parts[0])
		if iface == "lo" {
			continue
		}

		fields := strings.Fields(parts[1])
		if len(fields) < 16 {
			continue
		}

		rxBytes, err1 := strconv.ParseUint(fields[0], 10, 64)
		txBytes, err2 := strconv.ParseUint(fields[8], 10, 64)

		if err1 != nil || err2 != nil {
			continue
		}

		rx += rxBytes
		tx += txBytes
	}

	return
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

type cpuStat struct {
	Idle  uint64
	Total uint64
}

// readCPUStat читает текущую статистику процессора из /proc/stat.
func readCPUStat() (cpuStat, error) {
	data, err := os.ReadFile("/proc/stat")
	if err != nil {
		return cpuStat{}, err
	}

	var (
		label   string
		user    uint64
		nice    uint64
		sys     uint64
		idle    uint64
		iowait  uint64
		irq     uint64
		softirq uint64
		steal   uint64
	)

	_, err = fmt.Sscanf(
		string(data),
		"%s %d %d %d %d %d %d %d %d",
		&label,
		&user,
		&nice,
		&sys,
		&idle,
		&iowait,
		&irq,
		&softirq,
		&steal,
	)
	if err != nil {
		return cpuStat{}, err
	}

	total := user + nice + sys + idle + iowait + irq + softirq + steal

	return cpuStat{
		Idle:  idle + iowait,
		Total: total,
	}, nil
}
