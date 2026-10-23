package io.github.nezdelion.dlnatvcast.cast

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.nezdelion.dlnatvcast.DebugOverrides
import io.github.nezdelion.dlnatvcast.MainActivity
import io.github.nezdelion.dlnatvcast.R
import io.github.nezdelion.dlnatvcast.core.MediaItem
import io.github.nezdelion.dlnatvcast.core.http.MediaServer
import io.github.nezdelion.dlnatvcast.core.session.CastSession
import io.github.nezdelion.dlnatvcast.core.session.QueueEntry
import io.github.nezdelion.dlnatvcast.core.session.SessionState
import io.github.nezdelion.dlnatvcast.core.session.TransportState
import io.github.nezdelion.dlnatvcast.core.upnp.RendererDevice
import io.github.nezdelion.dlnatvcast.core.upnp.RendererDiscovery
import io.github.nezdelion.dlnatvcast.core.upnp.SsdpClient
import io.github.nezdelion.dlnatvcast.core.upnp.UdpSsdpTransport
import io.github.nezdelion.dlnatvcast.core.upnp.UpnpRendererClient
import io.github.nezdelion.dlnatvcast.media.ContentUriByteSource
import io.github.nezdelion.dlnatvcast.net.LanInfo
import io.github.nezdelion.dlnatvcast.net.LocalNetwork
import io.github.nezdelion.dlnatvcast.prefs.RendererStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress

/**
 * Foreground service (`mediaPlayback`) that owns the shared queue, the HTTP server and the
 * cast session. Lives from a share until the user ends the session.
 */
class CastService : Service(), CastHub.Controller {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var network: LocalNetwork
    private lateinit var store: RendererStore

    private var items: List<MediaItem> = emptyList()
    private var lan: LanInfo? = null
    private var server: MediaServer? = null
    private var session: CastSession? = null
    private var sessionScope: CoroutineScope? = null
    private var prepareJob: Job? = null

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            session?.setForeground(intent.action == Intent.ACTION_SCREEN_ON)
        }
    }

    override fun onCreate() {
        super.onCreate()
        network = LocalNetwork(this)
        store = RendererStore(this)
        createChannel()
        CastHub.controller = this
        ContextCompat.registerReceiver(
            this, screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHARE -> {
                goForeground(notification(getString(R.string.notification_preparing), null))
                handleShare(intent)
            }
            ACTION_TOGGLE -> session?.let { s -> scope.launch { s.togglePlayPause() } }
            ACTION_NEXT -> session?.let { s -> scope.launch { s.next() } }
            ACTION_STOP -> endSession()
            else -> if (session == null && prepareJob == null) stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        unregisterReceiver(screenReceiver)
        if (CastHub.controller === this) CastHub.controller = null
        val s = session
        // Best effort: the renderer should not keep playing a URL that is about to disappear.
        if (s != null) runBlocking { withTimeoutOrNull(1_500) { s.close(stopRenderer = true) } }
        teardown()
        scope.cancel()
        if (CastHub.state.value.phase !is CastPhase.Failed) CastHub.setPhase(CastPhase.Idle, null)
        super.onDestroy()
    }

    private fun handleShare(intent: Intent) {
        val uris = sharedUris(intent)
        val unreadable = intent.getIntExtra(EXTRA_UNREADABLE, 0)
        val typeHint = intent.type
        prepareJob?.cancel()
        prepareJob = scope.launch {
            // A new share replaces the previous queue; the renderer simply gets the new item.
            session?.close(stopRenderer = false)
            teardown()
            val lanInfo = network.currentLan()
            if (lanInfo == null) {
                fail(getString(R.string.error_wifi_required))
                return@launch
            }
            lan = lanInfo
            CastHub.setPhase(CastPhase.Preparing(getString(R.string.preparing_items)), null)
            val readable = withContext(Dispatchers.IO) {
                uris.mapNotNull { ContentUriByteSource.inspect(contentResolver, it, typeHint) }
            }
            val shared = uris.size + unreadable
            val skipped = shared - readable.size
            if (readable.isEmpty()) {
                fail(resources.getQuantityString(R.plurals.error_nothing_readable, shared, shared))
                return@launch
            }
            items = readable
            val notice = if (skipped > 0) resources.getQuantityString(R.plurals.notice_skipped, skipped, skipped) else null
            val server = try {
                startServer(lanInfo, readable)
            } catch (e: Exception) {
                fail(getString(R.string.error_server, e.message ?: e.javaClass.simpleName))
                return@launch
            }
            this@CastService.server = server

            val remembered = store.last
            if (remembered != null) {
                CastHub.setPhase(CastPhase.Preparing(getString(R.string.preparing_connecting, remembered.name)), notice)
                val device = network.withMulticastLock { discovery(lanInfo).resolve(remembered.udn, remembered.location) }
                if (device != null) {
                    startSession(device, notice)
                    return@launch
                }
            }
            CastHub.setPhase(CastPhase.ChooseRenderer(readable.size), notice)
            updateNotification(notification(getString(R.string.notification_choose_tv), null))
        }
    }

    private fun startServer(lanInfo: LanInfo, readable: List<MediaItem>): MediaServer {
        val override = DebugOverrides.serverOverride(this)
        return if (override != null) {
            MediaServer(readable, InetAddress.getByName("0.0.0.0"), override.port, override.advertisedAuthority).start()
        } else {
            MediaServer(readable, lanInfo.address).start()
        }
    }

    fun discovery(lanInfo: LanInfo) =
        RendererDiscovery(SsdpClient { UdpSsdpTransport(lanInfo.networkInterface) })

    override fun selectRenderer(device: RendererDevice) {
        scope.launch {
            if (server == null || items.isEmpty()) return@launch
            session?.close(stopRenderer = false)
            startSession(device, CastHub.state.value.notice)
        }
    }

    private fun startSession(device: RendererDevice, notice: String?) {
        val server = server ?: return
        store.remember(device)
        sessionScope?.cancel()
        val sScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        sessionScope = sScope
        val queue = items.mapIndexed { i, item ->
            QueueEntry(item.title, server.urlFor(i), item.mime, item.kind, item.source.length)
        }
        val s = CastSession(UpnpRendererClient(device), queue, sScope)
        session = s
        prepareJob = null
        s.setForeground(getSystemService(PowerManager::class.java).isInteractive)
        s.start()
        CastHub.setPhase(CastPhase.Casting(s, device), notice)
        sScope.launch {
            s.state
                .map { NotificationKey(it.current.title, it.transport, it.hasNext, it.rendererLost, it.index, it.queue.size) }
                .distinctUntilChanged()
                .collect {
                    Log.i(TAG, "item ${it.index + 1}/${it.size} ${it.transport}${if (it.lost) " (renderer lost)" else ""}")
                    updateNotification(notification(it.title, s.state.value))
                }
        }
    }

    override fun retry() {
        session?.retry()
    }

    override fun endSession() {
        scope.launch {
            prepareJob?.cancel()
            prepareJob = null
            session?.close(stopRenderer = true)
            teardown()
            CastHub.setPhase(CastPhase.Idle, null)
            ServiceCompat.stopForeground(this@CastService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun fail(message: String) {
        teardown()
        CastHub.setPhase(CastPhase.Failed(message), null)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun teardown() {
        sessionScope?.cancel()
        sessionScope = null
        session = null
        server?.close()
        server = null
    }

    private data class NotificationKey(
        val title: String,
        val transport: TransportState,
        val hasNext: Boolean,
        val lost: Boolean,
        val index: Int,
        val size: Int,
    )

    // Notification

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW)
        channel.setShowBadge(false)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun goForeground(notification: Notification) {
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
    }

    private fun updateNotification(notification: Notification) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    private fun serviceIntent(action: String, code: Int): PendingIntent = PendingIntent.getService(
        this, code, Intent(this, CastService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun notification(title: String, state: SessionState?): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        val compact = mutableListOf<Int>()
        var actions = 0
        if (state != null) {
            val text = when {
                state.rendererLost -> getString(R.string.state_lost)
                else -> getString(stateLabel(state.transport))
            }
            builder.setContentText(
                if (state.queue.size > 1) "${state.rendererName} · ${state.index + 1}/${state.queue.size} · $text"
                else "${state.rendererName} · $text",
            )
            val playing = state.transport == TransportState.PLAYING
            builder.addAction(
                if (playing) R.drawable.ic_pause else R.drawable.ic_play,
                getString(if (playing) R.string.action_pause else R.string.action_play),
                serviceIntent(ACTION_TOGGLE, 1),
            )
            compact += actions++
            if (state.hasNext) {
                builder.addAction(R.drawable.ic_next, getString(R.string.action_next), serviceIntent(ACTION_NEXT, 2))
                compact += actions++
            }
        }
        builder.addAction(R.drawable.ic_stop, getString(R.string.action_stop), serviceIntent(ACTION_STOP, 3))
        compact += actions
        builder.setStyle(
            androidx.media.app.NotificationCompat.MediaStyle().setShowActionsInCompactView(*compact.take(3).toIntArray()),
        )
        return builder.build()
    }

    companion object {
        const val ACTION_SHARE = "io.github.nezdelion.dlnatvcast.action.SHARE"
        const val ACTION_TOGGLE = "io.github.nezdelion.dlnatvcast.action.TOGGLE"
        const val ACTION_NEXT = "io.github.nezdelion.dlnatvcast.action.NEXT"
        const val ACTION_STOP = "io.github.nezdelion.dlnatvcast.action.STOP"
        private const val TAG = "DlnaTvCast"
        private const val EXTRA_UNREADABLE = "unreadable"
        private const val CHANNEL_ID = "cast"
        private const val NOTIFICATION_ID = 1

        fun stateLabel(transport: TransportState): Int = when (transport) {
            TransportState.PLAYING -> R.string.state_playing
            TransportState.PAUSED_PLAYBACK -> R.string.state_paused
            TransportState.STOPPED, TransportState.NO_MEDIA_PRESENT -> R.string.state_stopped
            TransportState.TRANSITIONING, TransportState.UNKNOWN -> R.string.state_loading
        }

        /** Builds the service intent for a share, carrying read grants for every URI. */
        fun shareIntent(context: Context, uris: List<Uri>, unreadableCount: Int, type: String?): Intent {
            val intent = Intent(context, CastService::class.java).setAction(ACTION_SHARE)
            intent.type = type
            intent.putExtra(EXTRA_UNREADABLE, unreadableCount)
            if (uris.isNotEmpty()) {
                val clip = ClipData.newRawUri(null, uris.first())
                uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
                intent.clipData = clip
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            return intent
        }

        private fun sharedUris(intent: Intent): List<Uri> {
            val clip = intent.clipData ?: return emptyList()
            return (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
        }
    }
}
