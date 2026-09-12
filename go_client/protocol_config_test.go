package main

import "testing"

// The third value in a legacy ports triple (9000) is the local client
// listen/TUN port.  It is sent in GETCONF and later becomes the endpoint in
// the WireGuard config; it is not the server's Direct/RAW/TURN port.
func TestRequestConfigLegacyPortIsLocalListenPort(t *testing.T) {
	conn := newFakeRawConfigConn("[Interface]\nPrivateKey = key\n[Peer]\nEndpoint = 127.0.0.1:9000")

	conf, err := RequestConfig(conn, "9000", "legacy-device", "legacy-pass")
	if err != nil {
		t.Fatalf("RequestConfig returned error: %v", err)
	}
	if got, want := conn.writeBuf.String(), "GETCONF:9000|legacy-device|legacy-pass"; got != want {
		t.Fatalf("unexpected GETCONF payload: got %q want %q", got, want)
	}
	if conf == "" {
		t.Fatal("expected WireGuard config")
	}
}
