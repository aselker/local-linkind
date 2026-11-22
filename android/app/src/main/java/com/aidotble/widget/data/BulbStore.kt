package com.aidotble.widget.data

import android.content.Context
import org.json.JSONArray

/** Persists the list of provisioned bulbs as JSON in SharedPreferences (no extra deps). */
class BulbStore private constructor(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(): List<Bulb> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { Bulb.fromJson(arr.getJSONObject(it)) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun get(address: String): Bulb? = all().firstOrNull { it.address.equals(address, ignoreCase = true) }

    /** Insert or replace by address, preserving list order (replace in place). */
    fun put(bulb: Bulb) {
        val list = all().toMutableList()
        val idx = list.indexOfFirst { it.address.equals(bulb.address, ignoreCase = true) }
        if (idx >= 0) list[idx] = bulb else list.add(bulb)
        save(list)
    }

    fun remove(address: String) {
        save(all().filterNot { it.address.equals(address, ignoreCase = true) })
    }

    fun update(address: String, mutate: (Bulb) -> Unit) {
        val bulb = get(address) ?: return
        mutate(bulb)
        put(bulb)
    }

    private fun save(list: List<Bulb>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    companion object {
        private const val PREFS = "aidot_bulbs"
        private const val KEY = "bulbs"

        fun get(context: Context): BulbStore = BulbStore(context.applicationContext)
    }
}
