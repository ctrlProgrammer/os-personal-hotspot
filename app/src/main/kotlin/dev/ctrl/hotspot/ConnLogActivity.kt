package dev.ctrl.hotspot

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ConnLogActivity : Activity() {
    private class Row(val id: Long, val line1: String, val line2: String, val open: Boolean)

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { refresh(); handler.postDelayed(this, 2000) }
    }
    private val hf = SimpleDateFormat("HH:mm:ss", Locale.US)

    private lateinit var search: EditText
    private lateinit var spinner: Spinner
    private lateinit var onlyOpen: CheckBox
    private lateinit var list: ListView
    private lateinit var summary: TextView
    private val rows = ArrayList<Row>()
    private var grouped = false
    private var deviceFilter: String? = null
    private val devices = ArrayList<String>().apply { add("Todos los dispositivos") }
    private lateinit var devAdapter: ArrayAdapter<String>

    private val rowAdapter = object : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = rows[position].id
        override fun hasStableIds() = true
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val r = rows[position]
            val l = (convertView as? LinearLayout) ?: LinearLayout(this@ConnLogActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(24, 12, 24, 12)
                addView(TextView(context).apply { textSize = 14f; typeface = Typeface.DEFAULT_BOLD })
                addView(TextView(context).apply { textSize = 12f })
            }
            (l.getChildAt(0) as TextView).text = r.line1
            (l.getChildAt(1) as TextView).text = r.line2
            l.setBackgroundColor(if (r.open) Color.argb(40, 0, 200, 0) else Color.TRANSPARENT)
            return l
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Registro de conexiones"
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16, 16, 16, 0) }

        search = EditText(this).apply {
            hint = "Buscar host / IP / puerto"; isSingleLine = true
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { refresh() }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        root.addView(search)

        devAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, devices)
        devAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner = Spinner(this).apply {
            adapter = devAdapter
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    val n = if (pos == 0) null else devices.getOrNull(pos)
                    if (n != deviceFilter) { deviceFilter = n; refresh() }
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        onlyOpen = CheckBox(this).apply { text = "Solo abiertas"; setOnCheckedChangeListener { _, _ -> refresh() } }
        root.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(spinner, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(onlyOpen)
        })

        fun btn(t: String, f: (Button) -> Unit) = Button(this).apply {
            text = t; textSize = 12f; setOnClickListener { f(this) }
        }
        val bar = LinearLayout(this)
        fun lp() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        bar.addView(btn("Top dominios") { b ->
            grouped = !grouped
            b.text = if (grouped) "Ver registro" else "Top dominios"
            refresh()
        }, lp())
        bar.addView(btn("CSV") { exportCsv() }, lp())
        bar.addView(btn("Copiar") { copyCsv() }, lp())
        bar.addView(btn("Limpiar") { StatsStore.clear(); refresh() }, lp())
        root.addView(bar)

        summary = TextView(this).apply { textSize = 12f; setPadding(8, 4, 8, 4) }
        root.addView(summary)
        list = ListView(this).apply { adapter = rowAdapter }
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    override fun onResume() { super.onResume(); handler.post(tick) }
    override fun onPause() { super.onPause(); handler.removeCallbacks(tick) }

    private fun snapshot(): List<StatsStore.Conn> {
        val q = search.text.toString().trim().lowercase()
        val all = StatsStore.history.toList()
        val seen = all.map { it.clientIp }.toSortedSet()
        if (devices.size - 1 != seen.size || !devices.drop(1).containsAll(seen)) {
            devices.subList(1, devices.size).clear()
            devices.addAll(seen)
            devAdapter.notifyDataSetChanged()
            val i = devices.indexOf(deviceFilter).let { if (it < 0) 0 else it }
            if (i == 0) deviceFilter = null
            if (spinner.selectedItemPosition != i) spinner.setSelection(i)
        }
        return all.asReversed().filter { c ->
            (deviceFilter == null || c.clientIp == deviceFilter) &&
                (!onlyOpen.isChecked || c.endMs == 0L) &&
                (q.isEmpty() || c.host.lowercase().contains(q) || c.clientIp.contains(q) || c.port.toString().contains(q))
        }
    }

    private fun refresh() {
        val conns = snapshot()
        val now = System.currentTimeMillis()
        // ancla de scroll: id del primer elemento visible y su offset
        val first = list.firstVisiblePosition
        val anchorId = rows.getOrNull(first)?.id
        val top = list.getChildAt(0)?.top ?: 0
        rows.clear()
        if (grouped) {
            conns.groupBy { it.host }.map { (h, cs) ->
                Triple(h, cs.size, cs.sumOf { it.up.get() } to cs.sumOf { it.down.get() })
            }.sortedByDescending { it.third.first + it.third.second }.take(200).forEachIndexed { i, t ->
                rows.add(Row(-(i + 1L), "${i + 1}. ${t.first}",
                    "${t.second} conex. | total ${fmt(t.third.first + t.third.second)} (↑${fmt(t.third.first)} ↓${fmt(t.third.second)})",
                    false))
            }
        } else {
            for (c in conns) {
                val open = c.endMs == 0L
                val dur = fmtDur((if (open) now else c.endMs) - c.startMs)
                rows.add(Row(c.id,
                    (if (open) "● " else "") + "${c.host}:${c.port}",
                    "${hf.format(Date(c.startMs))} | ${c.clientIp} | $dur | ↑${fmt(c.up.get())} ↓${fmt(c.down.get())}",
                    open))
            }
        }
        rowAdapter.notifyDataSetChanged()
        if (anchorId != null && !grouped && first > 0) {
            val idx = rows.indexOfFirst { it.id == anchorId }
            if (idx >= 0) list.setSelectionFromTop(idx, top)
        }
        summary.text = if (grouped) "Top dominios: ${rows.size}" else "${rows.size} conexiones (${StatsStore.active.size} abiertas)"
    }

    private fun fmt(b: Long): String = when {
        b < 1024 -> "$b B"
        b < 1024 * 1024 -> "%.1f KB".format(Locale.US, b / 1024.0)
        b < 1024L * 1024 * 1024 -> "%.1f MB".format(Locale.US, b / 1048576.0)
        else -> "%.2f GB".format(Locale.US, b / 1073741824.0)
    }

    private fun fmtDur(ms: Long): String {
        val s = ms / 1000
        return if (s < 60) "${s}s" else if (s < 3600) "${s / 60}m${s % 60}s" else "${s / 3600}h${(s % 3600) / 60}m"
    }

    private fun csv(): String {
        val iso = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val sb = StringBuilder("inicio,fin,dispositivo,host,puerto,up_bytes,down_bytes\n")
        for (c in StatsStore.history) {
            sb.append(iso.format(Date(c.startMs))).append(',')
                .append(if (c.endMs == 0L) "" else iso.format(Date(c.endMs))).append(',')
                .append(c.clientIp).append(',')
                .append('"').append(c.host.replace("\"", "\"\"")).append("\",")
                .append(c.port).append(',').append(c.up.get()).append(',').append(c.down.get()).append('\n')
        }
        return sb.toString()
    }

    private fun exportCsv() {
        try {
            val dir = getExternalFilesDir(null) ?: cacheDir
            val f = File(dir, "conexiones_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".csv")
            f.writeText(csv())
            Toast.makeText(this, "Guardado: ${f.absolutePath}", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Error al exportar: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun copyCsv() {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("conexiones.csv", csv()))
        Toast.makeText(this, "CSV copiado al portapapeles", Toast.LENGTH_SHORT).show()
    }
}
