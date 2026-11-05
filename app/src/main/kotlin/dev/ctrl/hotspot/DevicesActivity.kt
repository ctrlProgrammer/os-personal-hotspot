package dev.ctrl.hotspot

import android.app.Activity
import android.content.Context
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.Typeface
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
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
        list = buildScreen("Dispositivos")
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
        val totalUp = agg.values.sumOf { it[0] }
        val totalDown = agg.values.sumOf { it[1] }
        list.card {
            text("${rows.size} ${if (rows.size == 1) "dispositivo" else "dispositivos"}  ·  total ↑ ${Ui.fmtBytes(totalUp)}  ↓ ${Ui.fmtBytes(totalDown)}", 15f, Ui.TEXT, bold = true)
        }
        if (rows.isEmpty()) {
            list.card {
                text("Ningun dispositivo conectado", 16f, Ui.TEXT, bold = true)
                text("Conecta uno a la red DIRECT-OS-Hotspot", 14f, Ui.MUTED).setPadding(0, Ui.dp(context, 6), 0, 0)
            }
            return
        }
        for (r in rows) {
            val ip = r.ip
            val blocked = ip != null && Blocklist.isBlocked(ip)
            val a = ip?.let { agg[it] }
            list.card {
                val head = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                val names = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                names.text(r.name ?: "Dispositivo", 18f, Ui.TEXT, bold = true)
                names.text(ip ?: "IP desconocida", 14f, Ui.TEXT, mono = true)
                names.text(r.mac ?: "MAC desconocida", 12f, Ui.MUTED, mono = true)
                head.addView(names, LinearLayout.LayoutParams(0, -2, 1f))
                val chip = when {
                    blocked -> chip("Bloqueado", Ui.RED)
                    r.status == null || r.status == "conectado" -> chip("Conectado", Ui.GREEN)
                    else -> chip(r.status.replaceFirstChar { it.uppercase() }, Ui.AMBER)
                }
                head.addView(chip)
                addView(head)
                text("↑ ${Ui.fmtBytes(a?.get(0) ?: 0)}   ↓ ${Ui.fmtBytes(a?.get(1) ?: 0)}   ·   ${a?.get(2) ?: 0} conexiones", 13f, Ui.MUTED)
                    .setPadding(0, Ui.dp(context, 10), 0, 0)
                if (ip != null) {
                    val btns = LinearLayout(context).apply { gravity = Gravity.END }
                    btns.addView(Button(context).apply {
                        text = if (blocked) "Desbloquear" else "Expulsar"
                        styleSmall()
                        if (!blocked) {
                            setTextColor(0xFF0E1116.toInt())
                            background = Ui.ripple(context, Ui.RED, 10)
                        }
                        setOnClickListener { if (blocked) Blocklist.unblock(ip) else kick(ip, r.mac); render() }
                    })
                    addView(btns, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(context, 8) })
                }
            }
        }
    }

    private fun chip(label: String, color: Int) = TextView(this).apply {
        text = label; textSize = 12f; setTextColor(color); typeface = Typeface.DEFAULT_BOLD
        background = Ui.rounded(context, (color and 0x00FFFFFF) or 0x33000000, 20)
        setPadding(Ui.dp(context, 10), Ui.dp(context, 4), Ui.dp(context, 10), Ui.dp(context, 4))
    }

    private fun kick(ip: String, mac: String?) {
        Blocklist.block(ip)
        if (mac != null && Build.VERSION.SDK_INT >= 31) {
            try {
                manager?.removeClient(channel!!, android.net.MacAddress.fromString(mac), null)
            } catch (_: Exception) {}
        }
    }
}
