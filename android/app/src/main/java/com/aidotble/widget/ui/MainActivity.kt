package com.aidotble.widget.ui

import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.aidotble.widget.ble.BleOps
import com.aidotble.widget.data.BulbStore
import com.aidotble.widget.data.Group
import com.aidotble.widget.data.GroupStore
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
    private lateinit var groups: GroupStore
    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = BulbStore.get(this)
        groups = GroupStore.get(this)
        content = column()
        setContentView(ScrollView(this).apply { addView(content) })
        if (!Permissions.allGranted(this)) {
            ActivityCompat.requestPermissions(this, Permissions.required(), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        render()
        BulbWidgetProvider.refreshAll(this)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun render() {
        content.removeAllViews()
        content.add(text("Account-free bulb control", 22f, bold = true))

        val bulbs = store.all()
        val groupList = groups.all()

        // --- Groups: the control blocks the widget shows ---
        content.add(text("Groups (shown on the widget)", 16f, bold = true))
        if (bulbs.isEmpty()) {
            content.add(text("Add a bulb first, then gather bulbs into groups.", 14f))
        } else if (groupList.isEmpty()) {
            content.add(text("No groups yet. Tap “Add group” — a group can be a single bulb.", 14f))
        }
        for (g in groupList) {
            val glyph = if (g.lastOn) "●" else "○" // ● / ○
            content.add(text("$glyph  ${g.name}", 18f, bold = true))
            content.add(text(membersSummary(g), 12f))
            val controls = row()
            controls.add(button("On") { applyGroup(g, true) })
            controls.add(button("Off") { applyGroup(g, false) })
            controls.add(button("Edit") {
                startActivity(Intent(this, GroupEditActivity::class.java).putExtra("id", g.id))
            })
            content.add(controls)
        }
        if (bulbs.isNotEmpty()) {
            content.add(button("＋ Add group") {
                startActivity(Intent(this, GroupEditActivity::class.java))
            })
        }

        if (groupList.size > 1) {
            content.add(text("", 6f))
            val allRow = row()
            allRow.add(button("All on") { applyAll(true) })
            allRow.add(button("All off") { applyAll(false) })
            content.add(allRow)
        }

        // --- Bulbs: the provisioned devices (identity + membership source) ---
        content.add(text("", 10f))
        content.add(text("Bulbs", 16f, bold = true))
        if (bulbs.isEmpty()) {
            content.add(text("No bulbs yet. Tap “Add bulb” to scan for one over Bluetooth.", 14f))
        }
        for (bulb in bulbs) {
            content.add(text(bulb.name, 15f, bold = true))
            content.add(text("${bulb.address} · ${bulb.modelId}", 12f))
            val controls = row()
            controls.add(button("Edit / delete") {
                startActivity(Intent(this, BulbEditActivity::class.java).putExtra("address", bulb.address))
            })
            content.add(controls)
        }
        content.add(button("＋ Add bulb") {
            if (!Permissions.allGranted(this)) {
                ActivityCompat.requestPermissions(this, Permissions.required(), 1)
            } else {
                startActivity(Intent(this, ProvisionActivity::class.java))
            }
        })

        val awm = android.appwidget.AppWidgetManager.getInstance(this)
        if (awm.isRequestPinAppWidgetSupported) {
            content.add(text("", 8f))
            content.add(button("Add widget to home screen") {
                awm.requestPinAppWidget(
                    android.content.ComponentName(this, BulbWidgetProvider::class.java), null, null,
                )
            })
        }
    }

    private fun membersSummary(g: Group): String {
        if (g.members.isEmpty()) return "no bulbs — tap Edit to add some"
        val names = g.members.map { addr -> store.get(addr)?.name ?: addr }
        return "${g.members.size} bulb${if (g.members.size == 1) "" else "s"}: ${names.joinToString(", ")}"
    }

    private fun applyGroup(group: Group, on: Boolean) {
        Toast.makeText(this, "${group.name}: ${if (on) "on" else "off"}…", Toast.LENGTH_SHORT).show()
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                BleOps.applyToGroup(this@MainActivity, group, com.aidotble.widget.ble.Protocol.Attr.OnOff(on))
            }
            if (outcome.ok > 0) {
                groups.update(group.id) { it.lastOn = on }
                outcome.succeeded.forEach { addr -> store.update(addr) { it.lastOn = on } }
                BulbWidgetProvider.refreshAll(this@MainActivity)
            }
            render()
            Toast.makeText(this@MainActivity, "${group.name}: ${outcome.ok}/${outcome.total}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun applyAll(on: Boolean) {
        Toast.makeText(this, "All ${if (on) "on" else "off"}…", Toast.LENGTH_SHORT).show()
        scope.launch {
            val (ok, total) = withContext(Dispatchers.IO) { BleOps.setAll(this@MainActivity, on) }
            if (ok > 0) groups.setAllLastOn(on)
            BulbWidgetProvider.refreshAll(this@MainActivity)
            render()
            Toast.makeText(this@MainActivity, "All ${if (on) "on" else "off"}: $ok/$total", Toast.LENGTH_SHORT).show()
        }
    }
}
