package com.aidotble.widget.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * Coroutine wrapper around [BluetoothGatt] for one account-free control session.
 *
 * Typical one-shot control op (see [controlSession]):
 *   connect -> discoverServices -> enable b002 notifications -> sign-in -> setAttrs -> close.
 *
 * BLE GATT operations must be issued one at a time; every call here suspends until its
 * matching callback fires, so the whole flow stays serialized. Wrap calls in withTimeout at
 * the call site. Requires BLUETOOTH_CONNECT at runtime (checked by callers).
 */
@SuppressLint("MissingPermission")
class BulbClient(private val context: Context, val address: String) {

    private var gatt: BluetoothGatt? = null
    private val notifications = Channel<ByteArray>(Channel.UNLIMITED)

    private var connDeferred: CompletableDeferred<Boolean>? = null
    private var servicesDeferred: CompletableDeferred<Boolean>? = null
    private var mtuDeferred: CompletableDeferred<Int>? = null
    private var opDeferred: CompletableDeferred<Int>? = null
    private var lastRead: ByteArray = ByteArray(0)

    private var seq = 0
    private var key: ByteArray? = null
    private var signedIn = false

    @Volatile
    var connected = false
        private set

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connected = true
                    connDeferred?.takeUnless { it.isCompleted }?.complete(status == BluetoothGatt.GATT_SUCCESS)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    connected = false
                    connDeferred?.takeUnless { it.isCompleted }?.complete(false)
                    servicesDeferred?.takeUnless { it.isCompleted }?.complete(false)
                    mtuDeferred?.takeUnless { it.isCompleted }?.complete(-1)
                    opDeferred?.takeUnless { it.isCompleted }?.complete(BluetoothGatt.GATT_FAILURE)
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            servicesDeferred?.takeUnless { it.isCompleted }?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            mtuDeferred?.takeUnless { it.isCompleted }?.complete(if (status == BluetoothGatt.GATT_SUCCESS) mtu else -1)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            opDeferred?.takeUnless { it.isCompleted }?.complete(status)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            opDeferred?.takeUnless { it.isCompleted }?.complete(status)
        }

        // API 33+ value-bearing variants
        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            lastRead = value
            opDeferred?.takeUnless { it.isCompleted }?.complete(status)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            notifications.trySend(value)
        }

        // legacy (< API 33) variants
        @Deprecated("pre-33", ReplaceWith(""))
        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            lastRead = ch.value ?: ByteArray(0)
            opDeferred?.takeUnless { it.isCompleted }?.complete(status)
        }

        @Deprecated("pre-33", ReplaceWith(""))
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            notifications.trySend(ch.value ?: ByteArray(0))
        }
    }

    // -- connection ---------------------------------------------------------
    suspend fun connect(): Boolean {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
            ?: return false
        val device = adapter.getRemoteDevice(address)
        val d = CompletableDeferred<Boolean>()
        connDeferred = d
        gatt = device.connectGatt(context, false, callback, android.bluetooth.BluetoothDevice.TRANSPORT_LE)
        if (gatt == null) return false
        return (withTimeoutOrNull(8_000) { d.await() } == true)
    }

    /**
     * Negotiate a larger ATT MTU. Android defaults to 23 (20-byte writes), but command frames —
     * the 38-byte sign-in in particular — are larger, and unlike BlueZ, Android does NOT raise the
     * MTU on its own. Returns the granted MTU, or -1 on failure. Best-effort: callers proceed even
     * on -1 (some stacks don't report it but already use a larger MTU).
     */
    suspend fun requestMtu(mtu: Int = 512): Int {
        val g = gatt ?: return -1
        val d = CompletableDeferred<Int>()
        mtuDeferred = d
        if (!g.requestMtu(mtu)) return -1
        return withTimeoutOrNull(4_000) { d.await() } ?: -1
    }

    suspend fun discoverServices(): Boolean {
        val g = gatt ?: return false
        val d = CompletableDeferred<Boolean>()
        servicesDeferred = d
        if (!g.discoverServices()) return false
        return (withTimeoutOrNull(10_000) { d.await() } == true)
    }

    suspend fun enableNotifications(): Boolean {
        val g = gatt ?: return false
        val ch = characteristic(Protocol.CH_CMD_NOTIFY) ?: return false
        if (!g.setCharacteristicNotification(ch, true)) return false
        val cccd = ch.getDescriptor(Protocol.CCCD) ?: return false
        return writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
    }

    // -- reads --------------------------------------------------------------
    /** Read + decrypt getDeviceInfo (char a00b); returns its JSON, or null. */
    suspend fun readDeviceInfo(): JSONObject? {
        val g = gatt ?: return null
        val ch = characteristic(Protocol.CH_DEVINFO) ?: return null
        val d = CompletableDeferred<Int>()
        opDeferred = d
        if (!g.readCharacteristic(ch)) return null
        val status = withTimeoutOrNull(6_000) { d.await() } ?: return null
        if (status != BluetoothGatt.GATT_SUCCESS) return null
        return try {
            val plain = Crypto.decryptRead(lastRead, Crypto.provKey(address))
            val text = String(plain, Charsets.UTF_8).substringBefore('\u0000')
            JSONObject(text)
        } catch (_: Exception) {
            null
        }
    }

    // -- command channel ----------------------------------------------------
    private fun nextSeq(): Int { seq = (seq + 1) and 0xFF; return seq }

    private fun drainNotifications() {
        while (notifications.tryReceive().isSuccess) { /* discard stale */ }
    }

    /** Send one frame; return the first decoded response, or null on timeout. */
    private suspend fun exchange(category: Int, command: Int, payload: ByteArray, encrypt: Boolean): Protocol.Response? {
        drainNotifications()
        var frame = Protocol.buildFrame(nextSeq(), category, command, payload)
        if (encrypt) {
            val k = key ?: return null
            frame = Crypto.ctr(k, Crypto.SIGN_CONST, frame)
        }
        if (!writeCommand(frame)) return null
        var raw = withTimeoutOrNull(4_000) { notifications.receive() } ?: return null
        if (signedIn) {
            val k = key ?: return null
            raw = Crypto.ctr(k, Crypto.SIGN_CONST, raw)
        }
        return Protocol.parseResponse(raw)
    }

    /**
     * Authenticate the session for [modelId]. The sign-in frame itself is sent in the clear;
     * after the device ACK (cmd 0x90, status 0) every frame is AES-256-CTR with the derived key.
     */
    suspend fun signIn(modelId: String, deviceId: String): Boolean {
        key = Crypto.commandKey(modelId, address, deviceId)
        signedIn = false
        val k = key ?: return false
        val payload = Crypto.SIGN_CONST + Crypto.ctr(k, Crypto.SIGN_CONST, Crypto.SIGN_CONST)
        val resp = exchange(Protocol.CAT_SIGNIN, Protocol.CMD_SIGNIN, payload, encrypt = false)
        if (resp != null && resp.command == Protocol.CMD_SIGNIN_ACK && resp.ok) {
            signedIn = true
            return true
        }
        return false
    }

    suspend fun setAttrs(vararg attrs: Protocol.Attr): Protocol.Response? {
        if (!signedIn) return null
        return exchange(Protocol.CAT_ATTR, Protocol.CMD_SET_ATTRS, Protocol.encodeAttrs(attrs.toList()), encrypt = true)
    }

    // -- low-level write helpers (version-branched) -------------------------
    private suspend fun writeCommand(bytes: ByteArray): Boolean {
        val g = gatt ?: return false
        val ch = characteristic(Protocol.CH_CMD_WRITE) ?: return false
        val d = CompletableDeferred<Int>()
        opDeferred = d
        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(ch, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                ch.value = bytes
                g.writeCharacteristic(ch)
            }
        }
        if (!started) return false
        return (withTimeoutOrNull(4_000) { d.await() } == BluetoothGatt.GATT_SUCCESS)
    }

    private suspend fun writeDescriptor(desc: BluetoothGattDescriptor, value: ByteArray): Boolean {
        val g = gatt ?: return false
        val d = CompletableDeferred<Int>()
        opDeferred = d
        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(desc, value) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                desc.value = value
                g.writeDescriptor(desc)
            }
        }
        if (!started) return false
        return (withTimeoutOrNull(4_000) { d.await() } == BluetoothGatt.GATT_SUCCESS)
    }

    private fun characteristic(uuid: java.util.UUID): BluetoothGattCharacteristic? =
        gatt?.getService(Protocol.SERVICE)?.getCharacteristic(uuid)

    fun close() {
        try { gatt?.disconnect() } catch (_: Exception) {}
        try { gatt?.close() } catch (_: Exception) {}
        gatt = null
        signedIn = false
    }
}
