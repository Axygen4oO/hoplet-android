package main

import (
	"log"
	"time"
)

func CleanupPendingOrders() {
	dbMutex.Lock()
	defer dbMutex.Unlock()

	now := time.Now().Unix()
	removed := 0

	for id, order := range db.Orders {
		if order == nil {
			continue
		}

		if order.Status != "pending" {
			continue
		}

		if now-order.CreatedAt < 24*60*60 {
			continue
		}

		delete(db.Orders, id)
		removed++
	}

	if removed > 0 {
		saveDBLocked()
		log.Printf("[ORDERS] Removed %d expired pending orders", removed)
	}
}

func StartOrderCleanup() {
	go func() {
		CleanupPendingOrders()

		ticker := time.NewTicker(time.Hour)
		defer ticker.Stop()

		for range ticker.C {
			CleanupPendingOrders()
		}
	}()
}
