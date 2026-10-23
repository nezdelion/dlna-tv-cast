package io.github.nezdelion.dlnatvcast

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import io.github.nezdelion.dlnatvcast.cast.CastHub
import io.github.nezdelion.dlnatvcast.cast.CastPhase
import io.github.nezdelion.dlnatvcast.ui.AppTheme
import io.github.nezdelion.dlnatvcast.ui.HomeScreen
import io.github.nezdelion.dlnatvcast.ui.PickerScreen
import io.github.nezdelion.dlnatvcast.ui.PreparingScreen
import io.github.nezdelion.dlnatvcast.ui.RemoteScreen

class MainActivity : ComponentActivity() {
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            AppTheme {
                val ui by CastHub.state.collectAsState()
                // Picker opened from the home screen to choose a default TV without a share.
                var standalonePicker by remember { mutableStateOf(false) }
                when (val phase = ui.phase) {
                    is CastPhase.Casting -> RemoteScreen(phase, ui.notice)
                    is CastPhase.ChooseRenderer -> PickerScreen(
                        itemCount = phase.itemCount,
                        notice = ui.notice,
                        onPicked = { CastHub.controller?.selectRenderer(it) },
                        onCancel = { CastHub.controller?.endSession() },
                    )
                    is CastPhase.Preparing -> PreparingScreen(phase.message, onCancel = { CastHub.controller?.endSession() })
                    is CastPhase.Failed, CastPhase.Idle ->
                        if (standalonePicker) {
                            PickerScreen(itemCount = 0, notice = null, onPicked = { standalonePicker = false }, onCancel = { standalonePicker = false })
                        } else {
                            HomeScreen(
                                error = (phase as? CastPhase.Failed)?.message,
                                onDismissError = CastHub::dismissFailure,
                                onChooseTv = { standalonePicker = true },
                            )
                        }
                }
            }
        }
    }
}
