//go:build !linux

package main

import (
	"fmt"
	"os"
)

func createRawTUNFile(name string) (*os.File, error) {
	return nil, fmt.Errorf("raw TUN is only supported on linux: %s", name)
}
