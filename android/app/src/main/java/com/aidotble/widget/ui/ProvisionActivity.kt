package com.aidotble.widget.ui

import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.aidotble.widget.ble.BleOps
import com.aidotble.widget.ble.Discovery
import com.aidotble.widget.data.Bulb
import com.aidotble.widget.data.BulbStore
import com.aidotble.widget.data.Group
import com.aidotble.widget.data.GroupStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Scan for AiDot bulbs, then provision (resolve model-id + save) the one the user picks. */
class ProvisionActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var store: BulbStore
    private lateinit var content: LinearLayout
    private lateinit var status: TextView
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = BulbStore.get(this)
        content = column()
        status = text("", 14f)
        setContentView(ScrollView(this).apply { addView(content) })
        render(emptyList())
        startScan()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun render(found: List<Discovery.Found>) {
        content.removeAllViews()
        content.add(text("Add a bulb", 22f, bold = true))
        content.add(button("Scan again") { startScan() })
        content.add(status)
        val known = store.all().map { it.address.uppercase() }.toSet()
        for (f in found) {
            val already = f.address.uppercase() in known
            val label = "${f.name ?: "AiDot bulb"}  ·  ${f.address}" + if (already) "  (added)" else ""
            content.add(text(label, 15f, bold = true))
            content.add(button(if (already) "Re-provision" else "Add this bulb") { provision(f) })
        }
    }

    private fun startScan() {
        if (busy) return
        busy = true
        status.text = "Scanning…"
        scope.launch {
            val found = withContext(Dispatchers.IO) { Discovery.scan(this@ProvisionActivity) }
            busy = false
            status.text = if (found.isEmpty()) "No bulbs found. Make sure one is powered and nearby." else "Found ${found.size} bulb(s)."
            render(found)
        }
    }

    private fun provision(f: Discovery.Found) {
        if (busy) return
        busy = true
        status.text = "Connecting to ${f.address}…"
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                BleOps.provision(this@ProvisionActivity, f) { tried, total, model ->
                    runOnUiThread { status.text = "Resolving model-id ($tried/$total): $model" }
                }
            }
            busy = false
            if (result is BleOps.ProvResult.Fail) {
                status.text = "Couldn't provision ${f.address}: ${result.reason}. Try again (move closer / power-cycle)."
                Toast.makeText(this@ProvisionActivity, "Provisioning failed: ${result.reason}", Toast.LENGTH_LONG).show()
                return@launch
            }
            val ok = result as BleOps.ProvResult.Ok
            val existing = store.get(f.address)
            val name = existing?.name ?: (f.name ?: f.address)
            store.put(
                Bulb(
                    address = f.address,
                    deviceId = ok.deviceId,
                    modelId = ok.modelId,
                    name = name,
                    shortModel = f.shortModel,
                    brightnessPresets = existing?.brightnessPresets ?: Bulb.DEFAULT_BRIGHTNESS,
                    cctPresets = existing?.cctPresets ?: Bulb.DEFAULT_CCT,
                    rgbPresets = existing?.rgbPresets ?: emptyList(),
                ),
            )
            // For a brand-new bulb, seed a size-1 group so it shows up on the widget right away;
            // the user can merge it into a larger group later. Re-provisioning leaves groups alone.
            if (existing == null) {
                val groups = GroupStore.get(this@ProvisionActivity)
                if (groups.all().none { f.address in it.members }) {
                    groups.put(Group(name = name, members = listOf(f.address)))
                }
            }
            Toast.makeText(this@ProvisionActivity, "Added as ${ok.modelId}", Toast.LENGTH_LONG).show()
            finish()
        }
    }
}
