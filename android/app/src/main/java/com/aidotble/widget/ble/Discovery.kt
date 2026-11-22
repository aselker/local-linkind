package com.aidotble.widget.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import kotlinx.coroutines.delay

/** BLE discovery of AiDot/Leedarson bulbs (manufacturer id 0x6811). */
object Discovery {

    data class Found(val address: String, val name: String?, val shortModel: String)

    @SuppressLint("MissingPermission")
    suspend fun scan(context: Context, timeoutMs: Long = 8_000): List<Found> {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        val scanner = adapter?.bluetoothLeScanner ?: return emptyList()
        val found = LinkedHashMap<String, Found>()

        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val data = result.scanRecord?.getManufacturerSpecificData(Protocol.MFR_ID) ?: return
                // company id 0x6811 is already stripped; shortModelId sits at hex offset 16..22
                val hex = Protocol.bytesToHex(data)
                val short = if (hex.length >= 22) hex.substring(16, 22) else ""
                found[result.device.address] = Found(
                    address = result.device.address,
                    name = result.scanRecord?.deviceName,
                    shortModel = short,
                )
            }
        }

        val filter = ScanFilter.Builder()
            .setManufacturerData(Protocol.MFR_ID, ByteArray(0))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(listOf(filter), settings, cb)
        try {
            delay(timeoutMs)
        } finally {
            scanner.stopScan(cb)
        }
        return found.values.toList()
    }
}
