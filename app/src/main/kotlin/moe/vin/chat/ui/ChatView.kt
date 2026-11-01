package moe.vin.chat.ui

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import moe.vin.chat.Hub
import moe.vin.chat.engine.FileItem
import moe.vin.chat.engine.Item
import moe.vin.chat.engine.LineKind
import moe.vin.chat.engine.Room
import moe.vin.chat.engine.Status
import moe.vin.chat.engine.Tick
import moe.vin.chat.service.Notifications

// chat's own limit on a message, in bytes.
private const val TEXT_MAX = 880
private const val PICTURE_W = 260f
private const val PICTURE_H_MAX = 360f

private fun offer(ctx: Context, room: Room, uri: Uri) {
    val f = copyToOutbox(ctx, room, uri)
    if (f == null) Toast.makeText(ctx, "Couldn't read that file", Toast.LENGTH_SHORT).show()
    else room.offer(f.path)
}

@Composable
fun ChatScreen(nav: Nav, roomId: String, dm: String?) {
    val room = Hub.rooms[roomId]
    if (room == null) {
        LaunchedEffect(Unit) { nav.back() }
        return
    }
    val p = LocalPalette.current
    val ctx = LocalContext.current
    DisposableEffect(room) {
        room.visible = true
        room.seen()
        Notifications.clear(ctx, room.id)
        onDispose { room.visible = false }
    }
    var text by remember { mutableStateOf(Shared.text?.also { Shared.text = null }.orEmpty()) }
    var replyTo by remember { mutableStateOf<Item?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { offer(ctx, room, it) } }
    LaunchedEffect(room.status) {
        if (room.status == Status.OPEN) Shared.file?.let { offer(ctx, room, it); Shared.file = null }
    }
    val dmPeer = dm?.let { room.peer(it) }
    val title = if (dm != null) dmPeer?.nick ?: "direct" else room.title.ifBlank { "new room" }
    val route = if (room.info?.route == "tor") "tor" else "direct"
    val subtitle = if (dm != null) "direct · only they get these" else "${roomState(room)} · $route"
    Page(title, subtitle, onBack = nav::back, actions = {
        if (dm == null) {
            IconAction(Icons.People, "Peers", { nav.go(Screen.Peers(roomId)) })
            IconAction(Icons.More, "Room", { nav.go(Screen.RoomSettings(roomId)) })
        } else {
            IconAction(Icons.Shield, "Verify code", { nav.go(Screen.PeerInfo(roomId, dm)) })
        }
    }) {
        room.moveAsk?.let { ask ->
            Banner(
                "${ask.from} asks you to move this room to a new one" + (if (ask.coming > 0) ", with ${ask.names}" else "") + ". Nobody left behind is told where it went.",
                listOf("Move" to { room.answerMove(true) }, "Stay" to { room.answerMove(false) }),
            )
        }
        if (room.status == Status.FAILED) {
            val entry = Hub.kept.firstOrNull { it.id == room.id }
            Banner("chat stopped: ${room.error}", if (entry == null) emptyList() else listOf("Try again" to {
                Hub.rooms.remove(room.id)
                Hub.open(entry)
            }), bad = true)
        } else room.error?.let { err ->
            Banner(err, listOf("OK" to { room.error = null }))
            LaunchedEffect(err) { delay(6000); if (room.error == err) room.error = null }
        }
        val items = if (dm != null) room.items.filter { it.line.direct && it.line.peer == dm } else room.items
        Messages(room, items.asReversed(), Modifier.weight(1f), dm == null, onReply = { replyTo = it }, onDm = { nav.go(Screen.Chat(roomId, it)) })
        replyTo?.let { r ->
            Row(Modifier.fillMaxWidth().background(p.surface).padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Reply, null, tint = p.accent, modifier = Modifier.size(16.dp))
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    Text("Answering ${r.line.nick ?: ""}", style = Type.label, color = p.accent)
                    Text(body(r).second, style = Type.small, color = p.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconAction(Icons.Close, "Don't answer", { replyTo = null }, tint = p.muted)
            }
        }
        Hairline()
        val bytes = text.toByteArray().size
        Row(Modifier.fillMaxWidth().imePadding().padding(horizontal = 6.dp, vertical = 8.dp), verticalAlignment = Alignment.Bottom) {
            if (dm == null) IconAction(Icons.Attach, "Send a file", { pick.launch(arrayOf("*/*")) }, tint = p.muted, enabled = room.status == Status.OPEN)
            Column(
                Modifier.weight(1f).padding(horizontal = 4.dp).clip(RoundedCornerShape(20.dp)).background(p.surface)
                    .border(1.dp, p.line, RoundedCornerShape(20.dp)).padding(horizontal = 14.dp, vertical = 11.dp),
            ) {
                PrivateField(text, { text = it }, Modifier.fillMaxWidth(), placeholder = if (dmPeer != null) "Only ${dmPeer.nick} gets this" else "Message", multiLine = true)
                if (bytes > TEXT_MAX - 120) Text("${TEXT_MAX - bytes}", style = Type.label, color = if (bytes > TEXT_MAX) p.bad else p.faint, modifier = Modifier.align(Alignment.End))
            }
            val canSend = text.isNotBlank() && bytes <= TEXT_MAX && room.status == Status.OPEN
            IconAction(Icons.Send, "Send", {
                val t = text.trim()
                val mid = replyTo?.line?.mid
                when {
                    dm != null -> room.dm(dm, t)
                    mid != null -> room.reply(mid, t)
                    else -> room.say(t)
                }
                text = ""
                replyTo = null
            }, tint = p.accent, enabled = canSend)
        }
    }
}

@Composable
fun Banner(text: String, actions: List<Pair<String, () -> Unit>>, bad: Boolean = false) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().background(p.surface).padding(horizontal = 20.dp, vertical = 12.dp)) {
        Text(text, style = Type.small, color = if (bad) p.bad else p.text)
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
            for ((label, action) in actions) Text(label, style = Type.small.copy(fontWeight = FontWeight.SemiBold), color = p.accent,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = action).padding(horizontal = 10.dp, vertical = 6.dp))
        }
    }
    Hairline()
}

@Composable
private fun Messages(room: Room, items: List<Item>, modifier: Modifier, inRoom: Boolean, onReply: (Item) -> Unit, onDm: (String) -> Unit) {
    val p = LocalPalette.current
    val state = rememberLazyListState()
    if (items.isEmpty()) {
        Box(modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
            Text(
                if (room.status == Status.OPEN && inRoom) "Nothing here yet. Compare verify codes with whoever joins before you trust them." else if (inRoom) "Opening the room…" else "No direct messages yet.",
                style = Type.small, color = p.faint, textAlign = TextAlign.Center,
            )
        }
        return
    }
    LazyColumn(modifier.fillMaxWidth(), state = state, reverseLayout = true, contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 10.dp)) {
        items(items, key = { it.seq }) { item ->
            when (item.line.kind) {
                LineKind.NOTE -> Text(
                    item.line.text.removePrefix("* "), style = Type.label, color = p.faint, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 5.dp),
                )
                LineKind.WARN -> Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp).clip(RoundedCornerShape(10.dp))
                        .border(1.dp, p.warn.copy(alpha = 0.5f), RoundedCornerShape(10.dp)).padding(12.dp),
                ) {
                    Icon(Icons.Warn, null, tint = p.warn, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(item.line.text.removePrefix("* "), style = Type.small, color = p.text)
                }
                LineKind.MESSAGE -> Bubble(room, item, inRoom, onReply, onDm)
            }
        }
    }
}

// A reply's text starts with the quote, quote bytes long.
private fun body(item: Item): Pair<String?, String> {
    val l = item.line
    if (l.quote <= 0) return null to l.text
    val b = l.text.toByteArray()
    if (l.quote > b.size) return null to l.text
    return String(b, 0, l.quote).trim().removeSuffix("·").trim() to String(b, l.quote, b.size - l.quote)
}

private val clock = SimpleDateFormat("HH:mm", Locale.getDefault())

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Bubble(room: Room, item: Item, inRoom: Boolean, onReply: (Item) -> Unit, onDm: (String) -> Unit) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val l = item.line
    val mine = l.mine
    val sender = room.peer(l.from)
    val via = l.peer?.takeIf { it != l.from && !l.direct }?.let { room.peer(it) }
    var menu by remember { mutableStateOf(false) }
    val (quote, text) = body(item)
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp),
        horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
    ) {
        if (!mine) Row(Modifier.padding(start = 6.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(l.nick ?: "?", style = Type.small.copy(fontWeight = FontWeight.SemiBold), color = readable(l.colour ?: sender?.colour, p))
            if (via != null) Text("  via ${via.nick}", style = Type.label, color = p.faint)
            val code = sender?.code?.state
            if (code == "compare" || code == "differs" || code == "key-changed")
                Tag(when (code) { "differs" -> "codes differ"; "key-changed" -> "key changed"; else -> "code not compared" },
                    if (code == "compare") p.faint else p.bad, Modifier.padding(start = 8.dp))
        }
        if (l.direct) Text(if (mine) "direct to ${room.peer(l.peer)?.nick ?: "them"}" else "direct to you", style = Type.label, color = p.accent,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp))
        Box {
            Column(
                Modifier.widthIn(max = 320.dp).clip(RoundedCornerShape(14.dp)).background(if (mine) p.mine else p.surface)
                    .then(if (mine) Modifier else Modifier.border(1.dp, p.line, RoundedCornerShape(14.dp)))
                    .combinedClickable(onClick = {}, onLongClick = { menu = true }).padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                if (quote != null) {
                    Row(Modifier.padding(bottom = 6.dp)) {
                        Box(Modifier.width(2.dp).heightIn(min = 16.dp).background(p.accent.copy(alpha = 0.6f)))
                        Text(quote.removePrefix("↪").trim(), style = Type.small, color = p.muted, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 8.dp))
                    }
                }
                val file = l.file
                when {
                    // A file's number is only good for the session that offered it.
                    file != null && item.old -> Text(text.substringBefore(" - :"), style = Type.small, color = p.muted)
                    file != null -> FileCard(room, file, l.mine)
                    else -> Text(text, style = Type.body, color = p.text)
                }
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = p.raised) {
                if (l.mid != null && !l.direct && inRoom) DropdownMenuItem(text = { Text("Answer", style = Type.small) }, onClick = { menu = false; onReply(item) })
                if (l.file == null) DropdownMenuItem(text = { Text("Copy", style = Type.small) }, onClick = { menu = false; copyText(ctx, text) })
                if (!mine && sender?.dm == true && inRoom) DropdownMenuItem(text = { Text("Message ${sender.nick} directly", style = Type.small) }, onClick = { menu = false; onDm(sender.id) })
            }
        }
        Row(Modifier.padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(clock.format(Date(item.time)), style = Type.label, color = p.faint)
            val own = l.own
            if (mine && own != null && !item.old) {
                val tick = room.ticks[own]
                Spacer(Modifier.width(4.dp))
                Icon(
                    when (tick) { Tick.DELIVERED -> Icons.Checks; Tick.MISSED -> Icons.Warn; else -> Icons.Check },
                    when (tick) { Tick.DELIVERED -> "Everyone has it"; Tick.MISSED -> "Someone didn't get it"; else -> "Sent" },
                    tint = when (tick) { Tick.DELIVERED -> p.accent; Tick.MISSED -> p.warn; else -> p.faint },
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

@Composable
private fun FileCard(room: Room, num: Int, mine: Boolean) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val f: FileItem? = room.files[num]
    val pic = room.pictures[num]
    val bitmap = pic?.bitmap
    if (bitmap != null) {
        val image = remember(bitmap) { bitmap.asImageBitmap() }
        // As wide as a bubble allows, and no taller than a screenful's half.
        val ratio = bitmap.width.toFloat() / bitmap.height
        val h = minOf(PICTURE_W / ratio, PICTURE_H_MAX)
        Image(
            image, f?.name, contentScale = ContentScale.Fit,
            modifier = Modifier.padding(bottom = 6.dp).size((h * ratio).dp, h.dp).clip(RoundedCornerShape(8.dp)),
        )
    }
    if (f == null) {
        Text("file $num, no longer offered", style = Type.small, color = p.faint)
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(if (f.image) Icons.Image else Icons.File, null, tint = p.muted, modifier = Modifier.size(20.dp))
        Column(Modifier.padding(start = 10.dp).widthIn(max = 240.dp)) {
            Text(f.name, style = Type.small.copy(fontWeight = FontWeight.Medium), color = p.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val state = when {
                mine && f.sendingCount > 0 -> "sending to ${f.sendingName}${if (f.sendingCount > 1) " and ${f.sendingCount - 1} more" else ""} · ${(f.permille ?: 0) / 10}%"
                mine && f.sentTo != null -> "sent to ${f.sentTo}"
                mine -> "offered"
                f.state == "fetching" -> when (f.waiting) {
                    "verify" -> "waiting until verify codes are compared"
                    "sender" -> "waiting for its sender"
                    else -> "${sizeText(f.got ?: 0)} of ${sizeText(f.size)}" + (f.eta?.let { " · ${it}s left" } ?: "")
                }
                f.state == "queued" -> "waiting for file ${f.after ?: ""}"
                f.state == "failed" -> "failed" + (f.why?.let { ": $it" } ?: "")
                f.saved != null -> "saved"
                else -> null
            }
            Text(listOfNotNull(sizeText(f.size), state).joinToString(" · "), style = Type.label, color = if (f.state == "failed") p.bad else p.muted)
            pic?.error?.let { Text("can't show it: $it", style = Type.label, color = p.bad) }
        }
    }
    if (f.state == "fetching" && f.got != null && f.size > 0) {
        LinearProgressIndicator(
            progress = { (f.got.toFloat() / f.size).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(3.dp),
            color = p.accent, trackColor = p.line,
        )
    }
    val over = f.size > Hub.settings.fileLimitMb * 1024L * 1024L
    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        @Composable
        fun act(label: String, onClick: () -> Unit) = Text(label, style = Type.small.copy(fontWeight = FontWeight.SemiBold), color = p.accent,
            modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 4.dp))
        when {
            mine -> act("Withdraw") { room.cancel(num) }
            f.state == "fetching" || f.state == "queued" -> act("Stop") { room.cancel(num) }
            else -> {
                if (f.image && bitmap == null) act(if (over) "Show anyway" else "Show") { room.show(num, over) }
                if (f.saved == null) act(if (over) "Save anyway" else "Save") { room.save(num, over) }
                else act("Open") { openReceived(ctx, f.saved) }
            }
        }
    }
}
