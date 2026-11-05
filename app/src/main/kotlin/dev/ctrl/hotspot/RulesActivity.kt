package dev.ctrl.hotspot

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

class RulesActivity : Activity() {
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Rules.init(this)
        val body = buildScreen("Reglas")

        body.card {
            text("SOCKS5", 17f, bold = true)
            text("El proxy SOCKS5 corre en 192.168.49.1:1080 (con soporte UDP).", 14f, Ui.MUTED).spaceTop(6)
            text("El proxy HTTP corre en el puerto :8282. Las reglas aplican a ambos.", 14f, Ui.MUTED)
            kv("SOCKS5", "192.168.49.1:1080").spaceTop(8)
            kv("HTTP", "192.168.49.1:8282")
        }

        body.card {
            text("Nueva regla", 17f, bold = true)
            val input = EditText(context).apply {
                hint = "ejemplo: steampowered.com"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                setSingleLine()
                styleInput()
            }
            addView(input, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(context, 10) })
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(ruleButton("Permitir", Ui.GREEN, input, true), weighted(end = 6))
            row.addView(ruleButton("Bloquear", Ui.RED, input, false), weighted(start = 6))
            addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(context, 10) })
            text("Vale para el dominio y sus subdominios; gana la regla mas especifica", 12f, Ui.MUTED).spaceTop(10)
        }

        body.sectionTitle("Reglas activas")
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(list, LinearLayout.LayoutParams(-1, -2))
        render()
    }

    private fun weighted(start: Int = 0, end: Int = 0) = LinearLayout.LayoutParams(0, -2, 1f).apply {
        leftMargin = Ui.dp(this@RulesActivity, start); rightMargin = Ui.dp(this@RulesActivity, end)
    }

    private fun ruleButton(label: String, color: Int, input: EditText, allow: Boolean) = Button(this).apply {
        styleSmall()
        text = label
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(0xFF0E1116.toInt())
        background = Ui.ripple(context, color, 10)
        setOnClickListener {
            Rules.add(input.text.toString(), allow)
            input.setText("")
            render()
        }
    }

    private fun chip(allow: Boolean) = TextView(this).apply {
        text = if (allow) "PERMITIR" else "BLOQUEAR"
        textSize = 11f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(0xFF0E1116.toInt())
        background = Ui.rounded(context, if (allow) Ui.GREEN else Ui.RED, 8)
        setPadding(Ui.dp(context, 8), Ui.dp(context, 3), Ui.dp(context, 8), Ui.dp(context, 3))
    }

    private fun render() {
        list.removeAllViews()
        val rules = Rules.list()
        if (rules.isEmpty()) {
            list.card {
                gravity = Gravity.CENTER_HORIZONTAL
                text("Sin reglas todavia", 15f, bold = true)
                text("Todo el trafico esta permitido. Agrega un dominio arriba para permitirlo o bloquearlo.", 13f, Ui.MUTED).spaceTop(4)
            }
            return
        }
        for (r in rules) list.card {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.rounded(context, Ui.SURFACE, 12)
            val info = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            info.text(r.pattern, 15f, bold = true, mono = true)
            info.addView(chip(r.allow), LinearLayout.LayoutParams(-2, -2).apply { topMargin = Ui.dp(context, 6) })
            addView(info, LinearLayout.LayoutParams(0, -2, 1f))
            addView(Button(context).apply {
                styleSmall()
                text = "Borrar"
                setTextColor(Ui.RED)
                setOnClickListener { Rules.remove(r.pattern); render() }
            }, LinearLayout.LayoutParams(-2, -2))
        }
    }
}
