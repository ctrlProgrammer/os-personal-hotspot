package dev.ctrl.hotspot

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

class NetToolsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Herramientas de red"
        setContentView(TextView(this).apply { text = "Herramientas de red: pendiente"; setPadding(48, 160, 48, 48) })
    }
}
