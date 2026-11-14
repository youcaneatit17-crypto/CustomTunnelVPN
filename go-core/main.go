package main

import (
	"encoding/binary"
	"flag"
	"fmt"
	"io"
	"log"
	"net"
	"os"
	"os/exec"
	"os/signal"
	"strconv"
	"strings"
	"syscall"
	"time"

	"golang.org/x/crypto/ssh"
)

func getFreeLocalAddr() (string, error) {
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return "", err
	}
	addr := l.Addr().String()
	l.Close()
	return addr, nil
}

func listenTunSocket(sockName string) (*net.UnixListener, error) {
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
	case 0x01: // IPv4
		buf := make([]byte, 6)
		if _, err = io.ReadFull(r, buf); err != nil {
			return "", 0, err
		}
		host = net.IP(buf[:4]).String()
		port = int(binary.BigEndian.Uint16(buf[4:6]))
	case 0x03: // Domain
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
	case 0x04: // IPv6
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

func forwardDNSOverTCP(sshClient *ssh.Client, dstHost string, query []byte) ([]byte, error) {
	conn, err := sshClient.Dial("tcp", net.JoinHostPort(dstHost, "53"))
	if err != nil {
		return nil, err
	}
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(10 * time.Second))

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
	resp := make([]byte, respLen)
	if _, err := io.ReadFull(conn, resp); err != nil {
		return nil, err
	}
	return resp, nil
}

func handleSocksConn(clientConn net.Conn, sshClient *ssh.Client) {
	defer clientConn.Close()

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
		target := net.JoinHostPort(host, strconv.Itoa(port))
		remoteConn, err := sshClient.Dial("tcp", target)
		if err != nil {
			_, _ = clientConn.Write([]byte{0x05, 0x04, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
			return
		}
		defer remoteConn.Close()
		_, _ = clientConn.Write([]byte{0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0})

		go func() {
			_, _ = io.Copy(remoteConn, clientConn)
			_ = remoteConn.Close()
		}()
		_, _ = io.Copy(clientConn, remoteConn)

	case 0x03: // UDP ASSOCIATE (DNS-over-TCP relay)
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
			buf := make([]byte, 4096)
			for {
				n, srcAddr, err := udpConn.ReadFromUDP(buf)
				if err != nil {
					return
				}
				pktCopy := make([]byte, n)
				copy(pktCopy, buf[:n])

				dstHost, dstPort, socksHdr, dnsPayload, ok := parseSocksUDPPacket(pktCopy)
				if !ok || dstPort != 53 {
					continue
				}

				go func(addr *net.UDPAddr, h string, sh []byte, q []byte) {
					dnsResp, err := forwardDNSOverTCP(sshClient, h, q)
					if err != nil {
						return
					}
					out := make([]byte, len(sh)+len(dnsResp))
					copy(out, sh)
					copy(out[len(sh):], dnsResp)
					_, _ = udpConn.WriteToUDP(out, addr)
				}(srcAddr, dstHost, socksHdr, dnsPayload)
			}
		}()

		_, _ = io.Copy(io.Discard, clientConn)

	default:
		_, _ = clientConn.Write([]byte{0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
	}
}

func main() {
	dnsttBin := flag.String("dnstt", "", "Path to libdnstt.so")
	tunBin := flag.String("tunbin", "", "Path to libtun2socks.so")
	tunSock := flag.String("tunsock", "", "Abstract unix socket name to receive TUN fd")
	dns := flag.String("dns", "1.1.1.1", "DNS resolver")
	dnsType := flag.String("type", "NULL", "DNS record type (NULL, AUTO, TXT)")
	ns := flag.String("ns", "", "SlowDNS Nameserver")
	pubkey := flag.String("pubkey", "", "SlowDNS Public Key")
	sshAddr := flag.String("ssh", "127.0.0.1:22", "SSH Host:Port")
	user := flag.String("user", "", "SSH Username")
	pass := flag.String("pass", "", "SSH Password")
	socksAddr := flag.String("socks", "127.0.0.1:1080", "Local SOCKS5 listen address")
	flag.Parse()

	log.SetOutput(os.Stdout)
	log.SetFlags(0)

	targetDialAddr := *sshAddr
	var dnsttCmd *exec.Cmd

	if strings.HasPrefix(*sshAddr, "127.0.0.1:") || strings.HasPrefix(*sshAddr, "localhost:") {
		freeAddr, err := getFreeLocalAddr()
		if err != nil {
			log.Fatalf("[SlowDNS] Gagal mencari port lokal bebas: %v", err)
		}
		targetDialAddr = freeAddr

		resolverVal := strings.TrimSpace(*dns)
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

		log.Printf("[SlowDNS] Memulai transport (%s, type=%s) ke %s...", resolverVal, *dnsType, *ns)
		dnsttCmd = exec.Command(*dnsttBin, resolverFlag, resolverVal, "-type", *dnsType, "-pubkey", *pubkey, *ns, freeAddr)
		dnsttCmd.Stdout = os.Stdout
		dnsttCmd.Stderr = os.Stderr
		if err := dnsttCmd.Start(); err != nil {
			log.Fatalf("[SlowDNS] Gagal memulai: %v", err)
		}
		defer dnsttCmd.Process.Kill()
		time.Sleep(1500 * time.Millisecond)
	}

	log.Printf("[SSH] Menghubungkan ke %s sebagai %s...", targetDialAddr, *user)
	config := &ssh.ClientConfig{
		User: *user,
		Auth: []ssh.AuthMethod{
			ssh.Password(*pass),
		},
		HostKeyCallback: ssh.InsecureIgnoreHostKey(),
		Timeout:         45 * time.Second,
	}

	sshClient, err := ssh.Dial("tcp", targetDialAddr, config)
	if err != nil {
		log.Fatalf("[SSH] Gagal terhubung: %v", err)
	}
	defer sshClient.Close()
	log.Println("[SSH] Tersambung! Membuka SOCKS5 + DNS-over-TCP...")

	go func() {
		ticker := time.NewTicker(15 * time.Second)
		defer ticker.Stop()
		for range ticker.C {
			_, _, err := sshClient.SendRequest("keepalive@openssh.com", true, nil)
			if err != nil {
				log.Printf("[SSH] Koneksi terputus: %v", err)
				os.Exit(1)
			}
		}
	}()

	socksListener, err := net.Listen("tcp", *socksAddr)
	if err != nil {
		log.Fatalf("[SOCKS5] Gagal listen di %s: %v", *socksAddr, err)
	}
	go func() {
		for {
			conn, err := socksListener.Accept()
			if err != nil {
				return
			}
			go handleSocksConn(conn, sshClient)
		}
	}()

	var tunListener *net.UnixListener
	if *tunSock != "" && *tunBin != "" {
		tunListener, err = listenTunSocket(*tunSock)
		if err != nil {
			log.Fatalf("[TUN] Gagal membuka socket SCM_RIGHTS: %v", err)
		}
	}

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

			tunCmd = exec.Command(*tunBin, "--device", "fd://3", "--proxy", "socks5://"+*socksAddr, "--mtu", "1500", "--loglevel", "warn")
			tunCmd.ExtraFiles = []*os.File{tunFile}
			tunCmd.Stdout = os.Stdout
			tunCmd.Stderr = os.Stderr
			if err := tunCmd.Start(); err != nil {
				log.Printf("[TUN] Gagal menjalankan tun2socks: %v", err)
				return
			}
			log.Println("[TUN] Engine tun2socks aktif (FD 3 O_RDWR)")
			tunCmd.Wait()
		}()
	}

	sigCh := make(chan os.Signal, 1)
	signal.Notify(sigCh, syscall.SIGINT, syscall.SIGTERM)

	fmt.Println("READY_CONNECTED")

	<-sigCh
	_ = socksListener.Close()
	if tunCmd != nil && tunCmd.Process != nil {
		_ = tunCmd.Process.Kill()
	}
	if dnsttCmd != nil && dnsttCmd.Process != nil {
		_ = dnsttCmd.Process.Kill()
	}
}
