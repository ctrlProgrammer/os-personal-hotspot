package dev.ctrl.hotspot

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale

/** Grafico de lineas simple: subida (verde) y bajada (azul), ultimas muestras en bytes/s. */
class TrafficGraphView(ctx: Context) : View(ctx) {
    var up: List<Long> = emptyList()
    var down: List<Long> = emptyList()
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4f }
    private val grid = Paint().apply { color = Color.LTGRAY; strokeWidth = 1f }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.DKGRAY; textSize = 28f }

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(getDefaultSize(0, w), (resources.displayMetrics.density * 160).toInt())
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val maxV = maxOf(1024L, up.maxOrNull() ?: 0L, down.maxOrNull() ?: 0L)
        c.drawLine(0f, h - 1, w, h - 1, grid)
        c.drawLine(0f, h / 2, w, h / 2, grid)
        c.drawText("max " + TrafficActivity.rate(maxV), 8f, 30f, txt)
        plot(c, down, maxV, Color.rgb(25, 118, 210), w, h)
        plot(c, up, maxV, Color.rgb(46, 125, 50), w, h)
    }

    private fun plot(c: Canvas, data: List<Long>, maxV: Long, color: Int, w: Float, h: Float) {
        if (data.size < 2) return
        line.color = color
        val path = Path()
        val step = w / (TrafficActivity.WINDOW - 1)
        val x0 = w - (data.size - 1) * step
        data.forEachIndexed { i, v ->
            val x = x0 + i * step
            val y = h - 4 - (h - 8) * v / maxV
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        c.drawPath(path, line)
    }
}

class TrafficActivity : Activity() {
    companion object {
        const val WINDOW = 60

        fun bytes(b: Long): String = when {
            b >= 1L shl 30 -> String.format(Locale.US, "%.2f GB", b / 1073741824.0)
            b >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", b / 1048576.0)
            b >= 1L shl 10 -> String.format(Locale.US, "%.1f KB", b / 1024.0)
            else -> "$b B"
        }

        /** bytes/s -> "123.4 KB/s (1.00 Mbps)" */
        fun rate(bps: Long): String {
            val mbps = bps * 8 / 1_000_000.0
            val base = if (bps >= 1L shl 20) String.format(Locale.US, "%.2f MB/s", bps / 1048576.0)
                       else String.format(Locale.US, "%.1f KB/s", bps / 1024.0)
            return String.format(Locale.US, "%s (%.2f Mbps)", base, mbps)
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val upHist = ArrayDeque<Long>()
    private val downHist = ArrayDeque<Long>()
    private var lastUp = 0L
    private var lastDown = 0L
    private var lastT = 0L
    private lateinit var speedTv: TextView
    private lateinit var totalTv: TextView
    private lateinit var daysTv: TextView
    private lateinit var graph: TrafficGraphView

    private val tick = object : Runnable {
        override fun run() { sample(); handler.postDelayed(this, 1000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Trafico en vivo"
        val pad = (16 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        speedTv = TextView(this).apply { textSize = 18f }
        totalTv = TextView(this).apply { textSize = 15f; setPadding(0, pad / 2, 0, pad / 2) }
        graph = TrafficGraphView(this)
        daysTv = TextView(this).apply { textSize = 14f; setPadding(0, pad, 0, 0); typeface = Typeface.MONOSPACE }
        col.addView(speedTv)
        col.addView(totalTv)
        col.addView(TextView(this).apply { text = "Ultimos 60 s (verde: subida, azul: bajada)"; textSize = 12f })
        col.addView(graph, LinearLayout.LayoutParams(-1, -2))
        col.addView(daysTv)
        setContentView(ScrollView(this).apply { addView(col) })
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

        speedTv.text = "Subida:  ${rate(su)}\nBajada: ${rate(sd)}"
        totalTv.text = "Sesion: subida ${bytes(up)}, bajada ${bytes(down)}\nConexiones activas: ${StatsStore.active.size}"
        graph.up = upHist.toList(); graph.down = downHist.toList(); graph.invalidate()

        DailyUsage.tick(this) // hoy al dia aunque el servicio no haya hecho tick todavia
        daysTv.text = "Consumo por dia (sube / baja)\n" + DailyUsage.lastDays(this).mapIndexed { i, (d, u, dn) ->
            (if (i == 0) "Hoy  " else "     ") + "$d  ${bytes(u)} / ${bytes(dn)}"
        }.joinToString("\n")
    }
}
