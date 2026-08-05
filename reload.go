package main

import "log"

func SaveAndReload() {

	log.Println("[RELOAD] SaveAndReload() called")

	saveDB()

	if globalWgDev == nil {
		log.Println("[RELOAD] globalWgDev == nil")
		return
	}

	log.Println("[RELOAD] Calling reloadDB()")

	if err := reloadDB(globalWgDev); err != nil {
		log.Printf("[RELOAD] reloadDB error: %v", err)
		return
	}

	log.Println("[RELOAD] reloadDB completed")
}