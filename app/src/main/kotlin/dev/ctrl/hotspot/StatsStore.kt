package dev.ctrl.hotspot

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicLong

/** Almacen compartido (en memoria) de estadisticas del proxy. Lo escribe ProxyServer; lo leen las pantallas. */
object StatsStore {
    class Conn(
        val id: Long,
        val clientIp: String,   // IP del dispositivo cliente (p.ej. 192.168.49.184)
        val host: String,       // destino pedido (dominio o IP)
        val port: Int,
        val startMs: Long,
    ) {
        val up = AtomicLong()   // bytes cliente -> internet
        val down = AtomicLong() // bytes internet -> cliente
        @Volatile var endMs: Long = 0L  // 0 = sigue abierta
    }

    val totalUp = AtomicLong()
    val totalDown = AtomicLong()
    /** Conexiones abiertas ahora mismo. */
    val active = ConcurrentHashMap<Long, Conn>()
    /** Historial reciente (las mas nuevas al final), tope MAX_HISTORY. */
    val history = ConcurrentLinkedDeque<Conn>()
    const val MAX_HISTORY = 5000
    private val nextId = AtomicLong(1)

    fun open(clientIp: String, host: String, port: Int): Conn {
        val c = Conn(nextId.getAndIncrement(), clientIp, host, port, System.currentTimeMillis())
        active[c.id] = c
        history.addLast(c)
        while (history.size > MAX_HISTORY) history.pollFirst()
        return c
    }

    fun close(c: Conn) {
        c.endMs = System.currentTimeMillis()
        active.remove(c.id)
    }

    fun clear() { history.clear(); totalUp.set(0); totalDown.set(0) }
}
