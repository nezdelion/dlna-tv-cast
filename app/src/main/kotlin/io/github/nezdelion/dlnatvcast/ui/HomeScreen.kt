package io.github.nezdelion.dlnatvcast.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.nezdelion.dlnatvcast.DebugOverrides
import io.github.nezdelion.dlnatvcast.R
import io.github.nezdelion.dlnatvcast.net.LocalNetwork
import io.github.nezdelion.dlnatvcast.prefs.RendererStore
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(error: String?, onDismissError: () -> Unit, onChooseTv: () -> Unit) {
    val context = LocalContext.current
    val network = remember { LocalNetwork(context) }
    val store = remember { RendererStore(context) }
    var address by remember { mutableStateOf(network.currentLan()) }
    LaunchedEffect(Unit) {
        while (true) {
            address = network.currentLan()
            delay(2_000)
        }
    }
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.safeDrawingPadding().padding(24.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(R.string.home_hint), style = MaterialTheme.typography.bodyLarge)
            HorizontalDivider()
            Text(stringResource(R.string.home_network), style = MaterialTheme.typography.titleSmall)
            Text(
                address?.let { stringResource(R.string.home_address, it.address.hostAddress ?: "?", it.interfaceName ?: "?") }
                    ?: stringResource(R.string.error_wifi_required),
                modifier = Modifier.testTag("lan_address"),
            )
            Text(stringResource(R.string.home_tv), style = MaterialTheme.typography.titleSmall)
            Text(store.last?.let { "${it.name}\n${it.location}" } ?: stringResource(R.string.home_no_tv))
            Button(onClick = onChooseTv) { Text(stringResource(R.string.home_choose_tv)) }
            HorizontalDivider()
            DebugOverrides.Section()
        }
    }
    if (error != null) {
        AlertDialog(
            onDismissRequest = onDismissError,
            confirmButton = { TextButton(onClick = onDismissError) { Text(stringResource(android.R.string.ok)) } },
            title = { Text(stringResource(R.string.error_title)) },
            text = { Text(error, modifier = Modifier.testTag("error_text")) },
        )
    }
}

@Composable
fun PreparingScreen(message: String, onCancel: () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            androidx.compose.material3.LinearProgressIndicator()
            Text(message, style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = onCancel) { Text(stringResource(android.R.string.cancel)) }
        }
    }
}
