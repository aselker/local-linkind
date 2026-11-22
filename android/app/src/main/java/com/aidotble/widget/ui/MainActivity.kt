package com.aidotble.widget.ui

import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.aidotble.widget.ble.BleOps
import com.aidotble.widget.ble.Protocol
import com.aidotble.widget.data.Bulb
import com.aidotble.widget.data.BulbStore
import com.aidotble.widget.widget.BulbWidgetProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var store: BulbStore
    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = BulbStore.get(this)
        content = column()
        setContentView(ScrollView(this).apply { addView(content) })
        if (!Permissions.allGranted(this)) {
            ActivityCompat.requestPermissions(this, Permissions.required(), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        render()
        com.aidotble.widget.widget.BulbWidgetProvider.refreshAll(this)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun render() {
        content.removeAllViews()
        content.add(text("Account-free bulb control", 22f, bold = true))

        val bulbs = store.all()
        if (bulbs.isEmpty()) {
            content.add(text("No bulbs yet. Tap “Add bulb” to scan for one over Bluetooth.", 15f))
        }
        for (bulb in bulbs) {
            val glyph = if (bulb.lastOn) "●" else "○" // ● / ○
            content.add(text("$glyph  ${bulb.name}", 18f, bold = true))
            content.add(text("${bulb.address} · ${bulb.modelId}", 12f))
            val controls = row()
            controls.add(button("On") { apply(bulb, "on", Protocol.Attr.OnOff(true)) })
            controls.add(button("Off") { apply(bulb, "off", Protocol.Attr.OnOff(false)) })
            controls.add(button("Edit") {
                startActivity(Intent(this, BulbEditActivity::class.java).putExtra("address", bulb.address))
            })
            content.add(controls)
        }

        if (bulbs.size > 1) {
            content.add(text("", 6f))
            val allRow = row()
            allRow.add(button("All on") { applyAll(true) })
            allRow.add(button("All off") { applyAll(false) })
            content.add(allRow)
        }

        content.add(text("", 8f))
        content.add(button("＋ Add bulb") {
            if (!Permissions.allGranted(this)) {
                ActivityCompat.requestPermissions(this, Permissions.required(), 1)
            } else {
                startActivity(Intent(this, ProvisionActivity::class.java))
            }
        })

        val awm = android.appwidget.AppWidgetManager.getInstance(this)
        if (awm.isRequestPinAppWidgetSupported) {
            content.add(button("Add widget to home screen") {
                awm.requestPinAppWidget(
                    android.content.ComponentName(this, BulbWidgetProvider::class.java), null, null,
                )
            })
        }
    }

    private fun applyAll(on: Boolean) {
        Toast.makeText(this, "All ${if (on) "on" else "off"}…", Toast.LENGTH_SHORT).show()
        scope.launch {
            val (ok, total) = withContext(Dispatchers.IO) { BleOps.setAll(this@MainActivity, on) }
            BulbWidgetProvider.refreshAll(this@MainActivity)
            render()
            Toast.makeText(this@MainActivity, "All ${if (on) "on" else "off"}: $ok/$total", Toast.LENGTH_SHORT).show()
        }
    }

    private fun apply(bulb: Bulb, label: String, vararg attrs: Protocol.Attr) {
        Toast.makeText(this, "${bulb.name}: $label…", Toast.LENGTH_SHORT).show()
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                BleOps.applyAttrs(this@MainActivity, bulb.address, bulb.modelId, bulb.deviceId, *attrs)
            }
            if (ok && (label == "on" || label == "off")) {
                store.update(bulb.address) { it.lastOn = label == "on" }
                BulbWidgetProvider.refreshAll(this@MainActivity)
                render()
            }
            Toast.makeText(
                this@MainActivity,
                if (ok) "${bulb.name}: $label ✓" else "${bulb.name}: failed",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }
}
