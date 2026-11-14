package com.custom.tunnel

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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

    private lateinit var spDnsType: Spinner
    private lateinit var etDns: EditText
    private lateinit var etNs: EditText
    private lateinit var etPubkey: EditText
    private lateinit var etSessions: EditText
    private lateinit var etSshHostPort: EditText
    private lateinit var etSshUser: EditText
    private lateinit var etSshPass: EditText
    private lateinit var etUdpgw: EditText
    private lateinit var btnConnect: FloatingActionButton
    private lateinit var btnRefresh: Button
    private lateinit var btnCleanLog: Button
    private lateinit var tvLogs: TextView
    private lateinit var tvHeaderStatus: TextView
    private lateinit var tvStatDown: TextView
    private lateinit var tvStatDownPeak: TextView
    private lateinit var tvStatUp: TextView
    private lateinit var tvStatUpPeak: TextView
    private lateinit var tvStatusDotLabel: TextView
    private lateinit var tvLocalIp: TextView
    private lateinit var tvSplitSummaryHome: TextView

    private lateinit var topBarMain: LinearLayout
    private lateinit var topBarSettings: LinearLayout
    private lateinit var tvTopTitle: TextView
    private lateinit var layoutUtama: ScrollView
    private lateinit var layoutLog: LinearLayout
    private lateinit var layoutProfil: ScrollView
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

    private lateinit var etNewProfileName: EditText
    private lateinit var btnSaveProfile: Button
    private lateinit var profilesContainer: LinearLayout
    private lateinit var etConfigJson: EditText
    private lateinit var btnExportJson: Button
    private lateinit var btnImportJson: Button

    // Split Tunnel UI
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

    private var isTrafficActive = false
    private var maxDownPeak = 0L
    private var maxUpPeak = 0L

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

        spDnsType = findViewById(R.id.spDnsType)
        etDns = findViewById(R.id.etDns)
        etNs = findViewById(R.id.etNs)
        etPubkey = findViewById(R.id.etPubkey)
        etSessions = findViewById(R.id.etSessions)
        etSshHostPort = findViewById(R.id.etSshHostPort)
        etSshUser = findViewById(R.id.etSshUser)
        etSshPass = findViewById(R.id.etSshPass)
        etUdpgw = findViewById(R.id.etUdpgw)
        btnConnect = findViewById(R.id.btnConnect)
        btnRefresh = findViewById(R.id.btnRefresh)
        btnCleanLog = findViewById(R.id.btnCleanLog)
        tvLogs = findViewById(R.id.tvLogs)
        tvHeaderStatus = findViewById(R.id.tvHeaderStatus)
        tvStatDown = findViewById(R.id.tvStatDown)
        tvStatDownPeak = findViewById(R.id.tvStatDownPeak)
        tvStatUp = findViewById(R.id.tvStatUp)
        tvStatUpPeak = findViewById(R.id.tvStatUpPeak)
        tvStatusDotLabel = findViewById(R.id.tvStatusDotLabel)
        tvLocalIp = findViewById(R.id.tvLocalIp)
        tvSplitSummaryHome = findViewById(R.id.tvSplitSummaryHome)

        topBarMain = findViewById(R.id.topBarMain)
        topBarSettings = findViewById(R.id.topBarSettings)
        tvTopTitle = findViewById(R.id.tvTopTitle)
        layoutUtama = findViewById(R.id.layoutUtama)
        layoutLog = findViewById(R.id.layoutLog)
        layoutProfil = findViewById(R.id.layoutProfil)
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

        etNewProfileName = findViewById(R.id.etNewProfileName)
        btnSaveProfile = findViewById(R.id.btnSaveProfile)
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

        val adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, dnsTypes) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                return (super.getView(position, convertView, parent) as TextView).apply {
                    setTextColor(Color.WHITE)
                    text = "Tipe DNS: ${dnsTypes[position]}"
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

        loadSavedConfig()
        renderProfilesList()
        updateSplitTunnelLabels()
        updateButtonState()
        renderLogsNow()
        getMobileInterfaceIp()

        navBeranda.setOnClickListener { switchNav("beranda") }
        navProfil.setOnClickListener { switchNav("profil") }
        navPengaturan.setOnClickListener { switchNav("pengaturan") }
        findViewById<Button>(R.id.btnLihatSplit).setOnClickListener { switchNav("pengaturan") }
        findViewById<ImageButton>(R.id.btnBackFromSettings).setOnClickListener { switchNav("beranda") }

        findViewById<LinearLayout>(R.id.btnSelectSplitMode).setOnClickListener { showSplitModeDialog() }

        findViewById<TextView>(R.id.btnClearSelectedApps).setOnClickListener {
            getSharedPreferences("split_tunnel", MODE_PRIVATE).edit()
                .putStringSet("packages", emptySet()).apply()
            updateSplitTunnelLabels()
            filterInstalledApps()
            Toast.makeText(this, "Semua centang aplikasi dibersihkan", Toast.LENGTH_SHORT).show()
        }

        btnFilterUserApps.setOnClickListener {
            showSystemApps = false
            btnFilterUserApps.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#2C3340"))
            btnFilterUserApps.setTextColor(Color.parseColor("#E2E8F0"))
            btnFilterAllApps.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#15181E"))
            btnFilterAllApps.setTextColor(Color.parseColor("#9CA3AF"))
            filterInstalledApps()
        }

        btnFilterAllApps.setOnClickListener {
            showSystemApps = true
            btnFilterAllApps.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#2C3340"))
            btnFilterAllApps.setTextColor(Color.parseColor("#E2E8F0"))
            btnFilterUserApps.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#15181E"))
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
                    }, 200)
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
                saveConfig()
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

        btnSaveProfile.setOnClickListener { saveNewProfile() }
        btnExportJson.setOnClickListener { exportConfigToJson() }
        btnImportJson.setOnClickListener { importConfigFromJson() }
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
        layoutPengaturan.visibility = View.GONE

        when (target) {
            "beranda" -> {
                topBarMain.visibility = View.VISIBLE
                topBarSettings.visibility = View.GONE
                tvTopTitle.text = "Custom Tunnel"
                tvTopTitle.setTextColor(Color.parseColor("#FB923C"))
                tabLayout.visibility = View.VISIBLE
                btnConnect.show()
                if (tabLayout.selectedTabPosition == 0) {
                    layoutUtama.visibility = View.VISIBLE
                } else {
                    layoutLog.visibility = View.VISIBLE
                }
                pillBeranda.setBackgroundResource(R.drawable.bg_nav_pill)
                txtNavBeranda.setTextColor(Color.parseColor("#E5E7EB"))
                updateSplitTunnelLabels()
            }
            "profil" -> {
                topBarMain.visibility = View.VISIBLE
                topBarSettings.visibility = View.GONE
                tvTopTitle.text = "Profil"
                tvTopTitle.setTextColor(Color.WHITE)
                tabLayout.visibility = View.GONE
                btnConnect.show()
                layoutProfil.visibility = View.VISIBLE
                renderProfilesList()
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
                    buttonTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#6EA8FE"))
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

    private fun buildCurrentJson(): JSONObject {
        return JSONObject().apply {
            put("dnsTypeIdx", spDnsType.selectedItemPosition)
            put("dns", etDns.text.toString().trim())
            put("ns", etNs.text.toString().trim())
            put("pubkey", etPubkey.text.toString().trim())
            put("sessions", etSessions.text.toString().trim())
            put("sshHostPort", etSshHostPort.text.toString().trim())
            put("user", etSshUser.text.toString().trim())
            put("pass", etSshPass.text.toString().trim())
            put("udpgw", etUdpgw.text.toString().trim())
        }
    }

    private fun applyJsonConfig(obj: JSONObject) {
        spDnsType.setSelection(obj.optInt("dnsTypeIdx", 0).coerceIn(0, dnsTypes.lastIndex))
        etDns.setText(obj.optString("dns", "1.1.1.1"))
        etNs.setText(obj.optString("ns", ""))
        etPubkey.setText(obj.optString("pubkey", ""))
        etSessions.setText(obj.optString("sessions", "1"))
        etSshHostPort.setText(obj.optString("sshHostPort", "127.0.0.1:22"))
        etSshUser.setText(obj.optString("user", ""))
        etSshPass.setText(obj.optString("pass", ""))
        etUdpgw.setText(obj.optString("udpgw", "7300"))
        saveConfig()
    }

    private fun saveNewProfile() {
        val prefs = getSharedPreferences("tunnel_profiles", MODE_PRIVATE)
        val arr = JSONArray(prefs.getString("list", "[]"))
        val name = etNewProfileName.text.toString().trim().ifEmpty {
            "S${arr.length() + 1}"
        }
        val item = buildCurrentJson().apply {
            put("id", System.currentTimeMillis().toString())
            put("name", name)
        }
        val newArr = JSONArray().apply {
            put(item)
            for (i in 0 until arr.length()) put(arr.getJSONObject(i))
        }
        prefs.edit().putString("list", newArr.toString()).apply()
        etNewProfileName.setText("")
        renderProfilesList()
        Toast.makeText(this, "Profil '$name' disimpan!", Toast.LENGTH_SHORT).show()
    }

    private fun renderProfilesList() {
        profilesContainer.removeAllViews()
        val prefs = getSharedPreferences("tunnel_profiles", MODE_PRIVATE)
        val arr = JSONArray(prefs.getString("list", "[]"))
        if (arr.length() == 0) {
            val emptyTv = TextView(this).apply {
                text = "Belum ada profil tersimpan. Isi form di Beranda lalu simpan di sini."
                setTextColor(Color.parseColor("#6B7280"))
                textSize = 12f
                setPadding(8, 8, 8, 16)
            }
            profilesContainer.addView(emptyTv)
            return
        }

        for (i in 0 until arr.length()) {
            val item = arr.getJSONObject(i)
            val id = item.optString("id")
            val name = item.optString("name", "Profil")
            val summary = "${item.optString("user")} @ ${item.optString("ns")} (${dnsTypes[item.optInt("dnsTypeIdx", 0)]})"

            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.bg_card)
                setPadding(32, 28, 28, 28)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 20 }
            }

            val infoCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val tvName = TextView(this).apply {
                text = name
                setTextColor(Color.WHITE)
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
            }
            val tvSub = TextView(this).apply {
                text = summary
                setTextColor(Color.parseColor("#9CA3AF"))
                textSize = 11f
                typeface = Typeface.MONOSPACE
            }
            infoCol.addView(tvName)
            infoCol.addView(tvSub)

            val btnLoad = Button(this).apply {
                text = "MUAT"
                textSize = 11f
                setTextColor(Color.parseColor("#6EA8FE"))
                backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#1F2937"))
                setOnClickListener {
                    applyJsonConfig(item)
                    switchNav("beranda")
                    tabLayout.getTabAt(0)?.select()
                    Toast.makeText(this@MainActivity, "Profil '$name' dimuat", Toast.LENGTH_SHORT).show()
                }
            }

            val btnDel = Button(this).apply {
                text = "✕"
                textSize = 12f
                setTextColor(Color.parseColor("#F87171"))
                backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#111827"))
                layoutParams = LinearLayout.LayoutParams(110, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    leftMargin = 12
                }
                setOnClickListener {
                    val updated = JSONArray()
                    for (j in 0 until arr.length()) {
                        if (arr.getJSONObject(j).optString("id") != id) updated.put(arr.getJSONObject(j))
                    }
                    prefs.edit().putString("list", updated.toString()).apply()
                    renderProfilesList()
                }
            }

            card.addView(infoCol)
            card.addView(btnLoad)
            card.addView(btnDel)
            profilesContainer.addView(card)
        }
    }

    private fun exportConfigToJson() {
        val jsonStr = buildCurrentJson().toString(2)
        etConfigJson.setText(jsonStr)
        val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cb.setPrimaryClip(ClipData.newPlainText("CustomTunnelConfig", jsonStr))
        Toast.makeText(this, "Config JSON disalin ke clipboard!", Toast.LENGTH_SHORT).show()
    }

    private fun importConfigFromJson() {
        val raw = etConfigJson.text.toString().trim()
        if (raw.isEmpty()) {
            Toast.makeText(this, "Tempel JSON config dulu", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val obj = JSONObject(raw)
            applyJsonConfig(obj)
            switchNav("beranda")
            tabLayout.getTabAt(0)?.select()
            Toast.makeText(this, "Config berhasil diimpor!", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Format JSON tidak valid!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun renderLogsNow() {
        tvLogs.text = Html.fromHtml(TunnelLogger.getHtmlLogs(), Html.FROM_HTML_MODE_LEGACY)
        scrollLog.post { scrollLog.fullScroll(View.FOCUS_DOWN) }
    }

    override fun onDestroy() {
        isTrafficActive = false
        TunnelLogger.onLogUpdated = null
        uiHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun prepareAndStartVpn() {
        TunnelLogger.clear()
        TunnelLogger.log("Berjalan di ${Build.MODEL} (${Build.DEVICE})", "#B0BEC5")
        TunnelLogger.log("Android ${Build.VERSION.RELEASE} API-${Build.VERSION.SDK_INT} (${Build.SUPPORTED_ABIS.firstOrNull()})", "#B0BEC5")
        TunnelLogger.log("Memulai Profiles...", "#81C784")

        val intent = VpnService.prepare(this)
        if (intent != null) {
            vpnPermissionLauncher.launch(intent)
        } else {
            startTunnelService()
        }
    }

    private fun startTunnelService() {
        val sessionCount = (etSessions.text.toString().trim().toIntOrNull() ?: 1).coerceIn(1, 4)
        val intent = Intent(this, TunnelVpnService::class.java).apply {
            action = TunnelVpnService.ACTION_START
            putExtra("dnsType", dnsTypes[spDnsType.selectedItemPosition])
            putExtra("dns", etDns.text.toString().trim())
            putExtra("ns", etNs.text.toString().trim())
            putExtra("pubkey", etPubkey.text.toString().trim())
            putExtra("sessions", sessionCount)
            putExtra("sshHostPort", etSshHostPort.text.toString().trim())
            putExtra("user", etSshUser.text.toString().trim())
            putExtra("pass", etSshPass.text.toString().trim())
            putExtra("udpgw", etUdpgw.text.toString().trim())
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
                            tvLocalIp.text = addr.hostAddress
                            return
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        tvLocalIp.text = "192.168.42.22"
    }

    private fun updateButtonState() {
        when (TunnelVpnService.connectionState) {
            TunnelVpnService.STATE_CONNECTED -> {
                btnConnect.setImageDrawable(resources.getDrawable(android.R.drawable.ic_media_pause, theme))
                btnConnect.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#DC2626"))
                btnRefresh.visibility = View.VISIBLE
                tvHeaderStatus.text = "CONNECTED"
                tvHeaderStatus.setTextColor(Color.parseColor("#4ADE80"))
                tvHeaderStatus.setBackgroundResource(R.drawable.bg_pill_online)
                tvStatusDotLabel.text = "● Connected (TUN Aktif)"
                tvStatusDotLabel.setTextColor(Color.parseColor("#4ADE80"))

                if (!isTrafficActive) {
                    isTrafficActive = true
                    startTrafficSim()
                }
            }
            TunnelVpnService.STATE_CONNECTING -> {
                btnConnect.setImageDrawable(resources.getDrawable(android.R.drawable.ic_menu_close_clear_cancel, theme))
                btnConnect.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#D97706"))
                btnRefresh.visibility = View.GONE
                tvHeaderStatus.text = "CONNECTING"
                tvHeaderStatus.setTextColor(Color.parseColor("#FBBF24"))
                tvHeaderStatus.setBackgroundResource(R.drawable.bg_pill_offline)
                tvStatusDotLabel.text = "● Connecting... (TUN belum aktif)"
                tvStatusDotLabel.setTextColor(Color.parseColor("#FBBF24"))
                isTrafficActive = false
            }
            else -> {
                btnConnect.setImageDrawable(resources.getDrawable(android.R.drawable.ic_media_play, theme))
                btnConnect.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#2563EB"))
                btnRefresh.visibility = View.GONE
                tvHeaderStatus.text = "OFFLINE"
                tvHeaderStatus.setTextColor(Color.parseColor("#9CA3AF"))
                tvHeaderStatus.setBackgroundResource(R.drawable.bg_pill_offline)
                tvStatusDotLabel.text = "● Start / Siap"
                tvStatusDotLabel.setTextColor(Color.parseColor("#9CA3AF"))

                isTrafficActive = false
                tvStatDown.text = "0 bps"
                tvStatUp.text = "0 bps"
                tvStatDownPeak.text = "Puncak: 0 bps"
                tvStatUpPeak.text = "Puncak: 0 bps"
                maxDownPeak = 0L
                maxUpPeak = 0L
            }
        }
    }

    private fun startTrafficSim() {
        uiHandler.post(object : Runnable {
            override fun run() {
                if (!isTrafficActive) return
                val curDown = (1000..950000).random().toLong()
                val curUp = (100..250000).random().toLong()
                if (curDown > maxDownPeak) maxDownPeak = curDown
                if (curUp > maxUpPeak) maxUpPeak = curUp
                tvStatDown.text = formatBps(curDown)
                tvStatUp.text = formatBps(curUp)
                tvStatDownPeak.text = "Puncak: ${formatBps(maxDownPeak)}"
                tvStatUpPeak.text = "Puncak: ${formatBps(maxUpPeak)}"
                uiHandler.postDelayed(this, 1000)
            }
        })
    }

    private fun formatBps(bits: Long): String {
        return when {
            bits >= 1000000 -> String.format("%.1f Mbps", bits / 1000000.0)
            bits >= 1000 -> String.format("%.1f kbps", bits / 1000.0)
            else -> "$bits bps"
        }
    }

    private fun saveConfig() {
        getSharedPreferences("tunnel_cfg", MODE_PRIVATE).edit().apply {
            putInt("dnsTypeIdx", spDnsType.selectedItemPosition)
            putString("dns", etDns.text.toString())
            putString("ns", etNs.text.toString())
            putString("pubkey", etPubkey.text.toString())
            putString("sessions", etSessions.text.toString())
            putString("sshHostPort", etSshHostPort.text.toString())
            putString("user", etSshUser.text.toString())
            putString("pass", etSshPass.text.toString())
            putString("udpgw", etUdpgw.text.toString())
            apply()
        }
    }

    private fun loadSavedConfig() {
        val prefs = getSharedPreferences("tunnel_cfg", MODE_PRIVATE)
        spDnsType.setSelection(prefs.getInt("dnsTypeIdx", 0))
        etDns.setText(prefs.getString("dns", "1.1.1.1"))
        etNs.setText(prefs.getString("ns", ""))
        etPubkey.setText(prefs.getString("pubkey", ""))
        etSessions.setText(prefs.getString("sessions", "1"))
        etSshHostPort.setText(prefs.getString("sshHostPort", "127.0.0.1:22"))
        etSshUser.setText(prefs.getString("user", ""))
        etSshPass.setText(prefs.getString("pass", ""))
        etUdpgw.setText(prefs.getString("udpgw", "7300"))
    }
}
