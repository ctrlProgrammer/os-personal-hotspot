package dev.ctrl.hotspot

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var info: TextView
    private lateinit var btn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (24 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad, pad, pad, pad)
            setOnApplyWindowInsetsListener { v, w ->
                @Suppress("DEPRECATION")
                v.setPadding(pad, w.systemWindowInsetTop + pad, pad, w.systemWindowInsetBottom + pad)
                w
            }
        }
        info = TextView(this).apply { textSize = 18f; setPadding(0, 0, 0, pad) }
        btn = Button(this).apply { setOnClickListener { toggle() } }
        root.addView(info)
        root.addView(btn)
        for ((label, cls) in listOf(
            "Trafico en vivo" to TrafficActivity::class.java,
            "Dispositivos" to DevicesActivity::class.java,
            "Registro de conexiones" to ConnLogActivity::class.java,
            "Herramientas de red" to NetToolsActivity::class.java,
        )) root.addView(Button(this).apply { text = label; setOnClickListener { startActivity(Intent(this@MainActivity, cls)) } })
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        HotspotService.listener = { runOnUiThread { render() } }
        render()
    }

    override fun onPause() { HotspotService.listener = null; super.onPause() }

    private fun render() {
        info.text = HotspotService.status
        btn.text = if (HotspotService.running) "Apagar" else "Encender"
    }

    private fun toggle() {
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
        if (res.all { it == PackageManager.PERMISSION_GRANTED }) toggle()
        else HotspotService.update("Faltan permisos (Wi-Fi cercano / notificaciones)")
    }
}
