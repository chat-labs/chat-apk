package moe.vin.chat.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import moe.vin.chat.R
import moe.vin.chat.engine.Notify
import moe.vin.chat.engine.Room
import moe.vin.chat.ui.MainActivity

object Notifications {
    const val RUNNING = "running"
    const val MESSAGES = "messages"
    const val RUNNING_ID = 1
    const val EXTRA_ROOM = "room"

    fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(RUNNING, "Rooms open", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shown while chat keeps rooms connected"
            setShowBadge(false)
        })
        nm.createNotificationChannel(NotificationChannel(MESSAGES, "Messages", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "New messages, as much of them as the preview setting allows"
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        })
    }

    fun open(ctx: Context, roomId: String?): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (roomId != null) i.putExtra(EXTRA_ROOM, roomId)
        return PendingIntent.getActivity(ctx, roomId?.hashCode() ?: 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    // Never the room: as in chat, the notification shows who and what only as far as the preview
    // setting lets chat pass them on, and on the lock screen not even that.
    fun message(ctx: Context, room: Room, n: Notify) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (!nm.areNotificationsEnabled()) return
        val title = n.nick ?: if (n.mention) "A message for you" else "New message"
        val public = Notification.Builder(ctx, MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("New message")
            .build()
        val b = Notification.Builder(ctx, MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentIntent(open(ctx, room.id))
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setGroup(room.id)
        n.text?.let { b.setContentText(it).setStyle(Notification.BigTextStyle().bigText(it)) }
        nm.notify(room.id.hashCode(), b.build())
    }

    fun clear(ctx: Context, roomId: String) {
        ctx.getSystemService(NotificationManager::class.java).cancel(roomId.hashCode())
    }
}
