package main

import (
	"bytes"
	"net"
	"testing"
	"time"
)

// Direct остаётся no-DTLS, но не plain UDP: первый AUTH должен пройти через
// RTP-AEAD unwrap и привязать credential к соединению до handleConn.
func TestDirectListenerUnwrapsAuthAndBindsCredential(t *testing.T) {
	const password = "direct-listener-regression-password"

	keys := newWrapKeyStore()
	if err := keys.AddPassword(password); err != nil {
		t.Fatalf("add direct password: %v", err)
	}
	listener, err := listenDirectWrapped(&net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)}, keys)
	if err != nil {
		t.Fatalf("start direct listener: %v", err)
	}
	defer listener.Close()

	serverAddr, ok := listener.Addr().(*net.UDPAddr)
	if !ok {
		t.Fatalf("unexpected listener address type %T", listener.Addr())
	}
	client, err := net.DialUDP("udp", nil, serverAddr)
	if err != nil {
		t.Fatalf("dial direct listener: %v", err)
	}
	defer client.Close()

	key, err := deriveWrapKey(password)
	if err != nil {
		t.Fatalf("derive wrap key: %v", err)
	}
	want := []byte("AUTH:direct-test-device|" + password)
	wire, err := obfsWrapPacket(key, want, NewObfsConfig(), NewObfsState())
	if err != nil {
		t.Fatalf("wrap direct auth: %v", err)
	}
	if _, err := client.Write(wire); err != nil {
		t.Fatalf("send wrapped direct auth: %v", err)
	}

	pc, remote, err := listener.Accept()
	if err != nil {
		t.Fatalf("accept direct peer: %v", err)
	}
	defer pc.Close()
	direct := &directConn{pc: pc, addr: remote}
	if err := direct.SetReadDeadline(time.Now().Add(2 * time.Second)); err != nil {
		t.Fatalf("set direct read deadline: %v", err)
	}
	buf := make([]byte, 256)
	n, err := direct.Read(buf)
	if err != nil {
		t.Fatalf("unwrap direct auth: %v", err)
	}
	if !bytes.Equal(buf[:n], want) {
		t.Fatalf("unwrapped payload = %q, want %q", buf[:n], want)
	}
	if !connectionCredentialMatches(direct, password) {
		t.Fatal("direct credential was not bound to the RTP-AEAD connection")
	}
}
