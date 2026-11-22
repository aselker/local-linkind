package com.aidotble.widget.data

import org.json.JSONArray
import org.json.JSONObject

/** A provisioned bulb plus the widget presets the user configured for it. */
data class Bulb(
    val address: String,
    val deviceId: String,
    val modelId: String,
    var name: String = address,
    var shortModel: String = "",
    var brightnessPresets: List<Int> = DEFAULT_BRIGHTNESS,
    var cctPresets: List<Int> = DEFAULT_CCT,
    // RGB presets, each packed as 0xRRGGBB. Empty by default (white-focused use).
    var rgbPresets: List<Int> = emptyList(),
    // last-known state, for optimistic widget rendering
    var lastOn: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("address", address)
        put("deviceId", deviceId)
        put("modelId", modelId)
        put("name", name)
        put("shortModel", shortModel)
        put("brightnessPresets", JSONArray(brightnessPresets))
        put("cctPresets", JSONArray(cctPresets))
        put("rgbPresets", JSONArray(rgbPresets))
        put("lastOn", lastOn)
    }

    companion object {
        val DEFAULT_BRIGHTNESS = listOf(20, 50, 100)
        val DEFAULT_CCT = listOf(2700, 4000, 6500)

        fun fromJson(o: JSONObject): Bulb = Bulb(
            address = o.getString("address"),
            deviceId = o.getString("deviceId"),
            modelId = o.getString("modelId"),
            name = o.optString("name", o.getString("address")),
            shortModel = o.optString("shortModel", ""),
            brightnessPresets = o.optJSONArray("brightnessPresets").toIntList(DEFAULT_BRIGHTNESS),
            cctPresets = o.optJSONArray("cctPresets").toIntList(DEFAULT_CCT),
            rgbPresets = o.optJSONArray("rgbPresets").toIntList(emptyList()),
            lastOn = o.optBoolean("lastOn", false),
        )

        private fun JSONArray?.toIntList(fallback: List<Int>): List<Int> {
            if (this == null) return fallback
            return (0 until length()).map { getInt(it) }
        }
    }
}
