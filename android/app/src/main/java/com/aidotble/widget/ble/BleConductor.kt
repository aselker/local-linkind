package com.aidotble.widget.ble

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Serializes control ops **per bulb** and reuses a warm connection for a short window.
 *
 * Why: each control op is a full BLE connect → sign-in → setAttr → disconnect, which is slow. If
 * two ops for the same bulb overlap (tapping buttons in quick succession) their connections
 * collide and fail. A per-address [Mutex] makes rapid taps queue up and run one at a time instead
 * of erroring, and keeping the connection open for [IDLE_CLOSE_MS] after each op lets a burst reuse
 * it (fast) before it closes back to zero footprint. Different bulbs use different locks, so
 * "all on/off" drives them in parallel.
 */
object BleConductor {

    private const val TAG = "AidotBle"
    private const val IDLE_CLOSE_MS = 4_000L

    // After a bulb fails to connect (e.g. it's switched off at the wall), skip re-attempting it for
    // this long. A fresh connect attempt to an unreachable bulb ties up Android's BLE stack for
    // several seconds, which would stall taps to the bulbs that ARE on — so for a short window we
    // fail it instantly instead. Short enough that a bulb switched back on responds within a tap
    // or two; an explicit tap after the window retries it normally.
    private const val FAILURE_COOLDOWN_MS = 5_000L

    private class Session(val client: BulbClient) {
        var idleJob: Job? = null
    }

    private val sessions = ConcurrentHashMap<String, Session>()
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val recentFailures = ConcurrentHashMap<String, Long>()   // addr -> last connect-failure time
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun lockFor(address: String): Mutex = locks.getOrPut(address.uppercase()) { Mutex() }

    /** Apply attributes to one bulb. Serialized against other ops for the same address. */
    suspend fun apply(
        context: Context,
        address: String,
        modelId: String,
        deviceId: String,
        vararg attrs: Protocol.Attr,
    ): Boolean {
        val key = address.uppercase()
        // Fast-fail a bulb that just failed to connect, without taking its lock or touching the
        // radio — this is what keeps the other bulbs snappy while one is unreachable.
        val failedAt = recentFailures[key]
        if (failedAt != null && System.currentTimeMillis() - failedAt < FAILURE_COOLDOWN_MS) return false
        return lockFor(address).withLock { applyLocked(context, key, address, modelId, deviceId, *attrs) }
    }

    private suspend fun applyLocked(
        context: Context,
        key: String,
        address: String,
        modelId: String,
        deviceId: String,
        vararg attrs: Protocol.Attr,
    ): Boolean {
        val appCtx = context.applicationContext

        var session = sessions[key]?.also { it.idleJob?.cancel() }
        if (session == null || !session.client.connected) {
            session?.client?.close()
            sessions.remove(key)
            val client = BulbClient(appCtx, address)
            if (!connectAndSignIn(client, modelId, deviceId)) {
                client.close()
                recentFailures[key] = System.currentTimeMillis()
                return false
            }
            recentFailures.remove(key)   // reachable again
            session = Session(client).also { sessions[key] = it }
        }

        val resp = session.client.setAttrs(*attrs)
        val ok = resp != null && resp.ok
        if (ok) {
            scheduleIdleClose(key, session)
        } else {
            // a failed exchange may mean a dead link — drop it so the next op reconnects fresh
            session.client.close()
            sessions.remove(key)
        }
        return ok
    }

    private suspend fun connectAndSignIn(client: BulbClient, modelId: String, deviceId: String): Boolean {
        if (!client.connect()) { Log.w(TAG, "connect failed"); return false }
        client.requestMtu()
        if (!client.discoverServices()) { Log.w(TAG, "discover failed"); return false }
        if (!client.enableNotifications()) { Log.w(TAG, "notify enable failed"); return false }
        if (!client.signIn(modelId, deviceId)) { Log.w(TAG, "sign-in failed"); return false }
        return true
    }

    private fun scheduleIdleClose(key: String, session: Session) {
        session.idleJob?.cancel()
        session.idleJob = scope.launch {
            delay(IDLE_CLOSE_MS)
            lockFor(key).withLock {
                if (sessions[key] === session) {
                    session.client.close()
                    sessions.remove(key)
                }
            }
        }
    }
}
