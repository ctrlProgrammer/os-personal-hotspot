package dev.ctrl.hotspot

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

/** Grafico de lineas de los ultimos 60 s: bajada (acento) y subida (verde), en bytes/s. */
class TrafficGraphView(ctx: Context) : View(ctx) {
    var up: List<Long> = emptyList()
    var down: List<Long> = emptyList()
    private val d = resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f * d; strokeJoin = Paint.Join.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val grid = Paint().apply { color = Ui.SURFACE_HI; strokeWidth = 1f * d }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.MUTED; textSize = 11f * d }

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(getDefaultSize(0, w), (d * 180).toInt())
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val top = 16f * d; val bottom = h - 18f * d
        val maxV = maxOf(1024L, up.maxOrNull() ?: 0L, down.maxOrNull() ?: 0L)
        for (i in 0..2) {
            val y = top + (bottom - top) * i / 2
            c.drawLine(0f, y, w, y, grid)
        }
        txt.textAlign = Paint.Align.LEFT
        c.drawText(TrafficActivity.shortRate(maxV), 2f * d, top - 4f * d, txt)
        c.drawText(TrafficActivity.shortRate(maxV / 2), 2f * d, (top + bottom) / 2 - 4f * d, txt)
        c.drawText("-60 s", 2f * d, h - 3f * d, txt)
        txt.textAlign = Paint.Align.RIGHT
        c.drawText("ahora", w - 2f * d, h - 3f * d, txt)
        plot(c, down, maxV, Ui.ACCENT, w, top, bottom)
        plot(c, up, maxV, Ui.GREEN, w, top, bottom)
    }

    private fun plot(c: Canvas, data: List<Long>, maxV: Long, color: Int, w: Float, top: Float, bottom: Float) {
        if (data.size < 2) return
        val path = Path()
        val step = w / (TrafficActivity.WINDOW - 1)
        val x0 = w - (data.size - 1) * step
        var lastX = x0
        data.forEachIndexed { i, v ->
            val x = x0 + i * step
            val y = bottom - (bottom - top) * v / maxV
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            lastX = x
        }
        val area = Path(path).apply { lineTo(lastX, bottom); lineTo(x0, bottom); close() }
        fill.color = (color and 0x00FFFFFF) or 0x28000000
        c.drawPath(area, fill)
        line.color = color
        c.drawPath(path, line)
    }
}

class TrafficActivity : Activity() {
    companion object {
        const val WINDOW = 60

        fun bytes(b: Long): String = Ui.fmtBytes(b)

        fun shortRate(bps: Long): String =
            if (bps >= 1L shl 20) String.format(Locale.US, "%.1f MB/s", bps / 1048576.0)
            else String.format(Locale.US, "%.0f KB/s", bps / 1024.0)

        /** bytes/s -> "123.4 KB/s (1.00 Mbps)" */
        fun rate(bps: Long): String {
            val mbps = bps * 8 / 1_000_000.0
            val base = if (bps >= 1L shl 20) String.format(Locale.US, "%.2f MB/s", bps / 1048576.0)
                       else String.format(Locale.US, "%.1f KB/s", bps / 1024.0)
            return String.format(Locale.US, "%s (%.2f Mbps)", base, mbps)
        }

        private fun big(bps: Long): String =
            if (bps >= 1L shl 20) String.format(Locale.US, "%.2f MB/s", bps / 1048576.0)
            else String.format(Locale.US, "%.1f KB/s", bps / 1024.0)

        private fun mbps(bps: Long): String = String.format(Locale.US, "%.2f Mbps", bps * 8 / 1_000_000.0)
    }

    private val handler = Handler(Looper.getMainLooper())
    private val upHist = ArrayDeque<Long>()
    private val downHist = ArrayDeque<Long>()
    private var lastUp = 0L
    private var lastDown = 0L
    private var lastT = 0L
    private lateinit var downTv: TextView
    private lateinit var upTv: TextView
    private lateinit var downMbps: TextView
    private lateinit var upMbps: TextView
    private lateinit var sessionUp: TextView
    private lateinit var sessionDown: TextView
    private lateinit var sessionConns: TextView
    private lateinit var daysBox: LinearLayout
    private lateinit var graph: TrafficGraphView

    private val tick = object : Runnable {
        override fun run() { sample(); handler.postDelayed(this, 1000) }
    }

    private fun LinearLayout.caption(s: String): TextView =
        text(s, 12f, Ui.MUTED, bold = true).also { it.letterSpacing = 0.08f; it.setPadding(0, 0, 0, Ui.dp(context, 4)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val body = buildScreen("Trafico en vivo")
        val d = { v: Int -> Ui.dp(this, v) }

        body.card {
            val row = LinearLayout(context)
            fun column(label: String, color: Int): Pair<TextView, TextView> {
                val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                col.text(label, 13f, Ui.MUTED)
                val big = col.text("0.0 KB/s", 26f, color, bold = true).also { it.setPadding(0, d(4), 0, 0) }
                val sub = col.text("0.00 Mbps", 13f, Ui.MUTED)
                row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
                return big to sub
            }
            column("↓ Bajada", Ui.ACCENT).also { downTv = it.first; downMbps = it.second }
            column("↑ Subida", Ui.GREEN).also { upTv = it.first; upMbps = it.second }
            addView(row)
        }

        body.card {
            val head = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
            head.addView(TextView(context).apply {
                text = "ULTIMOS 60 S"; textSize = 12f; setTextColor(Ui.MUTED)
                typeface = Typeface.DEFAULT_BOLD; letterSpacing = 0.08f
            }, LinearLayout.LayoutParams(0, -2, 1f))
            head.addView(TextView(context).apply { text = "● Bajada  "; textSize = 12f; setTextColor(Ui.ACCENT) })
            head.addView(TextView(context).apply { text = "● Subida"; textSize = 12f; setTextColor(Ui.GREEN) })
            addView(head)
            graph = TrafficGraphView(context)
            addView(graph, LinearLayout.LayoutParams(-1, -2).apply { topMargin = d(8) })
        }

        body.card {
            caption("SESION")
            sessionUp = kv("Subido", "0 B")
            sessionDown = kv("Bajado", "0 B")
            sessionConns = kv("Conexiones activas", "0")
        }

        body.card {
            caption("ULTIMOS 7 DIAS")
            daysBox = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            addView(daysBox)
        }
    }

    override fun onResume() {
        super.onResume()
        lastUp = StatsStore.totalUp.get(); lastDown = StatsStore.totalDown.get()
        lastT = System.currentTimeMillis()
        handler.post(tick)
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        super.onPause()
    }

    private fun sample() {
        val now = System.currentTimeMillis()
        val up = StatsStore.totalUp.get(); val down = StatsStore.totalDown.get()
        val dt = maxOf(1L, now - lastT)
        // totales reiniciados (StatsStore.clear): contar desde cero
        if (up < lastUp || down < lastDown) { lastUp = 0; lastDown = 0 }
        val su = (up - lastUp) * 1000 / dt
        val sd = (down - lastDown) * 1000 / dt
        lastUp = up; lastDown = down; lastT = now
        upHist.addLast(su); downHist.addLast(sd)
        while (upHist.size > WINDOW) upHist.removeFirst()
        while (downHist.size > WINDOW) downHist.removeFirst()

        downTv.text = big(sd); downMbps.text = mbps(sd)
        upTv.text = big(su); upMbps.text = mbps(su)
        sessionUp.text = bytes(up); sessionDown.text = bytes(down)
        sessionConns.text = StatsStore.active.size.toString()
        graph.up = upHist.toList(); graph.down = downHist.toList(); graph.invalidate()

        DailyUsage.tick(this) // hoy al dia aunque el servicio no haya hecho tick todavia
        renderDays(DailyUsage.lastDays(this))
    }

    private fun renderDays(days: List<Triple<String, Long, Long>>) {
        daysBox.removeAllViews()
        val maxTotal = maxOf(1L, days.maxOfOrNull { it.second + it.third } ?: 1L)
        days.forEachIndexed { i, (day, u, dn) ->
            val today = i == 0
            val row = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, Ui.dp(context, 5), 0, Ui.dp(context, 5))
            }
            row.addView(TextView(this).apply {
                text = if (today) "Hoy" else day.takeLast(5); textSize = 12f
                setTextColor(if (today) Ui.TEXT else Ui.MUTED)
                if (today) typeface = Typeface.DEFAULT_BOLD
            }, LinearLayout.LayoutParams(Ui.dp(this, 46), -2))
            val track = LinearLayout(this).apply { background = Ui.rounded(context, Ui.SURFACE_HI, 4) }
            val frac = (u + dn).toFloat() / maxTotal
            val bar = View(this).apply { background = Ui.rounded(context, if (today) Ui.ACCENT else 0xFF3A5F99.toInt(), 4) }
            track.addView(bar, LinearLayout.LayoutParams(0, -1, maxOf(frac, 0.01f)))
            track.addView(View(this), LinearLayout.LayoutParams(0, -1, maxOf(1f - frac, 0f)))
            row.addView(track, LinearLayout.LayoutParams(0, Ui.dp(this, 12), 1f))
            row.addView(TextView(this).apply {
                text = bytes(u + dn); textSize = 12f; gravity = Gravity.END
                setTextColor(if (today) Ui.TEXT else Ui.MUTED)
            }, LinearLayout.LayoutParams(Ui.dp(this, 72), -2).apply { leftMargin = Ui.dp(this@TrafficActivity, 8) })
            daysBox.addView(row)
        }
    }
}
