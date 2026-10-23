package io.github.nezdelion.dlnatvcast.cast

import io.github.nezdelion.dlnatvcast.core.session.CastSession
import io.github.nezdelion.dlnatvcast.core.upnp.RendererDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** What the app is doing with the current share; shared by [CastService] and the UI. */
sealed interface CastPhase {
    data object Idle : CastPhase

    /** Reading shared items or looking for the remembered TV. */
    data class Preparing(val message: String) : CastPhase

    /** A queue is ready but no renderer is available yet. */
    data class ChooseRenderer(val itemCount: Int) : CastPhase

    data class Casting(val session: CastSession, val renderer: RendererDevice) : CastPhase

    /** Nothing can be cast; the renderer was not contacted. */
    data class Failed(val message: String) : CastPhase
}

data class CastUiState(
    val phase: CastPhase = CastPhase.Idle,
    /** One-off information such as skipped items. */
    val notice: String? = null,
)

/** In-process link between the UI and [CastService]. */
object CastHub {
    private val _state = MutableStateFlow(CastUiState())
    val state: StateFlow<CastUiState> = _state.asStateFlow()

    /** Set by the running service; null when no service is alive. */
    @Volatile
    var controller: Controller? = null

    interface Controller {
        fun selectRenderer(device: RendererDevice)
        fun endSession()
        fun retry()
    }

    internal fun setPhase(phase: CastPhase, notice: String? = _state.value.notice) {
        _state.value = CastUiState(phase, notice)
    }

    fun clearNotice() = _state.update { it.copy(notice = null) }

    /** UI dismisses a failure screen. */
    fun dismissFailure() = _state.update { if (it.phase is CastPhase.Failed) CastUiState() else it }
}
