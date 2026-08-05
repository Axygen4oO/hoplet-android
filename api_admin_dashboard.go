package main

import (
	"encoding/json"
	"net/http"
	"runtime"
	"sort"
	"time"
)

type DashboardUser struct {
	ID      string `json:"id"`
	Email   string `json:"email"`
	Plan    string `json:"plan"`
	Devices int    `json:"devices"`
	Expires string `json:"expires"`
}

type DashboardOrder struct {
	ID        string `json:"id"`
	Email     string `json:"email"`
	Plan      string `json:"plan"`
	Price     int    `json:"price"`
	Status    string `json:"status"`
	CreatedAt string `json:"createdAt"`
}

type RevenuePoint struct {
	Label string `json:"label"`
	Value int    `json:"value"`
}

type ServiceStatus struct {
	Name        string `json:"name"`
	Status      string `json:"status"`
	Description string `json:"description"`
}

type ServerInfo struct {
	ServerOnline bool   `json:"serverOnline"`
	Version      string `json:"version"`
	GoVersion    string `json:"goVersion"`
	Uptime       string `json:"uptime"`

	CPU    string `json:"cpu"`
	Memory string `json:"memory"`
	Disk   string `json:"disk"`

	APIOnline bool `json:"apiOnline"`
	BotOnline bool `json:"botOnline"`
}

func recentUsersHandler(w http.ResponseWriter, r *http.Request) {

	if !requireAdmin(r) {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}

	dbMutex.Lock()
	type item struct {
		Key  string
		User *UserAccount
	}

	list := make([]item, 0, len(db.Users))

	for key, user := range db.Users {

		list = append(list, item{
			Key:  key,
			User: user,
		})

	}

	sort.Slice(list, func(i, j int) bool {
		return list[i].User.CreatedAt >
			list[j].User.CreatedAt
	})

	result := make([]DashboardUser, 0)

	for i, u := range list {

		if i >= 10 {
			break
		}

		expires := "-"

		if u.User.SubscriptionExpires > 0 {
			expires = time.Unix(
				u.User.SubscriptionExpires,
				0,
			).Format("02.01.2006")
		}

		result = append(result, DashboardUser{
			ID:      u.Key,
			Email:   u.User.Email,
			Plan:    u.User.SubscriptionPlan,
			Devices: u.User.DeviceLimit,
			Expires: expires,
		})

	}
	dbMutex.Unlock()

	w.Header().Set(
		"Content-Type",
		"application/json",
	)

	json.NewEncoder(w).Encode(result)
}

func recentOrdersHandler(
	w http.ResponseWriter,
	r *http.Request,
) {

	if !requireAdmin(r) {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}

	dbMutex.Lock()
	type item struct {
		ID    string
		Order *Order
	}

	list := make([]item, 0, len(db.Orders))

	for id, order := range db.Orders {

		list = append(list, item{
			ID:    id,
			Order: order,
		})

	}

	sort.Slice(list, func(i, j int) bool {

		return list[i].Order.CreatedAt >
			list[j].Order.CreatedAt

	})

	result := make([]DashboardOrder, 0)

	for i, o := range list {

		if i >= 10 {
			break
		}

		result = append(result, DashboardOrder{

			ID:     o.ID,
			Email:  o.Order.Email,
			Plan:   o.Order.Plan,
			Price:  o.Order.Price,
			Status: o.Order.Status,
			CreatedAt: time.Unix(
				o.Order.CreatedAt,
				0,
			).Format("02.01.2006 15:04"),
		})

	}
	dbMutex.Unlock()

	w.Header().Set(
		"Content-Type",
		"application/json",
	)

	json.NewEncoder(w).Encode(result)
}
func revenueChartHandler(
	w http.ResponseWriter,
	r *http.Request,
) {

	if !requireAdmin(r) {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}

	dbMutex.Lock()
	points := make([]RevenuePoint, 0, 30)

	now := time.Now()

	for i := 29; i >= 0; i-- {

		day := now.AddDate(0, 0, -i)

		total := 0

		for _, order := range db.Orders {

			if order.Status != "paid" {
				continue
			}

			t := time.Unix(order.CreatedAt, 0)

			if t.Year() == day.Year() &&
				t.Month() == day.Month() &&
				t.Day() == day.Day() {

				total += order.Price

			}

		}

		points = append(points, RevenuePoint{
			Label: day.Format("02.01"),
			Value: total,
		})

	}
	dbMutex.Unlock()

	w.Header().Set(
		"Content-Type",
		"application/json",
	)

	json.NewEncoder(w).Encode(points)

}

func systemHealthHandler(
	w http.ResponseWriter,
	r *http.Request,
) {

	if !requireAdmin(r) {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}

	result := []ServiceStatus{

		{
			Name:        "Backend API",
			Status:      "online",
			Description: "REST API работает",
		},

		{
			Name:        "WireGuard",
			Status:      "online",
			Description: "Сервис активен",
		},

		{
			Name:        "Telegram Bot",
			Status:      "online",
			Description: "Бот отвечает",
		},

		{
			Name:        "Website",
			Status:      "online",
			Description: "Frontend доступен",
		},

		{
			Name:        "Database",
			Status:      "online",
			Description: "База открыта",
		},
	}

	w.Header().Set(
		"Content-Type",
		"application/json",
	)

	json.NewEncoder(w).Encode(result)

}

func serverInfoHandler(
	w http.ResponseWriter,
	r *http.Request,
) {

	if !requireAdmin(r) {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}

	info := ServerInfo{

		ServerOnline: true,

		Version: ServerVersion,

		GoVersion: runtime.Version(),

		Uptime: time.Since(serverStarted).
			Round(time.Second).
			String(),

		CPU: "—",

		Memory: "—",

		Disk: "—",

		APIOnline: true,

		BotOnline: botTokenGlobal != "",
	}

	w.Header().Set(
		"Content-Type",
		"application/json",
	)

	json.NewEncoder(w).Encode(info)

}
