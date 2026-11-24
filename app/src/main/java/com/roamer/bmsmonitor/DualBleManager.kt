package com.roamer.bmsmonitor

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

data class SeenDevice(
    val name: String,
    val mac: String,
    val rssi: Int
)

data class BatterySlotUi(
    val slot: Int,
    val mac: String,
    val bms: JkBmsProtocol.BmsState
)

@SuppressLint("MissingPermission")
class DualBleManager(private val context: Context) {
    private val prefs = context.getSharedPreferences("roamer_bms", Context.MODE_PRIVATE)
    private val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? get() = btManager.adapter
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val gatts = arrayOfNulls<BluetoothGatt>(2)
    private val chars = arrayOfNulls<BluetoothGattCharacteristic>(2)
    private val assemblers = arrayOf(JkBmsProtocol.FrameAssembler(), JkBmsProtocol.FrameAssembler())
    private val nextTryMs = longArrayOf(0L, 0L)
    private val lastReqMs = longArrayOf(0L, 0L)
    private val macs = arrayOf(
        prefs.getString("m0", "") ?: "",
        prefs.getString("m1", "") ?: ""
    )

    private val _slots = MutableStateFlow(
        listOf(
            BatterySlotUi(0, macs[0], JkBmsProtocol.BmsState()),
            BatterySlotUi(1, macs[1], JkBmsProtocol.BmsState())
        )
    )
    val slots: StateFlow<List<BatterySlotUi>> = _slots.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _seen = MutableStateFlow<List<SeenDevice>>(emptyList())
    val seen: StateFlow<List<SeenDevice>> = _seen.asStateFlow()

    private val scanMap = LinkedHashMap<String, SeenDevice>()
    private var scanCallback: ScanCallback? = null

    init {
        scope.launch {
            while (isActive) {
                tickLinks()
                delay(500L)
            }
        }
    }

    private fun updateSlot(slot: Int, transform: (BatterySlotUi) -> BatterySlotUi) {
        val cur = _slots.value.toMutableList()
        cur[slot] = transform(cur[slot])
        _slots.value = cur
    }

    fun startScan() {
        val scanner = adapter?.bluetoothLeScanner ?: return
        if (_scanning.value) return
        scanMap.clear()
        _seen.value = emptyList()
        _scanning.value = true

        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val dev = result.device ?: return
                val name = dev.name ?: result.scanRecord?.deviceName ?: return
                if (name.isBlank()) return
                scanMap[dev.address] = SeenDevice(name, dev.address, result.rssi)
                _seen.value = scanMap.values.sortedByDescending { it.rssi }
            }
        }
        scanCallback = cb
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner.startScan(null, settings, cb)

        mainHandler.postDelayed({ stopScan() }, 6000L)
    }

    fun stopScan() {
        val cb = scanCallback ?: return
        adapter?.bluetoothLeScanner?.stopScan(cb)
        scanCallback = null
        _scanning.value = false
    }

    fun assign(slot: Int, mac: String) {
        val clean = mac.uppercase()
        macs[slot] = clean
        prefs.edit().putString(if (slot == 0) "m0" else "m1", clean).apply()
        disconnect(slot)
        nextTryMs[slot] = 0L
        updateSlot(slot) { BatterySlotUi(slot, clean, JkBmsProtocol.BmsState()) }
    }

    fun forget(slot: Int) {
        macs[slot] = ""
        prefs.edit().remove(if (slot == 0) "m0" else "m1").apply()
        disconnect(slot)
        updateSlot(slot) { BatterySlotUi(slot, "", JkBmsProtocol.BmsState()) }
    }

    private fun tickLinks() {
        val now = System.currentTimeMillis()
        for (slot in 0..1) {
            val mac = macs[slot]
            if (mac.isEmpty()) continue
            val state = _slots.value[slot].bms
            if (!state.connected) {
                if (gatts[slot] == null && now >= nextTryMs[slot]) {
                    connect(slot, mac)
                }
            } else {
                val stalled = state.lastFrameMs == 0L || (now - state.lastFrameMs) > 4000L
                if (now - lastReqMs[slot] > 5000L && stalled) {
                    chars[slot]?.let { sendCmd(slot, it, JkBmsProtocol.CMD_CELL) }
                }
            }
        }
    }

    private fun connect(slot: Int, mac: String) {
        val dev = adapter?.getRemoteDevice(mac) ?: return
        nextTryMs[slot] = System.currentTimeMillis() + 6000L
        assemblers[slot].reset()

        val gatt = dev.connectGatt(context, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    mainHandler.post {
                        updateSlot(slot) { it.copy(bms = it.bms.copy(connected = true)) }
                    }
                    g.discoverServices()
                } else {
                    mainHandler.post {
                        chars[slot] = null
                        gatts[slot]?.close()
                        gatts[slot] = null
                        nextTryMs[slot] = System.currentTimeMillis() + 5000L
                        updateSlot(slot) { it.copy(bms = it.bms.copy(connected = false)) }
                    }
                }
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    g.disconnect()
                    return
                }
                val svc = g.getService(UUID.fromString(JkBmsProtocol.SERVICE_UUID))
                val chr = svc?.getCharacteristic(UUID.fromString(JkBmsProtocol.CHAR_UUID))
                if (chr == null) {
                    g.disconnect()
                    return
                }
                chars[slot] = chr
                g.setCharacteristicNotification(chr, true)
                val cccd = chr.getDescriptor(UUID.fromString(JkBmsProtocol.CCCD_UUID))
                if (cccd != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    } else {
                        @Suppress("DEPRECATION")
                        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        @Suppress("DEPRECATION")
                        g.writeDescriptor(cccd)
                    }
                }
                mainHandler.postDelayed({
                    sendCmd(slot, chr, JkBmsProtocol.CMD_INFO)
                }, 250L)
                mainHandler.postDelayed({
                    sendCmd(slot, chr, JkBmsProtocol.CMD_CELL)
                }, 550L)
            }

            override fun onCharacteristicChanged(
                g: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray
            ) {
                handleBytes(slot, value)
            }

            @Deprecated("Deprecated in API 33")
            override fun onCharacteristicChanged(
                g: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic
            ) {
                @Suppress("DEPRECATION")
                val v = characteristic.value ?: return
                handleBytes(slot, v)
            }
        }, BluetoothDevice.TRANSPORT_LE)

        gatts[slot] = gatt
    }

    private fun handleBytes(slot: Int, bytes: ByteArray) {
        val frame = assemblers[slot].feed(bytes) ?: return
        val type = frame[4].toInt() and 0xFF
        val now = System.currentTimeMillis()
        mainHandler.post {
            updateSlot(slot) { cur ->
                val nextBms = when (type) {
                    0x02 -> JkBmsProtocol.decodeCell(cur.bms, frame, now)
                    0x03 -> JkBmsProtocol.decodeInfo(cur.bms, frame)
                    else -> cur.bms
                }
                cur.copy(bms = nextBms)
            }
        }
    }

    private fun sendCmd(slot: Int, chr: BluetoothGattCharacteristic, cmd: Byte) {
        val g = gatts[slot] ?: return
        val pkt = JkBmsProtocol.buildCmd(cmd)
        lastReqMs[slot] = System.currentTimeMillis()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(chr, pkt, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
        } else {
            @Suppress("DEPRECATION")
            chr.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            @Suppress("DEPRECATION")
            chr.value = pkt
            @Suppress("DEPRECATION")
            g.writeCharacteristic(chr)
        }
    }

    private fun disconnect(slot: Int) {
        chars[slot] = null
        gatts[slot]?.disconnect()
        gatts[slot]?.close()
        gatts[slot] = null
    }

    fun release() {
        stopScan()
        disconnect(0)
        disconnect(1)
        scope.cancel()
    }
}
