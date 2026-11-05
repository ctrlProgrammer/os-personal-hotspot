package dev.ctrl.hotspot

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.TextUtils
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
    private class Row(val id: Long, val title: String, val sub: String, val up: String, val down: String, val open: Boolean)

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

    private fun dp(v: Int) = Ui.dp(this, v)

    private fun label(parent: ViewGroup?, text: String?): TextView =
        TextView(this).apply {
            this.text = text; textSize = 14f; setTextColor(Ui.TEXT); setBackgroundColor(Ui.SURFACE_HI)
            setPadding(dp(14), dp(10), dp(14), dp(10)); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }

    private inner class RowHolder(val root: LinearLayout) {
        val dot = TextView(this@ConnLogActivity).apply { text = "●"; textSize = 10f; setTextColor(Ui.GREEN); setPadding(0, 0, dp(8), 0) }
        val title = TextView(this@ConnLogActivity).apply {
            textSize = 15f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
            maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE
        }
        val sub = TextView(this@ConnLogActivity).apply { textSize = 12f; setTextColor(Ui.MUTED); maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        val up = TextView(this@ConnLogActivity).apply { textSize = 12f; setTextColor(Ui.GREEN); gravity = Gravity.END }
        val down = TextView(this@ConnLogActivity).apply { textSize = 12f; setTextColor(Ui.ACCENT); gravity = Gravity.END }

        init {
            root.orientation = LinearLayout.HORIZONTAL
            root.gravity = Gravity.CENTER_VERTICAL
            root.background = Ui.rounded(this@ConnLogActivity, Ui.SURFACE, 12)
            root.setPadding(dp(14), dp(10), dp(14), dp(10))
            root.addView(dot)
            val texts = LinearLayout(root.context).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(title); texts.addView(sub)
            root.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
            val stats = LinearLayout(root.context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, 0, 0) }
            stats.addView(up); stats.addView(down)
            root.addView(stats)
        }
    }

    private val rowAdapter = object : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = rows[position].id
        override fun hasStableIds() = true
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val r = rows[position]
            val h = (convertView?.tag as? RowHolder) ?: RowHolder(LinearLayout(this@ConnLogActivity)).also { it.root.tag = it }
            h.dot.visibility = if (r.open) View.VISIBLE else View.GONE
            h.title.text = r.title
            h.sub.text = r.sub
            h.up.text = r.up
            h.down.text = r.down
            return h.root
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Registro de conexiones"
        val body = buildScreen("Registro de conexiones", scroll = false)

        search = EditText(this).apply {
            hint = "Buscar host / IP / puerto"; isSingleLine = true
            styleInput()
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { refresh() }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        body.addView(search, LinearLayout.LayoutParams(-1, -2))

        devAdapter = object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, devices) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                label(parent, getItem(position)).apply { background = null }
            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
                label(parent, getItem(position))
        }
        spinner = Spinner(this).apply {
            adapter = devAdapter
            background = Ui.rounded(this@ConnLogActivity, Ui.SURFACE_HI, 12)
            setPopupBackgroundDrawable(Ui.rounded(this@ConnLogActivity, Ui.SURFACE_HI, 12))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    val n = if (pos == 0) null else devices.getOrNull(pos)
                    if (n != deviceFilter) { deviceFilter = n; refresh() }
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        onlyOpen = CheckBox(this).apply {
            text = "Solo abiertas"; textSize = 14f; setTextColor(Ui.TEXT)
            buttonTintList = ColorStateList.valueOf(Ui.ACCENT)
            setOnCheckedChangeListener { _, _ -> refresh() }
        }
        body.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(spinner, LinearLayout.LayoutParams(0, -2, 1f))
            addView(onlyOpen, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(12) })
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        fun btn(t: String, f: (Button) -> Unit) = Button(this).apply {
            text = t; styleSmall(); setOnClickListener { f(this) }
        }
        val bar = LinearLayout(this)
        fun lp(first: Boolean = false) = LinearLayout.LayoutParams(0, -2, 1f).apply { if (!first) leftMargin = dp(8) }
        bar.addView(btn("Top dominios") { b ->
            grouped = !grouped
            b.text = if (grouped) "Ver registro" else "Top dominios"
            refresh()
        }, lp(true))
        bar.addView(btn("CSV") { exportCsv() }, lp())
        bar.addView(btn("Copiar") { copyCsv() }, lp())
        bar.addView(btn("Limpiar") { StatsStore.clear(); refresh() }, lp())
        body.addView(bar, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        summary = TextView(this).apply { textSize = 12f; setTextColor(Ui.MUTED); setPadding(dp(4), dp(10), dp(4), dp(8)) }
        body.addView(summary)
        list = ListView(this).apply {
            adapter = rowAdapter
            divider = ColorDrawable(Color.TRANSPARENT); dividerHeight = dp(8)
            selector = ColorDrawable(Color.TRANSPARENT)
            setBackgroundColor(Color.TRANSPARENT); cacheColorHint = Color.TRANSPARENT
            isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER
        }
        body.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
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
                    "${t.second} conex. · total ${fmt(t.third.first + t.third.second)}",
                    "↑ ${fmt(t.third.first)}", "↓ ${fmt(t.third.second)}", false))
            }
        } else {
            for (c in conns) {
                val open = c.endMs == 0L
                val dur = fmtDur((if (open) now else c.endMs) - c.startMs)
                rows.add(Row(c.id, "${c.host}:${c.port}",
                    "${hf.format(Date(c.startMs))} · ${c.clientIp} · $dur",
                    "↑ ${fmt(c.up.get())}", "↓ ${fmt(c.down.get())}", open))
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
