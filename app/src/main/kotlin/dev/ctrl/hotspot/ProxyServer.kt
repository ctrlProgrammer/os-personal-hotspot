package dev.ctrl.hotspot

import android.net.Network
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Proxy HTTP minimo: soporta CONNECT (HTTPS y cualquier TCP) y HTTP plano.
 * Las conexiones salientes se atan a [egress] (red movil) cuando esta disponible.
 */
class ProxyServer(
    private val port: Int,
    private val egress: () -> Network?,
) {
    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()

    fun start() {
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(InetSocketAddress(port))
        server = s
        pool.execute {
            while (!s.isClosed) {
                try {
                    val c = s.accept()
                    pool.execute { handle(c) }
                } catch (_: Exception) {
                    break
                }
            }
        }
    }

    fun stop() {
        try { server?.close() } catch (_: Exception) {}
        server = null
    }

    private fun handle(client: Socket) {
        var upstream: Socket? = null
        var conn: StatsStore.Conn? = null
        try {
            client.soTimeout = 30_000
            client.tcpNoDelay = true
            val cin = client.getInputStream()
            val cout = client.getOutputStream()
            val head = readHead(cin) ?: return
            val text = String(head, Charsets.ISO_8859_1)
            val lines = text.split("\r\n")
            val parts = lines[0].split(" ")
            if (parts.size < 3) return
            val method = parts[0]
            val target = parts[1]
            client.soTimeout = 0

            if (method.equals("CONNECT", true)) {
                val (host, p) = splitHostPort(target, 443)
                conn = StatsStore.open(client.inetAddress?.hostAddress ?: "?", host, p)
                if (!Rules.check(host)) return
                upstream = connect(host, p)
                cout.write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray())
                cout.flush()
            } else {
                val url = java.net.URI(target)
                val host = url.host ?: return
                val p = if (url.port > 0) url.port else 80
                conn = StatsStore.open(client.inetAddress?.hostAddress ?: "?", host, p)
                if (!Rules.check(host)) return
                upstream = connect(host, p)
                val path = (url.rawPath.ifEmpty { "/" }) + (url.rawQuery?.let { "?$it" } ?: "")
                val sb = StringBuilder("$method $path ${parts[2]}\r\n")
                for (h in lines.drop(1)) {
                    if (h.isEmpty()) continue
                    val k = h.substringBefore(':').trim().lowercase()
                    if (k == "proxy-connection" || k == "connection" || k == "proxy-authorization") continue
                    sb.append(h).append("\r\n")
                }
                sb.append("Connection: close\r\n\r\n")
                upstream.getOutputStream().write(sb.toString().toByteArray(Charsets.ISO_8859_1))
            }
            pipe(client, upstream, conn)
        } catch (_: Exception) {
        } finally {
            try { client.close() } catch (_: Exception) {}
            try { upstream?.close() } catch (_: Exception) {}
            conn?.let { StatsStore.close(it) }
        }
    }

    private fun readHead(input: InputStream): ByteArray? {
        val buf = java.io.ByteArrayOutputStream()
        var state = 0
        while (buf.size() < 16 * 1024) {
            val b = input.read()
            if (b < 0) return null
            buf.write(b)
            state = when {
                b == '\r'.code && (state == 0 || state == 2) -> state + 1
                b == '\n'.code && (state == 1 || state == 3) -> state + 1
                else -> 0
            }
            if (state == 4) return buf.toByteArray()
        }
        return null
    }

    private fun splitHostPort(s: String, default: Int): Pair<String, Int> {
        val i = s.lastIndexOf(':')
        return if (i > 0 && !s.endsWith("]")) s.substring(0, i).trim('[', ']') to (s.substring(i + 1).toIntOrNull() ?: default)
        else s.trim('[', ']') to default
    }

    private fun connect(host: String, port: Int): Socket {
        val net = egress()
        val addrs = if (net != null) net.getAllByName(host) else InetAddress.getAllByName(host)
        var last: Exception? = null
        for (a in addrs.sortedBy { it is java.net.Inet6Address }) {
            val s = if (net != null) net.socketFactory.createSocket() else Socket()
            try {
                s.connect(InetSocketAddress(a, port), 10_000)
                s.tcpNoDelay = true
                return s
            } catch (e: Exception) {
                last = e
                try { s.close() } catch (_: Exception) {}
            }
        }
        throw last ?: java.io.IOException("no address for $host")
    }

    private fun pipe(a: Socket, b: Socket, conn: StatsStore.Conn) {
        val done = CountDownLatch(2)
        fun copy(from: Socket, to: Socket, upDir: Boolean) = pool.execute {
            try {
                val i = from.getInputStream()
                val o: OutputStream = to.getOutputStream()
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = i.read(buf)
                    if (n < 0) break
                    o.write(buf, 0, n)
                    if (upDir) { StatsStore.totalUp.addAndGet(n.toLong()); conn.up.addAndGet(n.toLong()) }
                    else { StatsStore.totalDown.addAndGet(n.toLong()); conn.down.addAndGet(n.toLong()) }
                }
                try { to.shutdownOutput() } catch (_: Exception) {}
            } catch (_: Exception) {
                try { a.close() } catch (_: Exception) {}
                try { b.close() } catch (_: Exception) {}
            } finally {
                done.countDown()
            }
        }
        copy(a, b, true)
        copy(b, a, false)
        done.await()
    }
}
