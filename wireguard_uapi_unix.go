//go:build !windows

package main

import (
	"net"

	"golang.zx2c4.com/wireguard/ipc"
)

func listenWireGuardUAPI(ifaceName string) (net.Listener, error) {
	uapiFile, err := ipc.UAPIOpen(ifaceName)
	if err != nil {
		return nil, err
	}

	return ipc.UAPIListen(ifaceName, uapiFile)
}
