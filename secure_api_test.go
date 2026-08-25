package main

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestPublicAPIHandlerDoesNotExposeAdminLogin(t *testing.T) {
	req := httptest.NewRequest(http.MethodPost, "/api/admin/login", strings.NewReader(`{}`))
	rec := httptest.NewRecorder()

	newPublicAPIHandler().ServeHTTP(rec, req)

	if rec.Code != http.StatusNotFound {
		t.Fatalf("expected public handler to hide /api/admin/login, got %d", rec.Code)
	}
}

func TestSensitiveAPIHandlerExposesAdminLogin(t *testing.T) {
	req := httptest.NewRequest(http.MethodPost, "/api/admin/login", strings.NewReader(`{}`))
	rec := httptest.NewRecorder()

	newSensitiveAPIHandler().ServeHTTP(rec, req)

	if rec.Code == http.StatusNotFound {
		t.Fatal("expected sensitive handler to expose /api/admin/login")
	}
}

func TestSensitiveAPIHandlerExposesProfileStatus(t *testing.T) {
	req := httptest.NewRequest(http.MethodGet, "/api/profile/status", nil)
	rec := httptest.NewRecorder()

	newSensitiveAPIHandler().ServeHTTP(rec, req)

	if rec.Code == http.StatusNotFound {
		t.Fatal("expected sensitive handler to expose /api/profile/status")
	}
}
