package com.vpn

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.net.VpnService
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var etServer   : EditText
    private lateinit var etPort     : EditText
    private lateinit var etPassword : EditText
    private lateinit var etTunIp    : EditText
    private lateinit var cbRouteAll : CheckBox
    private lateinit var btnConnect : Button
    private lateinit var tvStatus   : TextView
    private lateinit var prefs      : SharedPreferences

    private var connected = false
    private val VPN_REQUEST = 1

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val ok = intent.getBooleanExtra("connected", false)
            if (ok) {
                tvStatus.text = "✅ متصل / Connected"
                tvStatus.setTextColor(0xFF2E7D32.toInt())
            } else {
                val err = intent.getStringExtra("error") ?: "Unknown error"
                tvStatus.text = "❌ خطأ: $err"
                tvStatus.setTextColor(0xFFC62828.toInt())
                btnConnect.text = "اتصال / Connect"
                connected = false
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("vpn_prefs", Context.MODE_PRIVATE)

        // Explicit casts needed for API 23 (findViewById returns View, not generic T)
        etServer   = findViewById(R.id.et_server)    as EditText
        etPort     = findViewById(R.id.et_port)      as EditText
        etPassword = findViewById(R.id.et_password)  as EditText
        etTunIp    = findViewById(R.id.et_tun_ip)    as EditText
        cbRouteAll = findViewById(R.id.cb_route_all) as CheckBox
        btnConnect = findViewById(R.id.btn_connect)  as Button
        tvStatus   = findViewById(R.id.tv_status)    as TextView

        loadPrefs()

        btnConnect.setOnClickListener {
            if (connected) stopVpn() else requestVpnPermission()
        }
    }

    override fun onResume() {
        super.onResume()
        @Suppress("UnspecifiedRegisterReceiverFlag")
        registerReceiver(statusReceiver, IntentFilter("com.vpn.VPN_STATUS"))
    }

    override fun onPause() {
        super.onPause()
        savePrefs()
        try { unregisterReceiver(statusReceiver) } catch (e: Exception) { /* ok */ }
    }

    private fun requestVpnPermission() {
        val intent = VpnService.prepare(this)
        if (intent != null) startActivityForResult(intent, VPN_REQUEST)
        else onActivityResult(VPN_REQUEST, RESULT_OK, null)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == VPN_REQUEST) {
            if (resultCode == RESULT_OK) startVpn()
            else setStatus("تم رفض إذن VPN / Permission denied", false)
        }
    }

    private fun startVpn() {
        val server   = etServer.text.toString().trim()
        val portStr  = etPort.text.toString().trim()
        val password = etPassword.text.toString()
        val tunIp    = etTunIp.text.toString().trim()

        if (server.isEmpty() || password.isEmpty() || tunIp.isEmpty()) {
            setStatus("الرجاء ملء جميع الحقول / Fill all fields", false)
            return
        }

        startService(
            Intent(this, MyVpnService::class.java).apply {
                action = MyVpnService.ACTION_CONNECT
                putExtra(MyVpnService.EXTRA_SERVER,    server)
                putExtra(MyVpnService.EXTRA_PORT,      portStr.toIntOrNull() ?: 51820)
                putExtra(MyVpnService.EXTRA_PASSWORD,  password)
                putExtra(MyVpnService.EXTRA_TUN_IP,    tunIp)
                putExtra(MyVpnService.EXTRA_ROUTE_ALL, cbRouteAll.isChecked)
            }
        )
        connected = true
        tvStatus.setTextColor(0xFF1565C0.toInt())
        setStatus("⏳ جارٍ الاتصال... / Connecting...", true)
    }

    private fun stopVpn() {
        startService(
            Intent(this, MyVpnService::class.java).apply {
                action = MyVpnService.ACTION_DISCONNECT
            }
        )
        connected = false
        tvStatus.setTextColor(0xFF616161.toInt())
        setStatus("غير متصل / Disconnected", false)
    }

    private fun setStatus(msg: String, isConnected: Boolean) {
        tvStatus.text = msg
        btnConnect.text = if (isConnected) "قطع الاتصال / Disconnect" else "اتصال / Connect"
        btnConnect.setBackgroundColor(
            if (isConnected) 0xFFC62828.toInt() else 0xFF1A237E.toInt()
        )
    }

    private fun savePrefs() {
        prefs.edit()
            .putString("server",    etServer.text.toString())
            .putString("port",      etPort.text.toString())
            .putString("tun_ip",    etTunIp.text.toString())
            .putBoolean("route_all", cbRouteAll.isChecked)
            .apply()
    }

    private fun loadPrefs() {
        etServer.setText(prefs.getString("server",  ""))
        etPort.setText(prefs.getString("port",      "51820"))
        etTunIp.setText(prefs.getString("tun_ip",   "10.8.0.2"))
        cbRouteAll.isChecked = prefs.getBoolean("route_all", false)
    }
}
