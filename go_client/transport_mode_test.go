package main

import "testing"

func TestResolveTransportModeArg(t *testing.T) {
	tests := []struct {
		name      string
		explicit  string
		noTLS     bool
		turnTCP   bool
		wantMode  string
		wantNoTLS bool
		wantTCP   bool
	}{
		{"default normal dtls", "", false, false, "normal", false, false},
		{"direct explicit", "direct", false, false, "direct", true, false},
		{"turn tcp explicit", "turn_tcp", false, false, "turn_tcp", false, true},
		{"raw tun explicit", "raw_tun", false, false, "raw_tun", false, false},
		{"auto explicit", "auto", false, false, "normal", false, false},
		{"legacy direct flag", "", true, false, "direct", true, false},
		{"legacy turn tcp flag", "", false, true, "turn_tcp", false, true},
		{"conflict prefers direct", "", true, true, "direct", true, false},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			mode, noTLS, turnTCP := resolveTransportModeArg(tt.explicit, tt.noTLS, tt.turnTCP)
			if mode != tt.wantMode || noTLS != tt.wantNoTLS || turnTCP != tt.wantTCP {
				t.Fatalf("got (%q,%v,%v), want (%q,%v,%v)", mode, noTLS, turnTCP, tt.wantMode, tt.wantNoTLS, tt.wantTCP)
			}
		})
	}
}
