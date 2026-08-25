package main

import (
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestVKHashesHandlerRequiresValidAdminJWT(t *testing.T) {
	withTestDB(t, &Database{
		JWTSecret: "secret",
		VKHashes:  []string{"hash-1", "hash-2"},
	}, func() {
		token, err := createAdminToken()
		if err != nil {
			t.Fatalf("createAdminToken: %v", err)
		}

		req := httptest.NewRequest(http.MethodGet, "/api/vkhashes", nil)
		req.Header.Set("Authorization", "Bearer "+token)
		rec := httptest.NewRecorder()

		vkHashesHandler(rec, req)

		if rec.Code != http.StatusOK {
			t.Fatalf("expected 200, got %d", rec.Code)
		}
	})
}

func TestVKHashesHandlerRejectsMissingJWT(t *testing.T) {
	withTestDB(t, &Database{
		JWTSecret: "secret",
		VKHashes:  []string{"hash-1"},
	}, func() {
		req := httptest.NewRequest(http.MethodGet, "/api/vkhashes", nil)
		rec := httptest.NewRecorder()

		vkHashesHandler(rec, req)

		if rec.Code != http.StatusUnauthorized {
			t.Fatalf("expected 401, got %d", rec.Code)
		}
	})
}

func TestVKHashesHandlerRejectsInvalidJWT(t *testing.T) {
	withTestDB(t, &Database{
		JWTSecret: "secret",
		VKHashes:  []string{"hash-1"},
	}, func() {
		req := httptest.NewRequest(http.MethodGet, "/api/vkhashes", nil)
		req.Header.Set("Authorization", "Bearer invalid")
		rec := httptest.NewRecorder()

		vkHashesHandler(rec, req)

		if rec.Code != http.StatusUnauthorized {
			t.Fatalf("expected 401, got %d", rec.Code)
		}
	})
}
