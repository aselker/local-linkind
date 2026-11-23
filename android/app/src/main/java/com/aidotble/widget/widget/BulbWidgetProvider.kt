package com.aidotble.widget.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.aidotble.widget.R
import com.aidotble.widget.data.BulbStore
import com.aidotble.widget.data.Group
import com.aidotble.widget.data.GroupStore

/**
 * Home-screen widget. Renders every saved group with on/off + brightness/CCT/RGB preset buttons.
 * A group is one or more bulbs; a tap fans out to all of its members.
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
        const val EXTRA_GROUP = "group"   // group id (empty for all_on / all_off)
        const val EXTRA_KIND = "kind"     // on | off | brightness | cct | color | all_on | all_off
        const val EXTRA_VALUE = "value"   // int (pct or kelvin or packed rgb)

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

            val hasBulbs = BulbStore.get(context).all().isNotEmpty()
            val groups = GroupStore.get(context).all()
            if (groups.isEmpty()) {
                val msg = if (hasBulbs) "No groups yet — open the app to make one."
                          else "No bulbs — open the app to add one."
                root.addView(R.id.widget_container, label(context, msg, OFF_GREY))
                return root
            }

            // "All on / All off" drives every group at once (only worth showing for 2+).
            if (groups.size > 1) {
                val allRow = RemoteViews(context.packageName, R.layout.widget_row)
                allRow.addView(R.id.row_container, button(context, "All on", "", "all_on", 0))
                allRow.addView(R.id.row_container, button(context, "All off", "", "all_off", 0))
                root.addView(R.id.widget_container, allRow)
            }

            for (group in groups) {
                // compact header row: name (weighted) + live-state On / Off on one line
                val glyph = if (group.lastOn) "●" else "○" // ● / ○
                val header = RemoteViews(context.packageName, R.layout.widget_row)
                header.addView(R.id.row_container, nameView(context, "$glyph ${group.name}", if (group.lastOn) WHITE else OFF_GREY))
                header.addView(R.id.row_container, stateButton(context, "On", group.id, "on", 0, group.lastOn, AMBER))
                header.addView(R.id.row_container, stateButton(context, "Off", group.id, "off", 0, !group.lastOn, OFF_GREY))
                root.addView(R.id.widget_container, header)

                if (group.brightnessPresets.isNotEmpty()) {
                    val row = RemoteViews(context.packageName, R.layout.widget_row)
                    for (p in group.brightnessPresets) {
                        row.addView(R.id.row_container, button(context, "$p%", group.id, "brightness", p))
                    }
                    root.addView(R.id.widget_container, row)
                }

                if (group.cctPresets.isNotEmpty()) {
                    val row = RemoteViews(context.packageName, R.layout.widget_row)
                    for (k in group.cctPresets) {
                        row.addView(R.id.row_container, button(context, "${k}K", group.id, "cct", k))
                    }
                    root.addView(R.id.widget_container, row)
                }

                if (group.rgbPresets.isNotEmpty()) {
                    val row = RemoteViews(context.packageName, R.layout.widget_row)
                    for (c in group.rgbPresets) {
                        row.addView(R.id.row_container, swatchButton(context, group.id, c and 0xFFFFFF))
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

        private fun actionIntent(context: Context, groupId: String, kind: String, value: Int): PendingIntent {
            val intent = Intent(context, WidgetActionReceiver::class.java).apply {
                action = ACTION_APPLY
                putExtra(EXTRA_GROUP, groupId)
                putExtra(EXTRA_KIND, kind)
                putExtra(EXTRA_VALUE, value)
            }
            return PendingIntent.getBroadcast(
                context, reqCode++, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        private fun button(context: Context, text: String, groupId: String, kind: String, value: Int): RemoteViews {
            val rv = RemoteViews(context.packageName, R.layout.widget_button)
            rv.setTextViewText(R.id.btn, text)
            rv.setOnClickPendingIntent(R.id.btn, actionIntent(context, groupId, kind, value))
            return rv
        }

        /** On/Off button that is highlighted when it represents the group's current state. */
        private fun stateButton(
            context: Context, text: String, groupId: String, kind: String, value: Int,
            active: Boolean, activeBg: Int,
        ): RemoteViews {
            val rv = button(context, text, groupId, kind, value)
            if (active) {
                rv.setInt(R.id.btn, "setBackgroundColor", activeBg)
                rv.setTextColor(R.id.btn, DARK_TEXT)
            }
            return rv
        }

        /** A colour-swatch button whose background is the RGB preset itself. */
        private fun swatchButton(context: Context, groupId: String, packed: Int): RemoteViews {
            val rv = RemoteViews(context.packageName, R.layout.widget_button)
            rv.setTextViewText(R.id.btn, "    ")
            rv.setInt(R.id.btn, "setBackgroundColor", 0xFF000000.toInt() or packed)
            rv.setOnClickPendingIntent(R.id.btn, actionIntent(context, groupId, "color", packed))
            return rv
        }
    }
}
