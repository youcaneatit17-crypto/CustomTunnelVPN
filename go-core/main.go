package main

import (
	"bufio"
	"context"
	"crypto/md5"
	"encoding/binary"
	"flag"
	"fmt"
	"io"
	"log"
	"crypto/tls"
	"encoding/base64"
	"net"
	"os"
	"os/exec"
	"os/signal"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"time"

	"golang.org/x/crypto/ssh"
)

type closeWriter interface {
	CloseWrite() error
}

type tunnelSession struct {
	id        int
	dnsttCmd  *exec.Cmd
	sshClient *ssh.Client
}

func (s *tunnelSession) close() {
	if s == nil {
		return
	}
	if s.sshClient != nil {
		_ = s.sshClient.Close()
	}
	if s.dnsttCmd != nil && s.dnsttCmd.Process != nil {
		_ = s.dnsttCmd.Process.Kill()
	}
}

type tunnelManager struct {
	dnsttBin string
	dns      string
	dnsType  string
	ns       string
	pubkey   string
	sshAddr  string
	user     string
	pass     string
	sessions int

	mu            sync.RWMutex
	list          []*tunnelSession
	rr            atomic.Uint32
	refreshing    atomic.Bool
	activeStreams atomic.Int32
	lastTrafficNs atomic.Int64
}

func (m *tunnelManager) noteTraffic() {
	m.lastTrafficNs.Store(time.Now().UnixNano())
}

func (m *tunnelManager) isBusy() bool {
	if m.activeStreams.Load() <= 0 {
		return false
	}
	last := m.lastTrafficNs.Load()
	return last > 0 && time.Since(time.Unix(0, last)) < 4*time.Second
}

func (m *tunnelManager) getClient() *ssh.Client {
	m.mu.RLock()
	defer m.mu.RUnlock()
	if len(m.list) == 0 {
		return nil
	}
	idx := int(m.rr.Add(1)-1) % len(m.list)
	return m.list[idx].sshClient
}

func init() {
	net.DefaultResolver = &net.Resolver{
		PreferGo: true,
		Dial: func(ctx context.Context, network, address string) (net.Conn, error) {
			d := net.Dialer{Timeout: 5 * time.Second}
			return d.DialContext(ctx, "udp", "1.1.1.1:53")
		},
	}
}

func (m *tunnelManager) dialSingleSession(id int) (*tunnelSession, error) {
	targetDialAddr := m.sshAddr
	var dnsttCmd *exec.Cmd

	useSlowDns := (strings.TrimSpace(m.ns) != "" && strings.TrimSpace(m.pubkey) != "") || strings.HasPrefix(m.sshAddr, "127.0.0.1:") || strings.HasPrefix(m.sshAddr, "localhost:")
	if useSlowDns {
		freeAddr, err := getFreeLocalAddr()
		if err != nil {
			return nil, fmt.Errorf("gagal mencari port lokal: %v", err)
		}
		targetDialAddr = freeAddr

		resolverVal := strings.TrimSpace(m.dns)
		resolverFlag := "-udp"
		switch {
		case strings.HasPrefix(resolverVal, "https://"):
			resolverFlag = "-doh"
		case strings.HasPrefix(resolverVal, "tls://"), strings.HasPrefix(resolverVal, "dot://"):
			resolverFlag = "-dot"
			resolverVal = strings.TrimPrefix(strings.TrimPrefix(resolverVal, "tls://"), "dot://")
			if !strings.Contains(resolverVal, ":") {
				resolverVal += ":853"
			}
		case strings.HasPrefix(resolverVal, "tcp://"):
			resolverFlag = "-tcp"
			resolverVal = strings.TrimPrefix(resolverVal, "tcp://")
			if !strings.Contains(resolverVal, ":") {
				resolverVal += ":53"
			}
		default:
			resolverVal = strings.TrimPrefix(resolverVal, "udp://")
			if !strings.Contains(resolverVal, ":") {
				resolverVal += ":53"
			}
		}

		if id == 1 {
			log.Printf("[ss] SlowDNS: Memulai...")
			log.Printf("[ss] using UDP resolver=%s network=udp4 session=%d", resolverVal, m.sessions)
			log.Printf("[ss] Tujuan koneksi: %s", resolverVal)
			log.Printf("[ss] SlowDNS: Menghubungkan ke server")
			log.Printf("[ss] Metode SSH: SlowDNS")
			log.Printf("[ss] Menggunakan proxy: Tidak ada")
			log.Printf("[ss] Payload SSH: Tidak dikirim")
		}
		dnsttCmd = exec.Command(m.dnsttBin, resolverFlag, resolverVal, "-type", m.dnsType, "-pubkey", m.pubkey, m.ns, freeAddr)
		dnsttCmd.Stdout = io.Discard
		dnsttCmd.Stderr = io.Discard
		if err := dnsttCmd.Start(); err != nil {
			return nil, fmt.Errorf("gagal memulai SlowDNS: %v", err)
		}
		time.Sleep(1400 * time.Millisecond)
		if id == 1 {
			log.Printf("[ss] SlowDNS: %s aktif", m.dnsType)
		}
	} else if id == 1 {
		hasPayload := strings.TrimSpace(*customPayload) != ""
		hasProxy := strings.TrimSpace(*remoteProxy) != ""
		hasSni := strings.TrimSpace(*sniHost) != ""
		if hasProxy {
			log.Printf("[ss] Tujuan koneksi: %s (Proxy: %s)", m.sshAddr, strings.TrimSpace(*remoteProxy))
		} else {
			log.Printf("[ss] Tujuan koneksi: %s", m.sshAddr)
		}
		switch {
		case hasPayload && hasSni:
			log.Printf("[ss] Metode SSH: TLS/SNI + HTTP/WS Payload")
		case hasPayload:
			log.Printf("[ss] Metode SSH: HTTP/WS Payload Tunnel")
		case hasSni:
			log.Printf("[ss] Metode SSH: SSL/TLS (SNI: %s)", strings.TrimSpace(*sniHost))
		case hasProxy:
			log.Printf("[ss] Metode SSH: HTTP Proxy Tunnel")
		default:
			log.Printf("[ss] Metode SSH: Direct SSH-2")
		}
		if hasPayload {
			log.Printf("[ss] Payload SSH: Dikirim")
		}
	}

	var fingerprintStr string
	config := &ssh.ClientConfig{
		User: m.user,
		Auth: []ssh.AuthMethod{
			ssh.Password(m.pass),
		},
		HostKeyCallback: func(hostname string, remote net.Addr, key ssh.PublicKey) error {
			sum := md5.Sum(key.Marshal())
			parts := make([]string, len(sum))
			for i, b := range sum {
				parts[i] = fmt.Sprintf("%02x", b)
			}
			fingerprintStr = strings.Join(parts, ":")
			return nil
		},
		Timeout: 35 * time.Second,
	}

	var sshClient *ssh.Client
	var err error
	if dnsttCmd == nil && (strings.TrimSpace(*customPayload) != "" || strings.TrimSpace(*remoteProxy) != "" || strings.TrimSpace(*sniHost) != "") {
		var rawConn net.Conn
		rawConn, err = dialWithPayloadProxy(targetDialAddr, *remoteProxy, *customPayload, *sniHost, 20*time.Second)
		if err == nil {
			c, chans, reqs, sshErr := ssh.NewClientConn(rawConn, targetDialAddr, config)
			if sshErr != nil {
				_ = rawConn.Close()
				err = sshErr
			} else {
				sshClient = ssh.NewClient(c, chans, reqs)
			}
		}
	} else {
		sshClient, err = ssh.Dial("tcp", targetDialAddr, config)
	}
	if err != nil {
		if dnsttCmd != nil && dnsttCmd.Process != nil {
			_ = dnsttCmd.Process.Kill()
		}
		return nil, fmt.Errorf("gagal terhubung SSH (%s): %v", m.sshAddr, err)
	}

	if id == 1 {
		if fingerprintStr != "" {
			log.Printf("[ss] Sidik jari %s", fingerprintStr)
		}
		srvVer := strings.TrimSpace(string(sshClient.ServerVersion()))
		if srvVer != "" {
			log.Printf("[ss] %s", srvVer)
		}
		log.Printf("[ss] Algoritma pertukaran kunci: curve25519-sha256 / diffie-hellman-group14-sha1")
		log.Printf("[ss] Menggunakan algoritma: aes256-ctr / chacha20-poly1305@openssh.com")
	}

	return &tunnelSession{
		id:        id,
		dnsttCmd:  dnsttCmd,
		sshClient: sshClient,
	}, nil
}

func (m *tunnelManager) dialAllSessions() ([]*tunnelSession, error) {
	count := m.sessions
	if count < 1 {
		count = 1
	}
	if count > 4 {
		count = 4
	}

	var active []*tunnelSession
	for i := 1; i <= count; i++ {
		sess, err := m.dialSingleSession(i)
		if err != nil {
			if len(active) > 0 {
				log.Printf("[Sesi #%d] Dilewati (%v), berjalan dengan %d sesi aktif.", i, err, len(active))
				break
			}
			return nil, err
		}
		active = append(active, sess)
	}
	return active, nil
}

func (m *tunnelManager) refresh(reason string) {
	if !m.refreshing.CompareAndSwap(false, true) {
		return
	}
	defer m.refreshing.Store(false)

	log.Printf("[Refresh] %s", reason)
	newList, err := m.dialAllSessions()
	if err != nil {
		log.Printf("[Refresh] Gagal menyegarkan sesi: %v", err)
		return
	}

	m.mu.Lock()
	oldList := m.list
	m.list = newList
	m.mu.Unlock()

	if strings.TrimSpace(m.ns) != "" {
		log.Printf("[Refresh] %d jalur SlowDNS + SSH baru aktif!", len(newList))
	} else {
		log.Printf("[Refresh] %d sesi SSH baru aktif!", len(newList))
	}
	if len(oldList) > 0 {
		go func(items []*tunnelSession) {
			time.Sleep(2 * time.Second)
			for _, s := range items {
				s.close()
			}
		}(oldList)
	}
}

func (m *tunnelManager) closeAll() {
	m.mu.Lock()
	items := m.list
	m.list = nil
	m.mu.Unlock()
	for _, s := range items {
		s.close()
	}
}

type trackedConn struct {
	net.Conn
	timeout time.Duration
	onIO    func()
}

func (c *trackedConn) Read(b []byte) (int, error) {
	_ = c.Conn.SetDeadline(time.Now().Add(c.timeout))
	n, err := c.Conn.Read(b)
	if n > 0 && c.onIO != nil {
		c.onIO()
	}
	return n, err
}

func (c *trackedConn) Write(b []byte) (int, error) {
	_ = c.Conn.SetDeadline(time.Now().Add(c.timeout))
	n, err := c.Conn.Write(b)
	if n > 0 && c.onIO != nil {
		c.onIO()
	}
	return n, err
}

type dnsCacheEntry struct {
	packet    []byte
	expiresAt time.Time
}

type dnsCache struct {
	mu      sync.RWMutex
	entries map[string]dnsCacheEntry
}

func newDNSCache() *dnsCache {
	return &dnsCache{entries: make(map[string]dnsCacheEntry)}
}

func (c *dnsCache) get(query []byte) ([]byte, bool) {
	if len(query) <= 12 {
		return nil, false
	}
	key := string(query[12:])
	c.mu.RLock()
	entry, ok := c.entries[key]
	c.mu.RUnlock()
	if !ok || time.Now().After(entry.expiresAt) {
		return nil, false
	}
	resp := make([]byte, len(entry.packet))
	copy(resp, entry.packet)
	resp[0] = query[0]
	resp[1] = query[1]
	return resp, true
}

func (c *dnsCache) set(query, resp []byte, ttl time.Duration) {
	if len(query) <= 12 || len(resp) <= 12 {
		return
	}
	key := string(query[12:])
	stored := make([]byte, len(resp))
	copy(stored, resp)

	c.mu.Lock()
	if len(c.entries) > 500 {
		c.entries = make(map[string]dnsCacheEntry)
	}
	c.entries[key] = dnsCacheEntry{
		packet:    stored,
		expiresAt: time.Now().Add(ttl),
	}
	c.mu.Unlock()
}

func getFreeLocalAddr() (string, error) {
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return "", err
	}
	addr := l.Addr().String()
	l.Close()
	return addr, nil
}

func listenAbstractUnix(sockName string) (*net.UnixListener, error) {
	addr, err := net.ResolveUnixAddr("unix", "\x00"+sockName)
	if err != nil {
		return nil, err
	}
	return net.ListenUnix("unix", addr)
}

func acceptTunFd(listener *net.UnixListener) (int, error) {
	defer listener.Close()

	conn, err := listener.AcceptUnix()
	if err != nil {
		return -1, err
	}
	defer conn.Close()

	buf := make([]byte, 16)
	oob := make([]byte, syscall.CmsgSpace(4))
	_, oobn, _, _, err := conn.ReadMsgUnix(buf, oob)
	if err != nil {
		return -1, err
	}
	cmsgs, err := syscall.ParseSocketControlMessage(oob[:oobn])
	if err != nil || len(cmsgs) == 0 {
		return -1, fmt.Errorf("no control message received")
	}
	fds, err := syscall.ParseUnixRights(&cmsgs[0])
	if err != nil || len(fds) == 0 {
		return -1, fmt.Errorf("no fd in SCM_RIGHTS")
	}
	return fds[0], nil
}

func parseSocksAddr(r io.Reader) (host string, port int, err error) {
	atypBuf := make([]byte, 1)
	if _, err = io.ReadFull(r, atypBuf); err != nil {
		return "", 0, err
	}
	switch atypBuf[0] {
	case 0x01:
		buf := make([]byte, 6)
		if _, err = io.ReadFull(r, buf); err != nil {
			return "", 0, err
		}
		host = net.IP(buf[:4]).String()
		port = int(binary.BigEndian.Uint16(buf[4:6]))
	case 0x03:
		lenBuf := make([]byte, 1)
		if _, err = io.ReadFull(r, lenBuf); err != nil {
			return "", 0, err
		}
		dLen := int(lenBuf[0])
		buf := make([]byte, dLen+2)
		if _, err = io.ReadFull(r, buf); err != nil {
			return "", 0, err
		}
		host = string(buf[:dLen])
		port = int(binary.BigEndian.Uint16(buf[dLen : dLen+2]))
	case 0x04:
		buf := make([]byte, 18)
		if _, err = io.ReadFull(r, buf); err != nil {
			return "", 0, err
		}
		host = net.IP(buf[:16]).String()
		port = int(binary.BigEndian.Uint16(buf[16:18]))
	default:
		return "", 0, fmt.Errorf("unsupported atyp: %d", atypBuf[0])
	}
	return host, port, nil
}

func parseSocksUDPPacket(pkt []byte) (host string, port int, header []byte, payload []byte, ok bool) {
	if len(pkt) < 10 || pkt[2] != 0x00 {
		return "", 0, nil, nil, false
	}
	idx := 4
	switch pkt[3] {
	case 0x01:
		if len(pkt) < idx+6 {
			return "", 0, nil, nil, false
		}
		host = net.IP(pkt[idx : idx+4]).String()
		port = int(binary.BigEndian.Uint16(pkt[idx+4 : idx+6]))
		idx += 6
	case 0x03:
		if len(pkt) < idx+1 {
			return "", 0, nil, nil, false
		}
		dLen := int(pkt[idx])
		idx++
		if len(pkt) < idx+dLen+2 {
			return "", 0, nil, nil, false
		}
		host = string(pkt[idx : idx+dLen])
		port = int(binary.BigEndian.Uint16(pkt[idx+dLen : idx+dLen+2]))
		idx += dLen + 2
	case 0x04:
		if len(pkt) < idx+18 {
			return "", 0, nil, nil, false
		}
		host = net.IP(pkt[idx : idx+16]).String()
		port = int(binary.BigEndian.Uint16(pkt[idx+16 : idx+18]))
		idx += 18
	default:
		return "", 0, nil, nil, false
	}
	return host, port, pkt[:idx], pkt[idx:], true
}

func dialSSHWithTimeout(sshClient *ssh.Client, network, addr string, timeout time.Duration) (net.Conn, error) {
	if sshClient == nil {
		return nil, fmt.Errorf("ssh client nil")
	}
	type dialRes struct {
		conn net.Conn
		err  error
	}
	ch := make(chan dialRes, 1)
	go func() {
		c, e := sshClient.Dial(network, addr)
		ch <- dialRes{c, e}
	}()

	select {
	case res := <-ch:
		return res.conn, res.err
	case <-time.After(timeout):
		go func() {
			res := <-ch
			if res.conn != nil {
				_ = res.conn.Close()
			}
		}()
		return nil, fmt.Errorf("dial timeout")
	}
}

func queryDNSOnce(sshClient *ssh.Client, serverIP string, query []byte) ([]byte, error) {
	conn, err := dialSSHWithTimeout(sshClient, "tcp", net.JoinHostPort(serverIP, "53"), 7*time.Second)
	if err != nil {
		return nil, err
	}
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(time.Duration(*keepaliveSec) * time.Second))

	req := make([]byte, 2+len(query))
	binary.BigEndian.PutUint16(req[:2], uint16(len(query)))
	copy(req[2:], query)

	if _, err := conn.Write(req); err != nil {
		return nil, err
	}

	lenBuf := make([]byte, 2)
	if _, err := io.ReadFull(conn, lenBuf); err != nil {
		return nil, err
	}
	respLen := int(binary.BigEndian.Uint16(lenBuf))
	if respLen <= 0 || respLen > 4096 {
		return nil, fmt.Errorf("invalid dns length")
	}
	resp := make([]byte, respLen)
	if _, err := io.ReadFull(conn, resp); err != nil {
		return nil, err
	}
	return resp, nil
}

func resolveDNS(mgr *tunnelManager, cache *dnsCache, dstHost string, query []byte) ([]byte, error) {
	if cached, ok := cache.get(query); ok {
		return cached, nil
	}

	sshClient := mgr.getClient()
	resp, err := queryDNSOnce(sshClient, dstHost, query)
	if err != nil {
		fallback := "1.1.1.1"
		if dstHost == "1.1.1.1" {
			fallback = "8.8.8.8"
		}
		resp, err = queryDNSOnce(mgr.getClient(), fallback, query)
		if err != nil {
			return nil, err
		}
	}

	mgr.noteTraffic()
	cache.set(query, resp, 180*time.Second)
	return resp, nil
}

func relayBidirectional(localConn, remoteConn net.Conn, mgr *tunnelManager) {
	mgr.activeStreams.Add(1)
	defer mgr.activeStreams.Add(-1)

	c1 := &trackedConn{Conn: localConn, timeout: 180 * time.Second, onIO: mgr.noteTraffic}
	c2 := &trackedConn{Conn: remoteConn, timeout: 180 * time.Second, onIO: mgr.noteTraffic}

	var wg sync.WaitGroup
	wg.Add(2)

	go func() {
		defer wg.Done()
		buf := make([]byte, 65536)
		_, _ = io.CopyBuffer(c2, c1, buf)
		if cw, ok := remoteConn.(closeWriter); ok {
			_ = cw.CloseWrite()
		}
	}()

	go func() {
		defer wg.Done()
		buf := make([]byte, 65536)
		_, _ = io.CopyBuffer(c1, c2, buf)
		if cw, ok := localConn.(closeWriter); ok {
			_ = cw.CloseWrite()
		}
	}()

	wg.Wait()
}

func handleSocksConn(clientConn net.Conn, mgr *tunnelManager, cache *dnsCache, dialSem chan struct{}) {
	defer clientConn.Close()
	if tcpConn, ok := clientConn.(*net.TCPConn); ok {
		_ = tcpConn.SetNoDelay(true)
		_ = tcpConn.SetReadBuffer(262144)
		_ = tcpConn.SetWriteBuffer(262144)
	}

	hdr := make([]byte, 2)
	if _, err := io.ReadFull(clientConn, hdr); err != nil || hdr[0] != 0x05 {
		return
	}
	methods := make([]byte, int(hdr[1]))
	if _, err := io.ReadFull(clientConn, methods); err != nil {
		return
	}
	_, _ = clientConn.Write([]byte{0x05, 0x00})

	reqHdr := make([]byte, 3)
	if _, err := io.ReadFull(clientConn, reqHdr); err != nil || reqHdr[0] != 0x05 {
		return
	}
	cmd := reqHdr[1]
	host, port, err := parseSocksAddr(clientConn)
	if err != nil {
		return
	}

	switch cmd {
	case 0x01: // TCP CONNECT
		select {
		case dialSem <- struct{}{}:
		case <-time.After(10 * time.Second):
			_, _ = clientConn.Write([]byte{0x05, 0x04, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
			return
		}

		target := net.JoinHostPort(host, strconv.Itoa(port))
		remoteConn, err := dialSSHWithTimeout(mgr.getClient(), "tcp", target, 12*time.Second)
		<-dialSem
		if err != nil {
			_, _ = clientConn.Write([]byte{0x05, 0x04, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
			return
		}
		defer remoteConn.Close()

		if _, err := clientConn.Write([]byte{0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0}); err != nil {
			return
		}
		relayBidirectional(clientConn, remoteConn, mgr)

	case 0x03: // UDP ASSOCIATE (Cached DNS-over-TCP relay)
		udpConn, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.ParseIP("127.0.0.1"), Port: 0})
		if err != nil {
			_, _ = clientConn.Write([]byte{0x05, 0x01, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
			return
		}
		defer udpConn.Close()

		udpPort := udpConn.LocalAddr().(*net.UDPAddr).Port
		reply := []byte{
			0x05, 0x00, 0x00, 0x01,
			127, 0, 0, 1,
			byte(udpPort >> 8), byte(udpPort),
		}
		if _, err := clientConn.Write(reply); err != nil {
			return
		}

				go func() {
					var gwMu sync.Mutex
					var gwConn net.Conn
					var clientUDPAddr *net.UDPAddr
					var connsMu sync.RWMutex
					conns := make(map[uint16]string)
					connIDs := make(map[string]uint16)
					var nextID uint16 = 1

					defer func() {
						gwMu.Lock()
						if gwConn != nil {
							_ = gwConn.Close()
						}
						gwMu.Unlock()
					}()

					ensureUDPGW := func() (net.Conn, error) {
						gwMu.Lock()
						defer gwMu.Unlock()
						if gwConn != nil {
							return gwConn, nil
						}
						c, err := dialSSHWithTimeout(mgr.getClient(), "tcp", "127.0.0.1:7300", 8*time.Second)
						if err != nil {
							return nil, err
						}
						gwConn = c
						go func(conn net.Conn) {
							defer func() {
								_ = conn.Close()
								gwMu.Lock()
								if gwConn == conn {
									gwConn = nil
								}
								gwMu.Unlock()
							}()
							hdrBuf := make([]byte, 3)
							for {
								_ = conn.SetReadDeadline(time.Now().Add(90 * time.Second))
								if _, err := io.ReadFull(conn, hdrBuf); err != nil {
									return
								}
								pktLen := int(binary.BigEndian.Uint16(hdrBuf[:2]))
								flags := hdrBuf[2]
								if pktLen <= 0 || pktLen > 8192 {
									return
								}
								body := make([]byte, pktLen)
								if _, err := io.ReadFull(conn, body); err != nil {
									return
								}
								if (flags&0x01) != 0 {
									continue // keepalive
								}
								addrLen := 6
								if (flags&0x04) != 0 {
									addrLen = 18
								}
								if len(body) < 2+addrLen {
									continue
								}
								payload := body[2+addrLen:]
								var socksResp []byte
								if addrLen == 6 {
									socksResp = make([]byte, 10+len(payload))
									socksResp[3] = 0x01
									copy(socksResp[4:10], body[2:8])
									copy(socksResp[10:], payload)
								} else {
									socksResp = make([]byte, 22+len(payload))
									socksResp[3] = 0x04
									copy(socksResp[4:22], body[2:20])
									copy(socksResp[22:], payload)
								}
								connsMu.RLock()
								dst := clientUDPAddr
								connsMu.RUnlock()
								if dst != nil {
									mgr.noteTraffic()
									_, _ = udpConn.WriteToUDP(socksResp, dst)
								}
							}
						}(c)
						return c, nil
					}

					buf := make([]byte, 4096)
					for {
						n, srcAddr, err := udpConn.ReadFromUDP(buf)
						if err != nil {
							return
						}
						connsMu.Lock()
						clientUDPAddr = srcAddr
						connsMu.Unlock()

						pktCopy := make([]byte, n)
						copy(pktCopy, buf[:n])

						dstHost, dstPort, socksHdr, udpPayload, ok := parseSocksUDPPacket(pktCopy)
						if !ok {
							continue
						}

						if dstPort == 53 {
							go func(addr *net.UDPAddr, h string, sh []byte, q []byte) {
								dnsResp, err := resolveDNS(mgr, cache, h, q)
								if err != nil {
									return
								}
								out := make([]byte, len(sh)+len(dnsResp))
								copy(out, sh)
								copy(out[len(sh):], dnsResp)
								_, _ = udpConn.WriteToUDP(out, addr)
							}(srcAddr, dstHost, socksHdr, udpPayload)
							continue
						}

						// Teruskan UDP non-DNS (WebRTC/Video Call/Game) ke BadVPN UDPGW (127.0.0.1:7300)
						go func(h string, p int, payload []byte) {
							ip := net.ParseIP(h)
							if ip == nil {
								return
							}
							ip4 := ip.To4()
							if ip4 == nil {
								return
							}
							conn, err := ensureUDPGW()
							if err != nil {
								return
							}
							key := fmt.Sprintf("%s:%d", ip4.String(), p)
							connsMu.Lock()
							cid, exists := connIDs[key]
							if !exists {
								cid = nextID
								nextID++
								connIDs[key] = cid
								conns[cid] = key
							}
							connsMu.Unlock()

							// Format frame UDPGW: [2B len][1B flags=0x00][2B connID][4B IPv4][2B port][payload]
							bodyLen := 2 + 4 + 2 + len(payload)
							frame := make([]byte, 3+bodyLen)
							binary.BigEndian.PutUint16(frame[0:2], uint16(bodyLen))
							frame[2] = 0x00
							binary.BigEndian.PutUint16(frame[3:5], cid)
							copy(frame[5:9], ip4)
							binary.BigEndian.PutUint16(frame[9:11], uint16(p))
							copy(frame[11:], payload)

							gwMu.Lock()
							_ = conn.SetWriteDeadline(time.Now().Add(10 * time.Second))
							_, wErr := conn.Write(frame)
							gwMu.Unlock()
							if wErr != nil {
								_ = conn.Close()
							} else {
								mgr.noteTraffic()
							}
						}(dstHost, dstPort, udpPayload)
					}
				}()

		_, _ = io.Copy(io.Discard, clientConn)

	default:
		_, _ = clientConn.Write([]byte{0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
	}
}

var lastTrafficUnix int64
var keepaliveSec = flag.Int("keepalive", 3, "ssh keepalive interval seconds")

var customPayload = flag.String("payload", "", "custom HTTP payload")
var remoteProxy = flag.String("proxy", "", "remote proxy host:port or user:pass@host:port")
var sniHost = flag.String("sni", "", "TLS SNI hostname")


func renderPayloadString(raw, sshAddr string) string {
	host, port, err := net.SplitHostPort(sshAddr)
	if err != nil {
		host = sshAddr
		port = "22"
	}
	p := raw
	p = strings.ReplaceAll(p, "[crlf]", "\r\n")
	p = strings.ReplaceAll(p, "[lf]", "\n")
	p = strings.ReplaceAll(p, "[cr]", "\r")
	p = strings.ReplaceAll(p, "[host_port]", net.JoinHostPort(host, port))
	p = strings.ReplaceAll(p, "[ssh]", net.JoinHostPort(host, port))
	p = strings.ReplaceAll(p, "[host]", host)
	p = strings.ReplaceAll(p, "[port]", port)
	p = strings.ReplaceAll(p, "[protocol]", "HTTP/1.1")
	p = strings.ReplaceAll(p, "[ua]", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36")
	return p
}


var lastServerRxNs atomic.Int64

type rxTrackConn struct{ net.Conn }

func (c *rxTrackConn) Read(p []byte) (int, error) {
	n, err := c.Conn.Read(p)
	if n > 0 {
		lastServerRxNs.Store(time.Now().UnixNano())
	}
	return n, err
}

type bufferedNetConn struct {
	net.Conn
	r *bufio.Reader
}

func (b *bufferedNetConn) Read(p []byte) (int, error) {
	return b.r.Read(p)
}

func dialWithPayloadProxy(sshAddr, proxyStr, payloadStr, sni string, timeout time.Duration) (net.Conn, error) {
	dialTarget := sshAddr
	var proxyAuth string

	if strings.TrimSpace(proxyStr) != "" {
		px := strings.TrimSpace(proxyStr)
		if atIdx := strings.LastIndex(px, "@"); atIdx != -1 {
			proxyAuth = px[:atIdx]
			px = px[atIdx+1:]
		}
		if !strings.Contains(px, ":") {
			px = net.JoinHostPort(px, "80")
		}
		dialTarget = px
	}

	rawConn, err := net.DialTimeout("tcp", dialTarget, timeout)
	if err != nil {
		return nil, fmt.Errorf("gagal koneksi ke %s: %w", dialTarget, err)
	}
	if tc, ok := rawConn.(*net.TCPConn); ok {
		_ = tc.SetNoDelay(true); _ = tc.SetReadBuffer(262144); _ = tc.SetWriteBuffer(262144)
		_ = tc.SetKeepAlive(true)
		_ = tc.SetKeepAlivePeriod(45 * time.Second)
	}

	var conn net.Conn = rawConn
	if strings.TrimSpace(sni) != "" {
		tlsConn := tls.Client(rawConn, &tls.Config{
			ServerName:         strings.TrimSpace(sni),
			InsecureSkipVerify: true,
		})
		_ = tlsConn.SetDeadline(time.Now().Add(timeout))
		if err := tlsConn.Handshake(); err != nil {
			_ = rawConn.Close()
			return nil, fmt.Errorf("TLS handshake (%s) gagal: %w", sni, err)
		}
		_ = tlsConn.SetDeadline(time.Time{})
		conn = tlsConn
	}

	if strings.TrimSpace(payloadStr) != "" {
		rendered := renderPayloadString(payloadStr, sshAddr)
		if proxyAuth != "" && !strings.Contains(strings.ToLower(rendered), "proxy-authorization:") {
			b64 := base64.StdEncoding.EncodeToString([]byte(proxyAuth))
			rendered = strings.Replace(rendered, "\r\n\r\n", "\r\nProxy-Authorization: Basic "+b64+"\r\n\r\n", 1)
		}
		normSplit := strings.ReplaceAll(strings.ReplaceAll(rendered, "[delay_split]", "[split]"), "[instant_split]", "[split]")
		segments := strings.Split(normSplit, "[split]")
		for i, seg := range segments {
			if seg == "" {
				continue
			}
			_ = conn.SetWriteDeadline(time.Now().Add(timeout))
			if _, err := conn.Write([]byte(seg)); err != nil {
				_ = conn.Close()
				return nil, fmt.Errorf("gagal kirim payload: %w", err)
			}
			if i < len(segments)-1 {
				time.Sleep(100 * time.Millisecond)
			}
		}

		br := bufio.NewReader(conn)
		_ = conn.SetReadDeadline(time.Now().Add(timeout))
		statusLine, err := br.ReadString('\n')
		if err != nil {
			_ = conn.Close()
			return nil, fmt.Errorf("tidak ada respons HTTP dari server: %w", err)
		}
		log.Printf("[HTTP] Respons: %s", strings.TrimSpace(statusLine))
		for {
			hdrLine, err := br.ReadString('\n')
			if err != nil {
				_ = conn.Close()
				return nil, fmt.Errorf("header HTTP terputus: %w", err)
			}
			if hdrLine == "\r\n" || hdrLine == "\n" {
				break
			}
		}
		_ = conn.SetDeadline(time.Time{})
		lastServerRxNs.Store(time.Now().UnixNano()); return &rxTrackConn{Conn: &bufferedNetConn{Conn: conn, r: br}}, nil
	} else if strings.TrimSpace(proxyStr) != "" {
		connectReq := fmt.Sprintf("CONNECT %s HTTP/1.1\r\nHost: %s\r\n", sshAddr, sshAddr)
		if proxyAuth != "" {
			b64 := base64.StdEncoding.EncodeToString([]byte(proxyAuth))
			connectReq += "Proxy-Authorization: Basic " + b64 + "\r\n"
		}
		connectReq += "\r\n"
		_ = conn.SetWriteDeadline(time.Now().Add(timeout))
		if _, err := conn.Write([]byte(connectReq)); err != nil {
			_ = conn.Close()
			return nil, fmt.Errorf("gagal kirim CONNECT proxy: %w", err)
		}
		br := bufio.NewReader(conn)
		_ = conn.SetReadDeadline(time.Now().Add(timeout))
		statusLine, err := br.ReadString('\n')
		if err != nil {
			_ = conn.Close()
			return nil, fmt.Errorf("proxy tidak merespons CONNECT: %w", err)
		}
		log.Printf("[HTTP] Proxy: %s", strings.TrimSpace(statusLine))
		for {
			hdrLine, err := br.ReadString('\n')
			if err != nil || hdrLine == "\r\n" || hdrLine == "\n" {
				break
			}
		}
		_ = conn.SetDeadline(time.Time{})
		lastServerRxNs.Store(time.Now().UnixNano()); return &rxTrackConn{Conn: &bufferedNetConn{Conn: conn, r: br}}, nil
	}

	lastServerRxNs.Store(time.Now().UnixNano()); return &rxTrackConn{Conn: conn}, nil
}

func main() {
	dnsttBin := flag.String("dnstt", "", "Path to libdnstt.so")
	tunBin := flag.String("tunbin", "", "Path to libtun2socks.so")
	tunSock := flag.String("tunsock", "", "Abstract unix socket name to receive TUN fd")
	ctrlSock := flag.String("ctrlsock", "", "Abstract unix socket name for control commands")
	dns := flag.String("dns", "1.1.1.1", "DNS resolver")
	dnsType := flag.String("type", "NULL", "DNS record type (NULL, AUTO, TXT)")
	ns := flag.String("ns", "", "SlowDNS Nameserver")
	pubkey := flag.String("pubkey", "", "SlowDNS Public Key")
	sshAddr := flag.String("ssh", "127.0.0.1:22", "SSH Host:Port")
	user := flag.String("user", "", "SSH Username")
	pass := flag.String("pass", "", "SSH Password")
	sessions := flag.Int("sessions", 2, "Number of parallel SlowDNS+SSH sessions")
	socksAddr := flag.String("socks", "127.0.0.1:1080", "Local SOCKS5 listen address")
	xrayBin := flag.String("xraybin", "", "Path to libxray.so")
	xrayCfg := flag.String("xraycfg", "", "Path to Xray JSON config")
	flag.Parse()

	log.SetOutput(os.Stdout)
	log.SetFlags(0)

	var mgr *tunnelManager
	var xrayCmd *exec.Cmd

	if *xrayBin != "" && *xrayCfg != "" {
		cmd := exec.Command(*xrayBin, "run", "-c", *xrayCfg)
		cmd.Stdout = os.Stdout
		cmd.Stderr = os.Stderr
		if err := cmd.Start(); err != nil {
			log.Fatalf("[V2Ray] Gagal menjalankan Xray-core: %v", err)
		}
		xrayCmd = cmd
		log.Printf("[V2Ray] Xray-core dimulai, menunggu SOCKS5 %s...", *socksAddr)
		ready := false
		for i := 0; i < 60; i++ {
			c, err := net.DialTimeout("tcp", *socksAddr, 100*time.Millisecond)
			if err == nil {
				_ = c.Close()
				ready = true
				break
			}
			time.Sleep(100 * time.Millisecond)
		}
		if !ready {
			_ = cmd.Process.Kill()
			log.Fatalf("[V2Ray] Timeout menunggu SOCKS5 di %s", *socksAddr)
		}
		log.Println("[V2Ray] Tersambung! Membuka bridge TUN...")
		go func() {
			_ = cmd.Wait()
			log.Println("[V2Ray] Proses Xray berhenti.")
			os.Exit(1)
		}()
	} else {
		mgr = &tunnelManager{
			dnsttBin: *dnsttBin,
			dns:      *dns,
			dnsType:  *dnsType,
			ns:       *ns,
			pubkey:   *pubkey,
			sshAddr:  *sshAddr,
			user:     *user,
			pass:     *pass,
			sessions: *sessions,
		}
		activeList, err := mgr.dialAllSessions()
		if err != nil {
			log.Fatalf("[Core] %v", err)
		}
		mgr.mu.Lock()
		mgr.list = activeList
		mgr.mu.Unlock()
		log.Printf("[SSH] Tersambung (%d sesi aktif)! Membuka SOCKS5 (Full Speed)...", len(activeList))
	}

	if *ctrlSock != "" {
		ctrlListener, err := listenAbstractUnix(*ctrlSock)
		if err == nil {
			go func() {
				defer ctrlListener.Close()
				for {
					conn, err := ctrlListener.AcceptUnix()
					if err != nil {
						return
					}
					go func(c *net.UnixConn) {
						defer c.Close()
						line, _ := bufio.NewReader(c).ReadString('\n')
						if strings.TrimSpace(line) == "REFRESH" {
									if mgr == nil { os.Exit(0) }
							if strings.TrimSpace(mgr.ns) != "" {
						mgr.refresh("Menyegarkan port UDP SlowDNS & sesi SSH...")
					} else {
						mgr.refresh("Menyegarkan sesi SSH...")
					}
						}
					}(conn)
				}
			}()
		}
	}

	if mgr != nil && strings.TrimSpace(mgr.ns) == "" {
		go func() {
			miss := 0
			for range time.Tick(15 * time.Second) {
				c := mgr.getClient()
				if mgr.refreshing.Load() || mgr.isBusy() || c == nil || time.Since(time.Unix(0, lastServerRxNs.Load())) < 20*time.Second {
					miss = 0
					continue
				}
				done := make(chan error, 1)
				go func() { _, _, err := c.SendRequest("keepalive@openssh.com", true, nil); done <- err }()
				select {
				case err := <-done:
					if err != nil {
						miss++
					} else {
						miss = 0
						lastServerRxNs.Store(time.Now().UnixNano())
					}
				case <-time.After(12 * time.Second):
					miss++
				}
				if miss >= 2 {
					miss = 0
					mgr.refresh("Koneksi tidak merespons, menyegarkan sesi...")
				}
			}
		}()
	} else if mgr != nil {
		go func() {
			ticker := time.NewTicker(time.Duration(*keepaliveSec) * time.Second)
			defer ticker.Stop()
			stalledCount := 0
			for range ticker.C {
				if mgr.isBusy() || mgr.refreshing.Load() {
					stalledCount = 0
					continue
				}
				client := mgr.getClient()
				if client == nil {
					continue
				}
				done := make(chan error, 1)
				go func(c *ssh.Client) {
					_, _, err := c.SendRequest("keepalive@openssh.com", true, nil)
					done <- err
				}(client)

				select {
				case err := <-done:
					if err != nil {
						stalledCount++
					} else {
						stalledCount = 0
					}
				case <-time.After(6 * time.Second):
					stalledCount++
				}

				if stalledCount >= 2 {
					stalledCount = 0
					mgr.refresh("Auto-Heal: jalur SlowDNS melambat, menyegarkan sesi...")
				}
			}
		}()
	}

	cache := newDNSCache()
	dialSem := make(chan struct{}, 128)

	var socksListener net.Listener
	if mgr != nil {
		var err error
		socksListener, err = net.Listen("tcp", *socksAddr)
	if err != nil {
		log.Fatalf("[SOCKS5] Gagal listen di %s: %v", *socksAddr, err)
	}
	go func() {
		for {
			conn, err := socksListener.Accept()
			if err != nil {
				return
			}
			go handleSocksConn(conn, mgr, cache, dialSem)
		}
	}()
	}

	var tunListener *net.UnixListener
	if *tunSock != "" && *tunBin != "" {
		tunListener, err = listenAbstractUnix(*tunSock)
		if err != nil {
			log.Fatalf("[TUN] Gagal membuka socket SCM_RIGHTS: %v", err)
		}
	}

	var procMu sync.Mutex
	var tunCmd *exec.Cmd
	if tunListener != nil {
		go func() {
			tunFd, err := acceptTunFd(tunListener)
			if err != nil {
				log.Printf("[TUN] Gagal menerima FD: %v", err)
				return
			}
			tunFile := os.NewFile(uintptr(tunFd), "tun0")
			defer tunFile.Close()

			cmd := exec.Command(*tunBin, "--device", "fd://3", "--proxy", "socks5://"+*socksAddr, "--mtu", "1400", "--loglevel", "error")
			cmd.ExtraFiles = []*os.File{tunFile}
			cmd.Stdout = os.Stdout
			cmd.Stderr = os.Stderr
			if err := cmd.Start(); err != nil {
				log.Printf("[TUN] Gagal menjalankan tun2socks: %v", err)
				return
			}
			procMu.Lock()
			tunCmd = cmd
			procMu.Unlock()
			log.Println("[TUN] Engine tun2socks aktif (FD 3, MTU 1400)")
			_ = cmd.Wait()
		}()
	}

	sigCh := make(chan os.Signal, 1)
	signal.Notify(sigCh, syscall.SIGINT, syscall.SIGTERM)

	fmt.Println("READY_CONNECTED")

	<-sigCh
	if socksListener != nil { _ = socksListener.Close() }
	procMu.Lock()
	if tunCmd != nil && tunCmd.Process != nil {
		_ = tunCmd.Process.Kill()
	}
	procMu.Unlock()
	if xrayCmd != nil && xrayCmd.Process != nil { _ = xrayCmd.Process.Kill() }
	if mgr != nil { mgr.closeAll() }
}
