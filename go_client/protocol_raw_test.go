package main

import (
	"bytes"
	"io"
	"net"
	"testing"
	"time"
)

type fakeRawConfigConn struct {
	writeBuf bytes.Buffer
	readBuf  *bytes.Reader
}

func newFakeRawConfigConn(response string) *fakeRawConfigConn {
	return &fakeRawConfigConn{
		readBuf: bytes.NewReader([]byte(response)),
	}
}

func (c *fakeRawConfigConn) Read(p []byte) (int, error)       { return c.readBuf.Read(p) }
func (c *fakeRawConfigConn) Write(p []byte) (int, error)      { return c.writeBuf.Write(p) }
func (c *fakeRawConfigConn) Close() error                     { return nil }
func (c *fakeRawConfigConn) LocalAddr() net.Addr              { return dummyAddr("local") }
func (c *fakeRawConfigConn) RemoteAddr() net.Addr             { return dummyAddr("remote") }
func (c *fakeRawConfigConn) SetDeadline(time.Time) error      { return nil }
func (c *fakeRawConfigConn) SetReadDeadline(time.Time) error  { return nil }
func (c *fakeRawConfigConn) SetWriteDeadline(time.Time) error { return nil }

type dummyAddr string

func (d dummyAddr) Network() string { return "test" }
func (d dummyAddr) String() string  { return string(d) }

type timeoutErr struct{}

func (timeoutErr) Error() string   { return "i/o timeout" }
func (timeoutErr) Timeout() bool   { return true }
func (timeoutErr) Temporary() bool { return true }

type fakeAuthConn struct {
	writeBuf bytes.Buffer
	resp     string
	timeout  bool
}

func (c *fakeAuthConn) Read(p []byte) (int, error) {
	if c.timeout {
		return 0, timeoutErr{}
	}
	if c.resp == "" {
		return 0, io.EOF
	}
	n := copy(p, []byte(c.resp))
	c.resp = ""
	return n, nil
}
func (c *fakeAuthConn) Write(p []byte) (int, error)      { return c.writeBuf.Write(p) }
func (c *fakeAuthConn) Close() error                     { return nil }
func (c *fakeAuthConn) LocalAddr() net.Addr              { return dummyAddr("local") }
func (c *fakeAuthConn) RemoteAddr() net.Addr             { return dummyAddr("remote") }
func (c *fakeAuthConn) SetDeadline(time.Time) error      { return nil }
func (c *fakeAuthConn) SetReadDeadline(time.Time) error  { return nil }
func (c *fakeAuthConn) SetWriteDeadline(time.Time) error { return nil }

func TestRequestRawConfigUsesRawPayloadAndParsesResponse(t *testing.T) {
	conn := newFakeRawConfigConn("RAWCONF:10.0.0.2|1.1.1.1,8.8.8.8|1280")

	ip, dnsCSV, mtu, err := RequestRawConfig(conn, "device-123", "secret-pass")
	if err != nil {
		t.Fatalf("RequestRawConfig returned error: %v", err)
	}
	if got, want := conn.writeBuf.String(), "GETCONF_RAW:device-123|secret-pass"; got != want {
		t.Fatalf("unexpected raw request payload: got %q want %q", got, want)
	}
	if ip != "10.0.0.2" || dnsCSV != "1.1.1.1,8.8.8.8" || mtu != 1280 {
		t.Fatalf("unexpected parsed RAWCONF: got (%q,%q,%d)", ip, dnsCSV, mtu)
	}
}

func TestRequestRawConfigNoConf(t *testing.T) {
	conn := newFakeRawConfigConn("NOCONF")

	ip, dnsCSV, mtu, err := RequestRawConfig(conn, "device-123", "secret-pass")
	if err != nil {
		t.Fatalf("RequestRawConfig returned error: %v", err)
	}
	if ip != "" || dnsCSV != "" || mtu != 0 {
		t.Fatalf("expected empty raw config, got (%q,%q,%d)", ip, dnsCSV, mtu)
	}
}

func TestSendAuthTreatsSilentSuccessAsAccepted(t *testing.T) {
	conn := &fakeAuthConn{timeout: true}
	if err := SendAuth(conn, "device-123", "secret-pass"); err != nil {
		t.Fatalf("SendAuth returned error: %v", err)
	}
	if got, want := conn.writeBuf.String(), "AUTH:device-123|secret-pass"; got != want {
		t.Fatalf("unexpected auth payload: got %q want %q", got, want)
	}
}

func TestSendAuthLeavesServerResponseUnread(t *testing.T) {
	conn := &fakeAuthConn{resp: "DENIED:wrong_password"}
	err := SendAuth(conn, "device-123", "secret-pass")
	if err != nil {
		t.Fatalf("SendAuth returned error: %v", err)
	}
	if got, want := conn.writeBuf.String(), "AUTH:device-123|secret-pass"; got != want {
		t.Fatalf("unexpected auth payload: got %q want %q", got, want)
	}
	buf := make([]byte, 64)
	n, readErr := conn.Read(buf)
	if readErr != nil {
		t.Fatalf("expected unread server response, got read error: %v", readErr)
	}
	if got := string(buf[:n]); got != "DENIED:wrong_password" {
		t.Fatalf("unexpected unread response: %q", got)
	}
}

var _ net.Conn = (*fakeRawConfigConn)(nil)
var _ io.Reader = (*fakeRawConfigConn)(nil)
var _ net.Conn = (*fakeAuthConn)(nil)
