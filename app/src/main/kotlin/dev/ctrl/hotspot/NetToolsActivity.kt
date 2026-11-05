package dev.ctrl.hotspot

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.telephony.TelephonyManager
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL
import java.util.Locale

/** Herramientas de red: todas las pruebas salen por la red movil (fallback: red por defecto). */
class NetToolsActivity : Activity() {
    private lateinit var cm: ConnectivityManager
    private lateinit var out: TextView
    private lateinit var scroll: ScrollView
    private lateinit var domain: EditText

    @Volatile private var cellular: Network? = null
    private var callback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var cancelled = false
    @Volatile private var worker: Thread? = null
    @Volatile private var activeConn: HttpURLConnection? = null

    private val net: Network? get() = cellular ?: cm.activeNetwork

    private val tools = listOf("Ping", "DNS", "Traceroute", "Speedtest", "IP publica")
    private var selected = 0
    private val chips = ArrayList<Button>()
    private lateinit var runBtn: Button
    private lateinit var inputCard: LinearLayout
    private lateinit var status: TextView
    @Volatile private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Herramientas de red"
        cm = getSystemService(ConnectivityManager::class.java)
        requestCellular()
        val d = { v: Int -> Ui.dp(this, v) }

        val body = buildScreen("Herramientas de red", scroll = false)

        val chipRow = LinearLayout(this)
        tools.forEachIndexed { i, label ->
            val chip = Button(this).apply {
                text = label; styleSmall(); setOnClickListener { select(i) }
            }
            chips += chip
            chipRow.addView(chip, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = d(8) })
        }
        body.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(chipRow)
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = d(12) })

        inputCard = body.card {
            sectionTitle("Dominio")
            domain = EditText(context).apply { setText("google.com"); hint = "dominio"; setSingleLine(); styleInput() }
            addView(domain, LinearLayout.LayoutParams(-1, -2))
        }
        val actions = body.card {
            runBtn = button("Ejecutar", primary = true) { if (running) cancel() else startSelected() }
        }
        actions.setPadding(d(16), d(8), d(16), d(14))
        status = actions.text("", 12f, Ui.MUTED).also { it.setPadding(0, d(8), 0, 0) }

        val results = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(this@NetToolsActivity, 0xFF07090C.toInt())
            setPadding(d(14), d(12), d(14), d(12))
        }
        out = TextView(this).apply {
            typeface = Typeface.MONOSPACE; textSize = 13f; setTextColor(Ui.TEXT); setTextIsSelectable(true)
        }
        scroll = ScrollView(this).apply { isFillViewport = true; addView(out) }
        results.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val bar = LinearLayout(this).apply { gravity = Gravity.END; setPadding(0, d(10), 0, 0) }
        bar.addView(Button(this).apply {
            text = "Copiar"; styleSmall()
            setOnClickListener {
                getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("net-tools", out.text))
                Toast.makeText(context, "Copiado", Toast.LENGTH_SHORT).show()
            }
        }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = d(8) })
        bar.addView(Button(this).apply { text = "Limpiar"; styleSmall(); setOnClickListener { out.text = "" } })
        results.addView(bar, LinearLayout.LayoutParams(-1, -2))
        body.addView(results, LinearLayout.LayoutParams(-1, 0, 1f))

        select(0)
        refreshStatus()
    }

    private fun select(i: Int) {
        selected = i
        chips.forEachIndexed { k, b ->
            b.styleSmall(primary = k == i)
        }
        inputCard.visibility = if (i == 1 || i == 2) View.VISIBLE else View.GONE
    }

    private fun refreshStatus() = runOnUiThread {
        status.text = if (cellular != null) "Usando red movil" else "Red por defecto"
    }

    private fun setRunning(on: Boolean) {
        running = on
        runBtn.text = if (on) "Cancelar" else "Ejecutar"
        runBtn.background = Ui.ripple(this, if (on) Ui.RED else Ui.ACCENT, 12)
    }

    private fun startSelected() {
        when (selected) {
            0 -> run("Ping TCP:443") { ping() }
            1 -> run("DNS") { dns() }
            2 -> run("Traceroute") { traceroute() }
            3 -> confirmSpeedtest()
            else -> run("IP publica") { publicIp() }
        }
    }

    private fun requestCellular() {
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { cellular = network; refreshStatus() }
            override fun onLost(network: Network) { if (cellular == network) cellular = null; refreshStatus() }
        }
        callback = cb
        try { cm.requestNetwork(req, cb) } catch (e: Exception) { callback = null }
    }

    override fun onDestroy() {
        cancel()
        callback?.let { try { cm.unregisterNetworkCallback(it) } catch (_: Exception) {} }
        super.onDestroy()
    }

    // ---- infraestructura ----

    private fun log(s: String) = runOnUiThread {
        out.append(s + "\n")
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    /** Reemplaza la ultima linea (progreso en vivo). */
    private fun live(s: String) = runOnUiThread {
        val t = out.text.toString()
        val i = t.lastIndexOf('\n', t.length - 2)
        out.text = t.substring(0, i + 1) + s + "\n"
    }

    private fun cancel() {
        cancelled = true
        try { activeConn?.disconnect() } catch (_: Exception) {}
        worker?.interrupt()
    }

    private fun run(name: String, body: () -> Unit) {
        if (worker?.isAlive == true) { Toast.makeText(this, "Hay una prueba en curso", Toast.LENGTH_SHORT).show(); return }
        cancelled = false
        setRunning(true)
        val n = net
        log("== $name == (${if (cellular != null) "red movil" else if (n != null) "red por defecto (sin red movil)" else "SIN RED"})")
        worker = Thread {
            try { if (n != null) body() else log("No hay red disponible") }
            catch (e: Exception) { if (!cancelled) log("Error: ${e.javaClass.simpleName}: ${e.message}") }
            if (cancelled) log("(cancelado)")
            log("")
            runOnUiThread { setRunning(false) }
        }.also { it.isDaemon = true; it.start() }
    }

    // ---- (a) ping TCP ----

    private fun ping() {
        val n = net!!
        val count = 5
        for (host in listOf("1.1.1.1", "8.8.8.8", "google.com", "steampowered.com")) {
            val times = ArrayList<Long>()
            var lost = 0
            for (i in 0 until count) {
                if (cancelled) return
                val t0 = System.nanoTime()
                try {
                    val addr = n.getByName(host)
                    n.socketFactory.createSocket().use { it.connect(InetSocketAddress(addr, 443), 3000) }
                    times += (System.nanoTime() - t0) / 1_000_000
                } catch (e: Exception) { if (cancelled) return; lost++ }
                try { Thread.sleep(200) } catch (_: InterruptedException) { return }
            }
            val stats = if (times.isEmpty()) "sin respuesta" else
                "min=${times.min()} avg=${times.average().toLong()} max=${times.max()} ms"
            log(String.format(Locale.US, "%-18s %s  perdida=%d/%d (%d%%)", host, stats, lost, count, lost * 100 / count))
        }
    }

    // ---- (b) DNS ----

    private fun dns() {
        val host = domain.text.toString().trim().ifEmpty { "google.com" }
        val t0 = System.nanoTime()
        val addrs = net!!.getAllByName(host)
        val ms = (System.nanoTime() - t0) / 1_000_000
        log("$host -> ${addrs.size} IP en $ms ms")
        addrs.forEach { log("  ${it.hostAddress}") }
    }

    // ---- (c) traceroute ----

    private fun traceroute() {
        val host = domain.text.toString().trim().ifEmpty { "1.1.1.1" }
        log("Aviso: usa el binario 'ping' del sistema, que sale por la red por defecto, no necesariamente la movil.")
        val ip = try { net!!.getByName(host).hostAddress } catch (e: Exception) { log("No resuelve $host"); return }
        log("Destino $host ($ip)")
        for (ttl in 1..20) {
            if (cancelled) return
            val p = try {
                ProcessBuilder("ping", "-c", "1", "-t", ttl.toString(), "-W", "2", ip).redirectErrorStream(true).start()
            } catch (e: Exception) { log("No se pudo ejecutar ping: ${e.message}"); return }
            val text = try { p.inputStream.bufferedReader().readText() } finally { p.destroy() }
            val reached = Regex("bytes from ([0-9a-fA-F.:]+)").find(text)
            val hop = Regex("From ([0-9a-fA-F.:]+)").find(text)
            when {
                reached != null -> { log("%2d  %s  (destino)".format(ttl, reached.groupValues[1])); return }
                hop != null -> log("%2d  %s".format(ttl, hop.groupValues[1]))
                text.isBlank() || text.contains("not permitted", true) || text.contains("unknown", true) -> {
                    log("%2d  ? (ping no soportado o sin salida)".format(ttl)); if (ttl == 1) return
                }
                else -> log("%2d  *".format(ttl))
            }
        }
    }

    // ---- (d) speedtest ----

    private fun confirmSpeedtest() {
        AlertDialog.Builder(this)
            .setTitle("Speedtest")
            .setMessage("Descargara unos 25 MB y subira unos 10 MB por datos moviles. Continuar?")
            .setPositiveButton("Continuar") { _, _ -> run("Speedtest") { speedtest() } }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun open(url: String): HttpURLConnection =
        (net!!.openConnection(URL(url)) as HttpURLConnection).apply {
            connectTimeout = 8000; readTimeout = 10000
            activeConn = this
        }

    private fun speedtest() {
        log("Descarga...")
        var c = open("https://speed.cloudflare.com/__down?bytes=25000000")
        var total = 0L
        var t0 = System.nanoTime()
        var last = t0
        c.inputStream.use { ins ->
            val buf = ByteArray(32768)
            while (!cancelled) {
                val r = ins.read(buf)
                if (r < 0) break
                total += r
                val now = System.nanoTime()
                if (now - last > 500_000_000) { last = now; live(mbps("Descarga", total, now - t0)) }
            }
        }
        c.disconnect()
        live(mbps("Descarga", total, System.nanoTime() - t0) + " (final)")
        if (cancelled) return

        log("Subida...")
        val size = 10_000_000
        c = open("https://speed.cloudflare.com/__up")
        c.doOutput = true; c.requestMethod = "POST"
        c.setFixedLengthStreamingMode(size)
        total = 0; t0 = System.nanoTime(); last = t0
        c.outputStream.use { os ->
            val buf = ByteArray(32768)
            while (total < size && !cancelled) {
                val n = minOf(buf.size.toLong(), size - total).toInt()
                os.write(buf, 0, n); total += n
                val now = System.nanoTime()
                if (now - last > 500_000_000) { last = now; live(mbps("Subida", total, now - t0)) }
            }
        }
        if (!cancelled) try { c.responseCode; c.inputStream.close() } catch (_: Exception) {}
        c.disconnect()
        live(mbps("Subida", total, System.nanoTime() - t0) + " (final)")
    }

    private fun mbps(label: String, bytes: Long, ns: Long): String {
        val s = ns / 1e9
        return String.format(Locale.US, "%s: %.2f Mbps (%.1f MB en %.1f s)", label, if (s > 0) bytes * 8 / s / 1e6 else 0.0, bytes / 1e6, s)
    }

    // ---- (e) IP publica ----

    private fun publicIp() {
        val c = open("https://api.ipify.org")
        val ip = try { c.inputStream.bufferedReader().readText().trim() } finally { c.disconnect() }
        log("IP publica: $ip")
        try {
            val tm = getSystemService(TelephonyManager::class.java)
            @Suppress("MissingPermission")
            val t = tm.dataNetworkType
            val name = when (t) {
                TelephonyManager.NETWORK_TYPE_NR -> "5G"
                TelephonyManager.NETWORK_TYPE_LTE -> "4G (LTE)"
                TelephonyManager.NETWORK_TYPE_UMTS, TelephonyManager.NETWORK_TYPE_HSPA,
                TelephonyManager.NETWORK_TYPE_HSPAP, TelephonyManager.NETWORK_TYPE_HSDPA,
                TelephonyManager.NETWORK_TYPE_HSUPA -> "3G"
                TelephonyManager.NETWORK_TYPE_EDGE, TelephonyManager.NETWORK_TYPE_GPRS -> "2G"
                else -> "desconocido ($t)"
            }
            log("Tipo de red: $name")
        } catch (e: SecurityException) {
            log("Tipo de red: omitido (requiere permiso READ_PHONE_STATE)")
        }
    }
}
