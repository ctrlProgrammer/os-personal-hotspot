package dev.ctrl.hotspot

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

class TrafficActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Trafico en vivo"
        setContentView(TextView(this).apply { text = "Trafico en vivo: pendiente"; setPadding(48, 160, 48, 48) })
    }
}
