package moe.vin.chat.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import androidx.compose.runtime.snapshotFlow
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import moe.vin.chat.Hub
import moe.vin.chat.R
import moe.vin.chat.engine.Status
import moe.vin.chat.engine.Tor

// Keeps the app running while rooms are open: Android would otherwise stop it, and its chat and tor
// processes with it, soon after it leaves the screen.
class ChatService : Service() {
    private var watch: Job? = null
    private var wake: PowerManager.WakeLock? = null
    private var multicast: WifiManager.MulticastLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_QUIT -> {
                Hub.quit()
                return START_NOT_STICKY
            }
            ACTION_LOCK -> {
                Hub.lockUi()
                return START_NOT_STICKY
            }
        }
        // With its type from Android 10 on, as the platform takes it; before, without.
        ServiceCompat.startForeground(
            this,
            Notifications.RUNNING_ID,
            notification(Hub.rooms.size, Hub.tor.state, Hub.uiLocked),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        syncWakeLock(Hub.settings.stayAwake)
        if (multicast == null) {
            // Android drops LAN broadcasts unless an app asks for them; chat finds peers on the LAN that way.
            multicast = getSystemService(WifiManager::class.java).createMulticastLock("chat:lan").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
        if (watch == null) watch = Hub.scope.launch {
            snapshotFlow {
                ServiceSnap(
                    open = Hub.rooms.values.count { it.status == Status.OPEN || it.status == Status.STARTING },
                    all = Hub.rooms.size,
                    tor = Hub.tor.state,
                    stayAwake = Hub.settings.stayAwake,
                    locked = Hub.uiLocked,
                )
            }.collectLatest { snap ->
                syncWakeLock(snap.stayAwake)
                if (snap.all == 0) stopSelf()
                else getSystemService(NotificationManager::class.java)
                    .notify(Notifications.RUNNING_ID, notification(snap.open, snap.tor, snap.locked))
            }
        }
        return START_NOT_STICKY
    }

    private fun syncWakeLock(stayAwake: Boolean) {
        if (stayAwake && wake == null) {
            // chat sends in fixed slots, so its peers would time it out if the CPU slept between them.
            wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "chat:rooms").apply {
                setReferenceCounted(false)
                acquire()
            }
        } else if (!stayAwake && wake != null) {
            wake?.release()
            wake = null
        }
    }

    override fun onDestroy() {
        watch?.cancel()
        wake?.release()
        multicast?.release()
        super.onDestroy()
    }

    private fun notification(open: Int, tor: Tor.State, locked: Boolean): Notification {
        val quit = PendingIntent.getService(this, 1, Intent(this, ChatService::class.java).setAction(ACTION_QUIT), PendingIntent.FLAG_IMMUTABLE)
        val lock = PendingIntent.getService(this, 2, Intent(this, ChatService::class.java).setAction(ACTION_LOCK), PendingIntent.FLAG_IMMUTABLE)
        val torText = when (tor.phase) {
            Tor.Phase.STARTING -> " · Tor ${tor.progress}%"
            Tor.Phase.READY -> " · Tor connected"
            Tor.Phase.FAILED -> " · Tor failed"
            Tor.Phase.OFF -> ""
        }
        val lockText = if (locked) " · locked" else ""
        return Notification.Builder(this, Notifications.RUNNING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (open == 1) "1 room open" else "$open rooms open")
            .setContentText("chat keeps them connected$torText$lockText")
            .setContentIntent(Notifications.open(this, null))
            .setOngoing(true)
            .setVisibility(Notification.VISIBILITY_SECRET)
            .apply {
                if (!locked) addAction(Notification.Action.Builder(null, "Lock", lock).build())
                addAction(Notification.Action.Builder(null, "Quit", quit).build())
            }
            .build()
    }

    private data class ServiceSnap(
        val open: Int,
        val all: Int,
        val tor: Tor.State,
        val stayAwake: Boolean,
        val locked: Boolean,
    )

    companion object {
        private const val ACTION_QUIT = "moe.vin.chat.QUIT"
        private const val ACTION_LOCK = "moe.vin.chat.LOCK"

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, ChatService::class.java))
        }
    }
}
