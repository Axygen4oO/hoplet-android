package main

import (
	"bytes"
	"context"
	"fmt"
	"log"
	"net"
	"os"
	"os/exec"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

type rawCounters struct {
	sessionsCreated, sessionsClosed, getConfRawReceived, authenticated               atomic.Int64
	packetsReceived, packetsInjected, packetInjectErrors, packetsDropped             atomic.Int64
	sessionLookupHit, rawIPSessionMiss, repliesReceived, repliesSent, repliesDropped atomic.Int64
	unexpectedFirst                                                                  atomic.Int64
}

var rawStats rawCounters

func snapshotRawStats() map[string]int64 {
	return map[string]int64{
		"session_created": rawStats.sessionsCreated.Load(), "session_lookup_hit": rawStats.sessionLookupHit.Load(),
		"session_lookup_miss": rawStats.rawIPSessionMiss.Load(), "raw_packets_received": rawStats.packetsReceived.Load(),
		"raw_packets_authenticated": rawStats.authenticated.Load(), "raw_packets_injected": rawStats.packetsInjected.Load(),
		"raw_inject_errors": rawStats.packetInjectErrors.Load(), "reply_packets_received": rawStats.repliesReceived.Load(),
		"reply_packets_sent": rawStats.repliesSent.Load(), "reply_drops": rawStats.repliesDropped.Load(),
		"sessions_created": rawStats.sessionsCreated.Load(), "sessions_closed": rawStats.sessionsClosed.Load(),
		"getconf_raw_received": rawStats.getConfRawReceived.Load(), "authenticated": rawStats.authenticated.Load(),
		"packets_received": rawStats.packetsReceived.Load(), "packets_injected": rawStats.packetsInjected.Load(),
		"packet_inject_errors": rawStats.packetInjectErrors.Load(), "packets_dropped": rawStats.packetsDropped.Load(),
		"rawip_session_miss": rawStats.rawIPSessionMiss.Load(), "replies_received": rawStats.repliesReceived.Load(),
		"replies_sent": rawStats.repliesSent.Load(), "replies_dropped": rawStats.repliesDropped.Load(),
		"unexpected_first_packet": rawStats.unexpectedFirst.Load(),
	}
}

type rawTunDevice interface {
	Read([]byte) (int, error)
	Write([]byte) (int, error)
	Close() error
}

// Author model: one RawIP owns workers, each worker writes one transport
// connection. There is no routerIn/clientIn or logical UDP session layer.
type rawClientSessions struct {
	workers          []*downlinkWorker
	rrIndex, rrCount int
	chunkStartTs     int64
}
type rawRouter struct {
	file                                                         rawTunDevice
	mu                                                           sync.RWMutex
	sessions                                                     map[string]*rawClientSessions
	uplinkErrLogged, firstUplink, firstDownlink, noSessionLogged uint32
	closeOnce                                                    sync.Once
}

const (
	downlinkMaxDwellMS = 15
	downlinkWorkerBuf  = 256
)

var rawBuf2048Pool = sync.Pool{New: func() any { b := make([]byte, 2048); return &b }}

func getBuf2048() []byte { return *rawBuf2048Pool.Get().(*[]byte) }
func putBuf2048(b []byte) {
	if cap(b) >= 2048 {
		b = b[:2048]
		rawBuf2048Pool.Put(&b)
	}
}

type downlinkWorker struct {
	conn     net.Conn
	deviceID string
	sendCh   chan []byte
	done     chan struct{}
	stopOnce sync.Once
}

func newDownlinkWorker(conn net.Conn, deviceID string) *downlinkWorker {
	w := &downlinkWorker{conn: conn, deviceID: deviceID, sendCh: make(chan []byte, downlinkWorkerBuf), done: make(chan struct{})}
	go w.run()
	return w
}
func (w *downlinkWorker) run() {
	defer close(w.done)
	for pkt := range w.sendCh {
		if _, err := w.conn.Write(pkt); err != nil {
			rawStats.repliesDropped.Add(1)
		} else {
			atomic.AddInt64(&totalBytesToClient, int64(len(pkt)))
			rawStats.repliesSent.Add(1)
		}
		putBuf2048(pkt)
	}
}
func (w *downlinkWorker) enqueue(pkt []byte) bool {
	select {
	case w.sendCh <- pkt:
		return true
	default:
		rawStats.repliesDropped.Add(1)
		putBuf2048(pkt)
		return false
	}
}
func (w *downlinkWorker) stop() { w.stopOnce.Do(func() { close(w.sendCh); <-w.done }) }
func downlinkChunkSizeFor(pktSize int) int {
	switch {
	case pktSize > 1100:
		return 64
	case pktSize >= 701:
		return 24
	case pktSize >= 301:
		return 8
	case pktSize >= 101:
		return 3
	default:
		return 1
	}
}

func newRawRouter(ctx context.Context) (*rawRouter, error) {
	runCmdSilent("ip", "link", "del", rawIfaceName)
	time.Sleep(100 * time.Millisecond)
	tunFile, err := createRawTUNFile(rawIfaceName)
	if err != nil {
		return nil, fmt.Errorf("raw TUN: %w", err)
	}
	for _, cmd := range [][]string{{"ip", "addr", "add", rawServerCIDR, "dev", rawIfaceName}, {"ip", "link", "set", "mtu", fmt.Sprintf("%d", rawMTU), "dev", rawIfaceName}, {"ip", "link", "set", rawIfaceName, "up"}} {
		out, e := runCmd(cmd[0], cmd[1:]...)
		if e != nil && !strings.Contains(out, "File exists") {
			_ = tunFile.Close()
			return nil, fmt.Errorf("%s: %s", strings.Join(cmd, " "), out)
		}
	}
	if err := setupRawNAT(rawIfaceName); err != nil {
		_ = tunFile.Close()
		return nil, err
	}
	r := &rawRouter{file: tunFile, sessions: make(map[string]*rawClientSessions)}
	context.AfterFunc(ctx, func() { _ = r.Close() })
	go r.downlinkLoop()
	log.Printf("[RAW] TUN %s поднят (%s), MTU %d", rawIfaceName, rawServerCIDR, rawMTU)
	return r, nil
}
func (r *rawRouter) Close() error {
	if r == nil {
		return nil
	}
	var e error
	r.closeOnce.Do(func() {
		if r.file != nil {
			e = r.file.Close()
		}
		r.mu.Lock()
		ws := []*downlinkWorker{}
		for _, s := range r.sessions {
			ws = append(ws, s.workers...)
		}
		r.sessions = make(map[string]*rawClientSessions)
		r.mu.Unlock()
		for _, w := range ws {
			w.stop()
		}
	})
	return e
}
func (r *rawRouter) downlinkLoop() {
	buf := make([]byte, 2048)
	for {
		n, err := r.file.Read(buf)
		if err != nil {
			log.Printf("[RAW] downlink остановлен: %v", err)
			return
		}
		pkt := buf[:n]
		if len(pkt) < 20 || pkt[0]>>4 != 4 {
			continue
		}
		dst := net.IP(pkt[16:20]).String()
		w := r.pickDownlinkConn(dst, len(pkt))
		if w == nil {
			rawStats.rawIPSessionMiss.Add(1)
			if atomic.CompareAndSwapUint32(&r.noSessionLogged, 0, 1) {
				log.Printf("[RAW] downlink: нет сессии для %s", dst)
			}
			continue
		}
		rawStats.sessionLookupHit.Add(1)
		rawStats.repliesReceived.Add(1)
		if atomic.CompareAndSwapUint32(&r.firstDownlink, 0, 1) {
			log.Printf("[RAW] Первый downlink-пакет доставлен клиенту %s (%d байт)", dst, len(pkt))
		}
		out := getBuf2048()[:len(pkt)]
		copy(out, pkt)
		w.enqueue(out)
	}
}
func (r *rawRouter) pickDownlinkConn(dst string, pktSize int) *downlinkWorker {
	r.mu.Lock()
	defer r.mu.Unlock()
	s := r.sessions[dst]
	if s == nil || len(s.workers) == 0 {
		return nil
	}
	if s.rrIndex >= len(s.workers) {
		s.rrIndex = 0
	}
	now := time.Now().UnixMilli()
	if s.chunkStartTs == 0 {
		s.chunkStartTs = now
	} else if now-s.chunkStartTs >= downlinkMaxDwellMS {
		s.rrIndex = (s.rrIndex + 1) % len(s.workers)
		s.rrCount = 0
		s.chunkStartTs = now
	}
	w := s.workers[s.rrIndex]
	s.rrCount++
	if s.rrCount >= downlinkChunkSizeFor(pktSize) {
		s.rrIndex = (s.rrIndex + 1) % len(s.workers)
		s.rrCount = 0
		s.chunkStartTs = now
	}
	return w
}
func (r *rawRouter) register(ip string, conn net.Conn, deviceID string) *downlinkWorker {
	w := newDownlinkWorker(conn, deviceID)
	r.mu.Lock()
	s := r.sessions[ip]
	if s == nil {
		s = &rawClientSessions{}
		r.sessions[ip] = s
		rawStats.sessionsCreated.Add(1)
	}
	s.workers = append(s.workers, w)
	r.mu.Unlock()
	return w
}
func (r *rawRouter) unregister(ip string, w *downlinkWorker) {
	r.mu.Lock()
	if s := r.sessions[ip]; s != nil {
		for i, x := range s.workers {
			if x == w {
				s.workers = append(s.workers[:i], s.workers[i+1:]...)
				break
			}
		}
		if s.rrIndex >= len(s.workers) {
			s.rrIndex = 0
		}
		s.rrCount = 0
		if len(s.workers) == 0 {
			delete(r.sessions, ip)
			rawStats.sessionsClosed.Add(1)
		}
	}
	r.mu.Unlock()
	w.stop()
}
func (r *rawRouter) writeUplink(pkt []byte) error { _, err := r.file.Write(pkt); return err }

func handleConnRaw(ctx context.Context, clientConn net.Conn, router *rawRouter) {
	atomic.AddInt64(&totalConns, 1)
	atomic.AddInt32(&activeConns, 1)
	defer atomic.AddInt32(&activeConns, -1)
	stopConn := context.AfterFunc(ctx, func() { _ = clientConn.SetDeadline(time.Now()) })
	defer stopConn()
	buf := make([]byte, 1600)
	if err := clientConn.SetReadDeadline(time.Now().Add(30 * time.Second)); err != nil {
		return
	}
	n, err := clientConn.Read(buf)
	if err != nil {
		return
	}
	_ = clientConn.SetReadDeadline(time.Time{})
	first := string(buf[:n])
	isGetConf := strings.HasPrefix(first, "GETCONF_RAW:")
	isAuth := strings.HasPrefix(first, "AUTH:")
	if !isGetConf && !isAuth {
		rawStats.unexpectedFirst.Add(1)
		return
	}
	parts := strings.Split(strings.TrimSpace(strings.TrimPrefix(strings.TrimPrefix(first, "GETCONF_RAW:"), "AUTH:")), "|")
	deviceID, password := "unknown", ""
	if len(parts) > 0 && strings.TrimSpace(parts[0]) != "" {
		deviceID = strings.TrimSpace(parts[0])
	}
	if len(parts) > 1 {
		password = strings.TrimSpace(parts[1])
	}
	if !connectionCredentialMatches(clientConn, password) {
		_, _ = clientConn.Write([]byte("DENIED:wrong_password"))
		return
	}
	assignedIP, ok := rawAuthorizeConnection(clientConn, deviceID, password, isGetConf)
	if !ok {
		return
	}
	rawStats.authenticated.Add(1)
	if isGetConf {
		rawStats.getConfRawReceived.Add(1)
		if _, err := clientConn.Write([]byte(fmt.Sprintf("RAWCONF:%s|%s|%d", assignedIP, dns, rawMTU))); err != nil {
			return
		}
	}
	untrack := trackCredentialConnection(password, deviceID, clientConn)
	defer untrack()
	w := router.register(assignedIP, clientConn, deviceID)
	defer router.unregister(assignedIP, w)
	activeDevicesMu.Lock()
	activeDevices[deviceID]++
	activeDevicesMu.Unlock()
	defer func() {
		activeDevicesMu.Lock()
		activeDevices[deviceID]--
		if activeDevices[deviceID] <= 0 {
			delete(activeDevices, deviceID)
		}
		activeDevicesMu.Unlock()
	}()
	b := getBuf()
	defer putBuf(b)
	assignedAddr := net.ParseIP(assignedIP).To4()
	const idleTimeout = 90 * time.Second
	last := time.Now()
	for {
		select {
		case <-ctx.Done():
			return
		default:
		}
		_ = clientConn.SetReadDeadline(time.Now().Add(20 * time.Second))
		nn, e := clientConn.Read(*b)
		if e != nil {
			if isNetTimeout(e) && ctx.Err() == nil && time.Since(last) <= idleTimeout {
				continue
			}
			return
		}
		last = time.Now()
		rawStats.packetsReceived.Add(1)
		pkt := (*b)[:nn]
		if isRawKeepalive(pkt) {
			continue
		}
		if isRawDisconnectPacket(pkt) {
			return
		}
		if nn < 20 || pkt[0]>>4 != 4 || assignedAddr == nil || !bytes.Equal(pkt[12:16], assignedAddr) {
			rawStats.packetsDropped.Add(1)
			continue
		}
		atomic.AddInt64(&totalBytesFromClient, int64(nn))
		if e := router.writeUplink(pkt); e != nil {
			rawStats.packetInjectErrors.Add(1)
			if atomic.CompareAndSwapUint32(&router.uplinkErrLogged, 0, 1) {
				log.Printf("[RAW] Ошибка записи в TUN (ip=%s): %v", assignedIP, e)
			}
			continue
		}
		rawStats.packetsInjected.Add(1)
		if atomic.CompareAndSwapUint32(&router.firstUplink, 0, 1) {
			log.Printf("[RAW] Первый uplink-пакет от %s записан в TUN (%d байт)", assignedIP, nn)
		}
	}
}

func rawAuthorizeConnection(conn net.Conn, deviceID, password string, getConf bool) (string, bool) {
	dbMutex.Lock()
	defer dbMutex.Unlock()
	mainPass := password != "" && password == db.MainPassword
	entry, isGen := db.Passwords[password]
	valid := mainPass || (isGen && !isPasswordExpired(entry) && !entry.IsDeactivated)
	if !valid {
		msg := "DENIED:wrong_password"
		if isGen && isPasswordExpired(entry) {
			msg = "DENIED:expired"
		} else if isGen && entry.IsDeactivated {
			msg = "DENIED:deactivated"
		}
		_, _ = conn.Write([]byte(msg))
		return "", false
	}
	if isGen && !entry.canConnectAndBind(deviceID) {
		_, _ = conn.Write([]byte("DENIED:device_mismatch"))
		return "", false
	}
	dev, exists := db.Devices[deviceID]
	if !getConf && !exists {
		return "", false
	}
	if getConf && !exists {
		dev = &ClientDevice{DeviceID: deviceID, IP: getNextIP(), RawIP: getNextRawIP()}
		db.Devices[deviceID] = dev
	} else if dev != nil && getConf && dev.RawIP == "" {
		dev.RawIP = getNextRawIP()
	}
	if dev == nil || strings.TrimSpace(dev.RawIP) == "" {
		return "", false
	}
	dev.LastSeenAt = time.Now().Unix()
	saveDBLocked()
	return dev.RawIP, true
}

func parseRawControlPacket(pkt []byte) (kind, deviceID, password string, ok bool) {
	s := string(pkt)
	switch {
	case strings.HasPrefix(s, "GETCONF_RAW:"):
		kind = "GETCONF_RAW"
		s = strings.TrimPrefix(s, "GETCONF_RAW:")
	case strings.HasPrefix(s, "AUTH:"):
		kind = "AUTH"
		s = strings.TrimPrefix(s, "AUTH:")
	case strings.HasPrefix(s, "DISCONNECT_RAW:"):
		return "DISCONNECT_RAW", strings.TrimSpace(strings.TrimPrefix(s, "DISCONNECT_RAW:")), "", true
	default:
		return "", "", "", false
	}
	p := strings.Split(strings.TrimSpace(s), "|")
	deviceID = "unknown"
	if len(p) > 0 && strings.TrimSpace(p[0]) != "" {
		deviceID = strings.TrimSpace(p[0])
	}
	if len(p) > 1 {
		password = strings.TrimSpace(p[1])
	}
	return kind, deviceID, password, true
}
func isRawKeepalive(pkt []byte) bool { return len(pkt) > 0 && pkt[0] == 0xFF }
func isRawDisconnectPacket(pkt []byte) bool {
	k, _, _, ok := parseRawControlPacket(pkt)
	return ok && k == "DISCONNECT_RAW"
}
func extractIPv4Dst(pkt []byte) string {
	if len(pkt) < 20 || pkt[0]>>4 != 4 {
		return ""
	}
	return net.IP(pkt[16:20]).String()
}

func trackCredentialConnection(_ string, _ string, _ net.Conn) func() { return func() {} }

func setupRawNAT(rawIface string) error {
	log.Println("[RAW] Настройка NAT...")
	_ = os.WriteFile("/proc/sys/net/ipv4/ip_forward", []byte("1"), 0644)
	extIface := getDefaultInterface()
	if commandExists("iptables") {
		for i := 0; i < 5; i++ {
			_ = exec.Command("iptables", "-t", "nat", "-D", "POSTROUTING", "-s", rawServerCIDR, "-o", extIface, "-m", "comment", "--comment", "WDTT_MANAGED", "-j", "MASQUERADE").Run()
		}
		_ = exec.Command("iptables", "-t", "nat", "-I", "POSTROUTING", "1", "-s", rawServerCIDR, "-o", extIface, "-m", "comment", "--comment", "WDTT_MANAGED", "-j", "MASQUERADE").Run()
		setupForwardRules(rawIface)
	} else if commandExists("nft") {
		_ = exec.Command("nft", "add", "table", "ip", "wdtt_raw").Run()
		_ = exec.Command("nft", "add", "chain", "ip", "wdtt_raw", "postrouting", "{ type nat hook postrouting priority 100; }").Run()
		_ = exec.Command("nft", "add", "rule", "ip", "wdtt_raw", "postrouting", "ip", "saddr", rawServerCIDR, "oifname", extIface, "masquerade").Run()
		setupForwardRules(rawIface)
	} else {
		log.Printf("[RAW] WARNING: NAT helper not found")
	}
	return nil
}
