package com.vpn

import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.*

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

    // -------------------------------------------------------------------------

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

    // -------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("vpn_prefs", Context.MODE_PRIVATE)

        etServer   = findViewById(R.id.et_server)
        etPort     = findViewById(R.id.et_port)
        etPassword = findViewById(R.id.et_password)
        etTunIp    = findViewById(R.id.et_tun_ip)
        cbRouteAll = findViewById(R.id.cb_route_all)
        btnConnect = findViewById(R.id.btn_connect)
        tvStatus   = findViewById(R.id.tv_status)

        loadPrefs()

        btnConnect.setOnClickListener {
            if (connected) stopVpn() else requestVpnPermission()
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onResume() {
        super.onResume()
        val filter = IntentFilter("com.vpn.VPN_STATUS")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(statusReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        savePrefs()
        try { unregisterReceiver(statusReceiver) } catch (_: Exception) { }
    }

    // -------------------------------------------------------------------------

    private fun requestVpnPermission() {
        val intent = VpnService.prepare(this)
        if (intent != null) startActivityForResult(intent, VPN_REQUEST)
        else onActivityResult(VPN_REQUEST, RESULT_OK, null)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == VPN_REQUEST) {
            if (resultCode == RESULT_OK) startVpn()
            else updateStatus("تم رفض إذن VPN / Permission denied", false)
        }
    }

    private fun startVpn() {
        val server   = etServer.text.toString().trim()
        val portStr  = etPort.text.toString().trim()
        val password = etPassword.text.toString()
        val tunIp    = etTunIp.text.toString().trim()

        if (server.isEmpty() || password.isEmpty() || tunIp.isEmpty()) {
            updateStatus("الرجاء ملء جميع الحقول / Fill all fields", false)
            return
        }

        val port = portStr.toIntOrNull() ?: 51820

        startService(
            Intent(this, MyVpnService::class.java).apply {
                action = MyVpnService.ACTION_CONNECT
                putExtra(MyVpnService.EXTRA_SERVER,    server)
                putExtra(MyVpnService.EXTRA_PORT,      port)
                putExtra(MyVpnService.EXTRA_PASSWORD,  password)
                putExtra(MyVpnService.EXTRA_TUN_IP,    tunIp)
                putExtra(MyVpnService.EXTRA_ROUTE_ALL, cbRouteAll.isChecked)
            }
        )

        connected = true
        tvStatus.setTextColor(0xFF1565C0.toInt())
        updateStatus("⏳ جارٍ الاتصال... / Connecting...", true)
    }

    private fun stopVpn() {
        startService(
            Intent(this, MyVpnService::class.java).apply {
                action = MyVpnService.ACTION_DISCONNECT
            }
        )
        connected = false
        tvStatus.setTextColor(0xFF616161.toInt())
        updateStatus("غير متصل / Disconnected", false)
    }

    private fun updateStatus(msg: String, isConnecting: Boolean) {
        tvStatus.text = msg
        btnConnect.text = if (connected) "قطع الاتصال / Disconnect" else "اتصال / Connect"
        btnConnect.setBackgroundColor(
            if (connected) 0xFFC62828.toInt() else 0xFF1A237E.toInt()
        )
    }

    // -------------------------------------------------------------------------

    private fun savePrefs() {
        prefs.edit()
            .putString("server",    etServer.text.toString())
            .putString("port",      etPort.text.toString())
            .putString("tun_ip",    etTunIp.text.toString())
            .putBoolean("route_all", cbRouteAll.isChecked)
            .apply()
    }

    private fun loadPrefs() {
        etServer.setText(prefs.getString("server",    ""))
        etPort.setText(prefs.getString("port",        "51820"))
        etTunIp.setText(prefs.getString("tun_ip",     "10.8.0.2"))
        cbRouteAll.isChecked = prefs.getBoolean("route_all", false)
    }
}
