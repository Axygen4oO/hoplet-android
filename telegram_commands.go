package main

import "golang.zx2c4.com/wireguard/device"

func handleCommand(
	token string,
	adminID int64,
	cmd string,
	wgDev *device.Device,
) bool {

	switch cmd {

	case "/start", "/help":
		showMainPanel(
			token,
			adminID,
			0,
			false,
		)
		return true

	case "/panel":
		showMainPanel(token, adminID, 0, false)
		return true

	case "/list":
		sendPasswordList(
			token,
			adminID,
			0,
			false,
			wgDev,
		)
		return true
	}

	return false
}
