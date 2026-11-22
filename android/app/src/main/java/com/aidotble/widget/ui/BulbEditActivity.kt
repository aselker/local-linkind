package com.aidotble.widget.ui

import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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

/** Rename a bulb, edit the brightness/CCT presets the widget exposes, test, or delete it. */
class BulbEditActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var store: BulbStore
    private lateinit var bulb: Bulb

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = BulbStore.get(this)
        val address = intent.getStringExtra("address")
        val b = address?.let { store.get(it) }
        if (b == null) { finish(); return }
        bulb = b

        val col = column()
        setContentView(ScrollView(this).apply { addView(col) })

        col.add(text("Edit bulb", 22f, bold = true))
        col.add(text("${bulb.address} · ${bulb.modelId}", 12f))

        col.add(text("Name", 14f, bold = true))
        val nameField = input(bulb.name, "Name")
        col.add(nameField)

        col.add(text("Brightness presets (%, comma-separated)", 14f, bold = true))
        val brightField = input(bulb.brightnessPresets.toCsv(), "e.g. 20, 50, 100")
        col.add(brightField)

        col.add(text("Colour-temperature presets (kelvin, comma-separated)", 14f, bold = true))
        val cctField = input(bulb.cctPresets.toCsv(), "e.g. 2700, 4000, 6500")
        col.add(cctField)

        col.add(text("RGB colour presets (hex, comma-separated — leave blank for none)", 14f, bold = true))
        val rgbField = input(bulb.rgbPresets.toHexCsv(), "e.g. #ff3300, #00aaff")
        col.add(rgbField)

        col.add(text("", 8f))
        col.add(button("Save") { save(nameField, brightField, cctField, rgbField) })

        col.add(text("Test", 14f, bold = true))
        val testRow = row()
        testRow.add(button("On") { test("on", Protocol.Attr.OnOff(true)) })
        testRow.add(button("Off") { test("off", Protocol.Attr.OnOff(false)) })
        col.add(testRow)

        col.add(text("", 16f))
        col.add(button("Delete bulb") {
            store.remove(bulb.address)
            BulbWidgetProvider.refreshAll(this)
            finish()
        })
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun save(name: EditText, bright: EditText, cct: EditText, rgb: EditText) {
        val brightList = bright.text.toString().toIntListOrNull()
        val cctList = cct.text.toString().toIntListOrNull()
        val rgbList = rgb.text.toString().toRgbListOrEmpty()
        if (brightList == null || brightList.any { it !in 1..100 }) {
            Toast.makeText(this, "Brightness presets must be 1–100", Toast.LENGTH_SHORT).show(); return
        }
        if (cctList == null || cctList.any { it !in 1000..10000 }) {
            Toast.makeText(this, "CCT presets must be 1000–10000 K", Toast.LENGTH_SHORT).show(); return
        }
        if (rgbList == null) {
            Toast.makeText(this, "RGB presets must be 6-digit hex (e.g. #ff3300)", Toast.LENGTH_SHORT).show(); return
        }
        store.update(bulb.address) {
            it.name = name.text.toString().ifBlank { bulb.address }
            it.brightnessPresets = brightList
            it.cctPresets = cctList
            it.rgbPresets = rgbList
        }
        BulbWidgetProvider.refreshAll(this)
        Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun test(label: String, vararg attrs: Protocol.Attr) {
        Toast.makeText(this, "$label…", Toast.LENGTH_SHORT).show()
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                BleOps.applyAttrs(this@BulbEditActivity, bulb.address, bulb.modelId, bulb.deviceId, *attrs)
            }
            Toast.makeText(this@BulbEditActivity, if (ok) "$label ✓" else "failed", Toast.LENGTH_SHORT).show()
        }
    }
}
