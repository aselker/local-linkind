package com.aidotble.widget.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.aidotble.widget.ble.BleOps
import com.aidotble.widget.ble.Protocol
import com.aidotble.widget.data.BulbStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Handles a widget button tap: one short-lived BLE op, then done. Uses goAsync() so the work
 * finishes off the main thread without any long-lived service — zero footprint once it returns.
 */
class WidgetActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BulbWidgetProvider.ACTION_APPLY) return
        val address = intent.getStringExtra(BulbWidgetProvider.EXTRA_ADDRESS) ?: return
        val kind = intent.getStringExtra(BulbWidgetProvider.EXTRA_KIND) ?: return
        val value = intent.getIntExtra(BulbWidgetProvider.EXTRA_VALUE, 0)

        val appCtx = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (kind == "all_on" || kind == "all_off") {
                    val (ok, total) = BleOps.setAll(appCtx, kind == "all_on")
                    withContext(Dispatchers.Main) {
                        BulbWidgetProvider.refreshAll(appCtx)
                        Toast.makeText(appCtx, "All ${if (kind == "all_on") "on" else "off"}: $ok/$total", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }
                val store = BulbStore.get(appCtx)
                val bulb = store.get(address) ?: return@launch
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
                val ok = withTimeoutOrNull(9_000) {
                    BleOps.applyAttrs(appCtx, bulb.address, bulb.modelId, bulb.deviceId, *attrs.toTypedArray())
                } ?: false

                if (ok) {
                    store.update(address) { it.lastOn = kind != "off" }
                    withContext(Dispatchers.Main) { BulbWidgetProvider.refreshAll(appCtx) }
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(appCtx, "${bulb.name}: ${if (ok) "ok" else "failed"}", Toast.LENGTH_SHORT).show()
                }
            } finally {
                pending.finish()
            }
        }
    }
}
