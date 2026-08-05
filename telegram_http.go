package main

import (
	"bytes"
	"encoding/json"
	"io"
	"net/http"
	"time"
)

var telegramHTTPClient = &http.Client{Timeout: 10 * time.Second}

func postTelegramJSON(url string, payload interface{}) (*http.Response, error) {
	body, err := json.Marshal(payload)
	if err != nil {
		return nil, err
	}

	req, err := http.NewRequest(http.MethodPost, url, bytes.NewReader(body))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/json")

	return telegramHTTPClient.Do(req)
}

func postTelegramJSONAndClose(url string, payload interface{}) error {
	resp, err := postTelegramJSON(url, payload)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	_, _ = io.Copy(io.Discard, resp.Body)
	return nil
}
