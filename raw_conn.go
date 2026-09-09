package main

import (
	"errors"
	"net"
	"time"
)

// directConn is transport glue for one wrapped peer. RAW session ownership and
// packet routing are implemented by raw.go.
type directConn struct {
	pc   net.PacketConn
	addr net.Addr
}

func (c *directConn) Read(b []byte) (int, error) {
	for {
		n, _, err := c.pc.ReadFrom(b)
		if err != nil {
			var netErr net.Error
			if errors.As(err, &netErr) {
				return 0, err
			}
			continue
		}
		return n, nil
	}
}
func (c *directConn) Write(b []byte) (int, error)        { return c.pc.WriteTo(b, c.addr) }
func (c *directConn) Close() error                       { return c.pc.Close() }
func (c *directConn) LocalAddr() net.Addr                { return c.pc.LocalAddr() }
func (c *directConn) RemoteAddr() net.Addr               { return c.addr }
func (c *directConn) SetDeadline(t time.Time) error      { return c.pc.SetDeadline(t) }
func (c *directConn) SetReadDeadline(t time.Time) error  { return c.pc.SetReadDeadline(t) }
func (c *directConn) SetWriteDeadline(t time.Time) error { return c.pc.SetWriteDeadline(t) }
