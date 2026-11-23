package com.aidotble.widget.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * A named set of bulbs that the widget shows as one control block. A group may have a single
 * member (just one bulb) or several; a tap fans out to every member in parallel. The widget
 * presets (brightness / CCT / RGB) live on the group, since the group is what the widget renders.
 */
data class Group(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    var members: List<String> = emptyList(),          // bulb addresses
    var brightnessPresets: List<Int> = Bulb.DEFAULT_BRIGHTNESS,
    var cctPresets: List<Int> = Bulb.DEFAULT_CCT,
    var rgbPresets: List<Int> = emptyList(),           // packed 0xRRGGBB; empty by default
    var lastOn: Boolean = false,                       // optimistic aggregate state
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("members", JSONArray(members))
        put("brightnessPresets", JSONArray(brightnessPresets))
        put("cctPresets", JSONArray(cctPresets))
        put("rgbPresets", JSONArray(rgbPresets))
        put("lastOn", lastOn)
    }

    companion object {
        fun fromJson(o: JSONObject): Group = Group(
            id = o.optString("id", UUID.randomUUID().toString()),
            name = o.optString("name", "Group"),
            members = o.optJSONArray("members").toStringList(),
            brightnessPresets = o.optJSONArray("brightnessPresets").toIntList(Bulb.DEFAULT_BRIGHTNESS),
            cctPresets = o.optJSONArray("cctPresets").toIntList(Bulb.DEFAULT_CCT),
            rgbPresets = o.optJSONArray("rgbPresets").toIntList(emptyList()),
            lastOn = o.optBoolean("lastOn", false),
        )

        private fun JSONArray?.toIntList(fallback: List<Int>): List<Int> {
            if (this == null) return fallback
            return (0 until length()).map { getInt(it) }
        }

        private fun JSONArray?.toStringList(): List<String> {
            if (this == null) return emptyList()
            return (0 until length()).map { getString(it) }
        }
    }
}
