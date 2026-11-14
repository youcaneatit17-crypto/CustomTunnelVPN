package com.custom.tunnel

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.TimeUnit

class TunnelVpnService : VpnService() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var vpnInterface: ParcelFileDescriptor? = null
    private var coreProcess: Process? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var ctrlSockName: String? = null

    companion object {
        @Volatile
        var isRunning = false
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_REFRESH = "ACTION_REFRESH"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopTunnel()
                return START_NOT_STICKY
            }
            ACTION_REFRESH -> {
                triggerSeamlessRefresh()
                return START_STICKY
            }
        }

        if (isRunning) {
            return START_STICKY
        }

        val dnsType = intent?.getStringExtra("dnsType") ?: "NULL"
        val dns = intent?.getStringExtra("dns") ?: "1.1.1.1"
        val ns = intent?.getStringExtra("ns") ?: ""
        val pubkey = intent?.getStringExtra("pubkey") ?: ""
        val sshHostPort = intent?.getStringExtra("sshHostPort") ?: "127.0.0.1:22"
        val user = intent?.getStringExtra("user") ?: ""
        val pass = intent?.getStringExtra("pass") ?: ""
        val sessions = intent?.getIntExtra("sessions", 2) ?: 2

        acquireWakeLock()
        startForegroundNotification()
        isRunning = true

        serviceScope.launch {
            startTunnelSequence(dnsType, dns, ns, pubkey, sshHostPort, user, pass, sessions)
        }

        return START_STICKY
    }

    private fun triggerSeamlessRefresh() {
        val sock = ctrlSockName ?: return
        serviceScope.launch {
            try {
                LocalSocket().use { client ->
                    client.connect(LocalSocketAddress(sock, LocalSocketAddress.Namespace.ABSTRACT))
                    client.outputStream.write("REFRESH\n".toByteArray())
                    client.outputStream.flush()
                }
            } catch (e: Exception) {
                TunnelLogger.log("Gagal mengirim sinyal refresh: ${e.message}", "#EF5350")
            }
        }
    }

    private suspend fun startTunnelSequence(
        dnsType: String, dns: String, ns: String, pubkey: String,
        sshHostPort: String, user: String, pass: String, sessions: Int
    ) {
        try {
            val nativeDir = applicationInfo.nativeLibraryDir
            val localSocksPort = 1080
            val now = System.currentTimeMillis()
            val tunSockName = "custom_tunnel_fd_$now"
            val controlSock = "custom_tunnel_ctrl_$now"
            ctrlSockName = controlSock
            val connectedDeferred = CompletableDeferred<Boolean>()

            TunnelLogger.log("[ss] Memulai Go SSH + SlowDNS ($sessions Sesi)...", "#64B5F6")

            val coreCmd = arrayOf(
                "$nativeDir/libsshcore.so",
                "-dnstt", "$nativeDir/libdnstt.so",
                "-tunbin", "$nativeDir/libtun2socks.so",
                "-tunsock", tunSockName,
                "-ctrlsock", controlSock,
                "-dns", dns,
                "-type", dnsType,
                "-ns", ns,
                "-pubkey", pubkey,
                "-ssh", sshHostPort,
                "-user", user,
                "-pass", pass,
                "-sessions", sessions.toString(),
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

            val builder = Builder()
                .setSession("Custom Tunnel VPN")
                .setMtu(1400)
                .addAddress("10.0.0.2", 24)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("8.8.8.8")
                .addDnsServer("1.1.1.1")
                .addDisallowedApplication(packageName)

            vpnInterface = builder.establish()
            val pfd = vpnInterface ?: throw IllegalStateException("Gagal membuat TUN interface")

            LocalSocket().use { clientSocket ->
                clientSocket.connect(LocalSocketAddress(tunSockName, LocalSocketAddress.Namespace.ABSTRACT))
                clientSocket.setFileDescriptorsForSend(arrayOf(pfd.fileDescriptor))
                clientSocket.outputStream.write(byteArrayOf(1))
                clientSocket.outputStream.flush()
            }

            TunnelLogger.rawHtml("<b><font color='#00E676'>🇮🇩 MANTAP BERHASIL CONECT VPN NYA 🇮🇩</font></b>")
            TunnelLogger.log("DNS Cache & Auto-Heal aktif", "#81C784")
            TunnelLogger.log("Profiles: Berhasil terhubung", "#00E676")
            TunnelLogger.log("Selamat berselancar", "#00E676")

            startAutoPing(localSocksPort)

        } catch (e: Exception) {
            TunnelLogger.log("Error Koneksi: ${e.message}", "#EF5350")
            stopTunnel()
        }
    }

    private fun startAutoPing(socksPort: Int) {
        serviceScope.launch {
            val socksProxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))
            while (isRunning) {
                delay(15000)
                try {
                    val start = System.currentTimeMillis()
                    Socket(socksProxy).use { socket ->
                        socket.connect(InetSocketAddress.createUnresolved("1.1.1.1", 80), 6000)
                    }
                    val ping = System.currentTimeMillis() - start
                    TunnelLogger.log("Auto Ping (Tunnel): $ping ms", "#81C784")
                } catch (_: Exception) {
                    TunnelLogger.log("Auto Ping (Tunnel): Jalur sibuk / Timeout", "#FFB74D")
                }
            }
        }
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CustomTunnel::VpnWakeLock").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {}
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
            wakeLock = null
        } catch (_: Exception) {}
    }

    @Synchronized
    private fun stopTunnel() {
        if (!isRunning) return
        isRunning = false
        ctrlSockName = null
        try {
            coreProcess?.destroy()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (coreProcess?.waitFor(400, TimeUnit.MILLISECONDS) == false) {
                    coreProcess?.destroyForcibly()
                }
            }
        } catch (_: Exception) {}
        coreProcess = null

        try { vpnInterface?.close() } catch (_: Exception) {}
        vpnInterface = null

        releaseWakeLock()
        TunnelLogger.log("Koneksi VPN diputuskan.", "#EF5350")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onRevoke() {
        TunnelLogger.log("Izin VPN dicabut oleh sistem.", "#EF5350")
        stopTunnel()
        super.onRevoke()
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
            .setContentText("Go SSH + SlowDNS Multi-Session berjalan")
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
