package dev.ctrl.hotspot

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

class DevicesActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Dispositivos"
        setContentView(TextView(this).apply { text = "Dispositivos: pendiente"; setPadding(48, 160, 48, 48) })
    }
}
