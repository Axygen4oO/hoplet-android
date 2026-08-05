package main

import (
	"encoding/json"
	"net/http"
)

type registerRequest struct {
	Email    string `json:"email"`
	Password string `json:"password"`
}

type loginRequest struct {
	Email    string `json:"email"`
	Password string `json:"password"`
}

type apiResponse struct {
	Success bool   `json:"success"`
	Message string `json:"message,omitempty"`
}

type authUserResponse struct {
	Email      string `json:"email"`
	Role       string `json:"role"`
	TelegramID int64  `json:"telegram_id,omitempty"`
}

type authSuccessResponse struct {
	Success   bool              `json:"success"`
	Message   string            `json:"message,omitempty"`
	Token     string            `json:"token,omitempty"`
	ExpiresAt int64             `json:"expires_at,omitempty"`
	User      *authUserResponse `json:"user,omitempty"`
}

func registerHandler(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	var req registerRequest

	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "Invalid JSON", http.StatusBadRequest)
		return
	}

	result, err := RegisterUserAndIssueToken(req.Email, req.Password)
	if err != nil {
		w.WriteHeader(http.StatusBadRequest)

		json.NewEncoder(w).Encode(apiResponse{
			Success: false,
			Message: err.Error(),
		})

		return
	}

	json.NewEncoder(w).Encode(authSuccessResponse{
		Success:   true,
		Message:   "User created",
		Token:     result.Token,
		ExpiresAt: result.TokenExpiresAt,
		User: &authUserResponse{
			Email:      result.User.Email,
			Role:       result.User.Role,
			TelegramID: result.User.TelegramID,
		},
	})
}

func loginHandler(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	var req loginRequest

	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "Invalid JSON", http.StatusBadRequest)
		return
	}

	result, err := AuthenticateUser(req.Email, req.Password)
	if err != nil {
		w.WriteHeader(http.StatusUnauthorized)

		json.NewEncoder(w).Encode(apiResponse{
			Success: false,
			Message: "Invalid email or password",
		})

		return
	}

	json.NewEncoder(w).Encode(authSuccessResponse{
		Success:   true,
		Token:     result.Token,
		ExpiresAt: result.TokenExpiresAt,
		User: &authUserResponse{
			Email:      result.User.Email,
			Role:       result.User.Role,
			TelegramID: result.User.TelegramID,
		},
	})
}
