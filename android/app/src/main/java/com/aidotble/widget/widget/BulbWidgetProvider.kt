package com.aidotble.widget.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.aidotble.widget.R
import com.aidotble.widget.data.Bulb
import com.aidotble.widget.data.BulbStore

/**
 * Home-screen widget. Renders every saved bulb with on/off + brightness/CCT preset buttons.
 *
 * Low-resource by design: updatePeriodMillis=0 means the widget never wakes on its own; the only
 * work happens when the user taps a button, which fires a one-shot broadcast to
 * [WidgetActionReceiver]. Nothing runs in the background at idle.
 */
class BulbWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) manager.updateAppWidget(id, build(context))
    }

    companion object {
        const val ACTION_APPLY = "com.aidotble.widget.APPLY"
        const val EXTRA_ADDRESS = "address"
        const val EXTRA_KIND = "kind"     // on | off | brightness | cct | color | all_on | all_off
        const val EXTRA_VALUE = "value"   // int (pct or kelvin or packed rgb)
        const val ALL = "*"               // sentinel address for all_on / all_off

        private var reqCode = 1

        // colours (not const: .toInt() on an out-of-Int-range literal isn't a compile-time const)
        private val AMBER = 0xFFFFC107.toInt()
        private val DARK_TEXT = 0xFF202124.toInt()
        private val OFF_GREY = 0xFF9AA0A6.toInt()
        private val WHITE = 0xFFFFFFFF.toInt()

        /** Rebuild every placed widget instance (call after state/preset changes). */
        fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, BulbWidgetProvider::class.java))
            if (ids.isEmpty()) return
            val views = build(context)
            for (id in ids) manager.updateAppWidget(id, views)
        }

        private fun build(context: Context): RemoteViews {
            val root = RemoteViews(context.packageName, R.layout.widget_root)
            root.removeAllViews(R.id.widget_container)

            val bulbs = BulbStore.get(context).all()
            if (bulbs.isEmpty()) {
                root.addView(R.id.widget_container, label(context, "No bulbs — open the app to add one.", OFF_GREY))
                return root
            }

            // "All on / All off" controls every bulb at once (only worth showing for 2+).
            if (bulbs.size > 1) {
                val allRow = RemoteViews(context.packageName, R.layout.widget_row)
                allRow.addView(R.id.row_container, button(context, "All on", ALL, "all_on", 0))
                allRow.addView(R.id.row_container, button(context, "All off", ALL, "all_off", 0))
                root.addView(R.id.widget_container, allRow)
            }

            for (bulb in bulbs) {
                // compact header row: name (weighted) + live-state On / Off on one line
                val glyph = if (bulb.lastOn) "●" else "○" // ● / ○
                val header = RemoteViews(context.packageName, R.layout.widget_row)
                header.addView(R.id.row_container, nameView(context, "$glyph ${bulb.name}", if (bulb.lastOn) WHITE else OFF_GREY))
                header.addView(R.id.row_container, stateButton(context, "On", bulb.address, "on", 0, bulb.lastOn, AMBER))
                header.addView(R.id.row_container, stateButton(context, "Off", bulb.address, "off", 0, !bulb.lastOn, OFF_GREY))
                root.addView(R.id.widget_container, header)

                if (bulb.brightnessPresets.isNotEmpty()) {
                    val row = RemoteViews(context.packageName, R.layout.widget_row)
                    for (p in bulb.brightnessPresets) {
                        row.addView(R.id.row_container, button(context, "$p%", bulb.address, "brightness", p))
                    }
                    root.addView(R.id.widget_container, row)
                }

                if (bulb.cctPresets.isNotEmpty()) {
                    val row = RemoteViews(context.packageName, R.layout.widget_row)
                    for (k in bulb.cctPresets) {
                        row.addView(R.id.row_container, button(context, "${k}K", bulb.address, "cct", k))
                    }
                    root.addView(R.id.widget_container, row)
                }

                if (bulb.rgbPresets.isNotEmpty()) {
                    val row = RemoteViews(context.packageName, R.layout.widget_row)
                    for (c in bulb.rgbPresets) {
                        row.addView(R.id.row_container, swatchButton(context, bulb.address, c and 0xFFFFFF))
                    }
                    root.addView(R.id.widget_container, row)
                }
            }
            return root
        }

        private fun label(context: Context, s: String, color: Int): RemoteViews =
            RemoteViews(context.packageName, R.layout.widget_label).apply {
                setTextViewText(R.id.label, s)
                setTextColor(R.id.label, color)
            }

        private fun nameView(context: Context, s: String, color: Int): RemoteViews =
            RemoteViews(context.packageName, R.layout.widget_name).apply {
                setTextViewText(R.id.name, s)
                setTextColor(R.id.name, color)
            }

        private fun actionIntent(context: Context, address: String, kind: String, value: Int): PendingIntent {
            val intent = Intent(context, WidgetActionReceiver::class.java).apply {
                action = ACTION_APPLY
                putExtra(EXTRA_ADDRESS, address)
                putExtra(EXTRA_KIND, kind)
                putExtra(EXTRA_VALUE, value)
            }
            return PendingIntent.getBroadcast(
                context, reqCode++, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        private fun button(context: Context, text: String, address: String, kind: String, value: Int): RemoteViews {
            val rv = RemoteViews(context.packageName, R.layout.widget_button)
            rv.setTextViewText(R.id.btn, text)
            rv.setOnClickPendingIntent(R.id.btn, actionIntent(context, address, kind, value))
            return rv
        }

        /** On/Off button that is highlighted when it represents the bulb's current state. */
        private fun stateButton(
            context: Context, text: String, address: String, kind: String, value: Int,
            active: Boolean, activeBg: Int,
        ): RemoteViews {
            val rv = button(context, text, address, kind, value)
            if (active) {
                rv.setInt(R.id.btn, "setBackgroundColor", activeBg)
                rv.setTextColor(R.id.btn, DARK_TEXT)
            }
            return rv
        }

        /** A colour-swatch button whose background is the RGB preset itself. */
        private fun swatchButton(context: Context, address: String, packed: Int): RemoteViews {
            val rv = RemoteViews(context.packageName, R.layout.widget_button)
            rv.setTextViewText(R.id.btn, "    ")
            rv.setInt(R.id.btn, "setBackgroundColor", 0xFF000000.toInt() or packed)
            rv.setOnClickPendingIntent(R.id.btn, actionIntent(context, address, "color", packed))
            return rv
        }
    }
}
