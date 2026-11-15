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
