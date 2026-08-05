package main

import (
	"encoding/json"
	"log"
	"os"
)

const siteConfigPath = "/etc/wdtt/site_config.json"

type SiteConfig struct {
	VkHash string `json:"vk_hash"`
}

var siteConfig SiteConfig

func LoadSiteConfig() {
	f, err := os.Open(siteConfigPath)
	if err != nil {
		log.Printf("[SITE] site_config.json not found: %v", err)
		return
	}
	defer f.Close()

	if err := json.NewDecoder(f).Decode(&siteConfig); err != nil {
		log.Printf("[SITE] failed to parse site_config.json: %v", err)
		return
	}

	if siteConfig.VkHash != "" {
		log.Printf("[SITE] VK hash loaded")
	} else {
		log.Printf("[SITE] VK hash is empty")
	}
}
