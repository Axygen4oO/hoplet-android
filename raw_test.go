package main

import (
	"bytes"
	"context"
	"net"
	"os"
	"path/filepath"
	"testing"
	"time"
)

type fakeRawTUN struct {
	lastWrite []byte
	writeErr  error
}

func (f *fakeRawTUN) Read([]byte) (int, error) { return 0, os.ErrClosed }
func (f *fakeRawTUN) Write(p []byte) (int, error) {
	f.lastWrite = append([]byte(nil), p...)
	if f.writeErr != nil {
		return 0, f.writeErr
	}
	return len(p), nil
}
func (f *fakeRawTUN) Close() error { return nil }

func resetRawTestState(t *testing.T) {
	oldDB, oldFile, oldStats := db, dbFile, rawStats
	db = &Database{MainPassword: "secret", Passwords: map[string]*PasswordEntry{}, Devices: map[string]*ClientDevice{}}
	dbFile = filepath.Join(t.TempDir(), "db.json")
	rawStats = rawCounters{}
	t.Cleanup(func() { db, dbFile, rawStats = oldDB, oldFile, oldStats })
}
func ipv4(src, dst string) []byte {
	p := make([]byte, 20)
	p[0] = 0x45
	copy(p[12:16], net.ParseIP(src).To4())
	copy(p[16:20], net.ParseIP(dst).To4())
	return p
}

func TestRawControlAndKeepalive(t *testing.T) {
	k, d, p, ok := parseRawControlPacket([]byte("GETCONF_RAW:dev|pw"))
	if !ok || k != "GETCONF_RAW" || d != "dev" || p != "pw" {
		t.Fatalf("parse=%q %q %q %v", k, d, p, ok)
	}
	if !isRawKeepalive([]byte{0xff, 1}) || !isRawDisconnectPacket([]byte("DISCONNECT_RAW:dev")) {
		t.Fatal("control detection failed")
	}
}

func TestConnectionCredentialMatchesMainWrapKey(t *testing.T) {
	resetRawTestState(t)
	server, client := net.Pipe()
	defer server.Close()
	defer client.Close()
	bindingID := wrapConnectionBindingID(server.LocalAddr(), server.RemoteAddr())
	if bindingID == "" {
		t.Fatal("missing test connection binding")
	}
	wrapCredentialBindings.Store(bindingID, "main")
	t.Cleanup(func() { wrapCredentialBindings.Delete(bindingID) })
	if !connectionCredentialMatches(server, "secret") {
		t.Fatal("main password must match the dedicated main WRAP key id")
	}
	if connectionCredentialMatches(server, "not-secret") {
		t.Fatal("different password must not match the main WRAP key id")
	}
}

func TestRawHandleConnAuthorFlow(t *testing.T) {
	resetRawTestState(t)
	tun := &fakeRawTUN{}
	r := &rawRouter{file: tun, sessions: map[string]*rawClientSessions{}}
	server, client := net.Pipe()
	defer client.Close()
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	done := make(chan struct{})
	go func() { defer close(done); handleConnRaw(ctx, server, r) }()
	if _, err := client.Write([]byte("GETCONF_RAW:dev1|secret")); err != nil {
		t.Fatal(err)
	}
	resp := make([]byte, 128)
	_ = client.SetReadDeadline(time.Now().Add(time.Second))
	n, err := client.Read(resp)
	if err != nil || !bytes.HasPrefix(resp[:n], []byte("RAWCONF:")) {
		t.Fatalf("RAWCONF: %q %v", resp[:n], err)
	}
	var ip string
	deadline := time.Now().Add(time.Second)
	for ip == "" && time.Now().Before(deadline) {
		r.mu.RLock()
		for x := range r.sessions {
			ip = x
		}
		r.mu.RUnlock()
		if ip == "" {
			time.Sleep(time.Millisecond * 5)
		}
	}
	if ip == "" {
		t.Fatal("session not registered")
	}
	if _, err := client.Write(ipv4(ip, "1.1.1.1")); err != nil {
		t.Fatal(err)
	}
	deadline2 := time.Now().Add(time.Second)
	for tun.lastWrite == nil && time.Now().Before(deadline2) {
		time.Sleep(time.Millisecond * 5)
	}
	if !bytes.Equal(tun.lastWrite, ipv4(ip, "1.1.1.1")) {
		t.Fatalf("uplink=%v", tun.lastWrite)
	}
	cancel()
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("handler did not stop")
	}
}

func TestRawRoundRobinWorkers(t *testing.T) {
	r := &rawRouter{sessions: map[string]*rawClientSessions{}}
	s1, c1 := net.Pipe()
	defer c1.Close()
	s2, c2 := net.Pipe()
	defer c2.Close()
	w1 := r.register("10.0.0.2", s1, "d")
	w2 := r.register("10.0.0.2", s2, "d")
	defer r.unregister("10.0.0.2", w1)
	defer r.unregister("10.0.0.2", w2)
	if r.pickDownlinkConn("10.0.0.2", 1200) != w1 || r.pickDownlinkConn("10.0.0.2", 1200) != w1 {
		t.Fatal("large packet chunk did not stay on worker")
	}
	if r.pickDownlinkConn("10.0.0.2", 1200) != w1 {
		t.Fatal("unexpected round robin rotation")
	}
}

func TestRawRouterMiss(t *testing.T) {
	r := &rawRouter{sessions: map[string]*rawClientSessions{}}
	if r.pickDownlinkConn("10.0.0.9", 100) != nil {
		t.Fatal("expected miss")
	}
	if extractIPv4Dst([]byte{1, 2}) != "" {
		t.Fatal("invalid packet accepted")
	}
}
