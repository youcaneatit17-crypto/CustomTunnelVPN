package com.custom.tunnel

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.trilead.ssh2.Connection
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.DataInputStream
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

class TunnelVpnService : VpnService() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var vpnInterface: ParcelFileDescriptor? = null
    private var slowDnsProcess: Process? = null
    private var tun2SocksProcess: Process? = null
    private var sshConnection: Connection? = null
    private var socksServerSocket: ServerSocket? = null

    companion object {
        var isRunning = false
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopTunnel()
            return START_NOT_STICKY
        }

        val dns = intent?.getStringExtra("dns") ?: "8.8.8.8"
        val ns = intent?.getStringExtra("ns") ?: ""
        val pubkey = intent?.getStringExtra("pubkey") ?: ""
        val user = intent?.getStringExtra("user") ?: ""
        val pass = intent?.getStringExtra("pass") ?: ""
        val udpgw = intent?.getStringExtra("udpgw") ?: "7300"

        startForegroundNotification()
        isRunning = true

        serviceScope.launch {
            startTunnelSequence(dns, ns, pubkey, user, pass, udpgw)
        }

        return START_STICKY
    }

    private suspend fun startTunnelSequence(
        dns: String, ns: String, pubkey: String,
        user: String, pass: String, udpgw: String
    ) {
        try {
            val nativeDir = applicationInfo.nativeLibraryDir
            val localSshPort = 2222
            val localSocksPort = 1080

            // 1. Jalankan SlowDNS (libdnstt.so)
            TunnelLogger.log("[ss] SlowDNS: Memulai...", "#64B5F6")
            TunnelLogger.log("[ss] SlowDNS: Menghubungkan ke server ($ns)", "#64B5F6")

            val dnsttCmd = arrayOf(
                "$nativeDir/libdnstt.so",
                "-udp", "$dns:53",
                "-pubkey", pubkey,
                ns,
                "127.0.0.1:$localSshPort"
            )
            slowDnsProcess = ProcessBuilder(*dnsttCmd).redirectErrorStream(true).start()
            monitorProcessOutput(slowDnsProcess!!, "SlowDNS")

            delay(1500)

            // 2. Hubungkan SSH-2 via Terowongan SlowDNS (127.0.0.1:2222)
            TunnelLogger.log("[ss] Menginisialisasi SSH...", "#64B5F6")
            TunnelLogger.log("[ss] Memulai core SSH-2", "#64B5F6")

            val conn = Connection("127.0.0.1", localSshPort)
            conn.connect(null, 15000, 15000)
            val isAuthenticated = conn.authenticateWithPassword(user, pass)
            if (!isAuthenticated) {
                throw IllegalStateException("Autentikasi SSH gagal (Username/Password salah)")
            }
            sshConnection = conn

            // Jalankan Local SOCKS5 Proxy di 127.0.0.1:1080 di atas SSH Stream
            startLocalSocks5Server(conn, localSocksPort)

            // 3. Buat Antarmuka Virtual VPN (tun0)
            val builder = Builder()
                .setSession("Custom Tunnel VPN")
                .setMtu(1500)
                .addAddress("10.0.0.2", 24)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("8.8.8.8")
                .addDisallowedApplication(packageName)

            vpnInterface = builder.establish()
            val tunFd = vpnInterface?.fd ?: throw IllegalStateException("Gagal membuat TUN interface")

            // 4. Jalankan BadVPN tun2socks (libtun2socks.so)
            TunnelLogger.log("Mesin SOCKS aktif: BadVPN (UDPGW: $udpgw)", "#81C784")
            val tunCmd = arrayOf(
                "$nativeDir/libtun2socks.so",
                "--netif-ipaddr", "10.0.0.1",
                "--netif-netmask", "255.255.255.0",
                "--socks-server-addr", "127.0.0.1:$localSocksPort",
                "--tunfd", tunFd.toString(),
                "--tunmtu", "1500",
                "--loglevel", "3",
                "--udpgw-remote-server-addr", "127.0.0.1:$udpgw"
            )
            tun2SocksProcess = ProcessBuilder(*tunCmd).redirectErrorStream(true).start()
            monitorProcessOutput(tun2SocksProcess!!, "BadVPN")

            TunnelLogger.rawHtml("<b><font color='#00E676'>🇮🇩 MANTAP BERHASIL CONECT VPN NYA 🇮🇩</font></b>")
            TunnelLogger.log("DNS lewat terowongan aktif", "#81C784")
            TunnelLogger.log("Profiles: Berhasil terhubung", "#00E676")
            TunnelLogger.log("Selamat berselancar", "#00E676")

            startAutoPing()

        } catch (e: Exception) {
            TunnelLogger.log("Error Koneksi: ${e.message}", "#EF5350")
            stopTunnel()
        }
    }

    private fun startLocalSocks5Server(conn: Connection, port: Int) {
        socksServerSocket = ServerSocket(port, 50, InetAddress.getByName("127.0.0.1"))
        serviceScope.launch {
            while (isRunning && socksServerSocket?.isClosed == false) {
                try {
                    val client = socksServerSocket!!.accept()
                    launch { handleSocks5Client(client, conn) }
                } catch (_: Exception) { }
            }
        }
    }

    private suspend fun handleSocks5Client(client: Socket, conn: Connection) = withContext(Dispatchers.IO) {
        try {
            val input = DataInputStream(client.getInputStream())
            val output = client.getOutputStream()

            // SOCKS5 Handshake
            val ver = input.readByte().toInt()
            if (ver != 5) { client.close(); return@withContext }
            val nMethods = input.readUnsignedByte()
            val methods = ByteArray(nMethods)
            input.readFully(methods)
            output.write(byteArrayOf(0x05, 0x00)) // No authentication
            output.flush()

            // SOCKS5 Request
            input.readByte() // ver
            val cmd = input.readByte().toInt()
            input.readByte() // rsv
            val atyp = input.readByte().toInt()

            val destHost: String = when (atyp) {
                1 -> { // IPv4
                    val addr = ByteArray(4)
                    input.readFully(addr)
                    InetAddress.getByAddress(addr).hostAddress ?: ""
                }
                3 -> { // Domain
                    val len = input.readUnsignedByte()
                    val domain = ByteArray(len)
                    input.readFully(domain)
                    String(domain)
                }
                4 -> { // IPv6
                    val addr = ByteArray(16)
                    input.readFully(addr)
                    InetAddress.getByAddress(addr).hostAddress ?: ""
                }
                else -> { client.close(); return@withContext }
            }
            val destPort = input.readUnsignedShort()

            if (cmd != 1) { // Only TCP CONNECT
                output.write(byteArrayOf(0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                client.close()
                return@withContext
            }

            // Buka SSH Direct-TCPIP Stream
            val stream = conn.openStream(destHost, destPort)
            output.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            output.flush()

            val job1 = launch {
                try { input.copyTo(stream.stdin) } catch (_: Exception) {}
            }
            val job2 = launch {
                try { stream.stdout.copyTo(output) } catch (_: Exception) {}
            }
            joinAll(job1, job2)
            stream.close()
            client.close()
        } catch (_: Exception) {
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun monitorProcessOutput(process: Process, tag: String) {
        serviceScope.launch {
            try {
                BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        TunnelLogger.log("[$tag] $line", "#9E9E9E")
                    }
                }
            } catch (_: Exception) { }
        }
    }

    private fun startAutoPing() {
        serviceScope.launch {
            while (isRunning) {
                delay(4000)
                try {
                    val start = System.currentTimeMillis()
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress("1.1.1.1", 80), 5000)
                    }
                    val ping = System.currentTimeMillis() - start
                    TunnelLogger.log("Auto Ping: $ping ms", "#81C784")
                } catch (_: Exception) {
                    TunnelLogger.log("Auto Ping: Timeout", "#FFB74D")
                }
            }
        }
    }

    private fun stopTunnel() {
        isRunning = false
        try { socksServerSocket?.close() } catch (_: Exception) {}
        try { sshConnection?.close() } catch (_: Exception) {}
        try { tun2SocksProcess?.destroy() } catch (_: Exception) {}
        try { slowDnsProcess?.destroy() } catch (_: Exception) {}
        try { vpnInterface?.close() } catch (_: Exception) {}
        TunnelLogger.log("Koneksi VPN diputuskan.", "#EF5350")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startForegroundNotification() {
        val channelId = "custom_tunnel_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "Custom Tunnel Service", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Custom Tunnel Aktif")
            .setContentText("SlowDNS + SSH + BadVPN berjalan")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .build()
        startForeground(1, notification)
    }

    override fun onDestroy() {
        stopTunnel()
        serviceScope.cancel()
        super.onDestroy()
    }
}
