package com.aidotble.widget.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.aidotble.widget.ble.BleOps
import com.aidotble.widget.ble.Protocol
import com.aidotble.widget.data.BulbStore
import com.aidotble.widget.data.GroupStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Handles a widget button tap: one short-lived BLE op (fanning out to a group's members), then
 * done. Uses goAsync() so the work finishes off the main thread without any long-lived service —
 * zero footprint once it returns.
 */
class WidgetActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BulbWidgetProvider.ACTION_APPLY) return
        val groupId = intent.getStringExtra(BulbWidgetProvider.EXTRA_GROUP) ?: ""
        val kind = intent.getStringExtra(BulbWidgetProvider.EXTRA_KIND) ?: return
        val value = intent.getIntExtra(BulbWidgetProvider.EXTRA_VALUE, 0)

        val appCtx = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (kind == "all_on" || kind == "all_off") {
                    val on = kind == "all_on"
                    val (ok, total) = BleOps.setAll(appCtx, on)
                    if (ok > 0) GroupStore.get(appCtx).setAllLastOn(on)
                    withContext(Dispatchers.Main) {
                        BulbWidgetProvider.refreshAll(appCtx)
                        Toast.makeText(appCtx, "All ${if (on) "on" else "off"}: $ok/$total", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                val groups = GroupStore.get(appCtx)
                val group = groups.get(groupId) ?: return@launch
                val attrs: List<Protocol.Attr> = when (kind) {
                    "on" -> listOf(Protocol.Attr.OnOff(true))
                    "off" -> listOf(Protocol.Attr.OnOff(false))
                    "brightness" -> listOf(Protocol.Attr.OnOff(true), Protocol.Attr.Dimming(value))
                    "cct" -> listOf(Protocol.Attr.OnOff(true), Protocol.Attr.Cct(value))
                    "color" -> listOf(
                        Protocol.Attr.OnOff(true),
                        Protocol.Attr.Rgb((value shr 16) and 0xFF, (value shr 8) and 0xFF, value and 0xFF),
                    )
                    else -> return@launch
                }
                // Backstop so a stalled bulb can't push this broadcast past its ANR budget; the
                // per-bulb ops inside already fail fast on an unreachable (switched-off) bulb.
                val outcome = withTimeoutOrNull(9_000) { BleOps.applyToGroup(appCtx, group, *attrs.toTypedArray()) }
                val ok = outcome?.ok ?: 0
                val total = outcome?.total ?: group.members.size

                if (ok > 0) {
                    val on = kind != "off"
                    groups.update(groupId) { it.lastOn = on }
                    // Only the bulbs that actually responded get the new state — an off/unreachable
                    // member keeps its previous state, so the widget doesn't claim it turned on.
                    val bulbs = BulbStore.get(appCtx)
                    outcome!!.succeeded.forEach { addr -> bulbs.update(addr) { it.lastOn = on } }
                    withContext(Dispatchers.Main) { BulbWidgetProvider.refreshAll(appCtx) }
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(appCtx, "${group.name}: $ok/$total", Toast.LENGTH_SHORT).show()
                }
            } finally {
                pending.finish()
            }
        }
    }
}
