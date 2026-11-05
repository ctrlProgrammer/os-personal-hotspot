package dev.ctrl.hotspot

import android.net.Network
import java.io.DataInputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/**
 * SOCKS5 (RFC 1928) sin autenticacion. CONNECT (IPv4/IPv6/dominio) y UDP ASSOCIATE.
 * Las conexiones/datagramas salientes se atan a [egress] (red movil). Aplica [Rules].
 */
class Socks5Server(
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

    private fun reply(o: java.io.OutputStream, rep: Int, addr: InetAddress? = null, p: Int = 0) {
        val a = addr?.address ?: byteArrayOf(0, 0, 0, 0)
        val b = java.io.ByteArrayOutputStream()
        b.write(byteArrayOf(5, rep.toByte(), 0, if (a.size == 4) 1 else 4))
        b.write(a); b.write(p shr 8); b.write(p and 0xff)
        o.write(b.toByteArray()); o.flush()
    }

    /** Lee ATYP+ADDR+PORT; devuelve host (dominio o IP literal) y puerto, o null si ATYP invalido. */
    private fun readAddr(i: DataInputStream): Pair<String, Int>? {
        val host = when (i.readUnsignedByte()) {
            1 -> { val b = ByteArray(4); i.readFully(b); InetAddress.getByAddress(b).hostAddress }
            3 -> { val b = ByteArray(i.readUnsignedByte()); i.readFully(b); String(b, Charsets.ISO_8859_1) }
            4 -> { val b = ByteArray(16); i.readFully(b); InetAddress.getByAddress(b).hostAddress }
            else -> return null
        }
        return host!! to i.readUnsignedShort()
    }

    private fun handle(client: Socket) {
        var upstream: Socket? = null
        var conn: StatsStore.Conn? = null
        var udp: DatagramSocket? = null
        var out: DatagramSocket? = null
        try {
            client.soTimeout = 30_000
            client.tcpNoDelay = true
            val din = DataInputStream(client.getInputStream())
            val cout = client.getOutputStream()
            if (din.readUnsignedByte() != 5) return
            val methods = ByteArray(din.readUnsignedByte()); din.readFully(methods)
            if (0.toByte() !in methods) { cout.write(byteArrayOf(5, 0xFF.toByte())); return }
            cout.write(byteArrayOf(5, 0)); cout.flush()

            val hdr = ByteArray(3); din.readFully(hdr)
            if (hdr[0].toInt() != 5) return
            val cmd = hdr[1].toInt()
            val (host, p) = readAddr(din) ?: run { reply(cout, 8); return }
            val cip = client.inetAddress?.hostAddress ?: "?"
            client.soTimeout = 0

            when (cmd) {
                1 -> {
                    conn = StatsStore.open(cip, host, p)
                    if (!Rules.check(host)) { reply(cout, 2); return }
                    upstream = try { connect(host, p) } catch (e: Exception) {
                        reply(cout, if (e is java.net.UnknownHostException) 4 else if (e is java.net.ConnectException) 5 else 1); return
                    }
                    reply(cout, 0, upstream.localAddress, upstream.localPort)
                    pipe(client, upstream, conn)
                }
                3 -> {
                    // El cliente indica desde donde enviara (normalmente 0.0.0.0:0); solo se valida su IP.
                    conn = StatsStore.open(cip, "udp-associate", 0)
                    val net = egress()
                    udp = DatagramSocket(InetSocketAddress(client.localAddress, 0))
                    out = DatagramSocket(null).also { it.bind(InetSocketAddress(0)) }
                    net?.bindSocket(out)
                    reply(cout, 0, client.localAddress, udp.localPort)
                    udpRelay(client, udp, out, net, conn)
                }
                else -> reply(cout, 7)
            }
        } catch (_: Exception) {
        } finally {
            try { client.close() } catch (_: Exception) {}
            try { upstream?.close() } catch (_: Exception) {}
            try { udp?.close() } catch (_: Exception) {}
            try { out?.close() } catch (_: Exception) {}
            conn?.let { StatsStore.close(it) }
        }
    }

    /** Relay UDP mientras la conexion TCP de control siga abierta. */
    private fun udpRelay(ctl: Socket, udp: DatagramSocket, out: DatagramSocket, net: Network?, conn: StatsStore.Conn) {
        val clientAddr = java.util.concurrent.atomic.AtomicReference<InetSocketAddress?>(null)
        pool.execute { // fin de la asociacion: cierra al cerrarse el TCP de control
            try { while (ctl.getInputStream().read() >= 0) {} } catch (_: Exception) {}
            udp.close(); out.close()
        }
        pool.execute { // internet -> cliente
            val buf = ByteArray(65535)
            try {
                while (true) {
                    val pk = DatagramPacket(buf, buf.size)
                    out.receive(pk)
                    val to = clientAddr.get() ?: continue
                    val a = pk.address.address
                    val m = ByteArray(6 + a.size + pk.length)
                    m[3] = if (a.size == 4) 1 else 4
                    System.arraycopy(a, 0, m, 4, a.size)
                    m[4 + a.size] = (pk.port shr 8).toByte(); m[5 + a.size] = pk.port.toByte()
                    System.arraycopy(pk.data, pk.offset, m, 6 + a.size, pk.length)
                    udp.send(DatagramPacket(m, m.size, to))
                    StatsStore.totalDown.addAndGet(pk.length.toLong()); conn.down.addAndGet(pk.length.toLong())
                }
            } catch (_: Exception) {}
        }
        val buf = ByteArray(65535) // cliente -> internet (este hilo)
        try {
            while (true) {
                val pk = DatagramPacket(buf, buf.size)
                udp.receive(pk)
                if (pk.address != ctl.inetAddress) continue
                clientAddr.set(InetSocketAddress(pk.address, pk.port))
                val d = DataInputStream(java.io.ByteArrayInputStream(pk.data, pk.offset, pk.length))
                try {
                    d.skipBytes(2)
                    if (d.readUnsignedByte() != 0) continue // sin fragmentacion
                    val (host, p) = readAddr(d) ?: continue
                    if (!Rules.check(host)) continue
                    val n = d.available()
                    val data = ByteArray(n); d.readFully(data)
                    val dst = (if (net != null) net.getAllByName(host) else InetAddress.getAllByName(host))
                        .let { l -> l.firstOrNull { it is Inet4Address } ?: l.first() }
                    out.send(DatagramPacket(data, n, dst, p))
                    StatsStore.totalUp.addAndGet(n.toLong()); conn.up.addAndGet(n.toLong())
                } catch (_: IOException) {}
            }
        } catch (_: Exception) {}
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
        throw last ?: IOException("no address for $host")
    }

    private fun pipe(a: Socket, b: Socket, conn: StatsStore.Conn) {
        val done = CountDownLatch(2)
        fun copy(from: Socket, to: Socket, upDir: Boolean) = pool.execute {
            try {
                val i = from.getInputStream()
                val o = to.getOutputStream()
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
