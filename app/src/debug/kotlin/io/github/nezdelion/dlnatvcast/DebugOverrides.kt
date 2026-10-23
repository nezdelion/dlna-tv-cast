package io.github.nezdelion.dlnatvcast

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.nezdelion.dlnatvcast.net.ServerOverride

/**
 * Debug builds only: advertised-host override for testing on the emulator, where the renderer
 * reaches the phone through `adb forward` instead of the LAN.
 */
object DebugOverrides {
    private const val PREFS = "debug_overrides"

    fun serverOverride(context: Context): ServerOverride? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val authority = prefs.getString("authority", null)?.takeIf { it.isNotBlank() } ?: return null
        val port = prefs.getInt("port", 0).takeIf { it in 1..65535 } ?: return null
        return ServerOverride(port, authority)
    }

    private fun save(context: Context, authority: String, port: Int?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("authority", authority.trim())
            .putInt("port", port ?: 0)
            .apply()
    }

    @Composable
    fun Section() {
        val context = LocalContext.current
        val current = remember { serverOverride(context) }
        var authority by remember { mutableStateOf(current?.advertisedAuthority ?: "") }
        var port by remember { mutableStateOf(current?.port?.toString() ?: "") }
        var saved by remember { mutableStateOf(current != null) }
        Column(Modifier.fillMaxWidth()) {
            Text("Debug: advertised host override", style = MaterialTheme.typography.titleSmall)
            Text(
                if (saved) "Active: ${serverOverride(context)}" else "Off (server uses the Wi-Fi address)",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("debug_override_status"),
            )
            OutlinedTextField(authority, { authority = it }, label = { Text("Advertised host:port") }, singleLine = true)
            OutlinedTextField(port, { port = it }, label = { Text("Listen port") }, singleLine = true)
            Row {
                Button(onClick = {
                    save(context, authority, port.toIntOrNull())
                    saved = serverOverride(context) != null
                }) { Text("Save") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = {
                    save(context, "", null)
                    authority = ""; port = ""; saved = false
                }) { Text("Clear") }
            }
        }
    }
}
