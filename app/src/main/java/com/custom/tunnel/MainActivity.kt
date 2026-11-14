package com.custom.tunnel

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.text.Html
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.tabs.TabLayout

class MainActivity : AppCompatActivity() {

    private lateinit var etDns: EditText
    private lateinit var etNs: EditText
    private lateinit var etPubkey: EditText
    private lateinit var etSshHostPort: EditText
    private lateinit var etSshUser: EditText
    private lateinit var etSshPass: EditText
    private lateinit var etUdpgw: EditText
    private lateinit var btnConnect: Button
    private lateinit var tvLogs: TextView
    private lateinit var layoutUtama: ScrollView
    private lateinit var layoutLog: ScrollView
    private lateinit var tabLayout: TabLayout

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

        etDns = findViewById(R.id.etDns)
        etNs = findViewById(R.id.etNs)
        etPubkey = findViewById(R.id.etPubkey)
        etSshHostPort = findViewById(R.id.etSshHostPort)
        etSshUser = findViewById(R.id.etSshUser)
        etSshPass = findViewById(R.id.etSshPass)
        etUdpgw = findViewById(R.id.etUdpgw)
        btnConnect = findViewById(R.id.btnConnect)
        tvLogs = findViewById(R.id.tvLogs)
        layoutUtama = findViewById(R.id.layoutUtama)
        layoutLog = findViewById(R.id.layoutLog)
        tabLayout = findViewById(R.id.tabLayout)

        loadSavedConfig()

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                if (tab?.position == 0) {
                    layoutUtama.visibility = View.VISIBLE
                    layoutLog.visibility = View.GONE
                } else {
                    layoutUtama.visibility = View.GONE
                    layoutLog.visibility = View.VISIBLE
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        TunnelLogger.onLogUpdated = {
            runOnUiThread {
                tvLogs.text = Html.fromHtml(TunnelLogger.getHtmlLogs(), Html.FROM_HTML_MODE_LEGACY)
                layoutLog.post { layoutLog.fullScroll(View.FOCUS_DOWN) }
                updateButtonState()
            }
        }

        btnConnect.setOnClickListener {
            if (TunnelVpnService.isRunning) {
                val stopIntent = Intent(this, TunnelVpnService::class.java).apply {
                    action = TunnelVpnService.ACTION_STOP
                }
                startService(stopIntent)
                btnConnect.text = "HUBUNGKAN"
                btnConnect.backgroundTintList = getColorStateList(android.R.color.holo_green_dark)
            } else {
                saveConfig()
                tabLayout.getTabAt(1)?.select()
                prepareAndStartVpn()
            }
        }
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
        val intent = Intent(this, TunnelVpnService::class.java).apply {
            action = TunnelVpnService.ACTION_START
            putExtra("dns", etDns.text.toString().trim())
            putExtra("ns", etNs.text.toString().trim())
            putExtra("pubkey", etPubkey.text.toString().trim())
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
        btnConnect.text = "PUTUSKAN"
        btnConnect.backgroundTintList = getColorStateList(android.R.color.holo_red_dark)
    }

    private fun updateButtonState() {
        if (TunnelVpnService.isRunning) {
            btnConnect.text = "PUTUSKAN"
            btnConnect.backgroundTintList = getColorStateList(android.R.color.holo_red_dark)
        } else {
            btnConnect.text = "HUBUNGKAN"
            btnConnect.backgroundTintList = getColorStateList(android.R.color.holo_green_dark)
        }
    }

    private fun saveConfig() {
        getSharedPreferences("tunnel_cfg", MODE_PRIVATE).edit().apply {
            putString("dns", etDns.text.toString())
            putString("ns", etNs.text.toString())
            putString("pubkey", etPubkey.text.toString())
            putString("sshHostPort", etSshHostPort.text.toString())
            putString("user", etSshUser.text.toString())
            putString("pass", etSshPass.text.toString())
            putString("udpgw", etUdpgw.text.toString())
            apply()
        }
    }

    private fun loadSavedConfig() {
        val prefs = getSharedPreferences("tunnel_cfg", MODE_PRIVATE)
        etDns.setText(prefs.getString("dns", "8.8.8.8"))
        etNs.setText(prefs.getString("ns", ""))
        etPubkey.setText(prefs.getString("pubkey", ""))
        etSshHostPort.setText(prefs.getString("sshHostPort", "127.0.0.1:109"))
        etSshUser.setText(prefs.getString("user", ""))
        etSshPass.setText(prefs.getString("pass", ""))
        etUdpgw.setText(prefs.getString("udpgw", "7300"))
    }
}
