package main

import (
	"context"
	"log"
	"net"
	"os"
	"sync"
	"sync/atomic"
	"time"
)

var pktPool = sync.Pool{
	New: func() interface{} {
		return make([]byte, 2048)
	},
}

func getPktBuf(size int) []byte {
	b := pktPool.Get().([]byte)
	if cap(b) < size {
		b = make([]byte, size)
	}
	return b[:size]
}

func putPktBuf(b []byte) {
	if cap(b) < 2048 {
		return
	}
	pktPool.Put(b[:cap(b)])
}

const (
	returnChBuf   = 512
	prioBuf       = 32
	maxDwellMS    = 15
	prioThreshold = 128
)

func chunkSizeFor(pktSize int) int {
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

type WorkerSlot struct {
	ID     int
	SendCh chan []byte
	PrioCh chan []byte
}

type Dispatcher struct {
	localConn       net.PacketConn
	tunFile         *os.File
	ready           chan struct{}
	clientAddr      atomic.Pointer[net.Addr]
	mu              sync.Mutex
	workers         []*WorkerSlot
	rrIndex         int
	rrCount         int
	lastPktTime     int64
	chunkStartTs    int64
	ReturnCh        chan []byte
	ctx             context.Context
	cancel          context.CancelFunc
	wg              sync.WaitGroup
	stats           *Stats
	firstPktUp      uint32
	firstPktDown    uint32
	firstReadErr    uint32
	firstWriteErr   uint32
	tunReadCount    uint64
	tunSentCount    uint64
	tunDroppedCount uint64
	rawStats        rawClientCounters
}

func NewDispatcher(ctx context.Context, localConn net.PacketConn, stats *Stats) *Dispatcher {
	dctx, dcancel := context.WithCancel(ctx)
	ready := make(chan struct{})
	close(ready)
	d := &Dispatcher{
		localConn: localConn,
		ready:     ready,
		ReturnCh:  make(chan []byte, returnChBuf),
		ctx:       dctx,
		cancel:    dcancel,
		stats:     stats,
	}

	d.wg.Add(2)
	go d.readLoop()
	go d.writeLoop()
	return d
}

func NewDispatcherPendingTUN(ctx context.Context, stats *Stats) *Dispatcher {
	dctx, dcancel := context.WithCancel(ctx)
	d := &Dispatcher{
		ready:    make(chan struct{}),
		ReturnCh: make(chan []byte, returnChBuf),
		ctx:      dctx,
		cancel:   dcancel,
		stats:    stats,
	}

	d.wg.Add(2)
	go d.readLoop()
	go d.writeLoop()
	return d
}

func (d *Dispatcher) AttachTUN(f *os.File) {
	d.tunFile = f
	close(d.ready)
}

func (d *Dispatcher) Shutdown() {
	d.cancel()
	d.wg.Wait()
}

// RawSummary возвращает фактические счётчики RAW datapath для диагностики.
func (d *Dispatcher) RawSummary() string {
	return d.rawStats.summary()
}

func (d *Dispatcher) Register(w *WorkerSlot) {
	d.mu.Lock()
	d.workers = append(d.workers, w)
	count := len(d.workers)
	d.mu.Unlock()
	log.Printf("[ДИСП] Воркер #%d зарегистрирован (всего: %d)", w.ID, count)
}

func (d *Dispatcher) Unregister(slot *WorkerSlot) {
	d.mu.Lock()
	for i, w := range d.workers {
		if w == slot {
			d.workers = append(d.workers[:i], d.workers[i+1:]...)
			break
		}
	}
	remaining := len(d.workers)
	if d.rrIndex >= remaining && remaining > 0 {
		d.rrIndex = d.rrIndex % remaining
	}
	d.rrCount = 0
	d.mu.Unlock()
	log.Printf("[ДИСП] Воркер #%d отключён (осталось: %d)", slot.ID, remaining)
}

func (d *Dispatcher) readLoop() {
	defer d.wg.Done()

	select {
	case <-d.ctx.Done():
		return
	case <-d.ready:
	}
	if d.tunFile != nil {
		rawDiagf("readLoop: разблокирован, начинаю читать из tunFile (fd=%v)", d.tunFile.Fd())
	}

	buf := make([]byte, readBufSize)
	for {
		if err := d.ctx.Err(); err != nil {
			return
		}

		var n int
		var addr net.Addr
		var err error
		if d.tunFile != nil {
			n, err = d.tunFile.Read(buf)
		} else {
			n, addr, err = d.localConn.ReadFrom(buf)
		}
		if err != nil {
			if d.ctx.Err() != nil {
				return
			}
			if atomic.CompareAndSwapUint32(&d.firstReadErr, 0, 1) {
				src := "localConn"
				if d.tunFile != nil {
					src = "tunFile"
				}
				rawDiagf("readLoop: первая ошибка чтения из %s: %v", src, err)
			}
			time.Sleep(10 * time.Millisecond)
			continue
		}

		if d.tunFile == nil {
			d.clientAddr.Store(&addr)
		}
		d.stats.TotalBytesUp.Add(int64(n))

		if d.tunFile != nil {
			c := atomic.AddUint64(&d.tunReadCount, 1)
			d.rawStats.tunReadPackets.Add(1)
			d.rawStats.tunReadBytes.Add(uint64(n))
			if c%200 == 0 {
				rawDiagf("readLoop: прочитано из TUN=%d отправлено=%d дропнуто=%d",
					c, atomic.LoadUint64(&d.tunSentCount), atomic.LoadUint64(&d.tunDroppedCount))
			}
		}

		if atomic.CompareAndSwapUint32(&d.firstPktUp, 0, 1) {
			if d.tunFile != nil {
				log.Printf("[ДИСП] [ДЕБАГ] Получен ПЕРВЫЙ пакет от TUN (%d байт)", n)
			} else {
				log.Printf("[ДИСП] [ДЕБАГ] Получен ПЕРВЫЙ пакет от локального WireGuard (%d байт) с адреса %s", n, addr.String())
			}
		}

		pkt := getPktBuf(n)
		copy(pkt, buf[:n])
		pktSize := n

		d.mu.Lock()
		nw := len(d.workers)
		if nw == 0 {
			d.mu.Unlock()
			putPktBuf(pkt)
			continue
		}

		now := time.Now().UnixMilli()
		lastTime := d.lastPktTime
		d.lastPktTime = now
		if lastTime > 0 && now-lastTime > 10 {
			d.rrIndex = (d.rrIndex + 1) % nw
			d.rrCount = 0
			d.chunkStartTs = now
		}

		if pktSize <= prioThreshold {
			idx := d.rrIndex % nw
			sentPrio := false
			select {
			case d.workers[idx].PrioCh <- pkt:
				sentPrio = true
			default:
				for i := 1; i < nw; i++ {
					alt := (idx + i) % nw
					select {
					case d.workers[alt].PrioCh <- pkt:
						sentPrio = true
					default:
					}
					if sentPrio {
						break
					}
				}
			}
			if sentPrio {
				if d.tunFile != nil {
					atomic.AddUint64(&d.tunSentCount, 1)
				}
				d.mu.Unlock()
				continue
			}
		}

		chunk := chunkSizeFor(pktSize)

		if d.chunkStartTs == 0 {
			d.chunkStartTs = now
		} else if now-d.chunkStartTs >= maxDwellMS {
			d.rrIndex = (d.rrIndex + 1) % nw
			d.rrCount = 0
			d.chunkStartTs = now
		}

		sent := false
		idx := d.rrIndex % nw
		w := d.workers[idx]
		select {
		case w.SendCh <- pkt:
			sent = true
			d.rrCount++
			if d.rrCount >= chunk {
				d.rrIndex = (idx + 1) % nw
				d.rrCount = 0
				d.chunkStartTs = now
			}
		default:
			for i := 1; i < nw; i++ {
				altIdx := (idx + i) % nw
				select {
				case d.workers[altIdx].SendCh <- pkt:
					sent = true
					d.rrIndex = altIdx
					d.rrCount = 1
					d.chunkStartTs = now
				default:
				}
				if sent {
					break
				}
			}
		}

		if sent {
			if d.tunFile != nil {
				atomic.AddUint64(&d.tunSentCount, 1)
			}
		} else {
			d.rrIndex = (idx + 1) % nw
			d.rrCount = 0
			putPktBuf(pkt)
			if d.tunFile != nil {
				c := atomic.AddUint64(&d.tunDroppedCount, 1)
				if c == 1 || c%50 == 0 {
					rawDiagf("readLoop: пакет из TUN ДРОПНУТ — все воркеры перегружены (дропнуто всего=%d)", c)
				}
			}
		}
		d.mu.Unlock()
	}
}

func (d *Dispatcher) writeLoop() {
	defer d.wg.Done()

	select {
	case <-d.ctx.Done():
		return
	case <-d.ready:
	}

	for {
		select {
		case <-d.ctx.Done():
			return
		case pkt := <-d.ReturnCh:
			if d.tunFile != nil {
				if atomic.CompareAndSwapUint32(&d.firstPktDown, 0, 1) {
					log.Printf("[ДИСП] [ДЕБАГ] Отправляем ПЕРВЫЙ пакет обратно в TUN (%d байт)", len(pkt))
				}
				if _, err := d.tunFile.Write(pkt); err != nil {
					d.rawStats.tunWriteErrors.Add(1)
					if d.ctx.Err() != nil {
						putPktBuf(pkt)
						return
					}
					if atomic.CompareAndSwapUint32(&d.firstWriteErr, 0, 1) {
						rawDiagf("writeLoop: первая ошибка записи в tunFile: %v", err)
					}
				} else {
					d.rawStats.tunWritePackets.Add(1)
					d.rawStats.tunWriteBytes.Add(uint64(len(pkt)))
				}
				d.stats.TotalBytesDown.Add(int64(len(pkt)))
				putPktBuf(pkt)
				continue
			}

			addrPtr := d.clientAddr.Load()
			if addrPtr == nil {
				putPktBuf(pkt)
				continue
			}
			addr := *addrPtr
			if atomic.CompareAndSwapUint32(&d.firstPktDown, 0, 1) {
				log.Printf("[ДИСП] [ДЕБАГ] Отправляем ПЕРВЫЙ пакет обратно локальному WireGuard (%d байт) на адрес %s", len(pkt), addr.String())
			}
			if _, err := d.localConn.WriteTo(pkt, addr); err != nil {
				if d.ctx.Err() != nil {
					putPktBuf(pkt)
					return
				}
				if atomic.CompareAndSwapUint32(&d.firstWriteErr, 0, 1) {
					rawDiagf("writeLoop: первая ошибка записи в localConn: %v", err)
				}
			}
			d.stats.TotalBytesDown.Add(int64(len(pkt)))
			putPktBuf(pkt)
		}
	}
}
