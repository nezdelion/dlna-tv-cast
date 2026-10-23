package io.github.nezdelion.dlnatvcast

import android.content.Context
import androidx.compose.runtime.Composable
import io.github.nezdelion.dlnatvcast.net.ServerOverride

/** Release builds have no server override. */
object DebugOverrides {
    fun serverOverride(@Suppress("UNUSED_PARAMETER") context: Context): ServerOverride? = null

    @Composable
    fun Section() = Unit
}
