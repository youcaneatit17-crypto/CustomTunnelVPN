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
        val profileName: String,
        val method: String,
        val dnsType: String,
        val dns: String,
        val ns: String,
        val pubkey: String,
        val sshHostPort: String,
        val user: String,
        val pass: String,
        val sessions: Int,
        val udpgwPort: String
    )

    companion object {
        @Volatile
        var isRunning = false
        const val STATE_DISCONNECTED = 0
        const val STATE_CONNECTING = 1
        const val STATE_CONNECTED = 2
        @Volatile var connectionState = STATE_DISCONNECTED
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

        val method = intent?.getStringExtra("method") ?: "SlowDNS"
        val config = TunnelConfig(
            profileName = intent?.getStringExtra("profileName") ?: "Default",
            method = method,
            dnsType = intent?.getStringExtra("dnsType") ?: "NULL",
            dns = intent?.getStringExtra("dns") ?: "1.1.1.1",
            ns = if (method == "SlowDNS") (intent?.getStringExtra("ns") ?: "") else "",
            pubkey = if (method == "SlowDNS") (intent?.getStringExtra("pubkey") ?: "") else "",
            sshHostPort = intent?.getStringExtra("sshHostPort") ?: "127.0.0.1:22",
            user = intent?.getStringExtra("user") ?: "",
            pass = intent?.getStringExtra("pass") ?: "",
            sessions = intent?.getIntExtra("sessions", 1) ?: 1,
            udpgwPort = intent?.getStringExtra("udpgw") ?: "7300"
        )
        lastConfig = config

        acquireWakeLock()
        startForegroundNotification("Menghubungkan ${config.profileName}...")
        shouldKeepRunning = true
        isRunning = true
        connectionState = STATE_CONNECTING

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
                    TunnelLogger.log("Menyambung ulang (#$attempt) dalam ${backoffMs / 1000}s...", "#FFB74D")
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

            TunnelLogger.log("Profil: ${config.profileName} | Metode: ${config.method} (${config.sessions} sesi)", "#64B5F6")

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
                            val msg = line ?: continue
                            if (msg == "READY_CONNECTED") {
                                connectedDeferred.complete(true)
                            } else {
                                TunnelLogger.log(msg, "#90CAF9")
                            }
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    if (!connectedDeferred.isCompleted) {
                        connectedDeferred.complete(false)
                    }
                    if (shouldKeepRunning) {
                        TunnelLogger.log("Proses core terhenti (koneksi terputus).", "#EF5350")
                        connectionState = STATE_CONNECTING
                        sessionBroken.complete(Unit)
                    }
                }
            }

            val isConnected = withTimeoutOrNull(45000) { connectedDeferred.await() } ?: false
            if (!isConnected) {
                throw IllegalStateException("Handshake SSH/${config.method} gagal atau timeout")
            }

            val pfd = vpnInterface ?: Builder().apply {
                setSession("Custom Tunnel (${config.profileName})")
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
                        TunnelLogger.log("Split Tunnel: mengecualikan $count aplikasi.", "#B0BEC5")
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
                            TunnelLogger.log("Split Tunnel: daftar kosong, seluruh aplikasi dilewatkan VPN.", "#FFB74D")
                        } else {
                            TunnelLogger.log("Split Tunnel: hanya $count aplikasi terpilih lewat VPN.", "#B0BEC5")
                        }
                    }
                    else -> {
                        try { addDisallowedApplication(packageName) } catch (_: Exception) {}
                    }
                }
            }.establish()
                ?.also {
                    vpnInterface = it
                    connectionState = STATE_CONNECTED
                }
                ?: throw IllegalStateException("Gagal membuat antarmuka TUN Android")

            LocalSocket().use { clientSocket ->
                clientSocket.connect(LocalSocketAddress(tunSockName, LocalSocketAddress.Namespace.ABSTRACT))
                clientSocket.setFileDescriptorsForSend(arrayOf(pfd.fileDescriptor))
                clientSocket.outputStream.write(byteArrayOf(1))
                clientSocket.outputStream.flush()
            }

            wasConnected = true
            updateNotification("Terhubung • ${config.profileName}")
            TunnelLogger.log("TUN aktif (10.0.0.2/24, MTU 1400) -> SOCKS5 127.0.0.1:$localSocksPort", "#4ADE80")
            TunnelLogger.log("Relay UDPGW aktif -> 127.0.0.1:${config.udpgwPort.ifEmpty { "7300" }}", "#4ADE80")

            pingJob = launch {
                val socksProxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", localSocksPort))
                var failCount = 0

                while (isActive && shouldKeepRunning) {
                    delay(15000)
                    try {
                        val start = System.currentTimeMillis()
                        Socket(socksProxy).use { socket ->
                            socket.connect(InetSocketAddress.createUnresolved("1.1.1.1", 80), 6000)
                        }
                        val ping = System.currentTimeMillis() - start
                        if (failCount > 0) {
                            TunnelLogger.log("Jalur pulih ($ping ms)", "#4ADE80")
                        } else {
                            TunnelLogger.log("Ping TCP (1.1.1.1:80): $ping ms", "#81C784")
                        }
                        failCount = 0
                    } catch (_: Exception) {
                        failCount++
                        TunnelLogger.log("Ping timeout ($failCount/5)", "#FFB74D")

                        if (failCount == 3) {
                            TunnelLogger.log("Menyegarkan sesi SSH...", "#64B5F6")
                            triggerSeamlessRefresh()
                        } else if (failCount >= 5) {
                            TunnelLogger.log("Koneksi tidak merespons (5x timeout), menyambung ulang...", "#EF5350")
                            connectionState = STATE_CONNECTING
                            sessionBroken.complete(Unit)
                            break
                        }
                    }
                }
            }

            sessionBroken.await()

        } catch (e: CancellationException) {
        } catch (e: Exception) {
            if (shouldKeepRunning) {
                TunnelLogger.log("Error: ${e.message}", "#EF5350")
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
                TunnelLogger.log("Gagal refresh sesi: ${e.message}", "#EF5350")
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
                            TunnelLogger.log("Jaringan berubah, menyegarkan sesi...", "#64B5F6")
                            triggerSeamlessRefresh()
                        }
                    }
                }

                override fun onLost(network: Network) {
                    if (network.networkHandle == activeNetworkHandle) activeNetworkHandle = -1L
                    if (shouldKeepRunning) {
                        TunnelLogger.log("Jaringan utama terputus, menunggu koneksi...", "#FFB74D")
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
        connectionState = STATE_DISCONNECTED

        unregisterNetworkWatcher()
        tunnelJob?.cancel()
        cleanupAllResources()
        releaseWakeLock()

        if (userInitiated) {
            TunnelLogger.log("Koneksi VPN dihentikan.", "#EF5350")
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onRevoke() {
        TunnelLogger.log("Izin VPN dicabut sistem.", "#EF5350")
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
            .setContentTitle("Custom Tunnel")
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
                .setContentTitle("Custom Tunnel")
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
