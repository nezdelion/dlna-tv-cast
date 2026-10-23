package io.github.nezdelion.dlnatvcast.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.nezdelion.dlnatvcast.R
import io.github.nezdelion.dlnatvcast.core.upnp.DeviceDescription
import io.github.nezdelion.dlnatvcast.core.upnp.RendererDevice
import io.github.nezdelion.dlnatvcast.core.upnp.RendererDiscovery
import io.github.nezdelion.dlnatvcast.core.upnp.SsdpClient
import io.github.nezdelion.dlnatvcast.core.upnp.UdpSsdpTransport
import io.github.nezdelion.dlnatvcast.net.LocalNetwork
import io.github.nezdelion.dlnatvcast.prefs.RendererStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Lists renderers from SSDP plus manually added ones. [itemCount] > 0 when a share waits for a TV.
 */
@Composable
fun PickerScreen(itemCount: Int, notice: String?, onPicked: (RendererDevice) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val network = remember { LocalNetwork(context) }
    val store = remember { RendererStore(context) }
    val scope = rememberCoroutineScope()
    val devices = remember { mutableStateListOf<RendererDevice>() }
    var searching by remember { mutableStateOf(false) }
    var searchRound by remember { mutableStateOf(0) }
    var noWifi by remember { mutableStateOf(false) }
    var showAdd by remember { mutableStateOf(false) }

    fun addDevice(device: RendererDevice) {
        val existing = devices.indexOfFirst { it.udn == device.udn }
        if (existing >= 0) devices[existing] = device else devices += device
    }

    LaunchedEffect(searchRound) {
        val lan = network.currentLan()
        noWifi = lan == null
        if (lan == null) return@LaunchedEffect
        searching = true
        try {
            // Manually added renderers are re-validated alongside discovery.
            for (location in store.manualLocations) {
                launch {
                    val device = withContext(Dispatchers.IO) {
                        try {
                            DeviceDescription.fetch(location)
                        } catch (_: IOException) {
                            null
                        }
                    }
                    device?.let(::addDevice)
                }
            }
            network.withMulticastLock {
                RendererDiscovery(SsdpClient { UdpSsdpTransport(lan.networkInterface) }).discover().collect { addDevice(it) }
            }
        } finally {
            searching = false
        }
    }

    fun pick(device: RendererDevice) {
        store.remember(device)
        onPicked(device)
    }

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.picker_title), style = MaterialTheme.typography.headlineSmall)
            if (itemCount > 0) Text(pluralStringResource(R.plurals.picker_items, itemCount, itemCount))
            notice?.let { Text(it, color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.testTag("notice")) }
            when {
                noWifi -> Text(stringResource(R.string.error_wifi_required), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("no_wifi"))
                searching -> LinearProgressIndicator(Modifier.fillMaxWidth())
                devices.isEmpty() -> Text(stringResource(R.string.picker_empty), modifier = Modifier.testTag("picker_empty"))
            }
            LazyColumn(Modifier.weight(1f, fill = true)) {
                items(devices, key = { it.udn }) { device ->
                    Column(
                        Modifier.fillMaxWidth().clickable { pick(device) }.padding(vertical = 12.dp).testTag("renderer_${device.friendlyName}"),
                    ) {
                        Text(device.friendlyName, style = MaterialTheme.typography.titleMedium)
                        Text(device.location, style = MaterialTheme.typography.bodySmall)
                    }
                    HorizontalDivider()
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { searchRound++ }, enabled = !searching) { Text(stringResource(R.string.picker_search_again)) }
                OutlinedButton(onClick = { showAdd = true }) { Text(stringResource(R.string.picker_add_manual)) }
            }
            TextButton(onClick = onCancel) { Text(stringResource(android.R.string.cancel)) }
        }
    }

    if (showAdd) {
        var url by remember { mutableStateOf("http://") }
        var error by remember { mutableStateOf<String?>(null) }
        var checking by remember { mutableStateOf(false) }
        val invalidMessage = stringResource(R.string.manual_invalid)
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text(stringResource(R.string.manual_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.manual_hint), style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        url, { url = it; error = null }, singleLine = true,
                        label = { Text(stringResource(R.string.manual_label)) },
                        modifier = Modifier.testTag("manual_url"),
                    )
                    if (checking) LinearProgressIndicator(Modifier.fillMaxWidth())
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("manual_error")) }
                }
            },
            confirmButton = {
                TextButton(enabled = !checking, onClick = {
                    checking = true
                    scope.launch {
                        try {
                            val device = withContext(Dispatchers.IO) { DeviceDescription.fetch(url.trim()) }
                            store.addManual(device.location)
                            addDevice(device)
                            showAdd = false
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            error = "$invalidMessage\n${e.message}"
                        } finally {
                            checking = false
                        }
                    }
                }) { Text(stringResource(R.string.manual_add)) }
            },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
}
