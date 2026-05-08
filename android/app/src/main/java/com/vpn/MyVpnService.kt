package com.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

class MyVpnService : VpnService() {

    companion object {
        const val TAG = "PythonVPN"
        const val ACTION_CONNECT    = "com.vpn.ACTION_CONNECT"
        const val ACTION_DISCONNECT = "com.vpn.ACTION_DISCONNECT"
        const val EXTRA_SERVER      = "server"
        const val EXTRA_PORT        = "port"
        const val EXTRA_PASSWORD    = "password"
        const val EXTRA_TUN_IP      = "tun_ip"
        const val EXTRA_ROUTE_ALL   = "route_all"
        private const val NOTIF_CHANNEL = "vpn_channel"
        private const val NOTIF_ID      = 1
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var udpSocket: DatagramSocket? = null
    private val running = AtomicBoolean(false)
    private var tunnelThread: Thread? = null

    // -------------------------------------------------------------------------

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT) {
            disconnect()
            return START_NOT_STICKY
        }

        val server   = intent?.getStringExtra(EXTRA_SERVER)   ?: return START_NOT_STICKY
        val port     = intent.getIntExtra(EXTRA_PORT, 51820)
        val password = intent.getStringExtra(EXTRA_PASSWORD)  ?: return START_NOT_STICKY
        val tunIp    = intent.getStringExtra(EXTRA_TUN_IP)    ?: "10.8.0.2"
        val routeAll = intent.getBooleanExtra(EXTRA_ROUTE_ALL, false)

        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
        connect(server, port, password, tunIp, routeAll)
        return START_STICKY
    }

    override fun onDestroy() {
        disconnect()
        super.onDestroy()
    }

    // -------------------------------------------------------------------------

    private fun connect(
        server: String, port: Int, password: String,
        tunIp: String, routeAll: Boolean
    ) {
        running.set(true)

        tunnelThread = Thread {
            try {
                // 1. Derive key (slow scrypt — runs once on background thread)
                val key    = CryptoEngine.deriveKey(password)
                val cipher = CryptoEngine.SessionCipher(key)

                val serverAddr = InetAddress.getByName(server)

                // 2. UDP socket — must be protected BEFORE any data is sent
                val sock = DatagramSocket()
                protect(sock)
                udpSocket = sock

                // 3. Build TUN interface
                val builder = Builder()
                    .setSession("PythonVPN")
                    .addAddress(tunIp, 24)
                    .addDnsServer("8.8.8.8")
                    .addDnsServer("1.1.1.1")
                    .setMtu(1400)

                if (routeAll) {
                    builder.addRoute("0.0.0.0", 0)
                } else {
                    builder.addRoute("10.8.0.0", 24)
                }

                vpnInterface = builder.establish() ?: run {
                    broadcast("VPN permission lost")
                    return@Thread
                }
                val tun    = vpnInterface!!
                val tunIn  = FileInputStream(tun.fileDescriptor)
                val tunOut = FileOutputStream(tun.fileDescriptor)

                // 4. Handshake: tell server our tunnel IP
                val ipBytes      = InetAddress.getByName(tunIp).address
                val hsEncrypted  = cipher.encrypt(Protocol.pack(Protocol.TYPE_HANDSHAKE, ipBytes))
                sock.send(DatagramPacket(hsEncrypted, hsEncrypted.size, serverAddr, port))
                Log.i(TAG, "Handshake sent → $server:$port  tunIP=$tunIp")

                // 5. TUN → UDP (background daemon)
                Thread {
                    val buf = ByteArray(65535)
                    while (running.get()) {
                        try {
                            val n = tunIn.read(buf)
                            if (n > 0) {
                                val enc = cipher.encrypt(
                                    Protocol.pack(Protocol.TYPE_DATA, buf.copyOf(n))
                                )
                                sock.send(DatagramPacket(enc, enc.size, serverAddr, port))
                            }
                        } catch (e: Exception) {
                            if (running.get()) Log.w(TAG, "TUN→UDP: $e")
                        }
                    }
                }.also { it.isDaemon = true; it.start() }

                // 6. Keepalive (background daemon)
                Thread {
                    while (running.get()) {
                        try {
                            Thread.sleep(25_000)
                            val enc = cipher.encrypt(Protocol.pack(Protocol.TYPE_KEEPALIVE))
                            sock.send(DatagramPacket(enc, enc.size, serverAddr, port))
                        } catch (_: Exception) { }
                    }
                }.also { it.isDaemon = true; it.start() }

                // 7. UDP → TUN (main loop)
                Log.i(TAG, "Tunnel up — routeAll=$routeAll")
                broadcast(null) // signal success (null = no error)
                sock.soTimeout = 2000
                val recvBuf = ByteArray(65535)
                while (running.get()) {
                    try {
                        val pkt = DatagramPacket(recvBuf, recvBuf.size)
                        sock.receive(pkt)
                        val raw = pkt.data.copyOf(pkt.length)
                        val (type, payload) = Protocol.unpack(cipher.decrypt(raw))
                        if (type == Protocol.TYPE_DATA) tunOut.write(payload)
                    } catch (_: java.net.SocketTimeoutException) {
                        // normal — loop back to check running flag
                    } catch (e: Exception) {
                        if (running.get()) Log.w(TAG, "UDP→TUN: $e")
                    }
                }

                tunIn.close()
                tunOut.close()

            } catch (e: Exception) {
                Log.e(TAG, "Tunnel error: $e")
                broadcast(e.message ?: "Unknown error")
            } finally {
                udpSocket?.close(); udpSocket = null
                vpnInterface?.close(); vpnInterface = null
            }
        }
        tunnelThread?.start()
    }

    private fun disconnect() {
        running.set(false)
        tunnelThread?.interrupt()
        udpSocket?.close(); udpSocket = null
        vpnInterface?.close(); vpnInterface = null
        @Suppress("DEPRECATION")
        stopForeground(true)
        stopSelf()
    }

    // -------------------------------------------------------------------------

    private fun broadcast(error: String?) {
        sendBroadcast(
            Intent("com.vpn.VPN_STATUS").apply {
                putExtra("connected", error == null)
                if (error != null) putExtra("error", error)
            }
        )
    }

    private fun createNotificationChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(NOTIF_CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    NOTIF_CHANNEL,
                    "VPN Status",
                    NotificationManager.IMPORTANCE_LOW
                ).apply { description = "Python VPN active connection" }
            )
        }
    }

    private fun buildNotification(): Notification {
        val stopPi = PendingIntent.getService(
            this, 0,
            Intent(this, MyVpnService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openPi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, NOTIF_CHANNEL)
            .setContentTitle("Python VPN — متصل / Connected")
            .setContentText("النفق المشفر نشط / Encrypted tunnel active")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(openPi)
            .addAction(
                android.R.drawable.ic_media_pause,
                "قطع / Disconnect",
                stopPi
            )
            .setOngoing(true)
            .build()
    }
}
