package com.custom.tunnel

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
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.OnBackPressedCallback
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
    private lateinit var btnMethodPayload: Button
    private lateinit var btnMethodTls: Button
    private lateinit var btnMethodSlowDns: Button
    private lateinit var btnMethodDirect: Button
    private lateinit var cardPayloadSection: LinearLayout
    private lateinit var cardTlsSection: LinearLayout
    private lateinit var cardSlowDnsSection: LinearLayout
    private lateinit var etCustomPayload: EditText
    private lateinit var etRemoteProxy: EditText
    private lateinit var etTlsSni: EditText

    private lateinit var btnConnect: FloatingActionButton
    private lateinit var btnSaveEditorFab: FloatingActionButton
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
    private lateinit var ivBrandLogo: ImageView

    private lateinit var topBarMain: LinearLayout
    private lateinit var topBarSettings: LinearLayout
    private lateinit var btnBackFromEditor: ImageButton
    private lateinit var btnBackFromSettings: ImageButton
    private lateinit var btnClearSelectedApps: TextView
    private lateinit var btnAddProfileMenu: TextView
    private lateinit var tvTopTitle: TextView
    private lateinit var tvSettingsSubTitle: TextView

    private lateinit var layoutUtama: ScrollView
    private lateinit var layoutLog: LinearLayout
    private lateinit var layoutProfil: ScrollView
    private lateinit var layoutProfileEditor: ScrollView
    private lateinit var layoutSettingsMenu: ScrollView
    private lateinit var layoutSettingsAutoPing: ScrollView
    private lateinit var layoutSettingsVpnBehavior: ScrollView
    private lateinit var layoutSettingsTunnelCore: ScrollView
    private lateinit var layoutPengaturan: LinearLayout
    private lateinit var scrollLog: ScrollView
    private lateinit var tabLayout: TabLayout

    private lateinit var navBeranda: LinearLayout
    private lateinit var navProfil: LinearLayout
    private lateinit var navPengaturan: LinearLayout
    private lateinit var pillBeranda: ImageView
    private lateinit var pillProfil: ImageView
    private lateinit var pillPengaturan: ImageView
    private lateinit var txtNavBeranda: TextView
    private lateinit var txtNavProfil: TextView
    private lateinit var txtNavPengaturan: TextView

    private lateinit var profilesContainer: LinearLayout
    private lateinit var etConfigJson: EditText
    private lateinit var btnExportJson: Button
    private lateinit var btnImportJson: Button

    // Auto Ping Settings Views
    private lateinit var btnPingModeOff: Button
    private lateinit var btnPingModeKeepAlive: Button
    private lateinit var btnPingModeReset: Button
    private lateinit var btnPingModeAgresif: Button
    private lateinit var tvPingModeBanner: TextView
    private lateinit var valPingTarget: TextView
    private lateinit var valPingInterval: TextView
    private lateinit var valPingTimeout: TextView
    private lateinit var valPingThreshold: TextView
    private lateinit var tvMenuAutoPingDesc: TextView

    // VPN Behavior & Tunnel Core Views
    private lateinit var swAutoReconnect: Switch
    private lateinit var valVpnDns: TextView
    private lateinit var valVpnMtu: TextView
    private lateinit var valVpnSplitSub: TextView
    private lateinit var valSshKeepalive: TextView
    private lateinit var valConnectTimeout: TextView
    private lateinit var valDefaultUdpgw: TextView

    // Split Tunnel Views
    private lateinit var tvMenuSplitDesc: TextView
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
    private var usePayloadFlag = false
    private var useTlsFlag = false
    private var useSlowDnsFlag = true

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
        setupSettingsInteractions()
        migrateLegacyConfigIfNeeded()
        refreshActiveProfileHomeCard()
        renderProfilesList()
        refreshAllSettingsLabels()
        updateButtonState()
        renderLogsNow()
        getMobileInterfaceIp()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when (currentNav) {
                    "editor" -> switchNav("profil")
                    "settings_autoping", "settings_vpn", "settings_tunnel", "settings_apps" -> switchNav("pengaturan")
                    "profil", "pengaturan" -> switchNav("beranda")
                    else -> finish()
                }
            }
        })

        navBeranda.setOnClickListener { switchNav("beranda") }
        navProfil.setOnClickListener { switchNav("profil") }
        navPengaturan.setOnClickListener { switchNav("pengaturan") }

        btnBackFromEditor.setOnClickListener { switchNav("profil") }
        btnBackFromSettings.setOnClickListener {
            when (currentNav) {
                "settings_autoping", "settings_vpn", "settings_tunnel", "settings_apps" -> switchNav("pengaturan")
                else -> switchNav("beranda")
            }
        }
        btnAddProfileMenu.setOnClickListener { showAddProtocolPopup(it) }

        findViewById<Button>(R.id.btnHomePickProfile).setOnClickListener { showQuickProfileSelector() }
        findViewById<TextView>(R.id.btnHomeEditProfile).setOnClickListener {
            val active = getActiveProfile()
            if (active != null) openProfileEditor(active) else switchNav("profil")
        }
        
        btnModeSingle.setOnClickListener {
            etSessions.setText("1")
            updateEditorChips()
        }
        btnModeMulti.setOnClickListener {
            etSessions.setText("2")
            updateEditorChips()
        }
        btnMethodPayload.setOnClickListener {
            usePayloadFlag = !usePayloadFlag
            if (usePayloadFlag) useSlowDnsFlag = false
            syncEditorMethodString()
            updateEditorChips()
        }
        btnMethodTls.setOnClickListener {
            useTlsFlag = !useTlsFlag
            if (useTlsFlag) useSlowDnsFlag = false
            syncEditorMethodString()
            updateEditorChips()
        }
        btnMethodSlowDns.setOnClickListener {
            useSlowDnsFlag = true
            usePayloadFlag = false
            useTlsFlag = false
            syncEditorMethodString()
            updateEditorChips()
        }
        btnMethodDirect.setOnClickListener {
            useSlowDnsFlag = false
            usePayloadFlag = false
            useTlsFlag = false
            syncEditorMethodString()
            updateEditorChips()
        }

        btnSaveEditorFab.setOnClickListener { saveEditorProfile() }

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
        btnMethodPayload = findViewById(R.id.btnMethodPayload)
        btnMethodTls = findViewById(R.id.btnMethodTls)
        btnMethodSlowDns = findViewById(R.id.btnMethodSlowDns)
        btnMethodDirect = findViewById(R.id.btnMethodDirect)
        cardPayloadSection = findViewById(R.id.cardPayloadSection)
        cardTlsSection = findViewById(R.id.cardTlsSection)
        cardSlowDnsSection = findViewById(R.id.cardSlowDnsSection)
        etCustomPayload = findViewById(R.id.etCustomPayload)
        etRemoteProxy = findViewById(R.id.etRemoteProxy)
        etTlsSni = findViewById(R.id.etTlsSni)

        btnConnect = findViewById(R.id.btnConnect)
        btnSaveEditorFab = findViewById(R.id.btnSaveEditorFab)
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
        ivBrandLogo = findViewById(R.id.ivBrandLogo)

        topBarMain = findViewById(R.id.topBarMain)
        topBarSettings = findViewById(R.id.topBarSettings)
        btnBackFromEditor = findViewById(R.id.btnBackFromEditor)
        btnBackFromSettings = findViewById(R.id.btnBackFromSettings)
        btnClearSelectedApps = findViewById(R.id.btnClearSelectedApps)
        btnAddProfileMenu = findViewById(R.id.btnAddProfileMenu)
        tvTopTitle = findViewById(R.id.tvTopTitle)
        tvSettingsSubTitle = findViewById(R.id.tvSettingsSubTitle)

        layoutUtama = findViewById(R.id.layoutUtama)
        layoutLog = findViewById(R.id.layoutLog)
        layoutProfil = findViewById(R.id.layoutProfil)
        layoutProfileEditor = findViewById(R.id.layoutProfileEditor)
        layoutSettingsMenu = findViewById(R.id.layoutSettingsMenu)
        layoutSettingsAutoPing = findViewById(R.id.layoutSettingsAutoPing)
        layoutSettingsVpnBehavior = findViewById(R.id.layoutSettingsVpnBehavior)
        layoutSettingsTunnelCore = findViewById(R.id.layoutSettingsTunnelCore)
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

        btnPingModeOff = findViewById(R.id.btnPingModeOff)
        btnPingModeKeepAlive = findViewById(R.id.btnPingModeKeepAlive)
        btnPingModeReset = findViewById(R.id.btnPingModeReset)
        btnPingModeAgresif = findViewById(R.id.btnPingModeAgresif)
        tvPingModeBanner = findViewById(R.id.tvPingModeBanner)
        valPingTarget = findViewById(R.id.valPingTarget)
        valPingInterval = findViewById(R.id.valPingInterval)
        valPingTimeout = findViewById(R.id.valPingTimeout)
        valPingThreshold = findViewById(R.id.valPingThreshold)
        tvMenuAutoPingDesc = findViewById(R.id.tvMenuAutoPingDesc)

        swAutoReconnect = findViewById(R.id.swAutoReconnect)
        valVpnDns = findViewById(R.id.valVpnDns)
        valVpnMtu = findViewById(R.id.valVpnMtu)
        valVpnSplitSub = findViewById(R.id.valVpnSplitSub)
        valSshKeepalive = findViewById(R.id.valSshKeepalive)
        valConnectTimeout = findViewById(R.id.valConnectTimeout)
        valDefaultUdpgw = findViewById(R.id.valDefaultUdpgw)

        tvMenuSplitDesc = findViewById(R.id.tvMenuSplitDesc)
        tvSplitModeDesc = findViewById(R.id.tvSplitModeDesc)
        tvSelectedAppsCount = findViewById(R.id.tvSelectedAppsCount)
        btnFilterUserApps = findViewById(R.id.btnFilterUserApps)
        btnFilterAllApps = findViewById(R.id.btnFilterAllApps)
        etSearchApps = findViewById(R.id.etSearchApps)
        lvInstalledApps = findViewById(R.id.lvInstalledApps)
    }

    private fun setupSettingsInteractions() {
        val prefs = getSharedPreferences("app_settings", MODE_PRIVATE)

        // Menu items di halaman Pengaturan utama
        findViewById<LinearLayout>(R.id.menuItemVpnBehavior).setOnClickListener { switchNav("settings_vpn") }
        findViewById<LinearLayout>(R.id.menuItemAutoPing).setOnClickListener { switchNav("settings_autoping") }
        findViewById<LinearLayout>(R.id.menuItemSplitApps).setOnClickListener { switchNav("settings_apps") }
        findViewById<LinearLayout>(R.id.menuItemTunnelCore).setOnClickListener { switchNav("settings_tunnel") }

        // Mode chip Auto Ping
        btnPingModeOff.setOnClickListener {
            prefs.edit().putString("ping_mode", "off").apply()
            refreshAllSettingsLabels()
        }
        btnPingModeKeepAlive.setOnClickListener {
            prefs.edit().putString("ping_mode", "keepalive").apply()
            refreshAllSettingsLabels()
        }
        btnPingModeReset.setOnClickListener {
            prefs.edit().putString("ping_mode", "reset").apply()
            refreshAllSettingsLabels()
        }
        btnPingModeAgresif.setOnClickListener {
            prefs.edit()
                .putString("ping_mode", "agresif")
                .putInt("ping_interval", 2)
                .putInt("ping_timeout", 3)
                .putInt("ping_threshold", 2)
                .apply()
            refreshAllSettingsLabels()
        }

        // Collapsible Lanjutan di Auto Ping
        val hdrAdv = findViewById<LinearLayout>(R.id.hdrPingAdvanced)
        val bodyAdv = findViewById<LinearLayout>(R.id.bodyPingAdvanced)
        val arrowAdv = findViewById<TextView>(R.id.arrowPingAdvanced)
        hdrAdv.setOnClickListener {
            if (bodyAdv.visibility == View.VISIBLE) {
                bodyAdv.visibility = View.GONE
                arrowAdv.text = "⌄"
            } else {
                bodyAdv.visibility = View.VISIBLE
                arrowAdv.text = "⌃"
            }
        }

        // Dialog edit setelan lanjutan Auto Ping
        findViewById<LinearLayout>(R.id.itemPingTarget).setOnClickListener {
            showTextSettingDialog("Target ping (host:port atau URL)", prefs.getString("ping_target", "1.1.1.1:80") ?: "1.1.1.1:80", false) { v ->
                if (v.isNotEmpty()) {
                    prefs.edit().putString("ping_target", v).apply()
                    refreshAllSettingsLabels()
                }
            }
        }
        findViewById<LinearLayout>(R.id.itemPingInterval).setOnClickListener {
            showTextSettingDialog("Interval ping (detik)", prefs.getInt("ping_interval", 5).toString(), true) { v ->
                val num = v.toIntOrNull()?.coerceIn(1, 60) ?: return@showTextSettingDialog
                prefs.edit().putInt("ping_interval", num).apply()
                refreshAllSettingsLabels()
            }
        }
        findViewById<LinearLayout>(R.id.itemPingTimeout).setOnClickListener {
            showTextSettingDialog("Batas waktu ping (detik)", prefs.getInt("ping_timeout", 5).toString(), true) { v ->
                val num = v.toIntOrNull()?.coerceIn(2, 30) ?: return@showTextSettingDialog
                prefs.edit().putInt("ping_timeout", num).apply()
                refreshAllSettingsLabels()
            }
        }
        findViewById<LinearLayout>(R.id.itemPingThreshold).setOnClickListener {
            showTextSettingDialog("Ambang kegagalan sebelum reset", prefs.getInt("ping_threshold", 2).toString(), true) { v ->
                val num = v.toIntOrNull()?.coerceIn(1, 10) ?: return@showTextSettingDialog
                prefs.edit().putInt("ping_threshold", num).apply()
                refreshAllSettingsLabels()
            }
        }

        // Sub-halaman Perilaku VPN
        swAutoReconnect.isChecked = prefs.getBoolean("auto_reconnect", true)
        swAutoReconnect.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("auto_reconnect", isChecked).apply()
        }
        findViewById<LinearLayout>(R.id.itemVpnDns).setOnClickListener {
            val cur1 = prefs.getString("vpn_dns1", "8.8.8.8") ?: "8.8.8.8"
            val cur2 = prefs.getString("vpn_dns2", "1.1.1.1") ?: "1.1.1.1"
            showTextSettingDialog("DNS VPN (pisahkan koma, misal 8.8.8.8, 1.1.1.1)", "$cur1, $cur2", false) { v ->
                val parts = v.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                if (parts.isNotEmpty()) {
                    prefs.edit()
                        .putString("vpn_dns1", parts[0])
                        .putString("vpn_dns2", parts.getOrElse(1) { parts[0] })
                        .apply()
                    refreshAllSettingsLabels()
                }
            }
        }
        findViewById<LinearLayout>(R.id.itemVpnMtu).setOnClickListener {
            showTextSettingDialog("MTU antarmuka TUN (1200-1500)", prefs.getInt("vpn_mtu", 1400).toString(), true) { v ->
                val num = v.toIntOrNull()?.coerceIn(1200, 1500) ?: return@showTextSettingDialog
                prefs.edit().putInt("vpn_mtu", num).apply()
                refreshAllSettingsLabels()
            }
        }
        findViewById<LinearLayout>(R.id.itemVpnToSplitApps).setOnClickListener { switchNav("settings_apps") }

        // Sub-halaman SSH & SlowDNS
        findViewById<LinearLayout>(R.id.itemSshKeepalive).setOnClickListener {
            showTextSettingDialog("Interval SSH Keep-alive (detik)", prefs.getInt("ssh_keepalive", 3).toString(), true) { v ->
                val num = v.toIntOrNull()?.coerceIn(2, 30) ?: return@showTextSettingDialog
                prefs.edit().putInt("ssh_keepalive", num).apply()
                refreshAllSettingsLabels()
            }
        }
        findViewById<LinearLayout>(R.id.itemConnectTimeout).setOnClickListener {
            showTextSettingDialog("Batas waktu koneksi (detik)", prefs.getInt("connect_timeout", 30).toString(), true) { v ->
                val num = v.toIntOrNull()?.coerceIn(10, 90) ?: return@showTextSettingDialog
                prefs.edit().putInt("connect_timeout", num).apply()
                refreshAllSettingsLabels()
            }
        }
        findViewById<LinearLayout>(R.id.itemDefaultUdpgw).setOnClickListener {
            showTextSettingDialog("Port default BadVPN UDPGW", prefs.getString("default_udpgw", "7300") ?: "7300", true) { v ->
                val port = v.toIntOrNull()?.coerceIn(1, 65535) ?: return@showTextSettingDialog
                prefs.edit().putString("default_udpgw", port.toString()).apply()
                refreshAllSettingsLabels()
            }
        }

        // Sub-halaman VPN per aplikasi
        findViewById<LinearLayout>(R.id.btnSelectSplitMode).setOnClickListener { showSplitModeDialog() }
        btnClearSelectedApps.setOnClickListener {
            getSharedPreferences("split_tunnel", MODE_PRIVATE).edit()
                .putStringSet("packages", emptySet()).apply()
            refreshAllSettingsLabels()
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
    }

    private fun showTextSettingDialog(title: String, current: String, numeric: Boolean, onSave: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(current)
            setTextColor(Color.WHITE)
            setSelection(text.length)
            if (numeric) inputType = InputType.TYPE_CLASS_NUMBER
        }
        val box = FrameLayout(this).apply {
            setPadding(48, 20, 48, 8)
            addView(input)
        }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("Simpan") { _, _ -> onSave(input.text.toString().trim()) }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun refreshAllSettingsLabels() {
        val prefs = getSharedPreferences("app_settings", MODE_PRIVATE)
        val pingMode = prefs.getString("ping_mode", "off") ?: "off"
        val target = prefs.getString("ping_target", "1.1.1.1:80") ?: "1.1.1.1:80"
        val interval = prefs.getInt("ping_interval", 5)
        val timeout = prefs.getInt("ping_timeout", 5)
        val threshold = prefs.getInt("ping_threshold", 2)

        val chips = listOf(
            btnPingModeOff to "off",
            btnPingModeKeepAlive to "keepalive",
            btnPingModeReset to "reset",
            btnPingModeAgresif to "agresif"
        )
        for ((btn, key) in chips) {
            if (pingMode == key) {
                btn.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#2C3545"))
                btn.setTextColor(Color.WHITE)
            } else {
                btn.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#141820"))
                btn.setTextColor(Color.parseColor("#9CA3AF"))
            }
        }

        val modeTitle = when (pingMode) {
            "keepalive" -> "Keep-alive"
            "reset" -> "Reset"
            "agresif" -> "Agresif"
            else -> "Nonaktif"
        }

        tvPingModeBanner.text = when (pingMode) {
            "keepalive" -> "Ping $target tiap $interval detik untuk menjaga jalur tetap hidup tanpa mereset koneksi."
            "reset" -> "Ping $target tiap $interval detik. Koneksi disambung ulang setelah $threshold kali gagal berturut-turut."
            "agresif" -> "Mode agresif: Ping $target tiap $interval detik, reset cepat setelah $threshold kali gagal."
            else -> "Auto Ping nonaktif. Reconnect tetap dipantau otomatis lewat SSH Keep-alive & status jaringan."
        }

        valPingTarget.text = target
        valPingInterval.text = "$interval detik"
        valPingTimeout.text = "$timeout detik"
        valPingThreshold.text = "$threshold kegagalan"

        tvMenuAutoPingDesc.text = if (pingMode == "off") {
            "Nonaktif • Hanya pantau SSH Keep-alive & jaringan."
        } else {
            "Mode $modeTitle • Ping $target tiap $interval detik."
        }

        val dns1 = prefs.getString("vpn_dns1", "8.8.8.8") ?: "8.8.8.8"
        val dns2 = prefs.getString("vpn_dns2", "1.1.1.1") ?: "1.1.1.1"
        val mtu = prefs.getInt("vpn_mtu", 1400)
        val keepalive = prefs.getInt("ssh_keepalive", 3)
        val connTimeout = prefs.getInt("connect_timeout", 30)
        val defUdpgw = prefs.getString("default_udpgw", "7300") ?: "7300"

        valVpnDns.text = "$dns1, $dns2"
        valVpnMtu.text = mtu.toString()
        valSshKeepalive.text = "$keepalive detik"
        valConnectTimeout.text = "$connTimeout detik"
        valDefaultUdpgw.text = defUdpgw

        updateSplitTunnelLabels()
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
            Triple(R.id.hdrCardPayload, R.id.bodyCardPayload, R.id.arrowCardPayload),
            Triple(R.id.hdrCardSlowDns, R.id.bodyCardSlowDns, R.id.arrowCardSlowDns),
            Triple(R.id.hdrCardSsh, R.id.bodyCardSsh, R.id.arrowCardSsh)
        )
        for ((hdrId, bodyId, arrowId) in pairs) {
            val hdr = findViewById<LinearLayout>(hdrId)
            val body = findViewById<View>(bodyId)
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
        layoutSettingsMenu.visibility = View.GONE
        layoutSettingsAutoPing.visibility = View.GONE
        layoutSettingsVpnBehavior.visibility = View.GONE
        layoutSettingsTunnelCore.visibility = View.GONE
        layoutPengaturan.visibility = View.GONE

        btnBackFromEditor.visibility = View.GONE
        btnAddProfileMenu.visibility = View.GONE
        btnClearSelectedApps.visibility = View.GONE
        btnSaveEditorFab.hide()

        when (target) {
            "beranda" -> {
                topBarMain.visibility = View.VISIBLE
                topBarSettings.visibility = View.GONE
                ivBrandLogo.visibility = View.VISIBLE
                tvTopTitle.text = "Custom Tunnel"
                tvTopTitle.setTextColor(Color.parseColor("#F8FAFC"))
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
                refreshAllSettingsLabels()
            }
            "profil" -> {
                topBarMain.visibility = View.VISIBLE
                topBarSettings.visibility = View.GONE
                ivBrandLogo.visibility = View.GONE
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
                ivBrandLogo.visibility = View.GONE
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
                topBarMain.visibility = View.VISIBLE
                topBarSettings.visibility = View.GONE
                ivBrandLogo.visibility = View.GONE
                tvTopTitle.text = "Pengaturan"
                tvTopTitle.setTextColor(Color.WHITE)
                tvHeaderStatus.visibility = View.GONE
                tabLayout.visibility = View.GONE
                btnConnect.hide()
                layoutSettingsMenu.visibility = View.VISIBLE
                pillPengaturan.setBackgroundResource(R.drawable.bg_nav_pill)
                txtNavPengaturan.setTextColor(Color.parseColor("#E5E7EB"))
                refreshAllSettingsLabels()
            }
            "settings_autoping" -> {
                topBarMain.visibility = View.GONE
                topBarSettings.visibility = View.VISIBLE
                tvSettingsSubTitle.text = "Auto Ping"
                tabLayout.visibility = View.GONE
                btnConnect.hide()
                layoutSettingsAutoPing.visibility = View.VISIBLE
                pillPengaturan.setBackgroundResource(R.drawable.bg_nav_pill)
                txtNavPengaturan.setTextColor(Color.parseColor("#E5E7EB"))
                refreshAllSettingsLabels()
            }
            "settings_vpn" -> {
                topBarMain.visibility = View.GONE
                topBarSettings.visibility = View.VISIBLE
                tvSettingsSubTitle.text = "Perilaku VPN"
                tabLayout.visibility = View.GONE
                btnConnect.hide()
                layoutSettingsVpnBehavior.visibility = View.VISIBLE
                pillPengaturan.setBackgroundResource(R.drawable.bg_nav_pill)
                txtNavPengaturan.setTextColor(Color.parseColor("#E5E7EB"))
                refreshAllSettingsLabels()
            }
            "settings_tunnel" -> {
                topBarMain.visibility = View.GONE
                topBarSettings.visibility = View.VISIBLE
                tvSettingsSubTitle.text = "SSH & SlowDNS"
                tabLayout.visibility = View.GONE
                btnConnect.hide()
                layoutSettingsTunnelCore.visibility = View.VISIBLE
                pillPengaturan.setBackgroundResource(R.drawable.bg_nav_pill)
                txtNavPengaturan.setTextColor(Color.parseColor("#E5E7EB"))
                refreshAllSettingsLabels()
            }
            "settings_apps" -> {
                topBarMain.visibility = View.GONE
                topBarSettings.visibility = View.VISIBLE
                tvSettingsSubTitle.text = "VPN per aplikasi"
                btnClearSelectedApps.visibility = View.VISIBLE
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
        val defUdpgw = getSharedPreferences("app_settings", MODE_PRIVATE).getString("default_udpgw", "7300") ?: "7300"
        editorProtocol = profile?.optString("protocol", "SSH") ?: "SSH"
        if (profile == null) {
            editingProfileId = null
            val list = getProfilesArray()
            etEditProfileName.setText("ss${list.length() + 1}")
            usePayloadFlag = false
            useTlsFlag = false
            useSlowDnsFlag = true
            syncEditorMethodString()
            etCustomPayload.setText("")
            etRemoteProxy.setText("")
            etTlsSni.setText("")
            spDnsType.setSelection(0)
            etDns.setText("1.1.1.1")
            etNs.setText("")
            etPubkey.setText("")
            etSessions.setText("1")
            etSshHostPort.setText("127.0.0.1:22")
            etSshUser.setText("")
            etSshPass.setText("")
            etUdpgw.setText(defUdpgw)
        } else {
            editingProfileId = profile.optString("id")
            etEditProfileName.setText(profile.optString("name", "ss"))
            val savedMethod = profile.optString("method", "SlowDNS")
            usePayloadFlag = savedMethod.contains("Payload", ignoreCase = true)
            useTlsFlag = savedMethod.contains("TLS", ignoreCase = true)
            useSlowDnsFlag = savedMethod.contains("SlowDNS", ignoreCase = true)
            syncEditorMethodString()
            etCustomPayload.setText(profile.optString("payload", ""))
            etRemoteProxy.setText(profile.optString("proxy", ""))
            etTlsSni.setText(profile.optString("sni", ""))
            spDnsType.setSelection(profile.optInt("dnsTypeIdx", 0).coerceIn(0, dnsTypes.lastIndex))
            etDns.setText(profile.optString("dns", "1.1.1.1"))
            etNs.setText(profile.optString("ns", ""))
            etPubkey.setText(profile.optString("pubkey", ""))
            etSessions.setText(profile.optString("sessions", "1"))
            etSshHostPort.setText(profile.optString("sshHostPort", "127.0.0.1:22"))
            etSshUser.setText(profile.optString("user", ""))
            etSshPass.setText(profile.optString("pass", ""))
            etUdpgw.setText(profile.optString("udpgw", defUdpgw))
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

        fun styleChip(btn: Button, active: Boolean) {
            btn.backgroundTintList = ColorStateList.valueOf(Color.parseColor(if (active) "#2C3545" else "#161B24"))
            btn.setTextColor(if (active) Color.WHITE else Color.parseColor("#9CA3AF"))
        }

        val isDirect = !usePayloadFlag && !useTlsFlag && !useSlowDnsFlag
        styleChip(btnMethodPayload, usePayloadFlag)
        styleChip(btnMethodTls, useTlsFlag)
        styleChip(btnMethodSlowDns, useSlowDnsFlag)
        styleChip(btnMethodDirect, isDirect)

        cardPayloadSection.visibility = if (usePayloadFlag) View.VISIBLE else View.GONE
        cardTlsSection.visibility = if (useTlsFlag) View.VISIBLE else View.GONE
        cardSlowDnsSection.visibility = if (useSlowDnsFlag) View.VISIBLE else View.GONE
    }

    private fun syncEditorMethodString() {
        val parts = mutableListOf<String>()
        if (usePayloadFlag) parts.add("Payload")
        if (useTlsFlag) parts.add("TLS")
        if (useSlowDnsFlag) parts.add("SlowDNS")
        editorMethod = if (parts.isEmpty()) "Direct" else parts.joinToString(" + ")
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
            put("payload", etCustomPayload.text.toString())
            put("proxy", etRemoteProxy.text.toString().trim())
            put("sni", etTlsSni.text.toString().trim())
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

        Toast.makeText(this, "Profil '$name' disimpan & aktif", Toast.LENGTH_SHORT).show()
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
        val hostInfo = when {
            method.contains("SlowDNS", ignoreCase = true) -> active.optString("ns", "").ifEmpty { active.optString("sshHostPort", "") }
            active.optString("proxy", "").isNotEmpty() -> active.optString("proxy", "")
            else -> active.optString("sshHostPort", "")
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
                    Toast.makeText(this@MainActivity, "Profil '$name' dipilih", Toast.LENGTH_SHORT).show()
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

            val btnDup = ImageButton(this).apply {
                setImageResource(R.drawable.ic_action_copy)
                setBackgroundColor(Color.TRANSPARENT)
                setPadding(18, 18, 18, 18)
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

            val btnEdit = ImageButton(this).apply {
                setImageResource(R.drawable.ic_action_edit)
                setBackgroundColor(Color.TRANSPARENT)
                setPadding(18, 18, 18, 18)
                setOnClickListener { openProfileEditor(item) }
            }

            val btnDel = ImageButton(this).apply {
                setImageResource(R.drawable.ic_action_delete)
                setBackgroundColor(Color.TRANSPARENT)
                setPadding(18, 18, 18, 18)
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
        Toast.makeText(this, "JSON profil '${active.optString("name")}' disalin!", Toast.LENGTH_SHORT).show()
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
            Toast.makeText(this, "Profil '${obj.optString("name")}' diimpor!", Toast.LENGTH_SHORT).show()
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
        val summary = if (mode == "all") "Semua aplikasi" else "$modeTitle (${selected.size})"
        tvSplitModeDesc.text = modeTitle
        tvSelectedAppsCount.text = "${selected.size} dipilih"
        tvMenuSplitDesc.text = summary
        valVpnSplitSub.text = summary
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
            putExtra("payload", active.optString("payload", ""))
            putExtra("proxy", active.optString("proxy", ""))
            putExtra("sni", active.optString("sni", ""))
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
                            tvLocalIp.text = "${addr.hostAddress}"
                            return
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        tvLocalIp.text = "127.0.0.1"
    }

    private fun updateButtonState() {
        when (TunnelVpnService.connectionState) {
            TunnelVpnService.STATE_CONNECTED -> {
                btnConnect.setImageResource(R.drawable.ic_stop)
                btnConnect.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#DC2626"))
                tvHeaderStatus.text = "● Connected"
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
                btnConnect.setImageResource(R.drawable.ic_stop)
                btnConnect.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#D97706"))
                tvHeaderStatus.text = "● Connecting"
                tvHeaderStatus.setTextColor(Color.parseColor("#FBBF24"))
                tvHeaderStatus.setBackgroundResource(R.drawable.bg_pill_offline)
                tvStatusDotLabel.text = "● Menghubungkan..."
                tvStatusDotLabel.setTextColor(Color.parseColor("#FBBF24"))
                isTrafficMonitorActive = false
            }
            else -> {
                btnConnect.setImageResource(R.drawable.ic_play)
                btnConnect.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#2563EB"))
                tvHeaderStatus.text = "● Offline"
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
