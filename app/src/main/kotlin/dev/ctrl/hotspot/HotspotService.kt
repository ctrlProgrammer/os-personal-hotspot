package dev.ctrl.hotspot

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.os.IBinder
import android.os.PowerManager
import java.security.SecureRandom

/** Mantiene vivo el grupo Wi-Fi Direct + el proxy mientras la app esta "encendida". */
class HotspotService : Service() {

    companion object {
        const val ACTION_START = "dev.ctrl.hotspot.START"
        const val ACTION_STOP = "dev.ctrl.hotspot.STOP"
        const val PROXY_PORT = 8282
        const val SSID = "DIRECT-OS-Hotspot"
        private const val CHANNEL = "hotspot"

        @Volatile var running = false
        @Volatile var status = "Apagado"
        @Volatile var passphrase = ""
        @Volatile var listener: (() -> Unit)? = null

        fun update(s: String) { status = s; listener?.invoke() }
    }

    private var proxy: ProxyServer? = null
    private var p2p: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    @Volatile private var cellular: Network? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown(); stopSelf(); return START_NOT_STICKY
        }
        if (!running) startAll()
        return START_STICKY
    }

    override fun onDestroy() { shutdown(); super.onDestroy() }

    @SuppressLint("MissingPermission", "WakelockTimeout")
    private fun startAll() {
        running = true
        DailyUsage.start(this)
        startForeground(1, notification("Iniciando..."), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "oshotspot:cpu").apply { acquire() }
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "oshotspot:wifi").apply { acquire() }

        // Pedir red movil explicita para que la salida no dependa del wifi del telefono.
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { cellular = network }
            override fun onLost(network: Network) { if (cellular == network) cellular = null }
        }
        netCallback = cb
        cm.requestNetwork(
            NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build(), cb
        )

        try {
            proxy = ProxyServer(PROXY_PORT) { cellular }.also { it.start() }
        } catch (e: Exception) {
            fail("No se pudo abrir el puerto $PROXY_PORT: ${e.message}"); return
        }

        passphrase = Prefs.passphrase(this)
        val mgr = getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
        p2p = mgr
        val ch = mgr.initialize(this, mainLooper, null)
        channel = ch
        // limpiar cualquier grupo previo y crear el nuestro (primero 5 GHz, si falla banda automatica)
        mgr.removeGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = create(mgr, ch, 0)
            override fun onFailure(reason: Int) = create(mgr, ch, 0)
        })
    }

    // stage 0: 5 GHz + nombre/clave propios; 1: banda auto; 2: grupo por defecto del sistema
    @SuppressLint("MissingPermission")
    private fun create(mgr: WifiP2pManager, ch: WifiP2pManager.Channel, stage: Int) {
        val listener = object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                if (stage < 2) {
                    val band = if (stage == 0) "5 GHz" else "auto"
                    update("Activo ($band)\nRed: $SSID\nClave: $passphrase\nProxy: 192.168.49.1:$PROXY_PORT")
                    notify("Activo - 192.168.49.1:$PROXY_PORT")
                } else {
                    mgr.requestGroupInfo(ch) { g ->
                        update("Activo (grupo por defecto)\nRed: ${g?.networkName}\nClave: ${g?.passphrase}\nProxy: 192.168.49.1:$PROXY_PORT")
                        notify("Activo - 192.168.49.1:$PROXY_PORT")
                    }
                }
            }
            override fun onFailure(reason: Int) {
                android.util.Log.w("OSHotspot", "createGroup stage=$stage failed reason=$reason")
                if (stage < 2 && reason != WifiP2pManager.BUSY) create(mgr, ch, stage + 1)
                else fail(
                    if (reason == WifiP2pManager.BUSY) "Hay otro grupo Wi-Fi Direct activo.\nCierra NetShare (u otro hotspot) y reintenta."
                    else "Wi-Fi Direct fallo en todos los intentos (codigo $reason)."
                )
            }
        }
        if (stage == 2) { mgr.createGroup(ch, listener); return }
        val cfg = WifiP2pConfig.Builder()
            .setNetworkName(SSID)
            .setPassphrase(passphrase)
            .enablePersistentMode(false)
            .setGroupOperatingBand(
                if (stage == 0) WifiP2pConfig.GROUP_OWNER_BAND_5GHZ else WifiP2pConfig.GROUP_OWNER_BAND_AUTO
            )
            .build()
        mgr.createGroup(ch, cfg, listener)
    }

    @SuppressLint("MissingPermission")
    private fun shutdown() {
        if (!running) return
        running = false
        DailyUsage.stop(this)
        try { proxy?.stop() } catch (_: Exception) {}
        proxy = null
        val ch = channel
        if (ch != null) try { p2p?.removeGroup(ch, null) } catch (_: Exception) {}
        channel = null
        netCallback?.let {
            try { (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(it) } catch (_: Exception) {}
        }
        netCallback = null
        try { wakeLock?.release() } catch (_: Exception) {}
        try { wifiLock?.release() } catch (_: Exception) {}
        update("Apagado")
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun fail(msg: String) { update(msg); shutdown(); update(msg); stopSelf() }

    private fun notify(text: String) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(1, notification(text))
    }

    private fun notification(text: String): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Hotspot", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, HotspotService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentTitle("OS Hotspot")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Apagar", stop).build())
            .build()
    }
}

object Prefs {
    fun passphrase(c: Context): String {
        val sp = c.getSharedPreferences("hotspot", Context.MODE_PRIVATE)
        sp.getString("pass", null)?.let { return it }
        val chars = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val r = SecureRandom()
        val p = (1..12).map { chars[r.nextInt(chars.length)] }.joinToString("")
        sp.edit().putString("pass", p).apply()
        return p
    }
}
