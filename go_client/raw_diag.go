package main

import (
	"log"
	"time"
)

func rawDiagf(format string, args ...any) {
	ts := time.Now().Format("15:04:05.000")
	log.Printf("[RAW-DIAG %s] "+format, append([]any{ts}, args...)...)
}
