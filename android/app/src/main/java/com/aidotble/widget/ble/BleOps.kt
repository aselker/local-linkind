package com.aidotble.widget.ble

import android.content.Context
import android.util.Log
import com.aidotble.widget.data.BulbStore
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/**
 * High-level, one-shot BLE operations shared by the provisioning UI and the widget receiver.
 * Each opens a fresh [BulbClient], does its work, and always closes it.
 */
object BleOps {

    private const val TAG = "AidotBle"

    // shortModelId -> modelId, seeded with the empirically-resolved LS01017116 so the common
    // bulb signs in on the first try; the full assets/model_ids.txt is the fallback.
    private val SEED_MODELS = mapOf("00002a" to "LK.light.A001577")

    sealed class ProvResult {
        data class Ok(val deviceId: String, val modelId: String) : ProvResult()
        data class Fail(val reason: String) : ProvResult()
    }

    /** Build the ordered sign-in candidate list: seeded match first, then the bundled list. */
    fun candidateModels(context: Context, shortModel: String): List<String> {
        val out = LinkedHashSet<String>()
        SEED_MODELS[shortModel]?.let { out.add(it) }
        try {
            context.assets.open("model_ids.txt").bufferedReader().forEachLine { line ->
                val m = line.trim()
                if (m.isEmpty() || m.startsWith("#")) return@forEachLine
                out.add(m)
                out.add(m.lowercase())
            }
        } catch (_: Exception) { /* asset missing -> seed only */ }
        return out.toList()
    }

    /**
     * Provision a freshly-discovered bulb: read its deviceId, then resolve the product modelId by
     * trying each candidate until the bulb ACKs sign-in (reconnecting if a bad attempt drops the
     * link). Returns [ProvResult.Ok] or [ProvResult.Fail] with a human-readable stage/reason.
     */
    suspend fun provision(
        context: Context,
        found: Discovery.Found,
        onProgress: ((tried: Int, total: Int, modelId: String) -> Unit)? = null,
    ): ProvResult {
        val client = BulbClient(context, found.address)
        try {
            prepareReason(client)?.let { return ProvResult.Fail(it) }

            val info = client.readDeviceInfo()
                ?: return ProvResult.Fail("couldn't read device info (char a00b)")
            val deviceId = info.optString("D").takeIf { it.isNotEmpty() }
                ?: return ProvResult.Fail("device info has no deviceId")
            Log.d(TAG, "deviceId=$deviceId shortModel=${found.shortModel}")

            val candidates = candidateModels(context, found.shortModel)
            if (candidates.isEmpty()) return ProvResult.Fail("no candidate model-ids bundled")

            candidates.forEachIndexed { i, modelId ->
                onProgress?.invoke(i + 1, candidates.size, modelId)
                if (!client.connected) {
                    prepareReason(client)?.let {
                        Log.w(TAG, "reconnect failed mid-resolve: $it")
                        return@forEachIndexed
                    }
                }
                val ok = withTimeoutOrNull(6_000) { client.signIn(modelId, deviceId) } ?: false
                if (ok) {
                    Log.i(TAG, "signed in as $modelId")
                    return ProvResult.Ok(deviceId, modelId)
                }
            }
            return ProvResult.Fail("bulb rejected all ${candidates.size} model-ids at sign-in")
        } finally {
            client.close()
        }
    }

    /**
     * Apply attributes to an already-provisioned bulb (deviceId + modelId known). Returns true on
     * a success response. The widget's hot path; routed through [BleConductor] so rapid taps for
     * the same bulb queue instead of colliding.
     */
    suspend fun applyAttrs(
        context: Context,
        address: String,
        modelId: String,
        deviceId: String,
        vararg attrs: Protocol.Attr,
    ): Boolean = BleConductor.apply(context, address, modelId, deviceId, *attrs)

    /**
     * Turn every provisioned bulb on or off. Bulbs run in parallel (each is independently
     * serialized by [BleConductor]); successes update their stored last-known state. Returns
     * (succeeded, total).
     */
    suspend fun setAll(context: Context, on: Boolean): Pair<Int, Int> {
        val store = BulbStore.get(context)
        val bulbs = store.all()
        val results = coroutineScope {
            bulbs.map { b ->
                async {
                    b.address to applyAttrs(context, b.address, b.modelId, b.deviceId, Protocol.Attr.OnOff(on))
                }
            }.awaitAll()
        }
        var ok = 0
        for ((addr, success) in results) if (success) { store.update(addr) { it.lastOn = on }; ok++ }
        return ok to bulbs.size
    }

    /**
     * connect -> raise MTU -> discover -> enable notifications. Returns null on success, or a
     * short human-readable reason for the first stage that failed. MTU is best-effort (command
     * frames exceed Android's default 23-byte MTU, so we ask for more, but don't hard-fail if the
     * device doesn't report the change).
     */
    private suspend fun prepareReason(client: BulbClient): String? {
        if (!client.connect()) return "couldn't connect over BLE"
        val mtu = client.requestMtu()
        Log.d(TAG, "MTU negotiated: $mtu")
        if (!client.discoverServices()) return "GATT service discovery failed"
        if (!client.enableNotifications()) return "couldn't enable notifications (char b002)"
        return null
    }
}
