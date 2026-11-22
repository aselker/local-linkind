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

    private class Session(val client: BulbClient) {
        var idleJob: Job? = null
    }

    private val sessions = ConcurrentHashMap<String, Session>()
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun lockFor(address: String): Mutex = locks.getOrPut(address.uppercase()) { Mutex() }

    /** Apply attributes to one bulb. Serialized against other ops for the same address. */
    suspend fun apply(
        context: Context,
        address: String,
        modelId: String,
        deviceId: String,
        vararg attrs: Protocol.Attr,
    ): Boolean = lockFor(address).withLock {
        val key = address.uppercase()
        val appCtx = context.applicationContext

        var session = sessions[key]?.also { it.idleJob?.cancel() }
        if (session == null || !session.client.connected) {
            session?.client?.close()
            sessions.remove(key)
            val client = BulbClient(appCtx, address)
            if (!connectAndSignIn(client, modelId, deviceId)) {
                client.close()
                return@withLock false
            }
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
        ok
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
