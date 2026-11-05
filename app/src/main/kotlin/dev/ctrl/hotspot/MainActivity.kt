package dev.ctrl.hotspot

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var stateText: TextView
    private lateinit var detailText: TextView
    private lateinit var dot: TextView
    private lateinit var toggle: android.widget.Button
    private lateinit var upDown: TextView

    private val ticker = object : Runnable {
        override fun run() { render(); stateText.postDelayed(this, 1000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val body = buildScreen("OS Hotspot", back = false)
        val d = { v: Int -> Ui.dp(this, v) }

        body.card {
            val row = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
            dot = TextView(context).apply { text = "●"; textSize = 20f }
            stateText = TextView(context).apply { textSize = 20f; setTextColor(Ui.TEXT); typeface = android.graphics.Typeface.DEFAULT_BOLD; setPadding(d(10), 0, 0, 0) }
            row.addView(dot); row.addView(stateText)
            addView(row)
            upDown = text("", 13f, Ui.MUTED).also { it.setPadding(0, d(6), 0, 0) }
            toggle = button("Encender", primary = true) { onToggle() }
        }

        body.card {
            text("CONEXION", 12f, Ui.MUTED, bold = true)
            detailText = text("", 14f, Ui.TEXT, mono = true).also { it.setPadding(0, d(8), 0, d(8)) }
            button("Copiar datos") {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("hotspot", detailText.text))
                Toast.makeText(this@MainActivity, "Copiado", Toast.LENGTH_SHORT).show()
            }
        }

        body.sectionTitle("Herramientas")
        val tiles = listOf(
            "📈\nTrafico" to TrafficActivity::class.java,
            "📱\nDispositivos" to DevicesActivity::class.java,
            "🧾\nConexiones" to ConnLogActivity::class.java,
            "🛠\nHerramientas" to NetToolsActivity::class.java,
            "🛡\nReglas y SOCKS5" to RulesActivity::class.java,
        )
        tiles.chunked(2).forEach { pair ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEach { (label, cls) ->
                row.addView(TextView(this).apply {
                    text = label; textSize = 15f; setTextColor(Ui.TEXT); gravity = Gravity.CENTER
                    background = Ui.ripple(context, Ui.SURFACE); setPadding(d(8), d(22), d(8), d(22))
                    setOnClickListener { startActivity(Intent(this@MainActivity, cls)) }
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(d(4), d(4), d(4), d(4)) })
            }
            if (pair.size == 1) row.addView(android.view.View(this), LinearLayout.LayoutParams(0, 1, 1f))
            body.addView(row)
        }
    }

    override fun onResume() {
        super.onResume()
        HotspotService.listener = { runOnUiThread { render() } }
        stateText.post(ticker)
    }

    override fun onPause() {
        HotspotService.listener = null
        stateText.removeCallbacks(ticker)
        super.onPause()
    }

    private fun render() {
        val st = HotspotService.status
        val lines = st.split("\n")
        val running = HotspotService.running
        val active = st.startsWith("Activo")
        stateText.text = lines.firstOrNull() ?: ""
        dot.setTextColor(when { active -> Ui.GREEN; running -> Ui.AMBER; else -> Ui.MUTED })
        detailText.text = if (lines.size > 1) lines.drop(1).joinToString("\n") else "Sin conexion activa"
        toggle.text = if (running) "Apagar" else "Encender"
        upDown.text = if (running) "↑ ${Ui.fmtBytes(StatsStore.totalUp.get())}   ↓ ${Ui.fmtBytes(StatsStore.totalDown.get())}   ·   ${StatsStore.active.size} conexiones" else ""
    }

    private fun onToggle() {
        if (HotspotService.running) {
            startService(Intent(this, HotspotService::class.java).setAction(HotspotService.ACTION_STOP))
            return
        }
        val need = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) {
            need += Manifest.permission.NEARBY_WIFI_DEVICES
            need += Manifest.permission.POST_NOTIFICATIONS
        } else {
            need += Manifest.permission.ACCESS_FINE_LOCATION
        }
        val missing = need.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) { requestPermissions(missing.toTypedArray(), 1); return }
        HotspotService.update("Iniciando...")
        startForegroundService(Intent(this, HotspotService::class.java).setAction(HotspotService.ACTION_START))
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        if (res.all { it == PackageManager.PERMISSION_GRANTED }) onToggle()
        else HotspotService.update("Faltan permisos (Wi-Fi cercano / notificaciones)")
    }
}
