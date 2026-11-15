#!/data/data/com.termux/files/usr/bin/bash
set -e

rm -f .git/index.lock

python3 - << 'PYEOF'
# 1. TunnelLogger.kt -> Auto-clean maksimal 100 baris
with open("app/src/main/java/com/custom/tunnel/TunnelLogger.kt", "w") as f:
    f.write("""package com.custom.tunnel

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object TunnelLogger {
    private const val MAX_LINES = 100
    private val logs = mutableListOf<String>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    var onLogUpdated: (() -> Unit)? = null

    @Synchronized
    fun log(message: String, colorHex: String = "#E0E0E0") {
        val time = timeFormat.format(Date())
        val escaped = message.replace("<", "&lt;").replace(">", "&gt;")
        logs.add("<font color=\x27#6B7280\x27>[$time]</font> <font color=\x27$colorHex\x27>$escaped</font>")
        while (logs.size > MAX_LINES) {
            logs.removeAt(0)
        }
        onLogUpdated?.invoke()
    }

    @Synchronized
    fun rawHtml(html: String) {
        val time = timeFormat.format(Date())
        logs.add("<font color=\x27#6B7280\x27>[$time]</font> $html")
        while (logs.size > MAX_LINES) {
            logs.removeAt(0)
        }
        onLogUpdated?.invoke()
    }

    @Synchronized
    fun clear() {
        logs.clear()
        onLogUpdated?.invoke()
    }

    @Synchronized
    fun getHtmlLogs(): String = logs.joinToString("<br/>")
}
""")

# 2. TunnelVpnService.kt -> Hapus semua log palsu, tampilkan status nyata
with open("app/src/main/java/com/custom/tunnel/TunnelVpnService.kt", "w") as f:
    f.write("""package com.custom.tunnel

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
""")

# 3. activity_main.xml -> UI Utama Simpel Modern + Profil Modular + Editor Collapsible
with open("app/src/main/res/layout/activity_main.xml", "w") as f:
    f.write("""<?xml version="1.0" encoding="utf-8"?>
<androidx.coordinatorlayout.widget.CoordinatorLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="#0D1015"
    android:fitsSystemWindows="true">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:orientation="vertical">

        <!-- TOP BAR UTAMA & PROFIL -->
        <LinearLayout
            android:id="@+id/topBarMain"
            android:layout_width="match_parent"
            android:layout_height="?android:attr/actionBarSize"
            android:background="#141820"
            android:gravity="center_vertical"
            android:orientation="horizontal"
            android:paddingHorizontal="16dp">

            <ImageButton
                android:id="@+id/btnBackFromEditor"
                android:layout_width="36dp"
                android:layout_height="36dp"
                android:layout_marginEnd="10dp"
                android:background="?android:attr/selectableItemBackgroundBorderless"
                android:src="@android:drawable/ic_menu_revert"
                android:visibility="gone"
                app:tint="#E5E7EB" />

            <TextView
                android:id="@+id/tvTopTitle"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:text="Custom Tunnel"
                android:textColor="#FB923C"
                android:textSize="19sp"
                android:textStyle="bold" />

            <TextView
                android:id="@+id/tvHeaderStatus"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:background="@drawable/bg_pill_offline"
                android:fontFamily="monospace"
                android:paddingHorizontal="10dp"
                android:paddingVertical="4dp"
                android:text="OFFLINE"
                android:textColor="#9CA3AF"
                android:textSize="10sp"
                android:textStyle="bold" />

            <TextView
                android:id="@+id/btnAddProfileMenu"
                android:layout_width="40dp"
                android:layout_height="40dp"
                android:background="?android:attr/selectableItemBackgroundBorderless"
                android:gravity="center"
                android:text="+"
                android:textColor="#F3F4F6"
                android:textSize="26sp"
                android:visibility="gone" />
        </LinearLayout>

        <!-- TOP BAR PENGATURAN (VPN PER APLIKASI) -->
        <LinearLayout
            android:id="@+id/topBarSettings"
            android:layout_width="match_parent"
            android:layout_height="?android:attr/actionBarSize"
            android:background="#141820"
            android:gravity="center_vertical"
            android:orientation="horizontal"
            android:paddingHorizontal="12dp"
            android:visibility="gone">

            <ImageButton
                android:id="@+id/btnBackFromSettings"
                android:layout_width="40dp"
                android:layout_height="40dp"
                android:background="?android:attr/selectableItemBackgroundBorderless"
                android:src="@android:drawable/ic_menu_revert"
                app:tint="#E2E8F0" />

            <TextView
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_marginStart="8dp"
                android:layout_weight="1"
                android:text="VPN per aplikasi"
                android:textColor="#F3F4F6"
                android:textSize="18sp"
                android:textStyle="bold" />

            <TextView
                android:id="@+id/btnClearSelectedApps"
                android:layout_width="40dp"
                android:layout_height="40dp"
                android:background="?android:attr/selectableItemBackgroundBorderless"
                android:gravity="center"
                android:rotation="45"
                android:text="+"
                android:textColor="#E2E8F0"
                android:textSize="24sp" />
        </LinearLayout>

        <!-- SUB-TABS BERANDA -->
        <com.google.android.material.tabs.TabLayout
            android:id="@+id/tabLayout"
            android:layout_width="match_parent"
            android:layout_height="44dp"
            android:background="#141820"
            app:tabIndicatorColor="#60A5FA"
            app:tabIndicatorHeight="2.5dp"
            app:tabSelectedTextColor="#60A5FA"
            app:tabTextAppearance="@android:style/TextAppearance.Small"
            app:tabTextColor="#9CA3AF">

            <com.google.android.material.tabs.TabItem
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="Utama" />

            <com.google.android.material.tabs.TabItem
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="Log" />
        </com.google.android.material.tabs.TabLayout>

        <!-- CONTENT AREA -->
        <FrameLayout
            android:layout_width="match_parent"
            android:layout_height="0dp"
            android:layout_weight="1">

            <!-- 1. BERANDA: UTAMA (CLEAN & SIMPEL) -->
            <ScrollView
                android:id="@+id/layoutUtama"
                android:layout_width="match_parent"
                android:layout_height="match_parent"
                android:padding="14dp">

                <LinearLayout
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:orientation="vertical"
                    android:paddingBottom="84dp">

                    <!-- KARTU TRAFIK LIVE -->
                    <LinearLayout
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:layout_marginBottom="14dp"
                        android:background="@drawable/bg_card"
                        android:orientation="vertical"
                        android:padding="16dp">

                        <LinearLayout
                            android:layout_width="match_parent"
                            android:layout_height="wrap_content"
                            android:gravity="center_vertical"
                            android:orientation="horizontal">

                            <TextView
                                android:layout_width="0dp"
                                android:layout_height="wrap_content"
                                android:layout_weight="1"
                                android:text="📈  Trafik"
                                android:textColor="#E5E7EB"
                                android:textSize="14sp"
                                android:textStyle="bold" />

                            <TextView
                                android:id="@+id/tvActiveProtocolBadge"
                                android:layout_width="wrap_content"
                                android:layout_height="wrap_content"
                                android:background="@drawable/bg_input"
                                android:fontFamily="monospace"
                                android:paddingHorizontal="8dp"
                                android:paddingVertical="3dp"
                                android:text="SSH • SlowDNS"
                                android:textColor="#60A5FA"
                                android:textSize="10.5sp" />
                        </LinearLayout>

                        <LinearLayout
                            android:layout_width="match_parent"
                            android:layout_height="wrap_content"
                            android:layout_marginTop="16dp"
                            android:orientation="horizontal">

                            <LinearLayout
                                android:layout_width="0dp"
                                android:layout_height="wrap_content"
                                android:layout_weight="1"
                                android:orientation="vertical">

                                <TextView
                                    android:layout_width="wrap_content"
                                    android:layout_height="wrap_content"
                                    android:text="↓ Unduh"
                                    android:textColor="#FBBF24"
                                    android:textSize="12sp" />

                                <TextView
                                    android:id="@+id/tvStatDown"
                                    android:layout_width="wrap_content"
                                    android:layout_height="wrap_content"
                                    android:layout_marginTop="2dp"
                                    android:fontFamily="monospace"
                                    android:text="0 bps"
                                    android:textColor="#FFFFFF"
                                    android:textSize="18sp"
                                    android:textStyle="bold" />

                                <TextView
                                    android:id="@+id/tvStatDownPeak"
                                    android:layout_width="wrap_content"
                                    android:layout_height="wrap_content"
                                    android:layout_marginTop="2dp"
                                    android:text="Puncak 0 bps"
                                    android:textColor="#9CA3AF"
                                    android:textSize="11sp" />
                            </LinearLayout>

                            <LinearLayout
                                android:layout_width="0dp"
                                android:layout_height="wrap_content"
                                android:layout_weight="1"
                                android:gravity="end"
                                android:orientation="vertical">

                                <TextView
                                    android:layout_width="wrap_content"
                                    android:layout_height="wrap_content"
                                    android:text="↑ Unggah"
                                    android:textColor="#9CA3AF"
                                    android:textSize="12sp" />

                                <TextView
                                    android:id="@+id/tvStatUp"
                                    android:layout_width="wrap_content"
                                    android:layout_height="wrap_content"
                                    android:layout_marginTop="2dp"
                                    android:fontFamily="monospace"
                                    android:text="0 bps"
                                    android:textColor="#FFFFFF"
                                    android:textSize="18sp"
                                    android:textStyle="bold" />

                                <TextView
                                    android:id="@+id/tvStatUpPeak"
                                    android:layout_width="wrap_content"
                                    android:layout_height="wrap_content"
                                    android:layout_marginTop="2dp"
                                    android:text="Puncak 0 bps"
                                    android:textColor="#9CA3AF"
                                    android:textSize="11sp" />
                            </LinearLayout>
                        </LinearLayout>

                        <!-- STATUS PILL TENGAH -->
                        <LinearLayout
                            android:layout_width="match_parent"
                            android:layout_height="wrap_content"
                            android:layout_marginTop="14dp"
                            android:gravity="center"
                            android:orientation="horizontal">

                            <TextView
                                android:id="@+id/tvStatusDotLabel"
                                android:layout_width="wrap_content"
                                android:layout_height="wrap_content"
                                android:background="@drawable/bg_input"
                                android:paddingHorizontal="14dp"
                                android:paddingVertical="5dp"
                                android:text="● Tidak aktif"
                                android:textColor="#9CA3AF"
                                android:textSize="11.5sp" />
                        </LinearLayout>

                        <!-- IP LOKAL / INTERFACE -->
                        <LinearLayout
                            android:layout_width="match_parent"
                            android:layout_height="wrap_content"
                            android:layout_marginTop="10dp"
                            android:gravity="end|center_vertical"
                            android:orientation="horizontal">

                            <TextView
                                android:id="@+id/tvLocalIp"
                                android:layout_width="wrap_content"
                                android:layout_height="wrap_content"
                                android:fontFamily="monospace"
                                android:text="🌐 192.168.42.22"
                                android:textColor="#9CA3AF"
                                android:textSize="11.5sp" />
                        </LinearLayout>
                    </LinearLayout>

                    <!-- KARTU KONEKSI UTAMA (PROFIL AKTIF) -->
                    <LinearLayout
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:layout_marginBottom="14dp"
                        android:background="@drawable/bg_card"
                        android:orientation="vertical"
                        android:padding="16dp">

                        <TextView
                            android:layout_width="wrap_content"
                            android:layout_height="wrap_content"
                            android:text="Koneksi utama"
                            android:textColor="#F3F4F6"
                            android:textSize="14sp"
                            android:textStyle="bold" />

                        <View
                            android:layout_width="match_parent"
                            android:layout_height="1dp"
                            android:layout_marginVertical="10dp"
                            android:background="#232936" />

                        <LinearLayout
                            android:layout_width="match_parent"
                            android:layout_height="wrap_content"
                            android:gravity="center_vertical"
                            android:orientation="horizontal">

                            <LinearLayout
                                android:layout_width="0dp"
                                android:layout_height="wrap_content"
                                android:layout_weight="1"
                                android:orientation="vertical">

                                <TextView
                                    android:id="@+id/tvHomeProfileName"
                                    android:layout_width="wrap_content"
                                    android:layout_height="wrap_content"
                                    android:text="Default"
                                    android:textColor="#E5E7EB"
                                    android:textSize="15sp"
                                    android:textStyle="bold" />

                                <TextView
                                    android:id="@+id/tvHomeProfileSub"
                                    android:layout_width="wrap_content"
                                    android:layout_height="wrap_content"
                                    android:layout_marginTop="2dp"
                                    android:fontFamily="monospace"
                                    android:text="Belum ada server diatur"
                                    android:textColor="#9CA3AF"
                                    android:textSize="11sp" />
                            </LinearLayout>

                            <Button
                                android:id="@+id/btnHomePickProfile"
                                android:layout_width="wrap_content"
                                android:layout_height="38dp"
                                android:layout_marginEnd="6dp"
                                android:backgroundTint="#1E2532"
                                android:text="✓ Profil"
                                android:textAllCaps="false"
                                android:textColor="#E5E7EB"
                                android:textSize="12sp" />

                            <TextView
                                android:id="@+id/btnHomeEditProfile"
                                android:layout_width="wrap_content"
                                android:layout_height="wrap_content"
                                android:background="?android:attr/selectableItemBackground"
                                android:paddingHorizontal="10dp"
                                android:paddingVertical="8dp"
                                android:text="Lihat"
                                android:textColor="#60A5FA"
                                android:textSize="13sp"
                                android:textStyle="bold" />
                        </LinearLayout>

                        <Button
                            android:id="@+id/btnRefresh"
                            android:layout_width="match_parent"
                            android:layout_height="42dp"
                            android:layout_marginTop="12dp"
                            android:backgroundTint="#0284C7"
                            android:text="REFRESH SESI SSH"
                            android:textColor="#FFFFFF"
                            android:textSize="12sp"
                            android:textStyle="bold"
                            android:visibility="gone" />
                    </LinearLayout>

                    <!-- KARTU SPLIT TUNNEL RINGKAS -->
                    <LinearLayout
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:background="@drawable/bg_card"
                        android:gravity="center_vertical"
                        android:orientation="horizontal"
                        android:padding="14dp">

                        <TextView
                            android:id="@+id/tvSplitSummaryHome"
                            android:layout_width="0dp"
                            android:layout_height="wrap_content"
                            android:layout_weight="1"
                            android:text="VPN per aplikasi: Semua aplikasi"
                            android:textColor="#9CA3AF"
                            android:textSize="12.5sp" />

                        <TextView
                            android:id="@+id/btnLihatSplit"
                            android:layout_width="wrap_content"
                            android:layout_height="wrap_content"
                            android:background="?android:attr/selectableItemBackground"
                            android:paddingHorizontal="10dp"
                            android:paddingVertical="6dp"
                            android:text="Atur"
                            android:textColor="#60A5FA"
                            android:textSize="13sp"
                            android:textStyle="bold" />
                    </LinearLayout>
                </LinearLayout>
            </ScrollView>

            <!-- 2. BERANDA: LOG (AUTO-CLEAN 100 BARIS) -->
            <LinearLayout
                android:id="@+id/layoutLog"
                android:layout_width="match_parent"
                android:layout_height="match_parent"
                android:orientation="vertical"
                android:padding="12dp"
                android:visibility="gone">

                <LinearLayout
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:layout_marginBottom="8dp"
                    android:gravity="center_vertical"
                    android:orientation="horizontal">

                    <TextView
                        android:layout_width="0dp"
                        android:layout_height="wrap_content"
                        android:layout_weight="1"
                        android:text="Auto-clean maksimal 100 baris"
                        android:textColor="#6B7280"
                        android:textSize="11sp" />

                    <Button
                        android:id="@+id/btnCleanLog"
                        android:layout_width="wrap_content"
                        android:layout_height="36dp"
                        android:backgroundTint="#1E2532"
                        android:text="BERSIHKAN LOG"
                        android:textColor="#E5E7EB"
                        android:textSize="11sp" />
                </LinearLayout>

                <ScrollView
                    android:id="@+id/scrollLog"
                    android:layout_width="match_parent"
                    android:layout_height="match_parent"
                    android:background="@drawable/bg_card"
                    android:padding="10dp">

                    <TextView
                        android:id="@+id/tvLogs"
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:fontFamily="monospace"
                        android:lineSpacingExtra="3dp"
                        android:textColor="#D1D5DB"
                        android:textIsSelectable="true"
                        android:textSize="11sp" />
                </ScrollView>
            </LinearLayout>

            <!-- 3. PROFIL: DAFTAR PROFIL -->
            <ScrollView
                android:id="@+id/layoutProfil"
                android:layout_width="match_parent"
                android:layout_height="match_parent"
                android:padding="14dp"
                android:visibility="gone">

                <LinearLayout
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:orientation="vertical"
                    android:paddingBottom="84dp">

                    <LinearLayout
                        android:id="@+id/profilesContainer"
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:orientation="vertical" />

                    <!-- EKSPOR / IMPOR JSON -->
                    <LinearLayout
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:layout_marginTop="8dp"
                        android:background="@drawable/bg_card"
                        android:orientation="vertical"
                        android:padding="14dp">

                        <TextView
                            android:layout_width="wrap_content"
                            android:layout_height="wrap_content"
                            android:layout_marginBottom="8dp"
                            android:text="BACKUP / IMPOR CONFIG JSON"
                            android:textColor="#9CA3AF"
                            android:textSize="11sp"
                            android:textStyle="bold" />

                        <EditText
                            android:id="@+id/etConfigJson"
                            android:layout_width="match_parent"
                            android:layout_height="72dp"
                            android:layout_marginBottom="8dp"
                            android:background="@drawable/bg_input"
                            android:fontFamily="monospace"
                            android:gravity="top|start"
                            android:hint="Tempel JSON profil di sini..."
                            android:padding="10dp"
                            android:textColor="#D1D5DB"
                            android:textColorHint="#6B7280"
                            android:textSize="11sp" />

                        <LinearLayout
                            android:layout_width="match_parent"
                            android:layout_height="wrap_content"
                            android:orientation="horizontal">

                            <Button
                                android:id="@+id/btnExportJson"
                                android:layout_width="0dp"
                                android:layout_height="40dp"
                                android:layout_marginEnd="6dp"
                                android:layout_weight="1"
                                android:backgroundTint="#1E2532"
                                android:text="SALIN JSON"
                                android:textColor="#E5E7EB"
                                android:textSize="11sp" />

                            <Button
                                android:id="@+id/btnImportJson"
                                android:layout_width="0dp"
                                android:layout_height="40dp"
                                android:layout_marginStart="6dp"
                                android:layout_weight="1"
                                android:backgroundTint="#059669"
                                android:text="IMPOR PROFIL"
                                android:textColor="#FFFFFF"
                                android:textSize="11sp" />
                        </LinearLayout>
                    </LinearLayout>
                </LinearLayout>
            </ScrollView>

            <!-- 4. PROFIL: EDITOR MODULAR COLLAPSIBLE -->
            <ScrollView
                android:id="@+id/layoutProfileEditor"
                android:layout_width="match_parent"
                android:layout_height="match_parent"
                android:padding="14dp"
                android:visibility="gone">

                <LinearLayout
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:orientation="vertical"
                    android:paddingBottom="88dp">

                    <!-- KARTU NAMA PROFIL -->
                    <LinearLayout
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:layout_marginBottom="12dp"
                        android:background="@drawable/bg_card"
                        android:gravity="center_vertical"
                        android:orientation="horizontal"
                        android:padding="12dp">

                        <TextView
                            android:layout_width="wrap_content"
                            android:layout_height="wrap_content"
                            android:layout_marginEnd="10dp"
                            android:text="📄"
                            android:textSize="16sp" />

                        <EditText
                            android:id="@+id/etEditProfileName"
                            android:layout_width="0dp"
                            android:layout_height="40dp"
                            android:layout_weight="1"
                            android:background="@android:color/transparent"
                            android:hint="Nama Profil (misal: ss)"
                            android:singleLine="true"
                            android:textColor="#FFFFFF"
                            android:textColorHint="#6B7280"
                            android:textSize="15sp"
                            android:textStyle="bold" />
                    </LinearLayout>

                    <!-- KARTU MODE -->
                    <LinearLayout
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:layout_marginBottom="12dp"
                        android:background="@drawable/bg_card"
                        android:orientation="vertical"
                        android:padding="14dp">

                        <LinearLayout
                            android:id="@+id/hdrCardMode"
                            android:layout_width="match_parent"
                            android:layout_height="wrap_content"
                            android:gravity="center_vertical"
                            android:orientation="horizontal">

                            <TextView
                                android:layout_width="0dp"
                                android:layout_height="wrap_content"
                                android:layout_weight="1"
                                android:text="🎛️  Mode"
                                android:textColor="#F3F4F6"
                                android:textSize="14sp"
                                android:textStyle="bold" />

                            <TextView
                                android:id="@+id/arrowCardMode"
                                android:layout_width="wrap_content"
                                android:layout_height="wrap_content"
                                android:text="⌃"
                                android:textColor="#9CA3AF"
                                android:textSize="16sp" />
                        </LinearLayout>

                        <LinearLayout
                            android:id="@+id/bodyCardMode"
                            android:layout_width="match_parent"
                            android:layout_height="wrap_content"
                            android:layout_marginTop="12dp"
                            android:orientation="horizontal">

                            <Button
                                android:id="@+id/btnModeSingle"
                                android:layout_width="wrap_content"
                                android:layout_height="38dp"
                                android:layout_marginEnd="8dp"
                                android:backgroundTint="#2C3545"
                                android:text="Standard (1 Sesi)"
                                android:textAllCaps="false"
                                android:textColor="#FFFFFF"
                                android:textSize="12sp" />

                            <Button
                                android:id="@+id/btnModeMulti"
                                android:layout_width="wrap_content"
                                android:layout_height="38dp"
                                android:backgroundTint="#161B24"
                                android:text="Multi-Sesi (2x)"
                                android:textAllCaps="false"
                                android:textColor="#9CA3AF"
                                android:textSize="12sp" />
                        </LinearLayout>
                    </LinearLayout>

                    <!-- KARTU METODE -->
                    <LinearLayout
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:layout_marginBottom="12dp"
                        android:background="@drawable/bg_card"
                        android:orientation="vertical"
                        android:padding="14dp">

                        <LinearLayout
                            android:id="@+id/hdrCardMethod"
                            android:layout_width="match_parent"
                            android:layout_height="wrap_content"
                            android:gravity="center_vertical"
                            android:orientation="horizontal">

                            <TextView
                                android:layout_width="0dp"
                                android:layout_height="wrap_content"
                                android:layout_weight="1"
                                android:text="⚡  Metode"
                                android:textColor="#F3F4F6"
                                android:textSize="14sp"
                                android:textStyle="bold" />

                            <TextView
                                android:id="@+id/arrowCardMethod"
                                android:layout_width="wrap_content"
                                android:layout_height="wrap_content"
                                android:text="⌃"
                                android:textColor="#9CA3AF"
                                android:textSize="16sp" />
                        </LinearLayout>

                        <LinearLayout
                            android:id="@+id/bodyCardMethod"
                            android:layout_width="match_parent"
                            android:layout_height="wrap_content"
                            android:layout_marginTop="12dp"
                            android:orientation="horizontal">

                            <Button
                                android:id="@+id/btnMethodSlowDns"
                                android:layout_width="wrap_content"
                                android:layout_height="38dp"
                                android:layout_marginEnd="8dp"
                                android:backgroundTint="#2C3545"
                                android:text="SlowDNS"
                                android:textAllCaps="false"
                                android:textColor="#FFFFFF"
                                android:textSize="12sp" />

                            <Button
                                android:id="@+id/btnMethodDirect"
                                android:layout_width="wrap_content"
                                android:layout_height="38dp"
                                android:backgroundTint="#161B24"
                                android:text="Direct SSH"
                                android:textAllCaps="false"
                                android:textColor="#9CA3AF"
                                android:textSize="12sp" />
                        </LinearLayout>
                    </LinearLayout>

                    <!-- KARTU SLOWDNS (MUNCUL SAAT METODE = SLOWDNS) -->
                    <LinearLayout
                        android:id="@+id/cardSlowDnsSection"
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:layout_marginBottom="12dp"
                        android:background="@drawable/bg_card"
                        android:orientation="vertical"
                        android:padding="14dp">

                        <LinearLayout
                            android:id="@+id/hdrCardSlowDns"
                            android:layout_width="match_parent"
                            android:layout_height="wrap_content"
                            android:gravity="center_vertical"
                            android:orientation="horizontal">

                            <TextView
                                android:layout_width="0dp"
                                android:layout_height="wrap_content"
                                android:layout_weight="1"
                                android:text="👤  SlowDNS"
                                android:textColor="#F3F4F6"
                                android:textSize="14sp"
                                android:textStyle="bold" />

                            <TextView
                                android:id="@+id/arrowCardSlowDns"
                                android:layout_width="wrap_content"
                                android:layout_height="wrap_content"
                                android:text="⌃"
                                android:textColor="#9CA3AF"
                                android:textSize="16sp" />
                        </LinearLayout>

                        <LinearLayout
                            android:id="@+id/bodyCardSlowDns"
                            android:layout_width="match_parent"
                            android:layout_height="wrap_content"
                            android:layout_marginTop="12dp"
                            android:orientation="vertical">

                            <TextView
                                android:layout_width="wrap_content"
                                android:layout_height="wrap_content"
                                android:layout_marginBottom="4dp"
                                android:text="Type"
                                android:textColor="#9CA3AF"
                                android:textSize="11sp" />

                            <Spinner
                                android:id="@+id/spDnsType"
                                android:layout_width="match_parent"
                                android:layout_height="46dp"
                                android:layout_marginBottom="10dp"
                                android:background="@drawable/bg_input"
                                android:paddingHorizontal="12dp" />

                            <TextView
                                android:layout_width="wrap_content"
                                android:layout_height="wrap_content"
                                android:layout_marginBottom="4dp"
                                android:text="DNS (Domain Name System)"
                                android:textColor="#9CA3AF"
                                android:textSize="11sp" />

                            <EditText
                                android:id="@+id/etDns"
                                android:layout_width="match_parent"
                                android:layout_height="46dp"
                                android:layout_marginBottom="10dp"
                                android:background="@drawable/bg_input"
                                android:fontFamily="monospace"
                                android:hint="1.1.1.1"
                                android:paddingHorizontal="12dp"
                                android:singleLine="true"
                                android:textColor="#F3F4F6"
                                android:textColorHint="#6B7280"
                                android:textSize="13sp" />

                            <TextView
                                android:layout_width="wrap_content"
                                android:layout_height="wrap_content"
                                android:layout_marginBottom="4dp"
                                android:text="NS (Nameserver)"
                                android:textColor="#9CA3AF"
                                android:textSize="11sp" />

                            <EditText
                                android:id="@+id/etNs"
                                android:layout_width="match_parent"
                                android:layout_height="46dp"
                                android:layout_marginBottom="10dp"
                                android:background="@drawable/bg_input"
                                android:fontFamily="monospace"
                                android:hint="ns.domain.com"
                                android:paddingHorizontal="12dp"
                                android:singleLine="true"
                                android:textColor="#F3F4F6"
                                android:textColorHint="#6B7280"
                                android:textSize="13sp" />

                            <TextView
                                android:layout_width="wrap_content"
                                android:layout_height="wrap_content"
                                android:layout_marginBottom="4dp"
                                android:text="Kunci publik"
                                android:textColor="#9CA3AF"
                                android:textSize="11sp" />

                            <EditText
                                android:id="@+id/etPubkey"
                                android:layout_width="match_parent"
                                android:layout_height="wrap_content"
                                android:background="@drawable/bg_input"
                                android:fontFamily="monospace"
                                android:hint="64-char hex public key"
                                android:minHeight="46dp"
                                android:paddingHorizontal="12dp"
                                android:paddingVertical="10dp"
                                android:textColor="#FCD34D"
                                android:textColorHint="#6B7280"
                                android:textSize="12sp" />
                        </LinearLayout>
                    </LinearLayout>

                    <!-- KARTU AKUN SSH & BADVPN -->
                    <LinearLayout
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:background="@drawable/bg_card"
                        android:orientation="vertical"
                        android:padding="14dp">

                        <LinearLayout
                            android:id="@+id/hdrCardSsh"
                            android:layout_width="match_parent"
                            android:layout_height="wrap_content"
                            android:gravity="center_vertical"
                            android:orientation="horizontal">

                            <TextView
                                android:layout_width="0dp"
                                android:layout_height="wrap_content"
                                android:layout_weight="1"
                                android:text="🔑  Akun SSH &amp; UDPGW"
                                android:textColor="#F3F4F6"
                                android:textSize="14sp"
                                android:textStyle="bold" />

                            <TextView
                                android:id="@+id/arrowCardSsh"
                                android:layout_width="wrap_content"
                                android:layout_height="wrap_content"
                                android:text="⌃"
                                android:textColor="#9CA3AF"
                                android:textSize="16sp" />
                        </LinearLayout>

                        <LinearLayout
                            android:id="@+id/bodyCardSsh"
                            android:layout_width="match_parent"
                            android:layout_height="wrap_content"
                            android:layout_marginTop="12dp"
                            android:orientation="vertical">

                            <TextView
                                android:layout_width="wrap_content"
                                android:layout_height="wrap_content"
                                android:layout_marginBottom="4dp"
                                android:text="Host:Port SSH"
                                android:textColor="#9CA3AF"
                                android:textSize="11sp" />

                            <EditText
                                android:id="@+id/etSshHostPort"
                                android:layout_width="match_parent"
                                android:layout_height="46dp"
                                android:layout_marginBottom="10dp"
                                android:background="@drawable/bg_input"
                                android:fontFamily="monospace"
                                android:hint="127.0.0.1:22"
                                android:paddingHorizontal="12dp"
                                android:singleLine="true"
                                android:textColor="#F3F4F6"
                                android:textColorHint="#6B7280"
                                android:textSize="13sp" />

                            <LinearLayout
                                android:layout_width="match_parent"
                                android:layout_height="wrap_content"
                                android:layout_marginBottom="10dp"
                                android:orientation="horizontal">

                                <EditText
                                    android:id="@+id/etSshUser"
                                    android:layout_width="0dp"
                                    android:layout_height="46dp"
                                    android:layout_marginEnd="6dp"
                                    android:layout_weight="1"
                                    android:background="@drawable/bg_input"
                                    android:fontFamily="monospace"
                                    android:hint="Username"
                                    android:paddingHorizontal="12dp"
                                    android:singleLine="true"
                                    android:textColor="#F3F4F6"
                                    android:textColorHint="#6B7280"
                                    android:textSize="13sp" />

                                <EditText
                                    android:id="@+id/etSshPass"
                                    android:layout_width="0dp"
                                    android:layout_height="46dp"
                                    android:layout_marginStart="6dp"
                                    android:layout_weight="1"
                                    android:background="@drawable/bg_input"
                                    android:fontFamily="monospace"
                                    android:hint="Password"
                                    android:inputType="textPassword"
                                    android:paddingHorizontal="12dp"
                                    android:singleLine="true"
                                    android:textColor="#F3F4F6"
                                    android:textColorHint="#6B7280"
                                    android:textSize="13sp" />
                            </LinearLayout>

                            <LinearLayout
                                android:layout_width="match_parent"
                                android:layout_height="wrap_content"
                                android:orientation="horizontal">

                                <EditText
                                    android:id="@+id/etSessions"
                                    android:layout_width="0dp"
                                    android:layout_height="46dp"
                                    android:layout_marginEnd="6dp"
                                    android:layout_weight="1"
                                    android:background="@drawable/bg_input"
                                    android:fontFamily="monospace"
                                    android:hint="Sesi (1-4)"
                                    android:inputType="number"
                                    android:paddingHorizontal="12dp"
                                    android:singleLine="true"
                                    android:textColor="#F3F4F6"
                                    android:textColorHint="#6B7280"
                                    android:textSize="13sp" />

                                <EditText
                                    android:id="@+id/etUdpgw"
                                    android:layout_width="0dp"
                                    android:layout_height="46dp"
                                    android:layout_marginStart="6dp"
                                    android:layout_weight="1"
                                    android:background="@drawable/bg_input"
                                    android:fontFamily="monospace"
                                    android:hint="UDPGW (7300)"
                                    android:inputType="number"
                                    android:paddingHorizontal="12dp"
                                    android:singleLine="true"
                                    android:textColor="#F3F4F6"
                                    android:textColorHint="#6B7280"
                                    android:textSize="13sp" />
                            </LinearLayout>
                        </LinearLayout>
                    </LinearLayout>
                </LinearLayout>
            </ScrollView>

            <!-- 5. PENGATURAN: VPN PER APLIKASI -->
            <LinearLayout
                android:id="@+id/layoutPengaturan"
                android:layout_width="match_parent"
                android:layout_height="match_parent"
                android:orientation="vertical"
                android:paddingHorizontal="14dp"
                android:paddingTop="10dp"
                android:visibility="gone">

                <TextView
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:text="ⓘ Menampilkan aplikasi terpasang untuk memilih mana yang lewat VPN. Daftarnya tetap di perangkat ini."
                    android:textColor="#9CA3AF"
                    android:textSize="11.5sp" />

                <LinearLayout
                    android:id="@+id/btnSelectSplitMode"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:layout_marginTop="12dp"
                    android:background="?android:attr/selectableItemBackground"
                    android:orientation="vertical"
                    android:paddingVertical="6dp">

                    <TextView
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:text="Mode"
                        android:textColor="#F3F4F6"
                        android:textSize="15sp"
                        android:textStyle="bold" />

                    <TextView
                        android:id="@+id/tvSplitModeDesc"
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:layout_marginTop="2dp"
                        android:text="Semua aplikasi"
                        android:textColor="#9CA3AF"
                        android:textSize="12.5sp" />
                </LinearLayout>

                <TextView
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:layout_marginTop="6dp"
                    android:text="ⓘ Sambungkan ulang agar berlaku."
                    android:textColor="#9CA3AF"
                    android:textSize="11.5sp" />

                <TextView
                    android:id="@+id/tvSelectedAppsCount"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:layout_marginTop="8dp"
                    android:text="0 dipilih"
                    android:textColor="#E5E7EB"
                    android:textSize="12.5sp"
                    android:textStyle="bold" />

                <LinearLayout
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:layout_marginTop="10dp"
                    android:orientation="horizontal">

                    <Button
                        android:id="@+id/btnFilterUserApps"
                        android:layout_width="wrap_content"
                        android:layout_height="38dp"
                        android:layout_marginEnd="8dp"
                        android:backgroundTint="#2C3340"
                        android:text="Aplikasi pengguna"
                        android:textAllCaps="false"
                        android:textColor="#E2E8F0"
                        android:textSize="12sp" />

                    <Button
                        android:id="@+id/btnFilterAllApps"
                        android:layout_width="wrap_content"
                        android:layout_height="38dp"
                        android:backgroundTint="#15181E"
                        android:text="Semua aplikasi"
                        android:textAllCaps="false"
                        android:textColor="#9CA3AF"
                        android:textSize="12sp" />
                </LinearLayout>

                <EditText
                    android:id="@+id/etSearchApps"
                    android:layout_width="match_parent"
                    android:layout_height="44dp"
                    android:layout_marginTop="10dp"
                    android:layout_marginBottom="8dp"
                    android:background="@drawable/bg_input"
                    android:drawableStart="@android:drawable/ic_menu_search"
                    android:drawablePadding="8dp"
                    android:hint="Cari aplikasi..."
                    android:paddingHorizontal="12dp"
                    android:singleLine="true"
                    android:textColor="#F3F4F6"
                    android:textColorHint="#6B7280"
                    android:textSize="13sp" />

                <ListView
                    android:id="@+id/lvInstalledApps"
                    android:layout_width="match_parent"
                    android:layout_height="0dp"
                    android:layout_weight="1"
                    android:divider="#1E222A"
                    android:dividerHeight="1dp" />
            </LinearLayout>
        </FrameLayout>

        <!-- BOTTOM NAV BAR -->
        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="56dp"
            android:background="#141820"
            android:orientation="horizontal"
            android:paddingVertical="4dp">

            <LinearLayout
                android:id="@+id/navBeranda"
                android:layout_width="0dp"
                android:layout_height="match_parent"
                android:layout_weight="1"
                android:gravity="center"
                android:orientation="vertical">

                <TextView
                    android:id="@+id/pillBeranda"
                    android:layout_width="56dp"
                    android:layout_height="24dp"
                    android:background="@drawable/bg_nav_pill"
                    android:gravity="center"
                    android:text="🏠"
                    android:textSize="12sp" />

                <TextView
                    android:id="@+id/txtNavBeranda"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:layout_marginTop="2dp"
                    android:text="Beranda"
                    android:textColor="#E5E7EB"
                    android:textSize="10sp"
                    android:textStyle="bold" />
            </LinearLayout>

            <LinearLayout
                android:id="@+id/navProfil"
                android:layout_width="0dp"
                android:layout_height="match_parent"
                android:layout_weight="1"
                android:gravity="center"
                android:orientation="vertical">

                <TextView
                    android:id="@+id/pillProfil"
                    android:layout_width="56dp"
                    android:layout_height="24dp"
                    android:gravity="center"
                    android:text="📂"
                    android:textSize="12sp" />

                <TextView
                    android:id="@+id/txtNavProfil"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:layout_marginTop="2dp"
                    android:text="Profil"
                    android:textColor="#9CA3AF"
                    android:textSize="10sp" />
            </LinearLayout>

            <LinearLayout
                android:id="@+id/navPengaturan"
                android:layout_width="0dp"
                android:layout_height="match_parent"
                android:layout_weight="1"
                android:gravity="center"
                android:orientation="vertical">

                <TextView
                    android:id="@+id/pillPengaturan"
                    android:layout_width="56dp"
                    android:layout_height="24dp"
                    android:gravity="center"
                    android:text="⚙️"
                    android:textSize="12sp" />

                <TextView
                    android:id="@+id/txtNavPengaturan"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:layout_marginTop="2dp"
                    android:text="Pengaturan"
                    android:textColor="#9CA3AF"
                    android:textSize="10sp" />
            </LinearLayout>
        </LinearLayout>
    </LinearLayout>

    <!-- FAB CONNECT / DISCONNECT (BERANDA) -->
    <com.google.android.material.floatingactionbutton.FloatingActionButton
        android:id="@+id/btnConnect"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_gravity="end|bottom"
        android:layout_marginEnd="18dp"
        android:layout_marginBottom="72dp"
        android:src="@android:drawable/ic_media_play"
        app:backgroundTint="#1D4ED8"
        app:tint="#FFFFFF" />

    <!-- FAB SIMPAN PROFIL (EDITOR PROFIL) -->
    <com.google.android.material.floatingactionbutton.FloatingActionButton
        android:id="@+id/btnSaveEditorFab"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_gravity="end|bottom"
        android:layout_marginEnd="18dp"
        android:layout_marginBottom="72dp"
        android:src="@android:drawable/ic_menu_save"
        android:visibility="gone"
        app:backgroundTint="#2563EB"
        app:tint="#FFFFFF" />
</androidx.coordinatorlayout.widget.CoordinatorLayout>
""")

# 4. MainActivity.kt -> TrafficStats Real-time + Profil Modular + Editor Collapsible
with open("app/src/main/java/com/custom/tunnel/MainActivity.kt", "w") as f:
    f.write("""package com.custom.tunnel

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.net.TrafficStats
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.text.Editable
import android.text.Html
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.tabs.TabLayout
import org.json.JSONArray
import org.json.JSONObject
import java.net.NetworkInterface
import java.util.Collections

data class AppEntry(
    val name: String,
    val pkg: String,
    val icon: Drawable,
    val isSystem: Boolean
)

class MainActivity : AppCompatActivity() {

    private lateinit var etEditProfileName: EditText
    private lateinit var spDnsType: Spinner
    private lateinit var etDns: EditText
    private lateinit var etNs: EditText
    private lateinit var etPubkey: EditText
    private lateinit var etSessions: EditText
    private lateinit var etSshHostPort: EditText
    private lateinit var etSshUser: EditText
    private lateinit var etSshPass: EditText
    private lateinit var etUdpgw: EditText

    private lateinit var btnModeSingle: Button
    private lateinit var btnModeMulti: Button
    private lateinit var btnMethodSlowDns: Button
    private lateinit var btnMethodDirect: Button
    private lateinit var cardSlowDnsSection: LinearLayout

    private lateinit var btnConnect: FloatingActionButton
    private lateinit var btnSaveEditorFab: FloatingActionButton
    private lateinit var btnRefresh: Button
    private lateinit var btnCleanLog: Button
    private lateinit var tvLogs: TextView
    private lateinit var tvHeaderStatus: TextView
    private lateinit var tvActiveProtocolBadge: TextView
    private lateinit var tvStatDown: TextView
    private lateinit var tvStatDownPeak: TextView
    private lateinit var tvStatUp: TextView
    private lateinit var tvStatUpPeak: TextView
    private lateinit var tvStatusDotLabel: TextView
    private lateinit var tvLocalIp: TextView
    private lateinit var tvHomeProfileName: TextView
    private lateinit var tvHomeProfileSub: TextView
    private lateinit var tvSplitSummaryHome: TextView

    private lateinit var topBarMain: LinearLayout
    private lateinit var topBarSettings: LinearLayout
    private lateinit var btnBackFromEditor: ImageButton
    private lateinit var btnAddProfileMenu: TextView
    private lateinit var tvTopTitle: TextView
    private lateinit var layoutUtama: ScrollView
    private lateinit var layoutLog: LinearLayout
    private lateinit var layoutProfil: ScrollView
    private lateinit var layoutProfileEditor: ScrollView
    private lateinit var layoutPengaturan: LinearLayout
    private lateinit var scrollLog: ScrollView
    private lateinit var tabLayout: TabLayout

    private lateinit var navBeranda: LinearLayout
    private lateinit var navProfil: LinearLayout
    private lateinit var navPengaturan: LinearLayout
    private lateinit var pillBeranda: TextView
    private lateinit var pillProfil: TextView
    private lateinit var pillPengaturan: TextView
    private lateinit var txtNavBeranda: TextView
    private lateinit var txtNavProfil: TextView
    private lateinit var txtNavPengaturan: TextView

    private lateinit var profilesContainer: LinearLayout
    private lateinit var etConfigJson: EditText
    private lateinit var btnExportJson: Button
    private lateinit var btnImportJson: Button

    private lateinit var tvSplitModeDesc: TextView
    private lateinit var tvSelectedAppsCount: TextView
    private lateinit var btnFilterUserApps: Button
    private lateinit var btnFilterAllApps: Button
    private lateinit var etSearchApps: EditText
    private lateinit var lvInstalledApps: ListView

    private val dnsTypes = arrayOf("NULL", "AUTO", "TXT")
    private val uiHandler = Handler(Looper.getMainLooper())
    private var logRenderPending = false
    private var currentNav = "beranda"

    private var editingProfileId: String? = null
    private var editorProtocol = "SSH"
    private var editorMethod = "SlowDNS"

    private var isTrafficMonitorActive = false
    private var lastRxBytes = 0L
    private var lastTxBytes = 0L
    private var lastSampleTime = 0L
    private var maxDownPeakBps = 0L
    private var maxUpPeakBps = 0L

    private var allInstalledApps: List<AppEntry> = emptyList()
    private var filteredApps: List<AppEntry> = emptyList()
    private var showSystemApps = false
    private var appsLoaded = false

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startTunnelService()
        } else {
            TunnelLogger.log("Izin VPN ditolak pengguna.", "#EF5350")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        setupDnsSpinner()
        setupCollapsibleCards()
        migrateLegacyConfigIfNeeded()
        refreshActiveProfileHomeCard()
        renderProfilesList()
        updateSplitTunnelLabels()
        updateButtonState()
        renderLogsNow()
        getMobileInterfaceIp()

        navBeranda.setOnClickListener { switchNav("beranda") }
        navProfil.setOnClickListener { switchNav("profil") }
        navPengaturan.setOnClickListener { switchNav("pengaturan") }

        btnBackFromEditor.setOnClickListener { switchNav("profil") }
        btnAddProfileMenu.setOnClickListener { showAddProtocolPopup(it) }

        findViewById<Button>(R.id.btnHomePickProfile).setOnClickListener { showQuickProfileSelector() }
        findViewById<TextView>(R.id.btnHomeEditProfile).setOnClickListener {
            val active = getActiveProfile()
            if (active != null) openProfileEditor(active) else switchNav("profil")
        }
        findViewById<TextView>(R.id.btnLihatSplit).setOnClickListener { switchNav("pengaturan") }
        findViewById<ImageButton>(R.id.btnBackFromSettings).setOnClickListener { switchNav("beranda") }

        btnModeSingle.setOnClickListener {
            etSessions.setText("1")
            updateEditorChips()
        }
        btnModeMulti.setOnClickListener {
            etSessions.setText("2")
            updateEditorChips()
        }
        btnMethodSlowDns.setOnClickListener {
            editorMethod = "SlowDNS"
            updateEditorChips()
        }
        btnMethodDirect.setOnClickListener {
            editorMethod = "Direct"
            updateEditorChips()
        }

        btnSaveEditorFab.setOnClickListener { saveEditorProfile() }

        findViewById<LinearLayout>(R.id.btnSelectSplitMode).setOnClickListener { showSplitModeDialog() }
        findViewById<TextView>(R.id.btnClearSelectedApps).setOnClickListener {
            getSharedPreferences("split_tunnel", MODE_PRIVATE).edit()
                .putStringSet("packages", emptySet()).apply()
            updateSplitTunnelLabels()
            filterInstalledApps()
            Toast.makeText(this, "Pilihan aplikasi dibersihkan", Toast.LENGTH_SHORT).show()
        }

        btnFilterUserApps.setOnClickListener {
            showSystemApps = false
            btnFilterUserApps.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#2C3340"))
            btnFilterUserApps.setTextColor(Color.parseColor("#E2E8F0"))
            btnFilterAllApps.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#15181E"))
            btnFilterAllApps.setTextColor(Color.parseColor("#9CA3AF"))
            filterInstalledApps()
        }

        btnFilterAllApps.setOnClickListener {
            showSystemApps = true
            btnFilterAllApps.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#2C3340"))
            btnFilterAllApps.setTextColor(Color.parseColor("#E2E8F0"))
            btnFilterUserApps.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#15181E"))
            btnFilterUserApps.setTextColor(Color.parseColor("#9CA3AF"))
            filterInstalledApps()
        }

        etSearchApps.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { filterInstalledApps() }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                if (currentNav != "beranda") return
                if (tab?.position == 0) {
                    layoutUtama.visibility = View.VISIBLE
                    layoutLog.visibility = View.GONE
                } else {
                    layoutUtama.visibility = View.GONE
                    layoutLog.visibility = View.VISIBLE
                    renderLogsNow()
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        TunnelLogger.onLogUpdated = {
            uiHandler.post {
                updateButtonState()
                if (!logRenderPending) {
                    logRenderPending = true
                    uiHandler.postDelayed({
                        logRenderPending = false
                        renderLogsNow()
                    }, 180)
                }
            }
        }

        btnConnect.setOnClickListener {
            if (TunnelVpnService.isRunning) {
                val stopIntent = Intent(this, TunnelVpnService::class.java).apply {
                    action = TunnelVpnService.ACTION_STOP
                }
                startService(stopIntent)
                updateButtonState()
            } else {
                val active = getActiveProfile()
                if (active == null) {
                    Toast.makeText(this, "Buat atau pilih profil dulu", Toast.LENGTH_SHORT).show()
                    switchNav("profil")
                    return@setOnClickListener
                }
                switchNav("beranda")
                tabLayout.getTabAt(1)?.select()
                prepareAndStartVpn()
            }
        }

        btnRefresh.setOnClickListener {
            if (TunnelVpnService.isRunning) {
                tabLayout.getTabAt(1)?.select()
                val refreshIntent = Intent(this, TunnelVpnService::class.java).apply {
                    action = TunnelVpnService.ACTION_REFRESH
                }
                startService(refreshIntent)
            }
        }

        btnCleanLog.setOnClickListener {
            TunnelLogger.clear()
            tvLogs.text = ""
        }

        btnExportJson.setOnClickListener { exportActiveProfileToJson() }
        btnImportJson.setOnClickListener { importProfileFromJson() }
    }

    private fun bindViews() {
        etEditProfileName = findViewById(R.id.etEditProfileName)
        spDnsType = findViewById(R.id.spDnsType)
        etDns = findViewById(R.id.etDns)
        etNs = findViewById(R.id.etNs)
        etPubkey = findViewById(R.id.etPubkey)
        etSessions = findViewById(R.id.etSessions)
        etSshHostPort = findViewById(R.id.etSshHostPort)
        etSshUser = findViewById(R.id.etSshUser)
        etSshPass = findViewById(R.id.etSshPass)
        etUdpgw = findViewById(R.id.etUdpgw)

        btnModeSingle = findViewById(R.id.btnModeSingle)
        btnModeMulti = findViewById(R.id.btnModeMulti)
        btnMethodSlowDns = findViewById(R.id.btnMethodSlowDns)
        btnMethodDirect = findViewById(R.id.btnMethodDirect)
        cardSlowDnsSection = findViewById(R.id.cardSlowDnsSection)

        btnConnect = findViewById(R.id.btnConnect)
        btnSaveEditorFab = findViewById(R.id.btnSaveEditorFab)
        btnRefresh = findViewById(R.id.btnRefresh)
        btnCleanLog = findViewById(R.id.btnCleanLog)
        tvLogs = findViewById(R.id.tvLogs)
        tvHeaderStatus = findViewById(R.id.tvHeaderStatus)
        tvActiveProtocolBadge = findViewById(R.id.tvActiveProtocolBadge)
        tvStatDown = findViewById(R.id.tvStatDown)
        tvStatDownPeak = findViewById(R.id.tvStatDownPeak)
        tvStatUp = findViewById(R.id.tvStatUp)
        tvStatUpPeak = findViewById(R.id.tvStatUpPeak)
        tvStatusDotLabel = findViewById(R.id.tvStatusDotLabel)
        tvLocalIp = findViewById(R.id.tvLocalIp)
        tvHomeProfileName = findViewById(R.id.tvHomeProfileName)
        tvHomeProfileSub = findViewById(R.id.tvHomeProfileSub)
        tvSplitSummaryHome = findViewById(R.id.tvSplitSummaryHome)

        topBarMain = findViewById(R.id.topBarMain)
        topBarSettings = findViewById(R.id.topBarSettings)
        btnBackFromEditor = findViewById(R.id.btnBackFromEditor)
        btnAddProfileMenu = findViewById(R.id.btnAddProfileMenu)
        tvTopTitle = findViewById(R.id.tvTopTitle)
        layoutUtama = findViewById(R.id.layoutUtama)
        layoutLog = findViewById(R.id.layoutLog)
        layoutProfil = findViewById(R.id.layoutProfil)
        layoutProfileEditor = findViewById(R.id.layoutProfileEditor)
        layoutPengaturan = findViewById(R.id.layoutPengaturan)
        scrollLog = findViewById(R.id.scrollLog)
        tabLayout = findViewById(R.id.tabLayout)

        navBeranda = findViewById(R.id.navBeranda)
        navProfil = findViewById(R.id.navProfil)
        navPengaturan = findViewById(R.id.navPengaturan)
        pillBeranda = findViewById(R.id.pillBeranda)
        pillProfil = findViewById(R.id.pillProfil)
        pillPengaturan = findViewById(R.id.pillPengaturan)
        txtNavBeranda = findViewById(R.id.txtNavBeranda)
        txtNavProfil = findViewById(R.id.txtNavProfil)
        txtNavPengaturan = findViewById(R.id.txtNavPengaturan)

        profilesContainer = findViewById(R.id.profilesContainer)
        etConfigJson = findViewById(R.id.etConfigJson)
        btnExportJson = findViewById(R.id.btnExportJson)
        btnImportJson = findViewById(R.id.btnImportJson)

        tvSplitModeDesc = findViewById(R.id.tvSplitModeDesc)
        tvSelectedAppsCount = findViewById(R.id.tvSelectedAppsCount)
        btnFilterUserApps = findViewById(R.id.btnFilterUserApps)
        btnFilterAllApps = findViewById(R.id.btnFilterAllApps)
        etSearchApps = findViewById(R.id.etSearchApps)
        lvInstalledApps = findViewById(R.id.lvInstalledApps)
    }

    private fun setupDnsSpinner() {
        val adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, dnsTypes) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                return (super.getView(position, convertView, parent) as TextView).apply {
                    setTextColor(Color.WHITE)
                    text = dnsTypes[position]
                }
            }
            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                return (super.getDropDownView(position, convertView, parent) as TextView).apply {
                    setTextColor(Color.WHITE)
                    setBackgroundColor(Color.parseColor("#1D2025"))
                    setPadding(24, 24, 24, 24)
                }
            }
        }
        spDnsType.adapter = adapter
    }

    private fun setupCollapsibleCards() {
        val pairs = listOf(
            Triple(R.id.hdrCardMode, R.id.bodyCardMode, R.id.arrowCardMode),
            Triple(R.id.hdrCardMethod, R.id.bodyCardMethod, R.id.arrowCardMethod),
            Triple(R.id.hdrCardSlowDns, R.id.bodyCardSlowDns, R.id.arrowCardSlowDns),
            Triple(R.id.hdrCardSsh, R.id.bodyCardSsh, R.id.arrowCardSsh)
        )
        for ((hdrId, bodyId, arrowId) in pairs) {
            val hdr = findViewById<LinearLayout>(hdrId)
            val body = findViewById<LinearLayout>(bodyId)
            val arrow = findViewById<TextView>(arrowId)
            hdr.setOnClickListener {
                if (body.visibility == View.VISIBLE) {
                    body.visibility = View.GONE
                    arrow.text = "⌄"
                } else {
                    body.visibility = View.VISIBLE
                    arrow.text = "⌃"
                }
            }
        }
    }

    private fun switchNav(target: String) {
        currentNav = target

        pillBeranda.background = null
        pillProfil.background = null
        pillPengaturan.background = null
        txtNavBeranda.setTextColor(Color.parseColor("#9CA3AF"))
        txtNavProfil.setTextColor(Color.parseColor("#9CA3AF"))
        txtNavPengaturan.setTextColor(Color.parseColor("#9CA3AF"))

        layoutUtama.visibility = View.GONE
        layoutLog.visibility = View.GONE
        layoutProfil.visibility = View.GONE
        layoutProfileEditor.visibility = View.GONE
        layoutPengaturan.visibility = View.GONE
        btnBackFromEditor.visibility = View.GONE
        btnAddProfileMenu.visibility = View.GONE
        btnSaveEditorFab.hide()

        when (target) {
            "beranda" -> {
                topBarMain.visibility = View.VISIBLE
                topBarSettings.visibility = View.GONE
                tvTopTitle.text = "Custom Tunnel"
                tvTopTitle.setTextColor(Color.parseColor("#FB923C"))
                tvHeaderStatus.visibility = View.VISIBLE
                tabLayout.visibility = View.VISIBLE
                btnConnect.show()
                if (tabLayout.selectedTabPosition == 0) {
                    layoutUtama.visibility = View.VISIBLE
                } else {
                    layoutLog.visibility = View.VISIBLE
                }
                pillBeranda.setBackgroundResource(R.drawable.bg_nav_pill)
                txtNavBeranda.setTextColor(Color.parseColor("#E5E7EB"))
                refreshActiveProfileHomeCard()
                updateSplitTunnelLabels()
            }
            "profil" -> {
                topBarMain.visibility = View.VISIBLE
                topBarSettings.visibility = View.GONE
                tvTopTitle.text = "Profil"
                tvTopTitle.setTextColor(Color.WHITE)
                tvHeaderStatus.visibility = View.GONE
                btnAddProfileMenu.visibility = View.VISIBLE
                tabLayout.visibility = View.GONE
                btnConnect.hide()
                layoutProfil.visibility = View.VISIBLE
                renderProfilesList()
                pillProfil.setBackgroundResource(R.drawable.bg_nav_pill)
                txtNavProfil.setTextColor(Color.parseColor("#E5E7EB"))
            }
            "editor" -> {
                topBarMain.visibility = View.VISIBLE
                topBarSettings.visibility = View.GONE
                btnBackFromEditor.visibility = View.VISIBLE
                tvTopTitle.text = editorProtocol
                tvTopTitle.setTextColor(Color.WHITE)
                tvHeaderStatus.visibility = View.GONE
                tabLayout.visibility = View.GONE
                btnConnect.hide()
                btnSaveEditorFab.show()
                layoutProfileEditor.visibility = View.VISIBLE
                pillProfil.setBackgroundResource(R.drawable.bg_nav_pill)
                txtNavProfil.setTextColor(Color.parseColor("#E5E7EB"))
            }
            "pengaturan" -> {
                topBarMain.visibility = View.GONE
                topBarSettings.visibility = View.VISIBLE
                tabLayout.visibility = View.GONE
                btnConnect.hide()
                layoutPengaturan.visibility = View.VISIBLE
                pillPengaturan.setBackgroundResource(R.drawable.bg_nav_pill)
                txtNavPengaturan.setTextColor(Color.parseColor("#E5E7EB"))
                updateSplitTunnelLabels()
                loadInstalledAppsAsync()
            }
        }
    }

    private fun showAddProtocolPopup(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, "S   SSH")
        popup.menu.add(0, 2, 1, "V   V2Ray (Segera)")
        popup.menu.add(0, 3, 2, "U   UDP Custom (Segera)")
        popup.setOnMenuItemClickListener { item ->
            if (item.itemId == 1) {
                openProfileEditor(null)
            } else {
                Toast.makeText(this, "Protokol ${item.title.toString().trim()} siap ditambahkan berikutnya", Toast.LENGTH_SHORT).show()
            }
            true
        }
        popup.show()
    }

    private fun openProfileEditor(profile: JSONObject?) {
        editorProtocol = profile?.optString("protocol", "SSH") ?: "SSH"
        if (profile == null) {
            editingProfileId = null
            val list = getProfilesArray()
            etEditProfileName.setText("ss${list.length() + 1}")
            editorMethod = "SlowDNS"
            spDnsType.setSelection(0)
            etDns.setText("1.1.1.1")
            etNs.setText("")
            etPubkey.setText("")
            etSessions.setText("1")
            etSshHostPort.setText("127.0.0.1:22")
            etSshUser.setText("")
            etSshPass.setText("")
            etUdpgw.setText("7300")
        } else {
            editingProfileId = profile.optString("id")
            etEditProfileName.setText(profile.optString("name", "ss"))
            editorMethod = profile.optString("method", "SlowDNS")
            spDnsType.setSelection(profile.optInt("dnsTypeIdx", 0).coerceIn(0, dnsTypes.lastIndex))
            etDns.setText(profile.optString("dns", "1.1.1.1"))
            etNs.setText(profile.optString("ns", ""))
            etPubkey.setText(profile.optString("pubkey", ""))
            etSessions.setText(profile.optString("sessions", "1"))
            etSshHostPort.setText(profile.optString("sshHostPort", "127.0.0.1:22"))
            etSshUser.setText(profile.optString("user", ""))
            etSshPass.setText(profile.optString("pass", ""))
            etUdpgw.setText(profile.optString("udpgw", "7300"))
        }
        updateEditorChips()
        switchNav("editor")
    }

    private fun updateEditorChips() {
        val sess = etSessions.text.toString().trim().toIntOrNull() ?: 1
        if (sess <= 1) {
            btnModeSingle.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#2C3545"))
            btnModeSingle.setTextColor(Color.WHITE)
            btnModeMulti.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#161B24"))
            btnModeMulti.setTextColor(Color.parseColor("#9CA3AF"))
        } else {
            btnModeMulti.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#2C3545"))
            btnModeMulti.setTextColor(Color.WHITE)
            btnModeSingle.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#161B24"))
            btnModeSingle.setTextColor(Color.parseColor("#9CA3AF"))
        }

        if (editorMethod == "SlowDNS") {
            btnMethodSlowDns.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#2C3545"))
            btnMethodSlowDns.setTextColor(Color.WHITE)
            btnMethodDirect.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#161B24"))
            btnMethodDirect.setTextColor(Color.parseColor("#9CA3AF"))
            cardSlowDnsSection.visibility = View.VISIBLE
        } else {
            btnMethodDirect.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#2C3545"))
            btnMethodDirect.setTextColor(Color.WHITE)
            btnMethodSlowDns.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#161B24"))
            btnMethodSlowDns.setTextColor(Color.parseColor("#9CA3AF"))
            cardSlowDnsSection.visibility = View.GONE
        }
    }

    private fun saveEditorProfile() {
        val prefs = getSharedPreferences("tunnel_profiles", MODE_PRIVATE)
        val arr = getProfilesArray()
        val id = editingProfileId ?: System.currentTimeMillis().toString()
        val name = etEditProfileName.text.toString().trim().ifEmpty { "ss" }

        val obj = JSONObject().apply {
            put("id", id)
            put("protocol", editorProtocol)
            put("name", name)
            put("method", editorMethod)
            put("dnsTypeIdx", spDnsType.selectedItemPosition)
            put("dns", etDns.text.toString().trim().ifEmpty { "1.1.1.1" })
            put("ns", etNs.text.toString().trim())
            put("pubkey", etPubkey.text.toString().trim())
            put("sessions", (etSessions.text.toString().trim().toIntOrNull() ?: 1).coerceIn(1, 4).toString())
            put("sshHostPort", etSshHostPort.text.toString().trim().ifEmpty { "127.0.0.1:22" })
            put("user", etSshUser.text.toString().trim())
            put("pass", etSshPass.text.toString().trim())
            put("udpgw", etUdpgw.text.toString().trim().ifEmpty { "7300" })
        }

        val updated = JSONArray()
        var replaced = false
        for (i in 0 until arr.length()) {
            val cur = arr.getJSONObject(i)
            if (cur.optString("id") == id) {
                updated.put(obj)
                replaced = true
            } else {
                updated.put(cur)
            }
        }
        if (!replaced) {
            val prepended = JSONArray().apply {
                put(obj)
                for (i in 0 until updated.length()) put(updated.getJSONObject(i))
            }
            prefs.edit().putString("list", prepended.toString()).putString("active_id", id).apply()
        } else {
            prefs.edit().putString("list", updated.toString()).putString("active_id", id).apply()
        }

        Toast.makeText(this, "Profil \x27$name\x27 disimpan & aktif", Toast.LENGTH_SHORT).show()
        switchNav("profil")
    }

    private fun migrateLegacyConfigIfNeeded() {
        val pPrefs = getSharedPreferences("tunnel_profiles", MODE_PRIVATE)
        val arr = JSONArray(pPrefs.getString("list", "[]"))
        if (arr.length() > 0) return

        val old = getSharedPreferences("tunnel_cfg", MODE_PRIVATE)
        val oldNs = old.getString("ns", "") ?: ""
        val oldHost = old.getString("sshHostPort", "") ?: ""
        if (oldNs.isNotEmpty() || oldHost.isNotEmpty()) {
            val id = System.currentTimeMillis().toString()
            val item = JSONObject().apply {
                put("id", id)
                put("protocol", "SSH")
                put("name", "ss")
                put("method", if (oldNs.isNotEmpty()) "SlowDNS" else "Direct")
                put("dnsTypeIdx", old.getInt("dnsTypeIdx", 0))
                put("dns", old.getString("dns", "1.1.1.1"))
                put("ns", oldNs)
                put("pubkey", old.getString("pubkey", ""))
                put("sessions", old.getString("sessions", "1"))
                put("sshHostPort", oldHost.ifEmpty { "127.0.0.1:22" })
                put("user", old.getString("user", ""))
                put("pass", old.getString("pass", ""))
                put("udpgw", old.getString("udpgw", "7300"))
            }
            val newArr = JSONArray().apply { put(item) }
            pPrefs.edit().putString("list", newArr.toString()).putString("active_id", id).apply()
        }
    }

    private fun getProfilesArray(): JSONArray {
        val prefs = getSharedPreferences("tunnel_profiles", MODE_PRIVATE)
        return JSONArray(prefs.getString("list", "[]"))
    }

    private fun getActiveProfile(): JSONObject? {
        val prefs = getSharedPreferences("tunnel_profiles", MODE_PRIVATE)
        val arr = getProfilesArray()
        if (arr.length() == 0) return null
        val activeId = prefs.getString("active_id", "") ?: ""
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            if (obj.optString("id") == activeId) return obj
        }
        return arr.getJSONObject(0)
    }

    private fun setActiveProfileId(id: String) {
        getSharedPreferences("tunnel_profiles", MODE_PRIVATE).edit().putString("active_id", id).apply()
        refreshActiveProfileHomeCard()
        renderProfilesList()
    }

    private fun refreshActiveProfileHomeCard() {
        val active = getActiveProfile()
        if (active == null) {
            tvHomeProfileName.text = "Belum ada profil"
            tvHomeProfileSub.text = "Ketuk Lihat lalu + untuk membuat profil"
            tvActiveProtocolBadge.text = "SSH"
            return
        }
        val name = active.optString("name", "ss")
        val proto = active.optString("protocol", "SSH")
        val method = active.optString("method", "SlowDNS")
        val hostInfo = if (method == "SlowDNS") {
            active.optString("ns", "").ifEmpty { active.optString("sshHostPort", "") }
        } else {
            active.optString("sshHostPort", "")
        }
        tvHomeProfileName.text = name
        tvHomeProfileSub.text = "$proto • $method ($hostInfo)"
        tvActiveProtocolBadge.text = "$proto • $method"
    }

    private fun showQuickProfileSelector() {
        val arr = getProfilesArray()
        if (arr.length() == 0) {
            openProfileEditor(null)
            return
        }
        val names = Array(arr.length()) { idx ->
            val p = arr.getJSONObject(idx)
            "${p.optString("name")} (${p.optString("protocol", "SSH")} • ${p.optString("method", "SlowDNS")})"
        }
        val activeId = getActiveProfile()?.optString("id") ?: ""
        var checked = 0
        for (i in 0 until arr.length()) {
            if (arr.getJSONObject(i).optString("id") == activeId) checked = i
        }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Pilih Profil Aktif")
            .setSingleChoiceItems(names, checked) { dialog, which ->
                val chosen = arr.getJSONObject(which)
                setActiveProfileId(chosen.optString("id"))
                dialog.dismiss()
            }
            .setNeutralButton("+ Baru") { _, _ -> openProfileEditor(null) }
            .setNegativeButton("Tutup", null)
            .show()
    }

    private fun renderProfilesList() {
        profilesContainer.removeAllViews()
        val arr = getProfilesArray()
        val activeId = getActiveProfile()?.optString("id") ?: ""

        if (arr.length() == 0) {
            val emptyTv = TextView(this).apply {
                text = "Belum ada profil.\nKetuk tombol + di pojok kanan atas untuk menambah profil SSH."
                setTextColor(Color.parseColor("#9CA3AF"))
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(24, 64, 24, 64)
            }
            profilesContainer.addView(emptyTv)
            return
        }

        for (i in 0 until arr.length()) {
            val item = arr.getJSONObject(i)
            val id = item.optString("id")
            val name = item.optString("name", "ss")
            val proto = item.optString("protocol", "SSH")
            val method = item.optString("method", "SlowDNS")
            val isSelected = id == activeId

            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.bg_card)
                setPadding(28, 26, 20, 26)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 22 }
                setOnClickListener {
                    setActiveProfileId(id)
                    Toast.makeText(this@MainActivity, "Profil \x27$name\x27 dipilih", Toast.LENGTH_SHORT).show()
                }
            }

            val badge = TextView(this).apply {
                text = proto.take(1).uppercase()
                setTextColor(if (isSelected) Color.parseColor("#60A5FA") else Color.parseColor("#E5E7EB"))
                textSize = 13f
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                setBackgroundResource(R.drawable.bg_input)
                layoutParams = LinearLayout.LayoutParams(78, 78).apply { rightMargin = 26 }
            }

            val infoCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val tvName = TextView(this).apply {
                text = if (isSelected) "$name  ✓" else name
                setTextColor(Color.WHITE)
                textSize = 15f
                setTypeface(null, Typeface.BOLD)
            }
            val tvSub = TextView(this).apply {
                text = "$proto • $method"
                setTextColor(Color.parseColor("#60A5FA"))
                textSize = 11.5f
            }
            infoCol.addView(tvName)
            infoCol.addView(tvSub)

            val btnDup = TextView(this).apply {
                text = "⧉"
                setTextColor(Color.parseColor("#9CA3AF"))
                textSize = 18f
                setPadding(20, 12, 20, 12)
                setOnClickListener {
                    val prefs = getSharedPreferences("tunnel_profiles", MODE_PRIVATE)
                    val curArr = getProfilesArray()
                    val clone = JSONObject(item.toString()).apply {
                        put("id", System.currentTimeMillis().toString())
                        put("name", "$name (copy)")
                    }
                    curArr.put(clone)
                    prefs.edit().putString("list", curArr.toString()).apply()
                    renderProfilesList()
                }
            }

            val btnEdit = TextView(this).apply {
                text = "✎"
                setTextColor(Color.parseColor("#E5E7EB"))
                textSize = 18f
                setPadding(20, 12, 20, 12)
                setOnClickListener { openProfileEditor(item) }
            }

            val btnDel = TextView(this).apply {
                text = "🗑"
                setTextColor(Color.parseColor("#F87171"))
                textSize = 16f
                setPadding(18, 12, 16, 12)
                setOnClickListener {
                    val prefs = getSharedPreferences("tunnel_profiles", MODE_PRIVATE)
                    val curArr = getProfilesArray()
                    val updated = JSONArray()
                    for (j in 0 until curArr.length()) {
                        if (curArr.getJSONObject(j).optString("id") != id) {
                            updated.put(curArr.getJSONObject(j))
                        }
                    }
                    prefs.edit().putString("list", updated.toString()).apply()
                    refreshActiveProfileHomeCard()
                    renderProfilesList()
                }
            }

            card.addView(badge)
            card.addView(infoCol)
            card.addView(btnDup)
            card.addView(btnEdit)
            card.addView(btnDel)
            profilesContainer.addView(card)
        }
    }

    private fun exportActiveProfileToJson() {
        val active = getActiveProfile()
        if (active == null) {
            Toast.makeText(this, "Belum ada profil untuk disalin", Toast.LENGTH_SHORT).show()
            return
        }
        val jsonStr = active.toString(2)
        etConfigJson.setText(jsonStr)
        val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cb.setPrimaryClip(ClipData.newPlainText("CustomTunnelProfile", jsonStr))
        Toast.makeText(this, "JSON profil \x27${active.optString("name")}\x27 disalin!", Toast.LENGTH_SHORT).show()
    }

    private fun importProfileFromJson() {
        val raw = etConfigJson.text.toString().trim()
        if (raw.isEmpty()) {
            Toast.makeText(this, "Tempel JSON profil dulu", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val obj = JSONObject(raw)
            val newId = System.currentTimeMillis().toString()
            obj.put("id", newId)
            if (!obj.has("name")) obj.put("name", "Imported")
            if (!obj.has("protocol")) obj.put("protocol", "SSH")
            if (!obj.has("method")) obj.put("method", "SlowDNS")

            val prefs = getSharedPreferences("tunnel_profiles", MODE_PRIVATE)
            val arr = getProfilesArray()
            val newArr = JSONArray().apply {
                put(obj)
                for (i in 0 until arr.length()) put(arr.getJSONObject(i))
            }
            prefs.edit().putString("list", newArr.toString()).putString("active_id", newId).apply()
            etConfigJson.setText("")
            refreshActiveProfileHomeCard()
            renderProfilesList()
            Toast.makeText(this, "Profil \x27${obj.optString("name")}\x27 diimpor!", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(this, "Format JSON tidak valid!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showSplitModeDialog() {
        val prefs = getSharedPreferences("split_tunnel", MODE_PRIVATE)
        val currentMode = prefs.getString("mode", "all") ?: "all"
        val labels = arrayOf(
            "Semua aplikasi",
            "Kecualikan aplikasi terpilih",
            "Hanya aplikasi terpilih"
        )
        val keys = arrayOf("all", "exclude", "include")
        val checkedIdx = keys.indexOf(currentMode).coerceAtLeast(0)

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Mode")
            .setSingleChoiceItems(labels, checkedIdx) { dialog, which ->
                prefs.edit().putString("mode", keys[which]).apply()
                updateSplitTunnelLabels()
                filterInstalledApps()
                dialog.dismiss()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun updateSplitTunnelLabels() {
        val prefs = getSharedPreferences("split_tunnel", MODE_PRIVATE)
        val mode = prefs.getString("mode", "all") ?: "all"
        val selected = prefs.getStringSet("packages", emptySet()) ?: emptySet()
        val modeTitle = when (mode) {
            "exclude" -> "Kecualikan aplikasi terpilih"
            "include" -> "Hanya aplikasi terpilih"
            else -> "Semua aplikasi"
        }
        tvSplitModeDesc.text = modeTitle
        tvSelectedAppsCount.text = "${selected.size} dipilih"
        tvSplitSummaryHome.text = if (mode == "all") {
            "VPN per aplikasi: Semua aplikasi"
        } else {
            "VPN per aplikasi: $modeTitle (${selected.size})"
        }
    }

    private fun loadInstalledAppsAsync() {
        if (appsLoaded) {
            filterInstalledApps()
            return
        }
        Thread {
            val pm = packageManager
            val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            val list = ArrayList<AppEntry>()
            for (appInfo in packages) {
                if (appInfo.packageName == packageName) continue
                val isSys = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                val label = try {
                    pm.getApplicationLabel(appInfo).toString()
                } catch (_: Exception) {
                    appInfo.packageName
                }
                val icon = try {
                    pm.getApplicationIcon(appInfo)
                } catch (_: Exception) {
                    resources.getDrawable(android.R.drawable.sym_def_app_icon, theme)
                }
                list.add(AppEntry(label, appInfo.packageName, icon, isSys))
            }
            list.sortBy { it.name.lowercase() }
            allInstalledApps = list
            appsLoaded = true
            uiHandler.post { filterInstalledApps() }
        }.start()
    }

    private fun filterInstalledApps() {
        val q = etSearchApps.text.toString().trim().lowercase()
        val prefs = getSharedPreferences("split_tunnel", MODE_PRIVATE)
        val selected = (prefs.getStringSet("packages", emptySet()) ?: emptySet()).toMutableSet()

        filteredApps = allInstalledApps.filter { app ->
            val matchType = showSystemApps || !app.isSystem
            val matchQuery = q.isEmpty() || app.name.lowercase().contains(q) || app.pkg.lowercase().contains(q)
            matchType && matchQuery
        }.sortedWith(compareByDescending<AppEntry> { selected.contains(it.pkg) }.thenBy { it.name.lowercase() })

        lvInstalledApps.adapter = object : BaseAdapter() {
            override fun getCount(): Int = filteredApps.size
            override fun getItem(position: Int): Any = filteredApps[position]
            override fun getItemId(position: Int): Long = position.toLong()

            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val app = filteredApps[position]
                val row = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(8, 22, 8, 22)
                }

                val ivIcon = ImageView(this@MainActivity).apply {
                    setImageDrawable(app.icon)
                    layoutParams = LinearLayout.LayoutParams(96, 96).apply { rightMargin = 28 }
                }

                val textCol = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                val tvName = TextView(this@MainActivity).apply {
                    text = app.name
                    setTextColor(Color.WHITE)
                    textSize = 14f
                    setTypeface(null, Typeface.BOLD)
                }
                val tvPkg = TextView(this@MainActivity).apply {
                    text = app.pkg
                    setTextColor(Color.parseColor("#9CA3AF"))
                    textSize = 11f
                    typeface = Typeface.MONOSPACE
                }
                textCol.addView(tvName)
                textCol.addView(tvPkg)

                val cb = CheckBox(this@MainActivity).apply {
                    isChecked = selected.contains(app.pkg)
                    buttonTintList = ColorStateList.valueOf(Color.parseColor("#60A5FA"))
                    setOnCheckedChangeListener { _, isChecked ->
                        val cur = (prefs.getStringSet("packages", emptySet()) ?: emptySet()).toMutableSet()
                        if (isChecked) cur.add(app.pkg) else cur.remove(app.pkg)
                        prefs.edit().putStringSet("packages", cur).apply()
                        updateSplitTunnelLabels()
                    }
                }

                row.setOnClickListener { cb.isChecked = !cb.isChecked }
                row.addView(ivIcon)
                row.addView(textCol)
                row.addView(cb)
                return row
            }
        }
    }

    private fun renderLogsNow() {
        tvLogs.text = Html.fromHtml(TunnelLogger.getHtmlLogs(), Html.FROM_HTML_MODE_LEGACY)
        scrollLog.post { scrollLog.fullScroll(View.FOCUS_DOWN) }
    }

    override fun onDestroy() {
        isTrafficMonitorActive = false
        TunnelLogger.onLogUpdated = null
        uiHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun prepareAndStartVpn() {
        TunnelLogger.clear()
        TunnelLogger.log("Perangkat: ${Build.MODEL} (Android ${Build.VERSION.RELEASE} / ${Build.SUPPORTED_ABIS.firstOrNull()})", "#9CA3AF")

        val intent = VpnService.prepare(this)
        if (intent != null) {
            vpnPermissionLauncher.launch(intent)
        } else {
            startTunnelService()
        }
    }

    private fun startTunnelService() {
        val active = getActiveProfile() ?: return
        val dnsIdx = active.optInt("dnsTypeIdx", 0).coerceIn(0, dnsTypes.lastIndex)
        val sessionCount = (active.optString("sessions", "1").toIntOrNull() ?: 1).coerceIn(1, 4)

        val intent = Intent(this, TunnelVpnService::class.java).apply {
            action = TunnelVpnService.ACTION_START
            putExtra("profileName", active.optString("name", "ss"))
            putExtra("method", active.optString("method", "SlowDNS"))
            putExtra("dnsType", dnsTypes[dnsIdx])
            putExtra("dns", active.optString("dns", "1.1.1.1"))
            putExtra("ns", active.optString("ns", ""))
            putExtra("pubkey", active.optString("pubkey", ""))
            putExtra("sessions", sessionCount)
            putExtra("sshHostPort", active.optString("sshHostPort", "127.0.0.1:22"))
            putExtra("user", active.optString("user", ""))
            putExtra("pass", active.optString("pass", ""))
            putExtra("udpgw", active.optString("udpgw", "7300"))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        updateButtonState()
    }

    private fun getMobileInterfaceIp() {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (inf in interfaces) {
                if (inf.name.contains("rmnet") || inf.name.contains("wlan") || inf.name.contains("ccmni")) {
                    val addrs = Collections.list(inf.inetAddresses)
                    for (addr in addrs) {
                        if (!addr.isLoopbackAddress && (addr.hostAddress?.indexOf(':') ?: -1) < 0) {
                            tvLocalIp.text = "🌐 ${addr.hostAddress}"
                            return
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        tvLocalIp.text = "🌐 127.0.0.1"
    }

    private fun updateButtonState() {
        when (TunnelVpnService.connectionState) {
            TunnelVpnService.STATE_CONNECTED -> {
                btnConnect.setImageDrawable(resources.getDrawable(android.R.drawable.ic_media_pause, theme))
                btnConnect.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#DC2626"))
                btnRefresh.visibility = View.VISIBLE
                tvHeaderStatus.text = "CONNECTED"
                tvHeaderStatus.setTextColor(Color.parseColor("#4ADE80"))
                tvHeaderStatus.setBackgroundResource(R.drawable.bg_pill_online)
                tvStatusDotLabel.text = "● Terhubung"
                tvStatusDotLabel.setTextColor(Color.parseColor("#4ADE80"))

                if (!isTrafficMonitorActive) {
                    isTrafficMonitorActive = true
                    startRealTrafficMonitor()
                }
            }
            TunnelVpnService.STATE_CONNECTING -> {
                btnConnect.setImageDrawable(resources.getDrawable(android.R.drawable.ic_menu_close_clear_cancel, theme))
                btnConnect.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#D97706"))
                btnRefresh.visibility = View.GONE
                tvHeaderStatus.text = "CONNECTING"
                tvHeaderStatus.setTextColor(Color.parseColor("#FBBF24"))
                tvHeaderStatus.setBackgroundResource(R.drawable.bg_pill_offline)
                tvStatusDotLabel.text = "● Menghubungkan..."
                tvStatusDotLabel.setTextColor(Color.parseColor("#FBBF24"))
                isTrafficMonitorActive = false
            }
            else -> {
                btnConnect.setImageDrawable(resources.getDrawable(android.R.drawable.ic_media_play, theme))
                btnConnect.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#2563EB"))
                btnRefresh.visibility = View.GONE
                tvHeaderStatus.text = "OFFLINE"
                tvHeaderStatus.setTextColor(Color.parseColor("#9CA3AF"))
                tvHeaderStatus.setBackgroundResource(R.drawable.bg_pill_offline)
                tvStatusDotLabel.text = "● Tidak aktif"
                tvStatusDotLabel.setTextColor(Color.parseColor("#9CA3AF"))

                isTrafficMonitorActive = false
                tvStatDown.text = "0 bps"
                tvStatUp.text = "0 bps"
                tvStatDownPeak.text = "Puncak 0 bps"
                tvStatUpPeak.text = "Puncak 0 bps"
                maxDownPeakBps = 0L
                maxUpPeakBps = 0L
            }
        }
    }

    private fun startRealTrafficMonitor() {
        val uid = Process.myUid()
        lastRxBytes = TrafficStats.getUidRxBytes(uid).coerceAtLeast(0L)
        lastTxBytes = TrafficStats.getUidTxBytes(uid).coerceAtLeast(0L)
        lastSampleTime = System.currentTimeMillis()

        uiHandler.post(object : Runnable {
            override fun run() {
                if (!isTrafficMonitorActive) return
                val now = System.currentTimeMillis()
                val elapsedMs = (now - lastSampleTime).coerceAtLeast(1L)
                val curRx = TrafficStats.getUidRxBytes(uid).coerceAtLeast(0L)
                val curTx = TrafficStats.getUidTxBytes(uid).coerceAtLeast(0L)

                val deltaRxBytes = (curRx - lastRxBytes).coerceAtLeast(0L)
                val deltaTxBytes = (curTx - lastTxBytes).coerceAtLeast(0L)

                val downBps = (deltaRxBytes * 8L * 1000L) / elapsedMs
                val upBps = (deltaTxBytes * 8L * 1000L) / elapsedMs

                lastRxBytes = curRx
                lastTxBytes = curTx
                lastSampleTime = now

                if (downBps > maxDownPeakBps) maxDownPeakBps = downBps
                if (upBps > maxUpPeakBps) maxUpPeakBps = upBps

                tvStatDown.text = formatBps(downBps)
                tvStatUp.text = formatBps(upBps)
                tvStatDownPeak.text = "Puncak ${formatBps(maxDownPeakBps)}"
                tvStatUpPeak.text = "Puncak ${formatBps(maxUpPeakBps)}"

                uiHandler.postDelayed(this, 1000)
            }
        })
    }

    private fun formatBps(bits: Long): String {
        return when {
            bits >= 1_000_000L -> String.format("%.1f Mbps", bits / 1_000_000.0)
            bits >= 1_000L -> String.format("%.1f kbps", bits / 1_000.0)
            else -> "$bits bps"
        }
    }
}
""")

print("[OK] Seluruh update UI Utama/Profil modular & Log 100-baris jujur diterapkan.")
PYEOF

git add -A
git commit -m "feat: clean home UI, modular collapsible profiles, real TrafficStats, and honest 100-line log"
git push origin main
