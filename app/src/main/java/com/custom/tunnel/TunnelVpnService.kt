package com.custom.tunnel

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
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
import java.util.concurrent.atomic.AtomicBoolean

class TunnelVpnService : VpnService() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var tunnelJob: Job? = null
    private var networkDebounceJob: Job? = null
    private var vpnInterface: ParcelFileDescriptor? = null
    private var coreProcess: Process? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var ctrlSockName: String? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val isReconnecting = AtomicBoolean(false)
    private var lastConfig: TunnelConfig? = null

    @Volatile
    private var activeNetworkHandle: Long = -1L

    private data class TunnelConfig(
        val dnsType: String,
        val dns: String,
        val ns: String,
        val pubkey: String,
        val sshHostPort: String,
        val user: String,
        val pass: String,
        val sessions: Int
    )

    companion object {
        @Volatile
        var isRunning = false
        @Volatile
        var shouldKeepRunning = false

        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_REFRESH = "ACTION_REFRESH"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopTunnel(userInitiated = true)
                return START_NOT_STICKY
            }
            ACTION_REFRESH -> {
                triggerSeamlessRefresh()
                return START_STICKY
            }
        }

        if (shouldKeepRunning && tunnelJob?.isActive == true) {
            return START_STICKY
        }

        val config = TunnelConfig(
            dnsType = intent?.getStringExtra("dnsType") ?: "NULL",
            dns = intent?.getStringExtra("dns") ?: "1.1.1.1",
            ns = intent?.getStringExtra("ns") ?: "",
            pubkey = intent?.getStringExtra("pubkey") ?: "",
            sshHostPort = intent?.getStringExtra("sshHostPort") ?: "127.0.0.1:22",
            user = intent?.getStringExtra("user") ?: "",
            pass = intent?.getStringExtra("pass") ?: "",
            sessions = intent?.getIntExtra("sessions", 2) ?: 2
        )
        lastConfig = config

        acquireWakeLock()
        startForegroundNotification("Menghubungkan terowongan...")
        shouldKeepRunning = true
        isRunning = true

        registerNetworkWatcher()
        startSupervisorLoop(config)

        return START_STICKY
    }

    private fun startSupervisorLoop(config: TunnelConfig) {
        tunnelJob?.cancel()
        tunnelJob = serviceScope.launch {
            var attempt = 0
            while (shouldKeepRunning && isActive) {
                attempt++
                if (attempt > 1) {
                    val backoffMs = (attempt * 2000L).coerceAtMost(12000L)
                    TunnelLogger.log("WARN Coba lagi #$attempt dalam ${backoffMs / 1000}s (TUN tetap aktif; internet dijeda)...", "#FFB74D")
                    updateNotification("Menyambung ulang (#$attempt)...")
                    delay(backoffMs)
                    if (!shouldKeepRunning) break
                }

                val sessionCleanExit = runSingleTunnelSession(config)
                cleanupCoreProcessOnly()

                if (!shouldKeepRunning) break

                if (sessionCleanExit) {
                    attempt = 0
                }
            }
        }
    }

    private suspend fun runSingleTunnelSession(config: TunnelConfig): Boolean = coroutineScope {
        var pingJob: Job? = null
        var logJob: Job? = null
        val sessionBroken = CompletableDeferred<Unit>()
        var wasConnected = false

        try {
            val nativeDir = applicationInfo.nativeLibraryDir
            val localSocksPort = 1080
            val now = System.currentTimeMillis()
            val tunSockName = "custom_tunnel_fd_$now"
            val controlSock = "custom_tunnel_ctrl_$now"
            ctrlSockName = controlSock
            val connectedDeferred = CompletableDeferred<Boolean>()

            TunnelLogger.log("[ss] Memulai Go SSH + SlowDNS (${config.sessions} Sesi)...", "#64B5F6")

            val coreCmd = arrayOf(
                "$nativeDir/libsshcore.so",
                "-dnstt", "$nativeDir/libdnstt.so",
                "-tunbin", "$nativeDir/libtun2socks.so",
                "-tunsock", tunSockName,
                "-ctrlsock", controlSock,
                "-dns", config.dns,
                "-type", config.dnsType,
                "-ns", config.ns,
                "-pubkey", config.pubkey,
                "-ssh", config.sshHostPort,
                "-user", config.user,
                "-pass", config.pass,
                "-sessions", config.sessions.toString(),
                "-socks", "127.0.0.1:$localSocksPort"
            )
            val proc = ProcessBuilder(*coreCmd).redirectErrorStream(true).start()
            coreProcess = proc

            logJob = launch {
                try {
                    BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
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
                    if (shouldKeepRunning) {
                        TunnelLogger.log("Core terputus dari server (DC terdeteksi).", "#EF5350")
                        sessionBroken.complete(Unit)
                    }
                }
            }

            val isConnected = withTimeoutOrNull(45000) { connectedDeferred.await() } ?: false
            if (!isConnected) {
                throw IllegalStateException("Gagal membuat terowongan SSH/SlowDNS (Timeout atau Auth ditolak)")
            }

            val pfd = vpnInterface ?: Builder().apply {
                setSession("Custom Tunnel VPN")
                setMtu(1400)
                addAddress("10.0.0.2", 24)
                addRoute("0.0.0.0", 0)
                addDnsServer("8.8.8.8")
                addDnsServer("1.1.1.1")

                val stPrefs = getSharedPreferences("split_tunnel", MODE_PRIVATE)
                val stMode = stPrefs.getString("mode", "all") ?: "all"
                val stPkgs = stPrefs.getStringSet("packages", emptySet()) ?: emptySet()

                when (stMode) {
                    "exclude" -> {
                        try { addDisallowedApplication(packageName) } catch (_: Exception) {}
                        var count = 0
                        for (pkg in stPkgs) {
                            if (pkg != packageName) {
                                try {
                                    addDisallowedApplication(pkg)
                                    count++
                                } catch (_: Exception) {}
                            }
                        }
                        TunnelLogger.log("VPN per aplikasi: $count aplikasi dikecualikan.", "#B0BEC5")
                    }
                    "include" -> {
                        var count = 0
                        for (pkg in stPkgs) {
                            if (pkg != packageName) {
                                try {
                                    addAllowedApplication(pkg)
                                    count++
                                } catch (_: Exception) {}
                            }
                        }
                        if (count == 0) {
                            try { addDisallowedApplication(packageName) } catch (_: Exception) {}
                            TunnelLogger.log("VPN per aplikasi: Mode Hanya Terpilih kosong, fallback semua aplikasi.", "#FFB74D")
                        } else {
                            TunnelLogger.log("VPN per aplikasi: Hanya $count aplikasi terpilih lewat VPN.", "#B0BEC5")
                        }
                    }
                    else -> {
                        try { addDisallowedApplication(packageName) } catch (_: Exception) {}
                    }
                }
            }.establish()
                ?.also { vpnInterface = it }
                ?: throw IllegalStateException("Gagal membuat TUN interface")

            LocalSocket().use { clientSocket ->
                clientSocket.connect(LocalSocketAddress(tunSockName, LocalSocketAddress.Namespace.ABSTRACT))
                clientSocket.setFileDescriptorsForSend(arrayOf(pfd.fileDescriptor))
                clientSocket.outputStream.write(byteArrayOf(1))
                clientSocket.outputStream.flush()
            }

            wasConnected = true
            updateNotification("Go SSH + SlowDNS Terhubung")
            TunnelLogger.rawHtml("<b><font color='#00E676'>🇮🇩 MANTAP BERHASIL CONECT VPN NYA 🇮🇩</font></b>")
            TunnelLogger.log("Auto-Reconnect & Watchdog aktif", "#81C784")
            TunnelLogger.log("Profiles: Berhasil terhubung", "#00E676")

            // Watchdog Auto Ping + Auto Reconnect Trigger
            pingJob = launch {
                val socksProxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", localSocksPort))
                var failCount = 0

                while (isActive && shouldKeepRunning) {
                    delay(12000)
                    try {
                        val start = System.currentTimeMillis()
                        Socket(socksProxy).use { socket ->
                            socket.connect(InetSocketAddress.createUnresolved("1.1.1.1", 80), 6000)
                        }
                        val ping = System.currentTimeMillis() - start
                        if (failCount > 0) {
                            TunnelLogger.log("Jalur pulih kembali ($ping ms)", "#81C784")
                        } else {
                            TunnelLogger.log("Auto Ping (Tunnel): $ping ms", "#81C784")
                        }
                        failCount = 0
                    } catch (_: Exception) {
                        failCount++
                        TunnelLogger.log("Auto Ping gagal ($failCount/5) - Jalur macet/DC", "#FFB74D")

                        if (failCount == 3) {
                            TunnelLogger.log("Mencoba Soft-Reconnect (Refresh sesi)...", "#64B5F6")
                            triggerSeamlessRefresh()
                        } else if (failCount >= 5) {
                            TunnelLogger.log("Koneksi membeku (5x timeout), memulai Hard-Reconnect...", "#EF5350")
                            sessionBroken.complete(Unit)
                            break
                        }
                    }
                }
            }

            // Tunggu sampai sesi rusak/DC atau coroutine dibatalkan
            sessionBroken.await()

        } catch (e: CancellationException) {
            // Dibatalkan oleh user stop
        } catch (e: Exception) {
            if (shouldKeepRunning) {
                TunnelLogger.log("Error Koneksi: ${e.message}", "#EF5350")
            }
        } finally {
            pingJob?.cancel()
            logJob?.cancel()
        }

        wasConnected
    }

    private fun triggerSeamlessRefresh() {
        val sock = ctrlSockName ?: return
        if (!isReconnecting.compareAndSet(false, true)) return
        serviceScope.launch {
            try {
                LocalSocket().use { client ->
                    client.connect(LocalSocketAddress(sock, LocalSocketAddress.Namespace.ABSTRACT))
                    client.outputStream.write("REFRESH\n".toByteArray())
                    client.outputStream.flush()
                }
            } catch (e: Exception) {
                TunnelLogger.log("Gagal mengirim sinyal refresh: ${e.message}", "#EF5350")
            } finally {
                delay(1500)
                isReconnecting.set(false)
            }
        }
    }

    private fun registerNetworkWatcher() {
        try {
            unregisterNetworkWatcher()
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            activeNetworkHandle = cm.activeNetwork?.networkHandle ?: -1L
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()

            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val newHandle = network.networkHandle
                    if (activeNetworkHandle == -1L) { activeNetworkHandle = newHandle; return }
                    if (newHandle == activeNetworkHandle) return
                    activeNetworkHandle = newHandle
                    networkDebounceJob?.cancel()
                    networkDebounceJob = serviceScope.launch {
                        delay(1800)
                        if (shouldKeepRunning && ctrlSockName != null) {
                            TunnelLogger.log("WARN Jaringan berubah. Menyambung ulang sesi (TUN tetap aktif)...", "#64B5F6")
                            triggerSeamlessRefresh()
                        }
                    }
                }

                override fun onLost(network: Network) {
                    if (network.networkHandle == activeNetworkHandle) activeNetworkHandle = -1L
                    if (shouldKeepRunning) {
                        TunnelLogger.log("WARN Jaringan utama terputus; VPN tetap aktif, internet dijeda...", "#FFB74D")
                    }
                }
            }
            cm.registerNetworkCallback(request, callback)
            networkCallback = callback
        } catch (_: Exception) {}
    }

    private fun unregisterNetworkWatcher() {
        networkDebounceJob?.cancel()
        networkDebounceJob = null
        try {
            networkCallback?.let {
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                cm.unregisterNetworkCallback(it)
            }
        } catch (_: Exception) {}
        networkCallback = null
    }

    private fun cleanupCoreProcessOnly() {
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

    }

    private fun cleanupAllResources() {
        cleanupCoreProcessOnly()
        try { vpnInterface?.close() } catch (_: Exception) {}
        vpnInterface = null
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
    private fun stopTunnel(userInitiated: Boolean = true) {
        if (!shouldKeepRunning && !isRunning) return
        shouldKeepRunning = false
        isRunning = false

        unregisterNetworkWatcher()
        tunnelJob?.cancel()
        cleanupAllResources()
        releaseWakeLock()

        if (userInitiated) {
            TunnelLogger.log("Koneksi VPN diputuskan.", "#EF5350")
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onRevoke() {
        TunnelLogger.log("Izin VPN dicabut oleh sistem.", "#EF5350")
        stopTunnel(userInitiated = true)
        super.onRevoke()
    }

    private fun startForegroundNotification(statusText: String) {
        val channelId = "custom_tunnel_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "Custom Tunnel Service", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Custom Tunnel Aktif")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOnlyAlertOnce(true)
            .build()
        startForeground(1, notification)
    }

    private fun updateNotification(statusText: String) {
        try {
            val channelId = "custom_tunnel_channel"
            val notification = NotificationCompat.Builder(this, channelId)
                .setContentTitle("Custom Tunnel Aktif")
                .setContentText(statusText)
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setOnlyAlertOnce(true)
                .build()
            getSystemService(NotificationManager::class.java).notify(1, notification)
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        stopTunnel(userInitiated = false)
        serviceScope.cancel()
        super.onDestroy()
    }
}
