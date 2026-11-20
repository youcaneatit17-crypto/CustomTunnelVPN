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
import java.io.File
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.TimeUnit

class TunnelVpnService : VpnService() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var tunnelJob: Job? = null
    private var networkDebounceJob: Job? = null
    private var vpnInterface: ParcelFileDescriptor? = null
    private var coreProcess: Process? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var ctrlSockName: String? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var lastConfig: TunnelConfig? = null

    @Volatile
    private var activeNetworkHandle: Long = -1L

    private data class TunnelConfig(
        val profileName: String,
        val method: String,
        val payload: String,
        val proxy: String,
        val sni: String,
        val dnsType: String,
        val dns: String,
        val ns: String,
        val pubkey: String,
        val sshHostPort: String,
        val user: String,
        val pass: String,
        val sessions: Int,
        val udpgwPort: String,
        val protocol: String = "SSH",
        val v2rayLink: String = "",
        val v2rayBug: String = "",
        val v2rayHost: String = ""
    )

    companion object {
        @Volatile var isRunning = false
        const val STATE_DISCONNECTED = 0
        const val STATE_CONNECTING = 1
        const val STATE_CONNECTED = 2
        @Volatile var connectionState = STATE_DISCONNECTED
        @Volatile var shouldKeepRunning = false

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
                triggerRestartNow("Memulai ulang koneksi...")
                return START_STICKY
            }
        }

        if (shouldKeepRunning && tunnelJob?.isActive == true) {
            return START_STICKY
        }

        val appPrefs = getSharedPreferences("app_settings", MODE_PRIVATE)
        val defaultUdpgw = appPrefs.getString("default_udpgw", "7300") ?: "7300"

        val method = intent?.getStringExtra("method") ?: "SlowDNS"
        val useSlowDns = method.contains("SlowDNS", ignoreCase = true)
        val usePayload = method.contains("Payload", ignoreCase = true)
        val useTls = method.contains("TLS", ignoreCase = true)
        val config = TunnelConfig(
            profileName = intent?.getStringExtra("profileName") ?: "Default",
            method = method,
            payload = if (usePayload) (intent?.getStringExtra("payload") ?: "") else "",
            proxy = if (usePayload) (intent?.getStringExtra("proxy") ?: "") else "",
            sni = if (useTls) (intent?.getStringExtra("sni") ?: "") else "",
            dnsType = intent?.getStringExtra("dnsType") ?: "NULL",
            dns = intent?.getStringExtra("dns") ?: "1.1.1.1",
            ns = if (useSlowDns) (intent?.getStringExtra("ns") ?: "") else "",
            pubkey = if (useSlowDns) (intent?.getStringExtra("pubkey") ?: "") else "",
            sshHostPort = intent?.getStringExtra("sshHostPort") ?: "127.0.0.1:22",
            user = intent?.getStringExtra("user") ?: "",
            pass = intent?.getStringExtra("pass") ?: "",
            sessions = intent?.getIntExtra("sessions", 1) ?: 1,
            udpgwPort = (intent?.getStringExtra("udpgw") ?: "").ifEmpty { defaultUdpgw },
            protocol = intent?.getStringExtra("protocol") ?: "SSH",
            v2rayLink = intent?.getStringExtra("v2rayLink") ?: "",
            v2rayBug = intent?.getStringExtra("v2rayBug") ?: "",
            v2rayHost = intent?.getStringExtra("v2rayHost") ?: ""
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

    private fun triggerRestartNow(reason: String) {
        if (!shouldKeepRunning) return
        TunnelLogger.log(reason, "#64B5F6")
        connectionState = STATE_CONNECTING
        tunnelJob?.cancel()
        cleanupAllResources()
        lastConfig?.let { startSupervisorLoop(it) }
    }

    private fun startSupervisorLoop(config: TunnelConfig) {
        tunnelJob?.cancel()
        tunnelJob = serviceScope.launch {
            var attempt = 0
            while (shouldKeepRunning && isActive) {
                attempt++
                val autoReconnect = getSharedPreferences("app_settings", MODE_PRIVATE)
                    .getBoolean("auto_reconnect", true)

                if (attempt > 1) {
                    if (!autoReconnect) {
                        TunnelLogger.log("Auto-reconnect nonaktif, VPN dihentikan.", "#EF5350")
                        stopTunnel(userInitiated = false)
                        break
                    }
                    connectionState = STATE_CONNECTING
                    val backoffMs = if (attempt == 2) 1200L else ((attempt - 1) * 1800L).coerceAtMost(8000L)
                    TunnelLogger.log("Menyambung ulang (#$attempt) dalam ${backoffMs / 1000.0}s...", "#FFB74D")
                    updateNotification("Menyambung ulang (#$attempt)...")
                    delay(backoffMs)
                    if (!shouldKeepRunning) break
                }

                while (shouldKeepRunning && isActive && !isNetworkAvailable()) {
                    updateNotification("Menunggu jaringan...")
                    delay(1500)
                }
                if (!shouldKeepRunning) break

                val sessionCleanExit = runSingleTunnelSession(config)
                cleanupAllResources()

                if (!shouldKeepRunning) break

                if (sessionCleanExit) {
                    attempt = 1
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
            val appPrefs = getSharedPreferences("app_settings", MODE_PRIVATE)
            val keepaliveSec = appPrefs.getInt("ssh_keepalive", 15).coerceIn(2, 30)
            val handshakeTimeoutMs = (appPrefs.getInt("connect_timeout", 30).coerceIn(10, 90)) * 1000L
            val vpnMtu = appPrefs.getInt("vpn_mtu", 1400).coerceIn(1200, 1500)
            val dns1 = appPrefs.getString("vpn_dns1", "8.8.8.8")?.trim().orEmpty().ifEmpty { "8.8.8.8" }
            val dns2 = appPrefs.getString("vpn_dns2", "1.1.1.1")?.trim().orEmpty().ifEmpty { "1.1.1.1" }

            val nativeDir = applicationInfo.nativeLibraryDir
            val localSocksPort = 1080
            val now = System.currentTimeMillis()
            val tunSockName = "custom_tunnel_fd_$now"
            val controlSock = "custom_tunnel_ctrl_$now"
            ctrlSockName = controlSock
            val connectedDeferred = CompletableDeferred<Boolean>()

            val isV2Ray = config.protocol.equals("V2Ray", ignoreCase = true)
            if (isV2Ray) {
                TunnelLogger.log("Profil: ${config.profileName} | Protokol: V2Ray (${config.method})", "#64B5F6")
            } else {
                TunnelLogger.log("Profil: ${config.profileName} | Metode: ${config.method} (${config.sessions} sesi)", "#64B5F6")
            }

            val coreCmd = if (isV2Ray) {
                val parsed = V2RayConfigBuilder.build(config.v2rayLink, config.v2rayBug, config.v2rayHost, localSocksPort)
                val cfgFile = File(cacheDir, "xray_active.json")
                cfgFile.writeText(parsed.fullConfigJson)
                arrayOf("$nativeDir/libsshcore.so", "-xraybin", "$nativeDir/libxray.so", "-xraycfg", cfgFile.absolutePath, "-tunbin", "$nativeDir/libtun2socks.so", "-tunsock", tunSockName, "-ctrlsock", controlSock, "-socks", "127.0.0.1:$localSocksPort")
            } else arrayOf(
                "$nativeDir/libsshcore.so",
                "-dnstt", "$nativeDir/libdnstt.so",
                "-tunbin", "$nativeDir/libtun2socks.so",
                "-tunsock", tunSockName,
                "-ctrlsock", controlSock,
                "-dns", config.dns,
                "-type", config.dnsType,
                "-ns", config.ns,
                "-pubkey", config.pubkey,
                "-payload", config.payload,
                "-proxy", config.proxy,
                "-sni", config.sni,
                "-ssh", config.sshHostPort,
                "-user", config.user,
                "-pass", config.pass,
                "-sessions", config.sessions.toString(),
                "-keepalive", keepaliveSec.toString(),
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
                        TunnelLogger.log("Sesi core terhenti.", "#EF5350")
                        connectionState = STATE_CONNECTING
                        sessionBroken.complete(Unit)
                    }
                }
            }

            val isConnected = withTimeoutOrNull(handshakeTimeoutMs) { connectedDeferred.await() } ?: false
            if (!isConnected) {
                throw IllegalStateException("Handshake SSH/${config.method} gagal atau timeout")
            }

            val pfd = vpnInterface ?: Builder().apply {
                setSession("Custom Tunnel (${config.profileName})")
                setMtu(vpnMtu)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) { setMetered(false) }
                addAddress("10.0.0.2", 24)
                addRoute("0.0.0.0", 0)
                addDnsServer(dns1)
                if (dns2 != dns1) addDnsServer(dns2)

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
                            TunnelLogger.log("Split Tunnel: daftar kosong, seluruh aplikasi lewat VPN.", "#FFB74D")
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
            TunnelLogger.log("TUN aktif (10.0.0.2/24, MTU $vpnMtu) -> SOCKS5 127.0.0.1:$localSocksPort", "#4ADE80")
            TunnelLogger.log("Relay UDPGW aktif -> 127.0.0.1:${config.udpgwPort.ifEmpty { "7300" }}", "#4ADE80")

            pingJob = launch {
                val socksProxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", localSocksPort))
                var failCount = 0

                while (isActive && shouldKeepRunning) {
                    val prefs = getSharedPreferences("app_settings", MODE_PRIVATE)
                    val pingMode = prefs.getString("ping_mode", "off") ?: "off"
                    val intervalSec = prefs.getInt("ping_interval", 5).coerceIn(1, 60)
                    val timeoutSec = prefs.getInt("ping_timeout", 8).coerceIn(5, 30)
                    val failThreshold = prefs.getInt("ping_threshold", 4).coerceIn(3, 10)
                    val rawTarget = prefs.getString("ping_target", "1.1.1.1:80") ?: "1.1.1.1:80"

                    delay(intervalSec * 1000L)
                    if (pingMode == "off") {
                        failCount = 0
                        continue
                    }

                    val (targetHost, targetPort) = parsePingTarget(rawTarget)

                    try {
                        val start = System.currentTimeMillis()
                        Socket(socksProxy).use { socket ->
                            socket.connect(InetSocketAddress.createUnresolved(targetHost, targetPort), timeoutSec * 1000)
                        }
                        val ping = System.currentTimeMillis() - start
                        if (failCount > 0) {
                            TunnelLogger.log("Auto Ping pulih ($ping ms)", "#4ADE80")
                        } else {
                            TunnelLogger.log("Auto Ping ($targetHost:$targetPort): $ping ms", "#81C784")
                        }
                        failCount = 0
                    } catch (_: Exception) {
                        failCount++
                        if (pingMode == "keepalive") {
                            TunnelLogger.log("Auto Ping timeout ($targetHost:$targetPort) ($failCount/3)", "#FFB74D")
                            if (failCount >= 5) {
                                failCount = 0
                                TunnelLogger.log("Jalur tidak merespons, menyegarkan sesi SSH...", "#64B5F6")
                                try { ctrlSockName?.let { s -> LocalSocket().use { ls -> ls.connect(LocalSocketAddress(s, LocalSocketAddress.Namespace.ABSTRACT)); ls.outputStream.write("REFRESH\n".toByteArray()); ls.outputStream.flush() } } } catch (_: Exception) {}
                            }
                        } else {
                            TunnelLogger.log("Auto Ping gagal ($failCount/$failThreshold)", "#FFB74D")
                            if (failCount >= failThreshold) {
                                TunnelLogger.log("Ambang kegagalan Auto Ping tercapai, mereset koneksi...", "#EF5350")
                                connectionState = STATE_CONNECTING
                                sessionBroken.complete(Unit)
                                break
                            }
                        }
                    }
                }
            }

            sessionBroken.await()

        } catch (_: CancellationException) {
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

    private fun parsePingTarget(raw: String): Pair<String, Int> {
        val cleaned = raw.trim()
            .removePrefix("https://")
            .removePrefix("http://")
            .substringBefore("/")
        return if (cleaned.contains(":")) {
            val host = cleaned.substringBefore(":").ifEmpty { "1.1.1.1" }
            val port = cleaned.substringAfter(":").toIntOrNull() ?: 80
            Pair(host, port)
        } else {
            val defaultPort = if (raw.trim().startsWith("https://")) 443 else 80
            Pair(cleaned.ifEmpty { "1.1.1.1" }, defaultPort)
        }
    }

    private fun isNetworkAvailable(): Boolean {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        } catch (_: Exception) {
            true
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
                        delay(1200)
                        if (shouldKeepRunning) {
                            triggerRestartNow("Jaringan berubah, memulai ulang koneksi...")
                        }
                    }
                }

                override fun onLost(network: Network) {
                    if (network.networkHandle == activeNetworkHandle) activeNetworkHandle = -1L
                    if (shouldKeepRunning) {
                        triggerRestartNow("Jaringan terputus, memulai ulang koneksi...")
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
