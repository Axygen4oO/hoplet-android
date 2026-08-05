package main

import (
	"encoding/json"
	"errors"
	"net/http"
	"strings"
	"time"

	"github.com/golang-jwt/jwt/v5"
)

const adminTokenLifetime = 30 * 24 * time.Hour

type adminClaims struct {
	Admin bool `json:"admin"`
	jwt.RegisteredClaims
}

type adminLoginRequest struct {
	Password string `json:"password"`
}

type adminLoginResponse struct {
	Success bool   `json:"success"`
	Token   string `json:"token,omitempty"`
	Error   string `json:"error,omitempty"`
}

type changePasswordRequest struct {
	CurrentPassword string `json:"currentPassword"`
	NewPassword     string `json:"newPassword"`
}

type changePasswordResponse struct {
	Success bool   `json:"success"`
	Error   string `json:"error,omitempty"`
}

func createAdminToken() (string, error) {
	if db.JWTSecret == "" {
		return "", errors.New("jwt_secret is empty")
	}

	claims := adminClaims{
		Admin: true,
		RegisteredClaims: jwt.RegisteredClaims{
			ExpiresAt: jwt.NewNumericDate(time.Now().Add(adminTokenLifetime)),
			IssuedAt:  jwt.NewNumericDate(time.Now()),
		},
	}

	token := jwt.NewWithClaims(jwt.SigningMethodHS256, claims)

	return token.SignedString([]byte(db.JWTSecret))
}

func requireAdmin(r *http.Request) bool {
	if db.JWTSecret == "" {
		return false
	}

	auth := r.Header.Get("Authorization")

	if !strings.HasPrefix(auth, "Bearer ") {
		return false
	}

	tokenString := strings.TrimPrefix(auth, "Bearer ")

	token, err := jwt.ParseWithClaims(
		tokenString,
		&adminClaims{},
		func(token *jwt.Token) (interface{}, error) {
			return []byte(db.JWTSecret), nil
		},
	)

	if err != nil {
		return false
	}

	claims, ok := token.Claims.(*adminClaims)

	if !ok || !token.Valid {
		return false
	}

	return claims.Admin
}

func adminLoginHandler(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	var req adminLoginRequest

	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "Bad request", http.StatusBadRequest)
		return
	}

	if req.Password != db.MainPassword {
		w.WriteHeader(http.StatusUnauthorized)

		json.NewEncoder(w).Encode(adminLoginResponse{
			Success: false,
			Error:   "invalid password",
		})

		return
	}

	token, err := createAdminToken()

	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}

	json.NewEncoder(w).Encode(adminLoginResponse{
		Success: true,
		Token:   token,
	})
}

func changePasswordHandler(w http.ResponseWriter, r *http.Request) {

	if r.Method != http.MethodPost {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	if !requireAdmin(r) {
		http.Error(w, "Unauthorized", http.StatusUnauthorized)
		return
	}

	var req changePasswordRequest

	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "Bad request", http.StatusBadRequest)
		return
	}

	if req.CurrentPassword != db.MainPassword {

		w.WriteHeader(http.StatusBadRequest)

		json.NewEncoder(w).Encode(changePasswordResponse{
			Success: false,
			Error:   "Неверный текущий пароль",
		})

		return
	}

	if len(req.NewPassword) < 8 {

		w.WriteHeader(http.StatusBadRequest)

		json.NewEncoder(w).Encode(changePasswordResponse{
			Success: false,
			Error:   "Пароль должен содержать минимум 8 символов",
		})

		return
	}

	dbMutex.Lock()

	db.MainPassword = req.NewPassword

	saveDBLocked()

	dbMutex.Unlock()

	json.NewEncoder(w).Encode(changePasswordResponse{
		Success: true,
	})
}
