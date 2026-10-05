package dev.tanuj.coachcompass

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context

/**
 * Counts distinct Bluetooth LE advertisers heard in a rolling window. Addresses stay in memory only
 * for the window and never leave the phone; only the count is used.
 */
class BleCounter(ctx: Context) {
    data class Counts(val total: Int, val near: Int, val mid: Int, val far: Int)

    private val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private val seen = HashMap<String, Pair<Int, Long>>() // address -> (rssi, last seen)
    private var running = false

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = record(result)
        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::record)
    }

    private fun record(r: ScanResult) = synchronized(seen) {
        seen[r.device.address] = r.rssi to System.currentTimeMillis()
    }

    /** Returns null on success, or a message the screen can show. */
    @SuppressLint("MissingPermission")
    fun start(): String? {
        if (running) return null
        if (adapter == null) return "This phone has no Bluetooth"
        if (!adapter.isEnabled) return "Turn on Bluetooth to sense the coach"
        val scanner = adapter.bluetoothLeScanner ?: return "Bluetooth scanner unavailable"
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        return runCatching { scanner.startScan(null, settings, callback); running = true; null }
            .getOrElse { "Scan failed: ${it.message}" }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        if (!running) return
        runCatching { adapter?.bluetoothLeScanner?.stopScan(callback) }
        running = false
        synchronized(seen) { seen.clear() }
    }

    /** Devices heard in the last [windowMs], bucketed by signal strength (rough distance). */
    fun counts(windowMs: Long = 15_000, minRssi: Int = -92): Counts = synchronized(seen) {
        val now = System.currentTimeMillis()
        seen.entries.removeAll { now - it.value.second > windowMs }
        val rssis = seen.values.map { it.first }.filter { it >= minRssi }
        Counts(rssis.size, rssis.count { it >= -65 }, rssis.count { it in -80 until -65 }, rssis.count { it < -80 })
    }
}
