package main

import "strings"

func normalizeTransportModeArg(mode string) string {
	switch strings.ToLower(strings.TrimSpace(mode)) {
	case "auto":
		return "normal"
	case "normal", "direct", "turn_tcp", "raw_tun":
		return strings.ToLower(strings.TrimSpace(mode))
	case "turn-tcp", "turntcp":
		return "turn_tcp"
	case "raw-tun", "rawtun":
		return "raw_tun"
	default:
		return "normal"
	}
}

func resolveTransportModeArg(explicit string, noTLS bool, turnTCP bool) (string, bool, bool) {
	mode := strings.ToLower(strings.TrimSpace(explicit))
	if mode == "" {
		switch {
		case noTLS:
			mode = "direct"
		case turnTCP:
			mode = "turn_tcp"
		default:
			mode = "normal"
		}
	}
	mode = normalizeTransportModeArg(mode)
	return mode, mode == "direct", mode == "turn_tcp"
}
