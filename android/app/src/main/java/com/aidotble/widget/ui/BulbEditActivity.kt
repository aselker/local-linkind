package com.aidotble.widget.ui

import android.os.Bundle
import android.widget.EditText
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.aidotble.widget.ble.BleOps
import com.aidotble.widget.ble.Protocol
import com.aidotble.widget.data.Bulb
import com.aidotble.widget.data.BulbStore
import com.aidotble.widget.data.GroupStore
import com.aidotble.widget.widget.BulbWidgetProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A provisioned bulb's identity screen: rename it, test it, or delete it. The widget presets
 * (brightness/CCT/RGB) are a *group* setting now — edit them in the group editor instead.
 */
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

        col.add(text("Widget buttons (brightness, CCT, colours) are set per group — edit a group to change them.", 12f))

        col.add(text("", 8f))
        col.add(button("Save") { save(nameField) })

        col.add(text("Test", 14f, bold = true))
        val testRow = row()
        testRow.add(button("On") { test("on", Protocol.Attr.OnOff(true)) })
        testRow.add(button("Off") { test("off", Protocol.Attr.OnOff(false)) })
        col.add(testRow)

        col.add(text("", 16f))
        col.add(button("Delete bulb") {
            store.remove(bulb.address)
            GroupStore.get(this).onBulbRemoved(bulb.address)
            BulbWidgetProvider.refreshAll(this)
            finish()
        })
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun save(name: EditText) {
        store.update(bulb.address) {
            it.name = name.text.toString().ifBlank { bulb.address }
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
