package main

import (
	"context"
	"flag"
	"fmt"
	"log"
	"net"
	"os"
	"os/exec"
	"strings"
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

func main() {
	dnsttBin := flag.String("dnstt", "", "Path to libdnstt.so")
	dns := flag.String("dns", "8.8.8.8", "DNS resolver")
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

	// 1. Jika Host SSH adalah localhost/127.0.0.1, jalankan SlowDNS pada port lokal bebas (anti bentrok port 22/109)
	if strings.HasPrefix(*sshAddr, "127.0.0.1:") || strings.HasPrefix(*sshAddr, "localhost:") {
		freeAddr, err := getFreeLocalAddr()
		if err != nil {
			log.Fatalf("[SlowDNS] Gagal mencari port lokal bebas: %v", err)
		}
		targetDialAddr = freeAddr

		log.Printf("[SlowDNS] Memulai transport ke %s via %s:53 (local %s)...", *ns, *dns, freeAddr)
		cmd := exec.Command(*dnsttBin, "-udp", *dns+":53", "-pubkey", *pubkey, *ns, freeAddr)
		cmd.Stdout = os.Stdout
		cmd.Stderr = os.Stderr
		if err := cmd.Start(); err != nil {
			log.Fatalf("[SlowDNS] Gagal memulai: %v", err)
		}
		defer cmd.Process.Kill()
		time.Sleep(1500 * time.Millisecond)
	}

	// 2. Hubungkan Modern SSH-2 Client ke terowongan SlowDNS
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

	// Keep-Alive setiap 15 detik
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

	// 3. Jalankan SOCKS5 Server pada port bebas atau 1080
	conf := &socks5.Config{
		Dial: func(ctx context.Context, network, addr string) (net.Conn, error) {
			return sshClient.Dial(network, addr)
		},
	}
	server, err := socks5.New(conf)
	if err != nil {
		log.Fatalf("[SOCKS5] Gagal inisialisasi: %v", err)
	}

	fmt.Println("READY_CONNECTED")
	if err := server.ListenAndServe("tcp", *socksAddr); err != nil {
		log.Fatalf("[SOCKS5] Error: %v", err)
	}
}
