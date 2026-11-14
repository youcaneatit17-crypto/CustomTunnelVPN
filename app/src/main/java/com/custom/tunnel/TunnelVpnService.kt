package com.custom.tunnel

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket

class TunnelVpnService : VpnService() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var vpnInterface: ParcelFileDescriptor? = null
    private var coreProcess: Process? = null
    private var hevProcess: Process? = null

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

        val dnsType = intent?.getStringExtra("dnsType") ?: "NULL"
        val dns = intent?.getStringExtra("dns") ?: "1.1.1.1"
        val ns = intent?.getStringExtra("ns") ?: ""
        val pubkey = intent?.getStringExtra("pubkey") ?: ""
        val sshHostPort = intent?.getStringExtra("sshHostPort") ?: "127.0.0.1:22"
        val user = intent?.getStringExtra("user") ?: ""
        val pass = intent?.getStringExtra("pass") ?: ""
        val udpgw = intent?.getStringExtra("udpgw") ?: "7300"

        startForegroundNotification()
        isRunning = true

        serviceScope.launch {
            startTunnelSequence(dnsType, dns, ns, pubkey, sshHostPort, user, pass, udpgw)
        }

        return START_STICKY
    }

    private suspend fun startTunnelSequence(
        dnsType: String, dns: String, ns: String, pubkey: String,
        sshHostPort: String, user: String, pass: String, udpgw: String
    ) {
        try {
            val nativeDir = applicationInfo.nativeLibraryDir
            val localSocksPort = 1080
            val connectedDeferred = CompletableDeferred<Boolean>()

            TunnelLogger.log("[ss] Memulai Go SSH + SlowDNS Core...", "#64B5F6")

            val coreCmd = arrayOf(
                "$nativeDir/libsshcore.so",
                "-dnstt", "$nativeDir/libdnstt.so",
                "-dns", dns,
                "-type", dnsType,
                "-ns", ns,
                "-pubkey", pubkey,
                "-ssh", sshHostPort,
                "-user", user,
                "-pass", pass,
                "-socks", "127.0.0.1:$localSocksPort"
            )
            coreProcess = ProcessBuilder(*coreCmd).redirectErrorStream(true).start()

            serviceScope.launch {
                try {
                    BufferedReader(InputStreamReader(coreProcess!!.inputStream)).use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            if (line == "READY_CONNECTED") {
                                connectedDeferred.complete(true)
                            } else {
                                TunnelLogger.log(line!!, "#90CAF9")
                            }
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    if (!connectedDeferred.isCompleted) {
                        connectedDeferred.complete(false)
                    }
                    if (isRunning) stopTunnel()
                }
            }

            val isConnected = withTimeoutOrNull(50000) { connectedDeferred.await() } ?: false
            if (!isConnected) {
                throw IllegalStateException("Gagal membuat terowongan SSH/SlowDNS (Timeout atau Auth ditolak)")
            }

            // Buat Antarmuka Virtual VPN (tun0)
            val builder = Builder()
                .setSession("Custom Tunnel VPN")
                .setMtu(1500)
                .addAddress("10.0.0.2", 24)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("8.8.8.8")
                .addDnsServer("1.1.1.1")
                .addDisallowedApplication(packageName)

            vpnInterface = builder.establish()
            val tunFd = vpnInterface?.fd ?: throw IllegalStateException("Gagal membuat TUN interface")

            // Jalankan libhev-socks5-tunnel.so (engine sama seperti 1FTunnel)
            TunnelLogger.log("Mesin TUN aktif: hev-socks5-tunnel (fd://$tunFd)", "#81C784")

            // Buat konfigurasi minimal untuk hev-socks5-tunnel secara runtime atau argumen
            val hevConfigPath = File(cacheDir, "hev.yml").absolutePath
            File(hevConfigPath).writeText("""
                [tunnel]
                mtu = 1500
                fd = $tunFd

                [socks5]
                port = 1080
                address = 127.0.0.1
                udp = true
            """.trimIndent())

            val hevCmd = arrayOf(
                "$nativeDir/libhev-socks5-tunnel.so",
                hevConfigPath
            )
            hevProcess = ProcessBuilder(*hevCmd).redirectErrorStream(true).start()
            monitorProcessOutput(hevProcess!!, "HEV")

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
        if (!isRunning) return
        isRunning = false
        try { hevProcess?.destroy() } catch (_: Exception) {}
        try { coreProcess?.destroy() } catch (_: Exception) {}
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
            .setContentText("Go SSH + SlowDNS + HEV berjalan")
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
