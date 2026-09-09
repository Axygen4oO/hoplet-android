package main

import "testing"

func TestSelectSessionTransportPath(t *testing.T) {
	tests := []struct {
		name string
		tp   *TurnParams
		want sessionTransportPath
	}{
		{
			name: "normal uses dtls path",
			tp:   &TurnParams{},
			want: sessionTransportDTLS,
		},
		{
			name: "direct keeps direct udp path",
			tp:   &TurnParams{NoTLS: true},
			want: sessionTransportDirectUDP,
		},
		{
			name: "raw tun uses author raw turn no dtls path",
			tp:   &TurnParams{RawMode: true},
			want: sessionTransportRawTurnNoDTLS,
		},
		{
			name: "raw tun over turn tcp still avoids dtls",
			tp:   &TurnParams{RawMode: true, TCPTransport: true},
			want: sessionTransportRawTurnNoDTLS,
		},
		{
			name: "direct precedence stays unchanged when notls set",
			tp:   &TurnParams{NoTLS: true, RawMode: true},
			want: sessionTransportDirectUDP,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if got := selectSessionTransportPath(tt.tp); got != tt.want {
				t.Fatalf("got path %v, want %v", got, tt.want)
			}
		})
	}
}
