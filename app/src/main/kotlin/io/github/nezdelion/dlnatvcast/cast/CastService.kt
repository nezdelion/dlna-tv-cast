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
import io.github.nezdelion.dlnatvcast.core.cast.CastCoordinator
import io.github.nezdelion.dlnatvcast.core.cast.CastPhase
import io.github.nezdelion.dlnatvcast.core.cast.CastPlatform
import io.github.nezdelion.dlnatvcast.core.cast.ClockText
import io.github.nezdelion.dlnatvcast.core.cast.NotificationModel
import io.github.nezdelion.dlnatvcast.core.http.MediaServer
import io.github.nezdelion.dlnatvcast.core.session.CastSession
import io.github.nezdelion.dlnatvcast.core.session.RendererClient
import io.github.nezdelion.dlnatvcast.core.session.TransportState
import io.github.nezdelion.dlnatvcast.core.upnp.RendererDevice
import io.github.nezdelion.dlnatvcast.core.upnp.RendererDiscovery
import io.github.nezdelion.dlnatvcast.core.upnp.SsdpClient
import io.github.nezdelion.dlnatvcast.core.upnp.UdpSsdpTransport
import io.github.nezdelion.dlnatvcast.core.upnp.UpnpRendererClient
import io.github.nezdelion.dlnatvcast.media.ContentUriByteSource
import io.github.nezdelion.dlnatvcast.net.LocalNetwork
import io.github.nezdelion.dlnatvcast.prefs.RendererStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetAddress

/**
 * Foreground service (`mediaPlayback`) hosting a [CastCoordinator]: it owns the shared queue,
 * the HTTP server and the cast session from a share until the user ends casting.
 */
class CastService : Service(), CastHub.Controller, CastPlatform<Uri> {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var network: LocalNetwork
    private lateinit var store: RendererStore
    private lateinit var awake: AwakeLocks
    private lateinit var coordinator: CastCoordinator<Uri>
    private var shareType: String? = null

    private val session: CastSession?
        get() = (coordinator.state.value.phase as? CastPhase.Casting)?.session

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            session?.setForeground(intent.action == Intent.ACTION_SCREEN_ON)
        }
    }

    override fun onCreate() {
        super.onCreate()
        network = LocalNetwork(this)
        store = RendererStore(this)
        awake = AwakeLocks(this)
        // The cleanup scope outlives this service so a best-effort Stop can still go out.
        coordinator = CastCoordinator(scope, this, cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO))
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
        observe()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHARE -> {
                goForeground(notification(getString(R.string.notification_preparing), null))
                shareType = intent.type
                coordinator.share(sharedUris(intent), intent.getIntExtra(EXTRA_UNREADABLE, 0))
            }
            ACTION_TOGGLE -> session?.let { s -> scope.launch { s.togglePlayPause() } }
            ACTION_NEXT -> session?.let { s -> scope.launch { s.next() } }
            ACTION_STOP -> endSession()
            else -> if (coordinator.state.value.phase == CastPhase.Idle) stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        unregisterReceiver(screenReceiver)
        if (CastHub.controller === this) CastHub.controller = null
        // Never blocks: closes the local server now, Stop goes out in the background.
        coordinator.destroy()
        CastHub.publish(coordinator.state.value)
        scope.cancel()
        super.onDestroy()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observe() {
        scope.launch { coordinator.state.collect { CastHub.publish(it) } }
        scope.launch {
            coordinator.state
                .map { it.phase }
                .distinctUntilChanged()
                .flatMapLatest { phase ->
                    when (phase) {
                        is CastPhase.Casting -> {
                            phase.session.setForeground(getSystemService(PowerManager::class.java).isInteractive)
                            phase.session.state.map { NotificationModel.from(it) }.distinctUntilChanged()
                        }
                        is CastPhase.ChooseRenderer -> {
                            updateNotification(notification(getString(R.string.notification_choose_tv), null))
                            emptyFlow()
                        }
                        else -> emptyFlow()
                    }
                }
                .collect { model ->
                    Log.i(TAG, "item ${model.index + 1}/${model.count} ${model.transport}${if (model.rendererLost) " (renderer lost)" else ""}")
                    updateNotification(notification(model.title, model))
                }
        }
    }

    // CastHub.Controller

    override fun selectRenderer(device: RendererDevice) = coordinator.select(device)

    override fun endSession() {
        coordinator.end()
        // The notification goes now; the service stops once the renderer has had its Stop.
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    override fun retry() {
        session?.retry()
    }

    // CastPlatform

    override fun lanAddress(): InetAddress? = network.currentLan()?.address

    override suspend fun inspect(item: Uri): MediaItem? = withContext(Dispatchers.IO) {
        ContentUriByteSource.inspect(contentResolver, item, shareType)
    }

    override fun startServer(items: List<MediaItem>, lan: InetAddress): MediaServer {
        val override = DebugOverrides.serverOverride(this)
        return if (override != null) {
            MediaServer(items, InetAddress.getByName("0.0.0.0"), override.port, override.advertisedAuthority).start()
        } else {
            MediaServer(items, lan).start()
        }
    }

    override fun rememberedName(): String? = store.last?.name

    override suspend fun resolveRemembered(lan: InetAddress): RendererDevice? {
        val remembered = store.last ?: return null
        val lanInfo = network.currentLan() ?: return null
        return network.withMulticastLock {
            RendererDiscovery(SsdpClient { UdpSsdpTransport(lanInfo.networkInterface) })
                .resolve(remembered.udn, remembered.location)
        }
    }

    override fun remember(device: RendererDevice) = store.remember(device)

    override fun client(device: RendererDevice): RendererClient = UpnpRendererClient(device)

    override fun keepAwake(on: Boolean) = awake.hold(on)

    override fun finished() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

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

    private fun notification(title: String, model: NotificationModel?): Notification {
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
        if (model != null) {
            val stateText = if (model.rendererLost) getString(R.string.state_lost) else getString(stateLabel(model.transport))
            // Collapsed media notifications show one short line: time first, so it is not cut off;
            // renderer and queue position go to the header.
            val parts = mutableListOf<String>()
            val position = model.positionSec
            val duration = model.durationSec
            if (position != null && duration != null) {
                parts += getString(R.string.notification_position, ClockText.format(position, duration), ClockText.format(duration))
                builder.setProgress(duration.toInt(), position.toInt().coerceAtMost(duration.toInt()), false)
            }
            parts += stateText
            builder.setContentText(parts.joinToString(" · "))
            builder.setSubText(if (model.count > 1) "${model.rendererName} · ${model.index + 1}/${model.count}" else model.rendererName)
            builder.addAction(
                if (model.playing) R.drawable.ic_pause else R.drawable.ic_play,
                getString(if (model.playing) R.string.action_pause else R.string.action_play),
                serviceIntent(ACTION_TOGGLE, 1),
            )
            compact += actions++
            if (model.hasNext) {
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
