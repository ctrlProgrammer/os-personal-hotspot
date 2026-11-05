package dev.ctrl.hotspot

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

class ConnLogActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Registro de conexiones"
        setContentView(TextView(this).apply { text = "Registro de conexiones: pendiente"; setPadding(48, 160, 48, 48) })
    }
}
