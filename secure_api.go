package main

import "net/http"

func registerPublicAPIRoutes(mux *http.ServeMux) {
	mux.HandleFunc("/api/auth/register", registerHandler)
	mux.HandleFunc("/api/auth/register-by-subscription", registerBySubscriptionHandler)
	mux.HandleFunc("/api/auth/login", loginHandler)
	mux.HandleFunc("/api/user", userHandler)
	mux.HandleFunc("/api/subscription", subscriptionHandler)
	mux.HandleFunc("/api/subscription/url", subscriptionURLHandler)
	mux.HandleFunc("/api/devices", devicesHandler)
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
	mux.HandleFunc("/api/payment/webhook", paymentWebhookHandler)
}

func registerSensitiveAPIRoutes(mux *http.ServeMux) {
	mux.HandleFunc("/api/profile/status", handleAPIProfileStatus)
	mux.HandleFunc("/api/profile/unbind", handleAPIProfileUnbind)
	mux.HandleFunc("/api/device/name", deviceNameHandler)
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
}

func wrapAPIHandler(mux *http.ServeMux) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Access-Control-Allow-Origin", "*")
		w.Header().Set("Access-Control-Allow-Methods", "GET, POST, PUT, OPTIONS")
		w.Header().Set("Access-Control-Allow-Headers", "Content-Type, Authorization")

		if r.Method == http.MethodOptions {
			w.WriteHeader(http.StatusOK)
			return
		}

		mux.ServeHTTP(w, r)
	})
}

func newPublicAPIHandler() http.Handler {
	mux := http.NewServeMux()
	registerPublicAPIRoutes(mux)
	return wrapAPIHandler(mux)
}

func newSensitiveAPIHandler() http.Handler {
	mux := http.NewServeMux()
	registerSensitiveAPIRoutes(mux)
	return wrapAPIHandler(mux)
}
