package transportselector

import "strings"

func Normalize(mode string) string {
	switch strings.ToLower(strings.TrimSpace(mode)) {
	case "auto":
		// Авторский клиент по умолчанию использует обычный DTLS/UDP путь.
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

func Resolve(explicit string, noTLS bool, turnTCP bool) (string, bool, bool) {
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
	mode = Normalize(mode)
	return mode, mode == "direct", mode == "turn_tcp"
}
