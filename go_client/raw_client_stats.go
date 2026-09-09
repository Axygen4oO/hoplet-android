package main

import (
	"fmt"
	"sync/atomic"
)

type rawClientCounters struct {
	tunReadPackets  atomic.Uint64
	tunReadBytes    atomic.Uint64
	rawSendPackets  atomic.Uint64
	rawSendBytes    atomic.Uint64
	rawRecvPackets  atomic.Uint64
	rawRecvBytes    atomic.Uint64
	tunWritePackets atomic.Uint64
	tunWriteBytes   atomic.Uint64
	sendErrors      atomic.Uint64
	recvErrors      atomic.Uint64
	returnChDrops   atomic.Uint64
	tunWriteErrors  atomic.Uint64
}

func (c *rawClientCounters) summary() string {
	return fmt.Sprintf(
		"tun_read_packets=%d tun_read_bytes=%d raw_send_packets=%d raw_send_bytes=%d raw_recv_packets=%d raw_recv_bytes=%d tun_write_packets=%d tun_write_bytes=%d send_errors=%d recv_errors=%d returnch_drops=%d tun_write_errors=%d",
		c.tunReadPackets.Load(),
		c.tunReadBytes.Load(),
		c.rawSendPackets.Load(),
		c.rawSendBytes.Load(),
		c.rawRecvPackets.Load(),
		c.rawRecvBytes.Load(),
		c.tunWritePackets.Load(),
		c.tunWriteBytes.Load(),
		c.sendErrors.Load(),
		c.recvErrors.Load(),
		c.returnChDrops.Load(),
		c.tunWriteErrors.Load(),
	)
}
