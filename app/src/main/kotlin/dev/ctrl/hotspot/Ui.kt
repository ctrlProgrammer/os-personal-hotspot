package dev.ctrl.hotspot

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Kit visual comun: tema oscuro, margenes seguros (barra de estado/navegacion), tarjetas y botones. */
object Ui {
    val BG = 0xFF0E1116.toInt()
    val SURFACE = 0xFF171C24.toInt()
    val SURFACE_HI = 0xFF222A35.toInt()
    val ACCENT = 0xFF4F8CFF.toInt()
    val GREEN = 0xFF3DDC84.toInt()
    val RED = 0xFFFF5C5C.toInt()
    val AMBER = 0xFFFFB84D.toInt()
    val TEXT = 0xFFE6EDF3.toInt()
    val MUTED = 0xFF8B98A5.toInt()

    fun dp(c: Context, v: Int): Int = (v * c.resources.displayMetrics.density).toInt()

    fun rounded(c: Context, color: Int, radiusDp: Int = 16): GradientDrawable =
        GradientDrawable().apply { setColor(color); cornerRadius = dp(c, radiusDp).toFloat() }

    fun ripple(c: Context, color: Int, radiusDp: Int = 16): RippleDrawable =
        RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), rounded(c, color, radiusDp), null)

    fun fmtBytes(b: Long): String = when {
        b >= 1L shl 30 -> "%.2f GB".format(b / 1073741824.0)
        b >= 1L shl 20 -> "%.1f MB".format(b / 1048576.0)
        b >= 1L shl 10 -> "%.0f KB".format(b / 1024.0)
        else -> "$b B"
    }
}

/**
 * Crea la pantalla estandar: fondo oscuro, margenes seguros, cabecera con flecha atras y titulo.
 * Devuelve el contenedor vertical donde se agrega el contenido. [scroll] = dentro de un ScrollView.
 * Si [scroll] es false, el body ocupa todo el alto restante (util para listas y graficos).
 */
fun Activity.buildScreen(titleText: String, back: Boolean = true, scroll: Boolean = true): LinearLayout {
    val d = { v: Int -> Ui.dp(this, v) }
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Ui.BG)
        setOnApplyWindowInsetsListener { v, w ->
            @Suppress("DEPRECATION")
            v.setPadding(w.systemWindowInsetLeft, w.systemWindowInsetTop, w.systemWindowInsetRight, w.systemWindowInsetBottom)
            w
        }
    }
    val header = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(d(8), d(8), d(16), d(8))
    }
    if (back) header.addView(TextView(this).apply {
        text = "←"; textSize = 26f; setTextColor(Ui.TEXT); gravity = Gravity.CENTER
        setPadding(d(14), d(4), d(14), d(4))
        setOnClickListener { finish() }
    })
    else header.setPadding(d(20), d(8), d(16), d(8))
    header.addView(TextView(this).apply {
        text = titleText; textSize = 22f; setTextColor(Ui.TEXT); typeface = Typeface.DEFAULT_BOLD
    })
    root.addView(header, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    val body = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(d(16), d(4), d(16), d(16))
    }
    if (scroll) {
        val sv = ScrollView(this).apply { isFillViewport = true; addView(body) }
        root.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    } else {
        root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }
    setContentView(root)
    // iconos de la barra de estado/navegacion en claro (fondo oscuro); debe ir DESPUES de setContentView
    window.insetsController?.setSystemBarsAppearance(
        0,
        android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
            android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
    )
    root.requestApplyInsets()
    return body
}

/** Tarjeta con fondo redondeado; agrega hijos en [block]. */
fun LinearLayout.card(block: LinearLayout.() -> Unit): LinearLayout {
    val c = context
    val card = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        background = Ui.rounded(c, Ui.SURFACE)
        setPadding(Ui.dp(c, 16), Ui.dp(c, 14), Ui.dp(c, 16), Ui.dp(c, 14))
    }
    card.block()
    addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = Ui.dp(c, 12) })
    return card
}

fun LinearLayout.sectionTitle(text: String): TextView {
    val t = TextView(context).apply {
        this.text = text.uppercase(); textSize = 12f; setTextColor(Ui.MUTED)
        typeface = Typeface.DEFAULT_BOLD; letterSpacing = 0.08f
        setPadding(Ui.dp(context, 4), Ui.dp(context, 8), 0, Ui.dp(context, 8))
    }
    addView(t); return t
}

fun LinearLayout.text(s: String, sizeSp: Float = 15f, color: Int = Ui.TEXT, bold: Boolean = false, mono: Boolean = false): TextView {
    val t = TextView(context).apply {
        text = s; textSize = sizeSp; setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        if (mono) typeface = Typeface.MONOSPACE
    }
    addView(t); return t
}

/** Fila etiqueta (gris) + valor (blanco). Devuelve el TextView del valor para actualizarlo. */
fun LinearLayout.kv(label: String, value: String): TextView {
    val c = context
    val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, Ui.dp(c, 4), 0, Ui.dp(c, 4)) }
    row.addView(TextView(c).apply { text = label; textSize = 14f; setTextColor(Ui.MUTED) }, LinearLayout.LayoutParams(0, -2, 1f))
    val v = TextView(c).apply { text = value; textSize = 14f; setTextColor(Ui.TEXT); gravity = Gravity.END }
    row.addView(v, LinearLayout.LayoutParams(0, -2, 1.4f))
    addView(row); return v
}

fun LinearLayout.button(label: String, primary: Boolean = false, danger: Boolean = false, onClick: () -> Unit): Button {
    val c = context
    val color = when { danger -> Ui.RED; primary -> Ui.ACCENT; else -> Ui.SURFACE_HI }
    val b = Button(c).apply {
        text = label; isAllCaps = false; textSize = 15f; setTextColor(if (primary || danger) 0xFF0E1116.toInt() else Ui.TEXT)
        typeface = Typeface.DEFAULT_BOLD
        background = Ui.ripple(c, color, 12); stateListAnimator = null
        minHeight = Ui.dp(c, 48); minimumHeight = Ui.dp(c, 48)
        setOnClickListener { onClick() }
    }
    addView(b, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(c, 6) })
    return b
}

/** Estilo para un Button/EditText ya creado por una pantalla (botones pequenos en filas). */
fun Button.styleSmall(primary: Boolean = false) {
    isAllCaps = false; textSize = 13f; stateListAnimator = null
    setTextColor(if (primary) 0xFF0E1116.toInt() else Ui.TEXT)
    background = Ui.ripple(context, if (primary) Ui.ACCENT else Ui.SURFACE_HI, 10)
    minHeight = Ui.dp(context, 40); minimumHeight = Ui.dp(context, 40)
    minWidth = 0; minimumWidth = 0
    setPadding(Ui.dp(context, 14), 0, Ui.dp(context, 14), 0)
}

fun android.widget.EditText.styleInput() {
    setTextColor(Ui.TEXT); setHintTextColor(Ui.MUTED); textSize = 15f
    background = Ui.rounded(context, Ui.SURFACE_HI, 12)
    setPadding(Ui.dp(context, 14), Ui.dp(context, 10), Ui.dp(context, 14), Ui.dp(context, 10))
}

fun View.spaceTop(dp: Int) { (layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin = Ui.dp(context, dp) }
