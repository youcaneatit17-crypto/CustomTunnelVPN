package main

import (
	"context"
	"flag"
	"fmt"
	"log"
	"net"
	"os"
	"os/exec"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"github.com/armon/go-socks5"
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

	// 1. Jalankan SlowDNS (libdnstt.so)
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

	// 2. Hubungkan SSH-2 Client
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
	log.Println("[SSH] Tersambung! Membuka SOCKS5 forwarding...")

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

	// 3. Jalankan SOCKS5 Server
	conf := &socks5.Config{
		Dial: func(ctx context.Context, network, addr string) (net.Conn, error) {
			return sshClient.Dial(network, addr)
		},
	}
	server, err := socks5.New(conf)
	if err != nil {
		log.Fatalf("[SOCKS5] Gagal inisialisasi: %v", err)
	}

	go func() {
		if err := server.ListenAndServe("tcp", *socksAddr); err != nil {
			log.Fatalf("[SOCKS5] Error: %v", err)
		}
	}()

	// 4. Siapkan Abstract Unix Socket sebelum READY_CONNECTED agar tidak race condition
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

			// ExtraFiles[0] menjadi FD 3 di proses child
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
	if tunCmd != nil && tunCmd.Process != nil {
		_ = tunCmd.Process.Kill()
	}
	if dnsttCmd != nil && dnsttCmd.Process != nil {
		_ = dnsttCmd.Process.Kill()
	}
}
