package io.github.nezdelion.dlnatvcast.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.nezdelion.dlnatvcast.R
import io.github.nezdelion.dlnatvcast.cast.CastHub
import io.github.nezdelion.dlnatvcast.cast.CastPhase
import io.github.nezdelion.dlnatvcast.cast.CastService
import io.github.nezdelion.dlnatvcast.core.session.TransportState
import io.github.nezdelion.dlnatvcast.core.upnp.UpnpTime
import kotlinx.coroutines.launch

@Composable
fun RemoteScreen(phase: CastPhase.Casting, notice: String?) {
    val session = phase.session
    val state by session.state.collectAsState()
    val scope = rememberCoroutineScope()
    // Position while the user drags; null when following the renderer.
    var dragPosition by remember { mutableStateOf<Float?>(null) }


    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.safeDrawingPadding().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(state.rendererName, style = MaterialTheme.typography.labelLarge)
            Text(state.current.title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.testTag("title"))
            if (state.queue.size > 1) Text(stringResource(R.string.remote_position_in_queue, state.index + 1, state.queue.size))
            Text(
                stringResource(CastService.stateLabel(state.transport)),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.testTag("transport"),
            )
            notice?.let { Text(it, color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.testTag("notice")) }

            if (state.rendererLost) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(16.dp).testTag("lost_banner"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.state_lost), style = MaterialTheme.typography.titleMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { CastHub.controller?.retry() }) { Text(stringResource(R.string.action_retry)) }
                            OutlinedButton(onClick = { CastHub.controller?.endSession() }) { Text(stringResource(R.string.action_end)) }
                        }
                    }
                }
            }
            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("renderer_error"))
            }

            val duration = state.durationSec
            if (state.canSeek && duration != null) {
                val shown = dragPosition ?: (state.positionSec ?: 0).toFloat()
                Slider(
                    value = shown.coerceIn(0f, duration.toFloat()),
                    onValueChange = { dragPosition = it },
                    onValueChangeFinished = {
                        val target = dragPosition?.toLong()
                        dragPosition = null
                        if (target != null) scope.launch { session.seek(target) }
                    },
                    valueRange = 0f..duration.toFloat(),
                    modifier = Modifier.fillMaxWidth().testTag("seek"),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(UpnpTime.format(shown.toLong()), modifier = Modifier.testTag("position"))
                    Text(UpnpTime.format(duration))
                }
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            ) {
                if (state.queue.size > 1) {
                    FilledTonalButton(onClick = { scope.launch { session.previous() } }, enabled = state.hasPrevious) {
                        Text(stringResource(R.string.action_previous))
                    }
                }
                Button(onClick = { scope.launch { session.togglePlayPause() } }, modifier = Modifier.testTag("play_pause")) {
                    Text(stringResource(if (state.transport == TransportState.PLAYING) R.string.action_pause else R.string.action_play))
                }
                FilledTonalButton(onClick = { scope.launch { session.stop() } }) { Text(stringResource(R.string.action_stop)) }
                if (state.queue.size > 1) {
                    FilledTonalButton(onClick = { scope.launch { session.next() } }, enabled = state.hasNext) {
                        Text(stringResource(R.string.action_next))
                    }
                }
            }

            if (state.hasVolume) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(onClick = { scope.launch { session.changeVolume(-5) } }) { Text("−") }
                    Text(
                        if (state.muted == true) stringResource(R.string.remote_muted)
                        else stringResource(R.string.remote_volume, state.volume ?: 0),
                        modifier = Modifier.testTag("volume"),
                    )
                    OutlinedButton(onClick = { scope.launch { session.changeVolume(+5) } }) { Text("+") }
                    TextButton(onClick = { scope.launch { session.toggleMute() } }) {
                        Text(stringResource(if (state.muted == true) R.string.action_unmute else R.string.action_mute))
                    }
                }
            }

            TextButton(onClick = { CastHub.controller?.endSession() }, modifier = Modifier.testTag("end_session")) {
                Text(stringResource(R.string.action_end))
            }
        }
    }
}
