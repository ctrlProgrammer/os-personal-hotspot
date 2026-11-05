package dev.ctrl.hotspot

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

/** IPs de clientes bloqueadas en el proxy. Persistente en SharedPreferences. */
object Blocklist {
    private val ips: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private var prefs: android.content.SharedPreferences? = null

    fun init(ctx: Context) {
        if (prefs != null) return
        val p = ctx.applicationContext.getSharedPreferences("blocklist", Context.MODE_PRIVATE)
        prefs = p
        ips.addAll(p.getStringSet("ips", emptySet()) ?: emptySet())
    }

    fun isBlocked(ip: String?): Boolean = ip != null && ips.contains(ip)
    fun all(): Set<String> = ips.toSet()

    fun block(ip: String) { ips.add(ip); save() }
    fun unblock(ip: String) { ips.remove(ip); save() }

    private fun save() { prefs?.edit()?.putStringSet("ips", HashSet(ips))?.apply() }
}
