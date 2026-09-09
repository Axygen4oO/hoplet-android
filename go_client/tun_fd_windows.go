//go:build windows

package main

import (
	"fmt"
	"os"
)

func recvTunFD(sockPath string) (*os.File, error) {
	return nil, fmt.Errorf("tun fd handoff is unsupported on windows host build: %s", sockPath)
}
