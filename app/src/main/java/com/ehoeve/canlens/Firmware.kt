package com.ehoeve.canlens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** The DashKit's own view over Wi-Fi or USB: its metrics, the transmit counts and the firmware log, refreshed twice a second. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FirmwareSheet(model: AppModel, onClose: () -> Unit) {
    val tick by produceState(0) { while (true) { delay(500); value++ } }
    val status = model.live.dashKitWifi?.status ?: model.live.dashKit?.status ?: model.live.commaDashKit
    val lines = remember(tick, status?.logLines) { status?.log().orEmpty() }
    val clipboard = LocalClipboardManager.current
    val list = rememberLazyListState()
    LaunchedEffect(lines.size, status?.logLines) { if (lines.isNotEmpty()) list.scrollToItem(lines.size - 1) }
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("DashKit firmware", style = MaterialTheme.typography.titleLarge)
            status?.build?.takeIf { it.isNotEmpty() }?.let { Hint("Built $it") }
            val m = status?.metrics
            Text(
                if (status == null) "Not connected"
                else if (m == null) "Waiting for the first metrics"
                else "CPU ${m[0]}% · ${m[1]}%   Wi-Fi ${m[11]} dBm   free ${m[12]} KB\n" +
                    "Bus 0: ${m[2]}% load, ${m[4]} fps   Bus 1: ${m[3]}% load, ${m[5]} fps\n" +
                    "FIFO overflows ${m[6]} · ${m[7]}   dropped: queue ${m[8]}, USB ${m[9]}, Wi-Fi ${m[10]}\n" +
                    "Datagrams lost on the way ${status.datagramsLost} of ${status.datagrams + status.datagramsLost}\n" +
                    "Transmit: sent ${status.sent}, refused ${status.refused}, no safe moment ${status.expired}" +
                    status.buses.withIndex().joinToString("") { (bus, health) -> if (health == null) "" else "\nBus $bus errors: transmit ${health.transmitErrors}, receive ${health.receiveErrors}" },
                fontFamily = MONO, fontSize = 12.sp, lineHeight = 16.sp,
            )
            Hint("Load counts no stuff bits, so the wire is a little busier. Overflows and drops are totals since the DashKit started.")
            TextButton(onClick = { clipboard.setText(AnnotatedString(lines.joinToString("\n"))) }) { Text("Copy the log (${lines.size} lines)") }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp), state = list) {
                items(lines) { Text(it, fontFamily = MONO, fontSize = 11.sp, lineHeight = 14.sp) }
            }
        }
    }
}

/** The comma's own view: CPU, temperatures, memory, its CAN health per bus, processes that stopped, and its log. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommaSheet(model: AppModel, onClose: () -> Unit) {
    val tick by produceState(0) { while (true) { delay(500); value++ } }
    val status = model.live.comma
    val lines = remember(tick, status?.logLines) { status?.log().orEmpty() }
    val clipboard = LocalClipboardManager.current
    val list = rememberLazyListState()
    LaunchedEffect(lines.size, status?.logLines) { if (lines.isNotEmpty()) list.scrollToItem(lines.size - 1) }
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("comma", style = MaterialTheme.typography.titleLarge)
            Text(
                if (status == null) "Not connected to the comma"
                else listOf(status.device, status.buses, status.processes).filter { it.isNotEmpty() }.joinToString("\n")
                    .ifEmpty { "Nothing yet: its bridge has to publish deviceState, pandaStates, managerState and the log" },
                fontFamily = MONO, fontSize = 12.sp, lineHeight = 16.sp,
            )
            TextButton(onClick = { clipboard.setText(AnnotatedString(lines.joinToString("\n"))) }) { Text("Copy the log (${lines.size} lines)") }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp), state = list) {
                items(lines) { Text(it, fontFamily = MONO, fontSize = 11.sp, lineHeight = 14.sp) }
            }
        }
    }
}
