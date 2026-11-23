package com.aidotble.widget.data

import android.content.Context
import org.json.JSONArray

/**
 * Persists the user's groups (the widget's control blocks) as JSON in SharedPreferences.
 *
 * On first read it runs a one-time migration: every already-provisioned bulb becomes its own
 * size-1 group, inheriting that bulb's name and presets — so an existing setup keeps working
 * unchanged, and the user can then merge bulbs into larger groups if they want. The migration is
 * guarded by a flag, so deleting every group afterwards does not resurrect them.
 */
class GroupStore private constructor(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(): List<Group> {
        migrateIfNeeded()
        return read()
    }

    fun get(id: String): Group? = read().firstOrNull { it.id == id }

    /** Insert or replace by id, preserving list order. */
    fun put(group: Group) {
        val list = read().toMutableList()
        val idx = list.indexOfFirst { it.id == group.id }
        if (idx >= 0) list[idx] = group else list.add(group)
        save(list)
    }

    fun remove(id: String) {
        save(read().filterNot { it.id == id })
    }

    fun update(id: String, mutate: (Group) -> Unit) {
        val g = get(id) ?: return
        mutate(g)
        put(g)
    }

    /** Set the optimistic on/off state on every group at once (used by All on / All off). */
    fun setAllLastOn(on: Boolean) {
        save(read().onEach { it.lastOn = on })
    }

    /**
     * Drop a just-deleted bulb from every group, then delete any group left with no members.
     * Call when a bulb is removed so the widget never references a gone address.
     */
    fun onBulbRemoved(address: String) {
        val kept = read()
            .map { g -> g.copy(members = g.members.filterNot { it.equals(address, ignoreCase = true) }) }
            .filter { it.members.isNotEmpty() }
        save(kept)
    }

    private fun read(): List<Group> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { Group.fromJson(arr.getJSONObject(it)) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun save(list: List<Group>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    private fun migrateIfNeeded() {
        if (prefs.getBoolean(MIGRATED, false)) return
        val bulbs = BulbStore.get(context).all()
        val groups = bulbs.map { b ->
            Group(
                name = b.name,
                members = listOf(b.address),
                brightnessPresets = b.brightnessPresets,
                cctPresets = b.cctPresets,
                rgbPresets = b.rgbPresets,
                lastOn = b.lastOn,
            )
        }
        save(groups)
        prefs.edit().putBoolean(MIGRATED, true).apply()
    }

    companion object {
        private const val PREFS = "aidot_bulbs"   // shared file with BulbStore; separate keys
        private const val KEY = "groups"
        private const val MIGRATED = "groups_migrated"

        fun get(context: Context): GroupStore = GroupStore(context.applicationContext)
    }
}
