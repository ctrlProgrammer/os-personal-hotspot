package dev.ctrl.hotspot

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Consumo acumulado por dia (SharedPreferences, clave yyyy-MM-dd, valor "up,down" en bytes). */
object DailyUsage {
    private const val PREFS = "daily_usage"
    private const val PERIOD_MS = 10_000L
    private val handler = Handler(Looper.getMainLooper())
    private var lastUp = 0L
    private var lastDown = 0L
    private var started = false

    private fun fmt() = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    private val ticker = object : Runnable {
        lateinit var ctx: Context
        override fun run() { tick(ctx); handler.postDelayed(this, PERIOD_MS) }
    }

    /** Llamar al iniciar el servicio: tick cada 10 s. */
    fun start(ctx: Context) {
        if (started) return
        started = true
        lastUp = StatsStore.totalUp.get(); lastDown = StatsStore.totalDown.get()
        ticker.ctx = ctx.applicationContext
        handler.postDelayed(ticker, PERIOD_MS)
    }

    /** Llamar al parar el servicio: ultimo tick y detiene el ciclo. */
    fun stop(ctx: Context) {
        handler.removeCallbacks(ticker)
        if (started) tick(ctx)
        started = false
    }

    @Synchronized
    fun tick(ctx: Context) {
        val up = StatsStore.totalUp.get(); val down = StatsStore.totalDown.get()
        // si StatsStore.clear() reinicio los totales, el delta es el valor nuevo
        val dUp = if (up >= lastUp) up - lastUp else up
        val dDown = if (down >= lastDown) down - lastDown else down
        lastUp = up; lastDown = down
        if (dUp == 0L && dDown == 0L) return
        val p = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = fmt().format(Date())
        val (u, d) = read(p.getString(key, null))
        p.edit().putString(key, "${u + dUp},${d + dDown}").apply()
    }

    private fun read(s: String?): Pair<Long, Long> {
        val parts = s?.split(",") ?: return 0L to 0L
        return (parts.getOrNull(0)?.toLongOrNull() ?: 0L) to (parts.getOrNull(1)?.toLongOrNull() ?: 0L)
    }

    /** Hoy y los n-1 dias anteriores, el mas reciente primero: (fecha, subida, bajada). */
    fun lastDays(ctx: Context, n: Int = 7): List<Triple<String, Long, Long>> {
        val p = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cal = Calendar.getInstance()
        val f = fmt()
        return (0 until n).map {
            val key = f.format(cal.time)
            cal.add(Calendar.DAY_OF_YEAR, -1)
            val (u, d) = read(p.getString(key, null))
            Triple(key, u, d)
        }
    }
}
