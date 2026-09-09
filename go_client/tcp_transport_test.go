package main

import (
	"net"
	"testing"
	"time"
)

func TestDialTURNConnUDP(t *testing.T) {
	ln, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen udp: %v", err)
	}
	defer ln.Close()

	conn, closer, err := dialTURNConn(ln.LocalAddr().String(), false)
	if err != nil {
		t.Fatalf("dial udp: %v", err)
	}
	defer closer.Close()

	payload := []byte("ping")
	if _, err := conn.WriteTo(payload, nil); err != nil {
		t.Fatalf("write udp: %v", err)
	}

	buf := make([]byte, 32)
	_ = ln.SetDeadline(time.Now().Add(2 * time.Second))
	n, _, err := ln.ReadFrom(buf)
	if err != nil {
		t.Fatalf("read udp: %v", err)
	}
	if got := string(buf[:n]); got != string(payload) {
		t.Fatalf("udp payload mismatch: got %q want %q", got, payload)
	}
}

func TestDialTURNConnTCP(t *testing.T) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen tcp: %v", err)
	}
	defer ln.Close()

	accepted := make(chan struct{}, 1)
	go func() {
		c, err := ln.Accept()
		if err == nil {
			accepted <- struct{}{}
			_ = c.Close()
		}
	}()

	conn, closer, err := dialTURNConn(ln.Addr().String(), true)
	if err != nil {
		t.Fatalf("dial tcp: %v", err)
	}
	defer closer.Close()

	select {
	case <-accepted:
	case <-time.After(2 * time.Second):
		t.Fatal("tcp accept not observed")
	}

	if conn == nil {
		t.Fatal("tcp packet conn is nil")
	}
}
