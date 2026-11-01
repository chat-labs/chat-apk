package moe.vin.chat.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import androidx.compose.runtime.snapshotFlow
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
        if (intent?.action == ACTION_QUIT) {
            Hub.quit()
            return START_NOT_STICKY
        }
        startForeground(Notifications.RUNNING_ID, notification(Hub.rooms.size, Hub.tor.state), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        if (wake == null && Hub.settings.stayAwake) {
            // chat sends in fixed slots, so its peers would time it out if the CPU slept between them.
            wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "chat:rooms").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
        if (multicast == null) {
            // Android drops LAN broadcasts unless an app asks for them; chat finds peers on the LAN that way.
            multicast = getSystemService(WifiManager::class.java).createMulticastLock("chat:lan").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
        if (watch == null) watch = Hub.scope.launch {
            snapshotFlow { Triple(Hub.rooms.values.count { it.status == Status.OPEN || it.status == Status.STARTING }, Hub.rooms.size, Hub.tor.state) }
                .collectLatest { (open, all, tor) ->
                    if (all == 0) stopSelf()
                    else getSystemService(android.app.NotificationManager::class.java)
                        .notify(Notifications.RUNNING_ID, notification(open, tor))
                }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        watch?.cancel()
        wake?.release()
        multicast?.release()
        super.onDestroy()
    }

    private fun notification(open: Int, tor: Tor.State): Notification {
        val quit = PendingIntent.getService(this, 1, Intent(this, ChatService::class.java).setAction(ACTION_QUIT), PendingIntent.FLAG_IMMUTABLE)
        val torText = when (tor.phase) {
            Tor.Phase.STARTING -> " · Tor ${tor.progress}%"
            Tor.Phase.READY -> " · Tor connected"
            Tor.Phase.FAILED -> " · Tor failed"
            Tor.Phase.OFF -> ""
        }
        return Notification.Builder(this, Notifications.RUNNING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (open == 1) "1 room open" else "$open rooms open")
            .setContentText("chat keeps them connected$torText")
            .setContentIntent(Notifications.open(this, null))
            .setOngoing(true)
            .setVisibility(Notification.VISIBILITY_SECRET)
            .addAction(Notification.Action.Builder(null, "Quit", quit).build())
            .build()
    }

    companion object {
        private const val ACTION_QUIT = "moe.vin.chat.QUIT"

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, ChatService::class.java))
        }
    }
}
