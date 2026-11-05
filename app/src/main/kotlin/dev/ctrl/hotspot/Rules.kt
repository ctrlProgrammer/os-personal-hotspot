package dev.ctrl.hotspot

import android.content.Context

/** Reglas por dominio (sufijo). Gana la mas especifica (patron mas largo). Por defecto: permitido. */
object Rules {
    data class Rule(val pattern: String, val allow: Boolean)

    @Volatile private var prefs: android.content.SharedPreferences? = null
    @Volatile private var cache: List<Rule> = emptyList()

    fun init(c: Context) {
        if (prefs != null) return
        val sp = c.applicationContext.getSharedPreferences("rules", Context.MODE_PRIVATE)
        prefs = sp
        cache = (sp.getString("list", "") ?: "").lines().mapNotNull { l ->
            val i = l.indexOf('|')
            if (i < 1) null else Rule(l.substring(i + 1), l.substring(0, i) == "ALLOW")
        }
    }

    fun list(): List<Rule> = cache

    @Synchronized fun add(pattern: String, allow: Boolean) {
        val p = pattern.trim().lowercase().trimStart('*', '.').trimEnd('.')
        if (p.isEmpty() || '|' in p || '\n' in p) return
        save(cache.filter { it.pattern != p } + Rule(p, allow))
    }

    @Synchronized fun remove(pattern: String) = save(cache.filter { it.pattern != pattern })

    private fun save(l: List<Rule>) {
        cache = l
        prefs?.edit()?.putString("list", l.joinToString("\n") { (if (it.allow) "ALLOW|" else "BLOCK|") + it.pattern })?.apply()
    }

    /** true = permitido. */
    fun check(host: String): Boolean {
        val h = host.lowercase().trimEnd('.')
        return cache.filter { h == it.pattern || h.endsWith("." + it.pattern) }
            .maxByOrNull { it.pattern.length }?.allow ?: true
    }
}
