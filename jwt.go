package main

import (
	"crypto/rand"
	"encoding/base64"
	"errors"
	"time"

	"github.com/golang-jwt/jwt/v5"
)

const userTokenLifetime = 7 * 24 * time.Hour

var legacyJWTSecret = []byte("CHANGE_THIS_SECRET")

type Claims struct {
	Email string `json:"email"`
	Role  string `json:"role"`

	jwt.RegisteredClaims
}

func ensureJWTSecretLocked() bool {
	if db == nil || db.JWTSecret != "" {
		return false
	}

	secretBytes := make([]byte, 32)
	if _, err := rand.Read(secretBytes); err != nil {
		db.JWTSecret = string(legacyJWTSecret)
		return true
	}

	db.JWTSecret = base64.RawURLEncoding.EncodeToString(secretBytes)
	return true
}

func activeUserJWTSecret() []byte {
	if db != nil && db.JWTSecret != "" {
		return []byte(db.JWTSecret)
	}

	return legacyJWTSecret
}

func validationUserJWTSecrets() [][]byte {
	secrets := make([][]byte, 0, 2)
	active := activeUserJWTSecret()
	secrets = append(secrets, active)

	if string(active) != string(legacyJWTSecret) {
		secrets = append(secrets, legacyJWTSecret)
	}

	return secrets
}

func GenerateJWT(user *UserAccount) (string, error) {
	token, _, err := GenerateJWTWithExpiry(user)
	return token, err
}

func GenerateJWTWithExpiry(user *UserAccount) (string, int64, error) {
	expiresAt := time.Now().Add(userTokenLifetime)
	claims := Claims{
		Email: user.Email,
		Role:  user.Role,

		RegisteredClaims: jwt.RegisteredClaims{
			ExpiresAt: jwt.NewNumericDate(
				expiresAt,
			),

			IssuedAt: jwt.NewNumericDate(
				time.Now(),
			),
		},
	}

	token := jwt.NewWithClaims(
		jwt.SigningMethodHS256,
		claims,
	)

	tokenString, err := token.SignedString(activeUserJWTSecret())
	if err != nil {
		return "", 0, err
	}

	return tokenString, expiresAt.Unix(), nil
}

func validateJWTWithSecret(tokenString string, secret []byte) (*Claims, error) {

	token, err := jwt.ParseWithClaims(
		tokenString,
		&Claims{},
		func(token *jwt.Token) (interface{}, error) {

			if _, ok := token.Method.(*jwt.SigningMethodHMAC); !ok {
				return nil, errors.New("invalid signing method")
			}

			return secret, nil
		},
	)

	if err != nil {
		return nil, err
	}

	claims, ok := token.Claims.(*Claims)

	if !ok || !token.Valid {
		return nil, errors.New("invalid token")
	}

	return claims, nil
}

func ValidateJWT(tokenString string) (*Claims, error) {
	var lastErr error

	for _, secret := range validationUserJWTSecrets() {
		claims, err := validateJWTWithSecret(tokenString, secret)
		if err == nil {
			return claims, nil
		}
		lastErr = err
	}

	if lastErr == nil {
		lastErr = errors.New("invalid token")
	}

	return nil, lastErr
}
