package moe.vin.chat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import moe.vin.chat.Hub
import moe.vin.chat.engine.Room
import moe.vin.chat.engine.Status
import moe.vin.chat.engine.Tor
import moe.vin.chat.store.RoomEntry

private class RoomRow(val id: String, val title: String, val room: Room?, val entry: RoomEntry?)

@Composable
fun RoomsScreen(nav: Nav) {
    val p = LocalPalette.current
    val tor = Hub.tor.state
    val subtitle = when (tor.phase) {
        Tor.Phase.STARTING -> "tor ${tor.progress}% · ${tor.message.orEmpty()}"
        Tor.Phase.READY -> "tor connected"
        Tor.Phase.FAILED -> "tor failed: ${tor.message.orEmpty()}"
        Tor.Phase.OFF -> null
    }
    val rows = buildList {
        for (e in Hub.kept) add(RoomRow(e.id, Hub.rooms[e.id]?.title?.takeIf { it.isNotBlank() } ?: e.title, Hub.rooms[e.id], e))
        for (r in Hub.rooms.values) if (Hub.kept.none { it.id == r.id }) add(RoomRow(r.id, r.title.ifBlank { "new room" }, r, null))
    }
    Page("chat", subtitle, actions = { IconAction(Icons.Sliders, "Settings", { nav.go(Screen.Settings) }) }) {
        if (Shared.file != null || Shared.text != null) {
            Row(
                Modifier.fillMaxWidth().background(p.surface).padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Share, null, tint = p.accent, modifier = Modifier.size(18.dp))
                Text(
                    if (Shared.file != null) "Pick a room to send the file to" else "Pick a room for the text",
                    style = Type.small, color = p.text, modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                )
                Text("Cancel", style = Type.small, color = p.muted, modifier = Modifier.clickable { Shared.clear() })
            }
            Hairline()
        }
        if (rows.isEmpty()) {
            Column(Modifier.weight(1f).fillMaxWidth().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("No rooms yet", style = Type.body.copy(fontWeight = FontWeight.SemiBold), color = p.text)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Make one and send its invite to someone, over a channel you trust, or join one with an invite you were sent.",
                    style = Type.small, color = p.muted, textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(rows, key = { it.id }) { row ->
                    RoomItem(row) {
                        val entry = row.entry
                        if (row.room == null && entry != null) Hub.open(entry)
                        nav.go(Screen.Chat(row.id))
                    }
                    Hairline()
                }
            }
        }
        Hairline()
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button("New room", { nav.go(Screen.New) }, Modifier.weight(1f), icon = Icons.Plus)
            Button("Join", { nav.go(Screen.Join()) }, Modifier.weight(1f), primary = false, icon = Icons.Join)
        }
    }
}

@Composable
private fun RoomItem(row: RoomRow, onClick: () -> Unit) {
    val p = LocalPalette.current
    val room = row.room
    val route = room?.info?.route ?: row.entry?.route ?: "udp"
    val status = if (room == null) "kept · not open" else roomState(room)
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(p.raised),
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (route == "tor") Icons.Onion else Icons.People, null, tint = if (room?.status == Status.OPEN) p.accent else p.faint, modifier = Modifier.size(19.dp))
        }
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(row.title, style = Type.body.copy(fontWeight = FontWeight.Medium), color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(status, if (route == "tor") "tor" else "direct", if (row.entry?.history == true) "history" else null).joinToString(" · "),
                style = Type.label, color = if (room?.status == Status.FAILED) p.bad else p.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        val unread = room?.unread ?: 0
        if (unread > 0) {
            Box(
                Modifier.widthIn(min = 22.dp).height(22.dp).clip(CircleShape).background(if ((room?.mentions ?: 0) > 0) p.accent else p.raised)
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(if (unread > 99) "99+" else "$unread", style = Type.label, color = if ((room?.mentions ?: 0) > 0) p.onAccent else p.text)
            }
        }
    }
}

@Composable
private fun RoomOptions(
    route: String,
    onRoute: (String) -> Unit,
    keep: Boolean,
    onKeep: (Boolean) -> Unit,
    history: Boolean,
    onHistory: (Boolean) -> Unit,
) {
    val p = LocalPalette.current
    Text("HOW IT REACHES PEERS", style = Type.section, color = p.faint, modifier = Modifier.padding(horizontal = 20.dp))
    Spacer(Modifier.height(10.dp))
    Choice(listOf("udp" to "Direct", "tor" to "Tor"), route, onRoute, Modifier.padding(horizontal = 20.dp))
    Spacer(Modifier.height(8.dp))
    Text(
        if (route == "udp") "Peers see your IP address." else "Onion services hide your IP address; connecting takes longer.",
        style = Type.small, color = p.muted, modifier = Modifier.padding(horizontal = 20.dp),
    )
    Spacer(Modifier.height(10.dp))
    Toggle("Keep this room", "In the vault, to come back to. Peers are told you went offline when you close it, not that you left.", keep, onKeep)
    if (keep) Toggle("Keep its history", "Sealed in the vault. Everyone in the room is told you keep one.", history, onHistory)
}

@Composable
fun NewRoomScreen(nav: Nav) {
    var label by remember { mutableStateOf("") }
    var route by remember { mutableStateOf(Hub.settings.route) }
    var keep by remember { mutableStateOf(true) }
    var history by remember { mutableStateOf(false) }
    Page("New room", onBack = nav::back) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).imePadding()) {
            Spacer(Modifier.height(18.dp))
            Field("Name, for you only", Modifier.padding(horizontal = 20.dp)) {
                PrivateField(label, { label = it }, Modifier.fillMaxWidth(), placeholder = "nobody else sees it")
            }
            Spacer(Modifier.height(22.dp))
            RoomOptions(route, { route = it }, keep, { keep = it }, history, { history = it })
            Spacer(Modifier.height(14.dp))
            Text(
                "chat makes up the room's id and a password in words. Its invite holds both: send it over a channel you trust, as anyone who has it can join.",
                style = Type.small, color = LocalPalette.current.muted, modifier = Modifier.padding(horizontal = 20.dp),
            )
        }
        Button("Make the room", {
            val room = Hub.start(null, null, null, label.trim(), route, keep && history, keep)
            nav.replace(Screen.Invite(room.id))
        }, Modifier.fillMaxWidth().padding(16.dp))
    }
}

@Composable
fun JoinScreen(nav: Nav, start: String) {
    val p = LocalPalette.current
    var invite by remember { mutableStateOf(start) }
    var byId by remember { mutableStateOf(false) }
    var id by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    var route by remember { mutableStateOf(Hub.settings.route) }
    var keep by remember { mutableStateOf(true) }
    var history by remember { mutableStateOf(false) }
    val ok = if (byId) id.isNotBlank() else invite.trim().startsWith("chat:")
    Page("Join a room", onBack = nav::back) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).imePadding()) {
            Spacer(Modifier.height(18.dp))
            if (!byId) {
                Field("Invite", Modifier.padding(horizontal = 20.dp)) {
                    PrivateField(invite, { invite = it }, Modifier.fillMaxWidth(), placeholder = "chat:…", mono = true)
                }
                Text(
                    "Use the room's id and password instead", style = Type.small, color = p.accent,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp).clickable { byId = true },
                )
            } else {
                Field("Room id", Modifier.padding(horizontal = 20.dp)) { PrivateField(id, { id = it }, Modifier.fillMaxWidth(), mono = true) }
                Spacer(Modifier.height(12.dp))
                Field("Password", Modifier.padding(horizontal = 20.dp)) { PrivateField(password, { password = it }, Modifier.fillMaxWidth(), placeholder = "blank for none") }
                Text(
                    "Use an invite instead", style = Type.small, color = p.accent,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp).clickable { byId = false },
                )
            }
            Spacer(Modifier.height(10.dp))
            Field("Name, for you only", Modifier.padding(horizontal = 20.dp)) {
                PrivateField(label, { label = it }, Modifier.fillMaxWidth(), placeholder = "nobody else sees it")
            }
            Spacer(Modifier.height(22.dp))
            RoomOptions(route, { route = it }, keep, { keep = it }, history, { history = it })
        }
        Button("Join", {
            val room = if (byId) Hub.start(id.trim(), password, null, label.trim(), route, keep && history, keep)
            else Hub.start(null, null, invite.trim(), label.trim(), route, keep && history, keep)
            nav.replace(Screen.Chat(room.id))
        }, Modifier.fillMaxWidth().padding(16.dp), enabled = ok)
    }
}

@Composable
fun InviteScreen(nav: Nav, roomId: String) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val room = Hub.rooms[roomId]
    val info = room?.info
    Page("Invite", room?.title?.takeIf { it.isNotBlank() }, onBack = nav::back) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp)) {
            if (info == null) {
                Text(if (room?.status == Status.FAILED) "The room didn't open: ${room.error}" else "The invite shows once the room is open…", style = Type.body, color = p.muted)
                return@Column
            }
            if (info.qr.isNotEmpty()) {
                QrCode(info.qr, Modifier.fillMaxWidth().padding(horizontal = 24.dp))
                Spacer(Modifier.height(18.dp))
            }
            val invite = info.invite.orEmpty()
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(p.surface).border(1.dp, p.line, RoundedCornerShape(10.dp)).padding(14.dp)) {
                Text(invite, style = Type.code, color = p.text)
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button("Copy", { copySecret(ctx, "chat invite", invite) }, Modifier.weight(1f), primary = false, icon = Icons.Copy)
                Button("Share", { shareText(ctx, invite) }, Modifier.weight(1f), primary = false, icon = Icons.Share)
            }
            Spacer(Modifier.height(18.dp))
            Text(
                "It holds the room's id and password, so anyone who has it can join. Send it over a channel you trust, then compare verify codes with whoever joins.",
                style = Type.small, color = p.muted,
            )
            Spacer(Modifier.height(18.dp))
            Text("ROOM", style = Type.section, color = p.faint)
            Text(info.room, style = Type.code, color = p.text)
            Spacer(Modifier.height(8.dp))
            Text("PASSWORD", style = Type.section, color = p.faint)
            Text(info.password.ifEmpty { "(none)" }, style = Type.code, color = p.text)
        }
        Button("Go to the room", { nav.replace(Screen.Chat(roomId)) }, Modifier.fillMaxWidth().padding(16.dp))
    }
}
