package com.roamer.bmsmonitor

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.util.Locale

class MainActivity : ComponentActivity() {
    private lateinit var ble: DualBleManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ble = DualBleManager(this)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = Color(0xFF0B0E14),
                    surface = Color(0xFF121720),
                    primary = Color(0xFF06B6D4),
                    onBackground = Color(0xFFF1F5F9),
                    onSurface = Color(0xFFF1F5F9)
                )
            ) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    RoamerDashboard(ble)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ble.release()
    }
}

@Composable
fun RoamerDashboard(ble: DualBleManager) {
    val slots by ble.slots.collectAsState()
    val scanning by ble.scanning.collectAsState()
    val seen by ble.seen.collectAsState()
    var showSetup by remember { mutableStateOf(false) }

    val requiredPerms = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    val context = androidx.compose.ui.platform.LocalContext.current
    var hasPerms by remember {
        mutableStateOf(requiredPerms.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        })
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { res ->
        hasPerms = res.values.all { it }
        if (hasPerms && showSetup) ble.startScan()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Roamer BMS", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text("Dual-battery direct BLE monitor", fontSize = 12.sp, color = Color(0xFF94A3B8))
                }
                OutlinedButton(onClick = { showSetup = !showSetup }) {
                    Text(if (showSetup) "Hide Setup" else "BLE Setup")
                }
            }
        }

        if (!hasPerms) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                    border = BorderStroke(1.dp, Color(0xFF334155))
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Bluetooth permission required", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Grant Nearby Devices permission so the app can scan and connect directly to both batteries.",
                            fontSize = 13.sp,
                            color = Color(0xFF94A3B8)
                        )
                        Button(onClick = { launcher.launch(requiredPerms) }) {
                            Text("Grant Bluetooth Access")
                        }
                    }
                }
            }
        }

        if (showSetup) {
            item {
                SetupPanel(
                    scanning = scanning,
                    seen = seen,
                    slots = slots,
                    onScan = {
                        if (hasPerms) ble.startScan() else launcher.launch(requiredPerms)
                    },
                    onAssign = { slot, mac -> ble.assign(slot, mac) },
                    onForget = { slot -> ble.forget(slot) }
                )
            }
        }

        items(slots, key = { it.slot }) { slotUi ->
            BatteryCard(slotUi)
        }
    }
}

@Composable
fun BatteryCard(slotUi: BatterySlotUi) {
    val b = slotUi.bms
    val title = "Battery ${slotUi.slot + 1}"
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF121720)),
        border = BorderStroke(1.dp, Color(0xFF1F2836))
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                val statusText = when {
                    slotUi.mac.isEmpty() -> "Unassigned"
                    !b.connected -> "Connecting..."
                    b.lastFrameMs == 0L -> "Waiting for frame..."
                    else -> "Live"
                }
                val statusColor = if (b.connected && b.lastFrameMs > 0L) Color(0xFF10B981) else Color(0xFFF59E0B)
                Text(statusText, fontSize = 12.sp, color = statusColor, fontWeight = FontWeight.Medium)
            }

            if (slotUi.mac.isEmpty()) {
                Text("No battery paired. Open BLE Setup to scan.", fontSize = 13.sp, color = Color(0xFF94A3B8))
                return@Column
            }

            if (b.lastFrameMs == 0L || b.layout == 0) {
                Text(slotUi.mac, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Color(0xFF94A3B8))
                return@Column
            }

            Text(
                "${b.soc}%",
                fontSize = 40.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )

            LinearProgressIndicator(
                progress = { (b.soc.coerceIn(0, 100)) / 100f },
                modifier = Modifier.fillMaxWidth().height(8.dp),
                color = when {
                    b.soc < 15 -> Color(0xFFF43F5E)
                    b.soc < 30 -> Color(0xFFF59E0B)
                    else -> Color(0xFF10B981)
                },
                trackColor = Color(0xFF1F2836)
            )

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                MetricCell("Voltage", String.format(Locale.US, "%.2f V", b.vTotal))
                MetricCell("Current", String.format(Locale.US, "%.1f A", b.current))
                MetricCell("Power", String.format(Locale.US, "%.0f W", b.power))
                MetricCell("Delta", String.format(Locale.US, "%.0f mV", b.cellDelta * 1000f))
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                MetricCell("Capacity", String.format(Locale.US, "%.0f / %.0f Ah", b.capRemain, b.capNominal))
                MetricCell("Temps", String.format(Locale.US, "%.1f / %.1f C", b.t1, b.t2))
                MetricCell("MOS", String.format(Locale.US, "%.1f C", b.tMos))
            }

            Text(
                "Charge MOS ${if (b.chg) "ON" else "OFF"} · Discharge MOS ${if (b.dchg) "ON" else "OFF"} · ${b.cycles} cycles · SOH ${b.soh}%",
                fontSize = 12.sp,
                color = Color(0xFF94A3B8)
            )

            if (b.errors != 0) {
                val errs = (0 until 16).filter { (b.errors and (1 shl it)) != 0 }
                    .map { JkBmsProtocol.ERROR_LABELS[it] }
                Text("Fault: " + errs.joinToString(" · "), fontSize = 13.sp, color = Color(0xFFF43F5E))
            }

            if (b.nCells > 0) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (rowStart in 0 until b.nCells step 4) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (idx in rowStart until minOf(rowStart + 4, b.nCells)) {
                                val cv = b.cells[idx]
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .background(Color(0xFF0B0E14), RoundedCornerShape(6.dp))
                                        .padding(vertical = 6.dp, horizontal = 4.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        "${idx + 1}: ${String.format(Locale.US, "%.3f", cv)}",
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MetricCell(label: String, value: String) {
    Column {
        Text(label, fontSize = 11.sp, color = Color(0xFF94A3B8))
        Text(value, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
    }
}

@Composable
fun SetupPanel(
    scanning: Boolean,
    seen: List<SeenDevice>,
    slots: List<BatterySlotUi>,
    onScan: () -> Unit,
    onAssign: (Int, String) -> Unit,
    onForget: (Int) -> Unit
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF121720)),
        border = BorderStroke(1.dp, Color(0xFF1F2836))
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "Close the Roamer SMART app first: a JK-BMS only accepts one Bluetooth connection.",
                fontSize = 12.sp,
                color = Color(0xFF94A3B8)
            )
            Button(onClick = onScan, enabled = !scanning) {
                Text(if (scanning) "Scanning (6s)..." else "Scan for Batteries")
            }
            seen.forEach { dev ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(dev.name, fontWeight = FontWeight.Medium)
                        Text("${dev.mac} · ${dev.rssi} dBm", fontSize = 11.sp, color = Color(0xFF94A3B8))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = { onAssign(0, dev.mac) }) { Text("#1") }
                        OutlinedButton(onClick = { onAssign(1, dev.mac) }) { Text("#2") }
                    }
                }
            }
            HorizontalDivider(color = Color(0xFF1F2836))
            slots.forEach { s ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Slot ${s.slot + 1}: ${s.mac.ifEmpty { "none" }}", fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    if (s.mac.isNotEmpty()) {
                        TextButton(onClick = { onForget(s.slot) }) { Text("Forget") }
                    }
                }
            }
        }
    }
}
