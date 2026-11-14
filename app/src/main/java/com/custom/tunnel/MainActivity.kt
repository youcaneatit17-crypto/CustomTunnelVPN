package com.custom.tunnel

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var btnConnect: Button
    private lateinit var tvLogs: TextView
    private lateinit var tabLayout: TabLayout
    private lateinit var layoutUtama: ScrollView
    private lateinit var layoutLog: ScrollView

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startTunnelService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnConnect = findViewById(R.id.btnConnect)
        tvLogs = findViewById(R.id.tvLogs)
        tabLayout = findViewById(R.id.tabLayout)
        layoutUtama = findViewById(R.id.layoutUtama)
        layoutLog = findViewById(R.id.layoutLog)

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

        lifecycleScope.launch {
            TunnelLogger.logs.collect { spannedLog ->
                tvLogs.text = spannedLog
                layoutLog.post { layoutLog.fullScroll(View.FOCUS_DOWN) }
            }
        }

        btnConnect.setOnClickListener {
            if (TunnelVpnService.isRunning) {
                val stopIntent = Intent(this, TunnelVpnService::class.java).apply {
                    action = TunnelVpnService.ACTION_STOP
                }
                startService(stopIntent)
                btnConnect.text = "HUBUNGKAN"
                btnConnect.setBackgroundColor(0xFF2E7D32.toInt())
            } else {
                val prepareIntent = VpnService.prepare(this)
                if (prepareIntent != null) {
                    vpnPermissionLauncher.launch(prepareIntent)
                } else {
                    startTunnelService()
                }
            }
        }
    }

    private fun startTunnelService() {
        TunnelLogger.clear()
        TunnelLogger.log("Berjalan di ${Build.MODEL} (${Build.DEVICE})")
        TunnelLogger.log("Android ${Build.VERSION.RELEASE} API-${Build.VERSION.SDK_INT} (${Build.SUPPORTED_ABIS.firstOrNull()})")
        TunnelLogger.log("Memulai Profiles...")

        tabLayout.getTabAt(1)?.select()

        val intent = Intent(this, TunnelVpnService::class.java).apply {
            action = TunnelVpnService.ACTION_START
            putExtra("dns", findViewById<EditText>(R.id.etDnsResolver).text.toString().trim())
            putExtra("ns", findViewById<EditText>(R.id.etNameserver).text.toString().trim())
            putExtra("pubkey", findViewById<EditText>(R.id.etPublicKey).text.toString().trim())
            putExtra("user", findViewById<EditText>(R.id.etSshUser).text.toString().trim())
            putExtra("pass", findViewById<EditText>(R.id.etSshPass).text.toString().trim())
            putExtra("udpgw", findViewById<EditText>(R.id.etUdpGwPort).text.toString().trim())
        }
        startForegroundService(intent)
        btnConnect.text = "PUTUSKAN"
        btnConnect.setBackgroundColor(0xFFC62828.toInt())
    }
}
