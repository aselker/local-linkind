package com.aidotble.widget.ui

import android.os.Bundle
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.aidotble.widget.ble.BleOps
import com.aidotble.widget.ble.Protocol
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
import android.widget.ScrollView

/**
 * Create or edit a group: its name, which bulbs belong to it, and the brightness/CCT/RGB presets
 * the widget shows for it. Pass "id" to edit an existing group; omit it to create a new one.
 */
class GroupEditActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var groups: GroupStore
    private lateinit var group: Group
    private var isNew = false
    private val memberBoxes = LinkedHashMap<String, CheckBox>()  // address -> checkbox

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        groups = GroupStore.get(this)
        val id = intent.getStringExtra("id")
        group = id?.let { groups.get(it) } ?: Group(name = "New group").also { isNew = true }

        val bulbs = BulbStore.get(this).all()
        if (bulbs.isEmpty()) {
            Toast.makeText(this, "Add a bulb before making a group.", Toast.LENGTH_LONG).show()
            finish(); return
        }

        val col = column()
        setContentView(ScrollView(this).apply { addView(col) })

        col.add(text(if (isNew) "New group" else "Edit group", 22f, bold = true))

        col.add(text("Name", 14f, bold = true))
        val nameField = input(group.name, "e.g. Living room")
        col.add(nameField)

        col.add(text("Bulbs in this group", 14f, bold = true))
        for (b in bulbs) {
            val box = checkbox("${b.name}  ·  ${b.address}", b.address in group.members)
            memberBoxes[b.address] = box
            col.add(box)
        }

        col.add(text("Brightness presets (%, comma-separated)", 14f, bold = true))
        val brightField = input(group.brightnessPresets.toCsv(), "e.g. 20, 50, 100")
        col.add(brightField)

        col.add(text("Colour-temperature presets (kelvin, comma-separated)", 14f, bold = true))
        val cctField = input(group.cctPresets.toCsv(), "e.g. 2700, 4000, 6500")
        col.add(cctField)

        col.add(text("RGB colour presets (hex, comma-separated — leave blank for none)", 14f, bold = true))
        val rgbField = input(group.rgbPresets.toHexCsv(), "e.g. #ff3300, #00aaff")
        col.add(rgbField)

        col.add(text("", 8f))
        col.add(button("Save") { save(nameField, brightField, cctField, rgbField) })

        col.add(text("Test", 14f, bold = true))
        val testRow = row()
        testRow.add(button("On") { test(Protocol.Attr.OnOff(true)) })
        testRow.add(button("Off") { test(Protocol.Attr.OnOff(false)) })
        col.add(testRow)

        if (!isNew) {
            col.add(text("", 16f))
            col.add(button("Delete group") {
                groups.remove(group.id)
                BulbWidgetProvider.refreshAll(this)
                finish()
            })
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun selectedMembers(): List<String> =
        memberBoxes.filterValues { it.isChecked }.keys.toList()

    private fun save(name: EditText, bright: EditText, cct: EditText, rgb: EditText) {
        val members = selectedMembers()
        if (members.isEmpty()) {
            Toast.makeText(this, "Pick at least one bulb for the group.", Toast.LENGTH_SHORT).show(); return
        }
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
        group.name = name.text.toString().ifBlank { "Group" }
        group.members = members
        group.brightnessPresets = brightList
        group.cctPresets = cctList
        group.rgbPresets = rgbList
        groups.put(group)
        BulbWidgetProvider.refreshAll(this)
        Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun test(vararg attrs: Protocol.Attr) {
        val members = selectedMembers()
        if (members.isEmpty()) {
            Toast.makeText(this, "Pick at least one bulb first.", Toast.LENGTH_SHORT).show(); return
        }
        // test the current (unsaved) selection
        val probe = group.copy(members = members)
        Toast.makeText(this, "Testing…", Toast.LENGTH_SHORT).show()
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                BleOps.applyToGroup(this@GroupEditActivity, probe, *attrs)
            }
            Toast.makeText(this@GroupEditActivity, "${outcome.ok}/${outcome.total} ok", Toast.LENGTH_SHORT).show()
        }
    }
}
