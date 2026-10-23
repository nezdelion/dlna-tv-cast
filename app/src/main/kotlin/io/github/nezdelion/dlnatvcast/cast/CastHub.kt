package io.github.nezdelion.dlnatvcast.cast

import io.github.nezdelion.dlnatvcast.core.cast.CastPhase
import io.github.nezdelion.dlnatvcast.core.cast.CoordinatorState
import io.github.nezdelion.dlnatvcast.core.upnp.RendererDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** In-process link between the UI and [CastService]; outlives service instances. */
object CastHub {
    private val _state = MutableStateFlow(CoordinatorState())
    val state: StateFlow<CoordinatorState> = _state.asStateFlow()

    /** Set by the running service; null when no service is alive. */
    @Volatile
    var controller: Controller? = null

    interface Controller {
        fun selectRenderer(device: RendererDevice)
        fun endSession()
        fun retry()
    }

    internal fun publish(state: CoordinatorState) {
        _state.value = state
    }

    /** UI dismisses a failure screen. */
    fun dismissFailure() = _state.update { if (it.phase is CastPhase.Failed) CoordinatorState() else it }
}
