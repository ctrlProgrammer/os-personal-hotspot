package dev.ctrl.hotspot

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** Ver/agregar/borrar reglas por dominio (sufijo). Aplican a SOCKS5 (1080) y al proxy HTTP (8282). */
class RulesActivity : Activity() {
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Rules.init(this)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setOnApplyWindowInsetsListener { v, w ->
                @Suppress("DEPRECATION")
                v.setPadding(pad, w.systemWindowInsetTop + pad, pad, w.systemWindowInsetBottom + pad)
                w
            }
        }
        root.addView(TextView(this).apply {
            text = "SOCKS5: 192.168.49.1:1080 (sin auth, TCP + UDP ASSOCIATE)\nReglas por sufijo de dominio; gana la mas especifica. Sin regla = permitido."
        })
        val input = EditText(this).apply {
            hint = "dominio (ej. steampowered.com)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        root.addView(input)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((label, allow) in listOf("Permitir" to true, "Bloquear" to false)) {
            row.addView(Button(this).apply {
                text = label
                setOnClickListener { Rules.add(input.text.toString(), allow); input.setText(""); render() }
            })
        }
        root.addView(row)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(android.widget.ScrollView(this).apply { addView(list) })
        setContentView(root)
        render()
    }

    private fun render() {
        list.removeAllViews()
        for (r in Rules.list()) list.addView(Button(this).apply {
            text = (if (r.allow) "PERMITIR  " else "BLOQUEAR  ") + r.pattern + "   (tocar para borrar)"
            setOnClickListener { Rules.remove(r.pattern); render() }
        })
    }
}
