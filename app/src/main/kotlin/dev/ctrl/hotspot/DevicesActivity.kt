package dev.ctrl.hotspot

import android.app.Activity
import android.content.Context
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

class DevicesActivity : Activity() {
    private class Row(val ip: String?, val mac: String?, val name: String?, val status: String?)

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var list: LinearLayout
    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var p2pDevices: List<WifiP2pDevice> = emptyList()
    private val tick = object : Runnable {
        override fun run() { refresh(); handler.postDelayed(this, 3000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Dispositivos"
        Blocklist.init(this)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 32, 32, 32) }
        setContentView(ScrollView(this).apply { addView(list) })
        manager = getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        channel = manager?.initialize(this, Looper.getMainLooper(), null)
    }

    override fun onResume() { super.onResume(); tick.run() }
    override fun onPause() { super.onPause(); handler.removeCallbacks(tick) }

    private fun refresh() {
        val m = manager
        val ch = channel
        if (m == null || ch == null) { render(); return }
        try {
            m.requestGroupInfo(ch) { g -> p2pDevices = g?.clientList?.toList() ?: emptyList(); render() }
        } catch (_: SecurityException) { render() }
    }

    /** MAC -> IP desde /proc/net/arp; puede estar restringido en Android 10+ (devuelve vacio). */
    private fun readArp(): Map<String, String> = try {
        File("/proc/net/arp").readLines().drop(1).mapNotNull {
            val f = it.trim().split(Regex("\\s+"))
            if (f.size >= 4 && f[3] != "00:00:00:00:00:00") f[3].lowercase() to f[0] else null
        }.toMap()
    } catch (_: Exception) { emptyMap() }

    private fun statusText(s: Int) = when (s) {
        WifiP2pDevice.CONNECTED -> "conectado"
        WifiP2pDevice.INVITED -> "invitado"
        WifiP2pDevice.FAILED -> "fallido"
        WifiP2pDevice.AVAILABLE -> "disponible"
        else -> "no disponible"
    }

    private fun render() {
        val arp = readArp()
        val rows = ArrayList<Row>()
        for (d in p2pDevices) {
            // La MAC de interfaz en ARP difiere de la P2P en el primer octeto; compara el resto
            val key = d.deviceAddress.lowercase().substring(2)
            val ip = arp.entries.firstOrNull { it.key.substring(2) == key }?.value
            rows.add(Row(ip, d.deviceAddress, d.deviceName, statusText(d.status)))
        }
        val agg = HashMap<String, LongArray>() // ip -> up, down, n
        for (c in StatsStore.history.toList()) {
            val a = agg.getOrPut(c.clientIp) { LongArray(3) }
            a[0] += c.up.get(); a[1] += c.down.get(); a[2]++
        }
        val known = rows.mapNotNull { it.ip }.toMutableSet()
        for (ip in arp.values + agg.keys + Blocklist.all()) if (ip != "?" && known.add(ip)) rows.add(Row(ip, null, null, null))

        list.removeAllViews()
        if (rows.isEmpty()) list.addView(TextView(this).apply { text = "Sin dispositivos conectados" })
        for (r in rows) {
            val ip = r.ip
            val blocked = ip != null && Blocklist.isBlocked(ip)
            val a = ip?.let { agg[it] }
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 16, 0, 16) }
            box.addView(TextView(this).apply {
                textSize = 16f
                text = (r.name ?: "Dispositivo") + "  " + (ip ?: "IP desconocida") + if (blocked) "  [BLOQUEADO]" else ""
            })
            box.addView(TextView(this).apply {
                text = "MAC: ${r.mac ?: "?"}  Estado: ${r.status ?: "?"}\n" +
                    "Subido: ${fmt(a?.get(0) ?: 0)}  Bajado: ${fmt(a?.get(1) ?: 0)}  Conexiones: ${a?.get(2) ?: 0}"
            })
            val btns = LinearLayout(this).apply { gravity = Gravity.START }
            if (ip != null) btns.addView(Button(this).apply {
                text = if (blocked) "Desbloquear" else "Expulsar"
                setOnClickListener { if (blocked) Blocklist.unblock(ip) else kick(ip, r.mac); render() }
            })
            box.addView(btns)
            list.addView(box)
        }
    }

    private fun kick(ip: String, mac: String?) {
        Blocklist.block(ip)
        if (mac != null && Build.VERSION.SDK_INT >= 31) {
            try {
                manager?.removeClient(channel!!, android.net.MacAddress.fromString(mac), null)
            } catch (_: Exception) {}
        }
    }

    private fun fmt(b: Long): String = when {
        b >= 1L shl 30 -> "%.2f GB".format(b / 1073741824.0)
        b >= 1L shl 20 -> "%.1f MB".format(b / 1048576.0)
        b >= 1L shl 10 -> "%.0f KB".format(b / 1024.0)
        else -> "$b B"
    }
}
