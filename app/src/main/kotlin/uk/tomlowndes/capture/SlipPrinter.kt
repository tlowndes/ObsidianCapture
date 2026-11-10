package uk.tomlowndes.capture

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** A printer seen in a scan or saved for next time. */
data class SlipPrinterInfo(val name: String, val address: String)

/**
 * Sends an ESC/POS job to a Bluetooth LE pocket printer (the Phomemo M02 Pro and others that take ESC/POS).
 * The printer is picked once from a scan and remembered.
 */
@SuppressLint("MissingPermission")
object SlipPrinter {
    /** Services that carry the print channel on common pocket and receipt printers. Phomemo's is ff00 / ff02. */
    private val SERVICES = listOf("0000ff00", "000018f0", "0000ffe0", "0000fee7", "49535343", "e7810a71")
    private const val PREFERRED_CHAR = "0000ff02"
    private const val MAX_CHUNK = 180

    private fun p(ctx: Context) = ctx.applicationContext.getSharedPreferences("slip", Context.MODE_PRIVATE)

    fun saved(ctx: Context): SlipPrinterInfo? {
        val a = p(ctx).getString("printer", null) ?: return null
        return SlipPrinterInfo(p(ctx).getString("printerName", null) ?: "Printer", a)
    }

    fun save(ctx: Context, info: SlipPrinterInfo) {
        p(ctx).edit().putString("printer", info.address).putString("printerName", info.name).apply()
    }

    /** What to ask for before scanning or connecting. Android 10 and 11 need location to scan. */
    val permissions: Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    fun allowed(ctx: Context) = permissions.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }

    private fun adapter(ctx: Context) = ctx.getSystemService(BluetoothManager::class.java)?.adapter

    fun bluetoothOn(ctx: Context) = adapter(ctx)?.isEnabled == true

    /** Scans for [ms] and reports each named device once. */
    suspend fun scan(ctx: Context, ms: Long = 8_000, onFound: (SlipPrinterInfo) -> Unit) {
        val scanner = adapter(ctx)?.takeIf { it.isEnabled }?.bluetoothLeScanner ?: error("Bluetooth is off")
        val seen = mutableSetOf<String>()
        val cb = object : ScanCallback() {
            override fun onScanResult(type: Int, r: ScanResult) {
                val name = r.scanRecord?.deviceName ?: r.device.name ?: return
                if (seen.add(r.device.address)) onFound(SlipPrinterInfo(name, r.device.address))
            }
        }
        scanner.startScan(cb)
        try { delay(ms) } finally { runCatching { scanner.stopScan(cb) } }
    }

    /** Likely printers first: Phomemo's names start with M0x, T0x or Q0x. */
    fun looksLikePrinter(name: String) = Regex("^(M0|T0|Q0|D3|P1|MX|MPT|PT-|X\\d|GB0|GT0)", RegexOption.IGNORE_CASE).containsMatchIn(name) ||
        name.contains("print", true) || name.contains("phomemo", true)

    private class Link : BluetoothGattCallback() {
        val connected = CompletableDeferred<Boolean>()
        val discovered = CompletableDeferred<Boolean>()
        val mtu = CompletableDeferred<Int>()
        @Volatile var written: CompletableDeferred<Int>? = null
        @Volatile var lost = false

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, state: Int) {
            if (state == BluetoothProfile.STATE_CONNECTED) connected.complete(true)
            else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                lost = true
                connected.complete(false); discovered.complete(false); mtu.complete(23); written?.complete(-1)
            }
        }
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) { discovered.complete(status == BluetoothGatt.GATT_SUCCESS) }
        override fun onMtuChanged(g: BluetoothGatt, value: Int, status: Int) { mtu.complete(if (status == BluetoothGatt.GATT_SUCCESS) value else 23) }
        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) { written?.complete(status) }
    }

    /** Connects, sends [job] in small pieces and disconnects. [progress] gets 0..100. Throws with a plain message. */
    suspend fun print(ctx: Context, printer: SlipPrinterInfo, job: ByteArray, progress: (Int) -> Unit) {
        val a = adapter(ctx) ?: error("This phone has no Bluetooth")
        if (!a.isEnabled) error("Bluetooth is off")
        val device: BluetoothDevice = a.getRemoteDevice(printer.address)
        val link = Link()
        val gatt = device.connectGatt(ctx, false, link, BluetoothDevice.TRANSPORT_LE) ?: error("Couldn't start Bluetooth")
        try {
            if (withTimeoutOrNull(12_000) { link.connected.await() } != true) error("Couldn't reach ${printer.name}. Is it switched on and nearby?")
            gatt.requestMtu(MAX_CHUNK + 3)
            val mtu = withTimeoutOrNull(3_000) { link.mtu.await() } ?: 23
            if (!gatt.discoverServices() || withTimeoutOrNull(10_000) { link.discovered.await() } != true) error("${printer.name} didn't answer")
            val ch = channel(gatt) ?: error("${printer.name} has no print channel this app knows")
            val noResponse = (ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
            val type = if (noResponse) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            val step = (mtu - 3).coerceIn(20, MAX_CHUNK)
            var i = 0
            var last = -1
            while (i < job.size) {
                if (link.lost) error("${printer.name} disconnected part way through")
                val piece = job.copyOfRange(i, minOf(job.size, i + step))
                val done = CompletableDeferred<Int>().also { link.written = it }
                write(gatt, ch, piece, type)
                val status = withTimeoutOrNull(if (noResponse) 1_500 else 5_000) { done.await() }
                if (status == null && !noResponse) error("${printer.name} stopped answering")
                if (status != null && status != BluetoothGatt.GATT_SUCCESS) error("${printer.name} turned down the data (code $status)")
                // The head prints slower than Bluetooth sends, so give it a moment between pieces.
                delay(if (noResponse) 12 else 3)
                i += piece.size
                val pc = i * 100 / job.size
                if (pc != last) { last = pc; progress(pc) }
            }
            delay(1_000)
        } finally {
            runCatching { gatt.disconnect() }
            delay(200)
            runCatching { gatt.close() }
        }
    }

    private fun channel(g: BluetoothGatt): BluetoothGattCharacteristic? {
        fun writable(c: BluetoothGattCharacteristic) =
            (c.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0
        val known = g.services.filter { s -> SERVICES.any { s.uuid.toString().startsWith(it) } }
        return known.flatMap { it.characteristics }.firstOrNull { it.uuid.toString().startsWith(PREFERRED_CHAR) && writable(it) }
            ?: known.flatMap { it.characteristics }.firstOrNull(::writable)
            ?: g.services.filterNot { it.uuid.toString().startsWith("00001800") || it.uuid.toString().startsWith("00001801") }
                .flatMap { it.characteristics }.firstOrNull(::writable)
    }

    /** One write, retried while the Bluetooth stack is busy. */
    @Suppress("DEPRECATION")
    private suspend fun write(g: BluetoothGatt, ch: BluetoothGattCharacteristic, data: ByteArray, type: Int) {
        repeat(200) {
            val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(ch, data, type) == BluetoothStatusCodes.SUCCESS
            } else {
                ch.writeType = type; ch.value = data; g.writeCharacteristic(ch)
            }
            if (ok) return
            delay(10)
        }
        error("Bluetooth stayed busy")
    }
}
