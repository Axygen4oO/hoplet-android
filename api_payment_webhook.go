package main

import (
	"encoding/json"
	"log"
	"net/http"
)

type YooWebhook struct {
	Type string `json:"type"`

	Event string `json:"event"`

	Object struct {
		ID     string `json:"id"`
		Status string `json:"status"`
	} `json:"object"`
}

func paymentWebhookHandler(w http.ResponseWriter, r *http.Request) {

	log.Println("[YooKassa] webhook received")

	var hook YooWebhook

	if err := json.NewDecoder(r.Body).Decode(&hook); err != nil {
		log.Println("[YooKassa] bad json:", err)
		http.Error(w, "bad json", 400)
		return
	}

	log.Printf("[YooKassa] event=%s paymentID=%s status=%s\n",
		hook.Event,
		hook.Object.ID,
		hook.Object.Status,
	)

	if hook.Event == "payment.succeeded" {
		ActivateOrder(hook.Object.ID)
		log.Println("[YooKassa] ActivateOrder called")
	}

	w.WriteHeader(http.StatusOK)
}
