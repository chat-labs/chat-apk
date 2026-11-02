package moe.vin.chat.ui

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import moe.vin.chat.store.TrustedKey

private class RoomRow(val id: String, val title: String, val room: Room?, val entry: RoomEntry?)

@Composable
fun RoomsScreen(nav: Nav, twoPane: Boolean = false) {
    val p = LocalPalette.current
    // Beside the open room, a room or a page replaces it rather than going on top.
    fun show(s: Screen) = if (twoPane) nav.home(s) else nav.go(s)
    val selected = when (val t = nav.top) {
        is Screen.Chat -> t.room
        is Screen.Peers -> t.room
        is Screen.PeerInfo -> t.room
        is Screen.Files -> t.room
        is Screen.PictureView -> t.room
        is Screen.Invite -> t.room
        is Screen.RoomSettings -> t.room
        else -> null
    }
    val tor = Hub.tor.state
    val subtitle = when (tor.phase) {
        Tor.Phase.STARTING -> "tor ${tor.progress}% · ${tor.message.orEmpty()}"
        Tor.Phase.READY -> "tor connected"
        Tor.Phase.FAILED -> "tor failed: ${tor.message.orEmpty()}"
        Tor.Phase.OFF -> null
    }
    val rows = buildList {
        for (e in Hub.kept) {
            val r = Hub.rooms[e.id]
            add(RoomRow(e.id, r?.title?.takeIf { it.isNotBlank() } ?: e.title, r, e))
        }
        for (r in Hub.rooms.values) {
            if (Hub.kept.none { it.id == r.id }) {
                add(RoomRow(r.id, r.title.ifBlank { "new room" }, r, null))
            }
        }
    }

    var calling by remember { mutableStateOf(false) }
    var panicAsk by remember { mutableStateOf(false) }
    var forgetting by remember { mutableStateOf<RoomEntry?>(null) }
    var renaming by remember { mutableStateOf<RoomRow?>(null) }

    Page("chat", subtitle, actions = {
        if (Hub.settings.panicButton) {
            IconAction(Icons.Bolt, "Panic", { panicAsk = true }, tint = p.bad)
        }
        if (Hub.verifiedKeys.isNotEmpty()) {
            IconAction(Icons.Phone, "Call verified peer", { calling = true })
        }
        IconAction(Icons.Lock, "Lock screen", { Hub.lockUi() })
        IconAction(Icons.Sliders, "Settings", { show(Screen.Settings) })
    }) {
        if (Shared.file != null || Shared.text != null) {
            Row(
                Modifier.fillMaxWidth().background(p.surface).padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Share, null, tint = p.accent, modifier = Modifier.size(18.dp))
                Text(
                    if (Shared.file != null) "Pick a room to send the file to" else "Pick a room for the text",
                    style = Type.small,
                    color = p.text,
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                )
                Text("Cancel", style = Type.small, color = p.muted, modifier = Modifier.clickable { Shared.clear() })
            }
            Hairline()
        }
        if (rows.isEmpty()) {
            Column(
                Modifier.weight(1f).fillMaxWidth().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("No rooms yet", style = Type.body.copy(fontWeight = FontWeight.SemiBold), color = p.text)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Make one and send its invite over a channel you trust, join one with an invite, or call someone whose signing key you've verified.",
                    style = Type.small,
                    color = p.muted,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(rows, key = { it.id }) { row ->
                    RoomItem(
                        row = row,
                        selected = twoPane && row.id == selected,
                        onClick = {
                            val entry = row.entry
                            if (row.room == null && entry != null) Hub.open(entry)
                            show(Screen.Chat(row.id))
                        },
                        onInvite = { show(Screen.Invite(row.id)) },
                        onRename = { renaming = row },
                        onForget = { row.entry?.let { forgetting = it } },
                    )
                    Hairline()
                }
            }
        }
        BottomBar {
            Button("New room", { show(Screen.New) }, Modifier.weight(1f), icon = Icons.Plus)
            Button("Join", { show(Screen.Join()) }, Modifier.weight(1f), primary = false, icon = Icons.Join)
        }
    }

    if (calling) {
        CallDialog(
            onDismiss = { calling = false },
            onOpenSettings = { calling = false; show(Screen.Settings) },
            onStarted = { room -> calling = false; show(Screen.Chat(room.id)) },
        )
    }

    if (panicAsk) {
        Confirm(
            title = if (Hub.settings.panicWipe) "Panic and erase everything?" else "Panic shutoff?",
            text = if (Hub.settings.panicWipe)
                "Every room and Tor process is killed on the spot without network goodbyes, and the vault, histories, keys and saved files are erased."
            else
                "Every room and Tor process is killed on the spot without network goodbyes, notifications and clipboard are cleared, and the vault is sealed.",
            action = if (Hub.settings.panicWipe) "Kill & erase" else "Shut off now",
            danger = true,
            onConfirm = { panicAsk = false; Hub.panic() },
            onDismiss = { panicAsk = false },
        )
    }

    forgetting?.let { entry ->
        Confirm(
            title = "Forget ${entry.title}?",
            text = "You leave it, and its history and the files you saved from it are deleted.",
            action = "Forget",
            danger = true,
            onConfirm = {
                val id = entry.id
                forgetting = null
                Hub.forget(entry)
                if (selected == id) nav.home()
            },
            onDismiss = { forgetting = null },
        )
    }

    renaming?.let { row ->
        RenameDialog(
            initial = row.room?.label ?: row.entry?.label.orEmpty(),
            onSave = { next ->
                val r = row.room
                val e = row.entry
                if (r != null) {
                    Hub.rename(r, next)
                } else if (e != null) {
                    val idx = Hub.kept.indexOfFirst { it.id == e.id }
                    if (idx >= 0) {
                        Hub.kept[idx] = Hub.kept[idx].copy(label = next)
                        Hub.updateSettings(Hub.settings)
                    }
                }
                renaming = null
            },
            onDismiss = { renaming = null },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RoomItem(
    row: RoomRow,
    selected: Boolean,
    onClick: () -> Unit,
    onInvite: () -> Unit,
    onRename: () -> Unit,
    onForget: () -> Unit,
) {
    val p = LocalPalette.current
    val room = row.room
    val entry = row.entry
    val route = room?.info?.route ?: entry?.route ?: "udp"
    val isCall = room?.isCall == true
    val status = if (room == null) "kept · offline" else roomState(room)
    val transferring = room?.files?.values?.any { it.state == "fetching" || it.sendingCount > 0 } == true
    var menu by remember { mutableStateOf(false) }

    Box {
        Row(
            Modifier.fillMaxWidth()
                .background(if (selected) p.raised else p.bg)
                .combinedClickable(onClick = onClick, onLongClick = { menu = true })
                .padding(horizontal = 20.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(11.dp)).background(p.surface)
                    .border(1.dp, p.line, RoundedCornerShape(11.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    when {
                        isCall -> Icons.Phone
                        route == "tor" -> Icons.Onion
                        else -> Icons.People
                    },
                    null,
                    tint = when {
                        room?.status == Status.FAILED -> p.bad
                        room?.status == Status.OPEN -> p.accent
                        else -> p.faint
                    },
                    modifier = Modifier.size(19.dp),
                )
                if ((room?.net?.online ?: 0) > 0) {
                    Box(
                        Modifier.align(Alignment.BottomEnd).padding(3.dp)
                            .size(7.dp).clip(CircleShape).background(p.good),
                    )
                }
            }
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(
                    row.title,
                    style = Type.body.copy(fontWeight = FontWeight.Medium),
                    color = p.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val preview = room?.lastPreview
                if (preview != null) {
                    Text(
                        preview,
                        style = Type.small,
                        color = p.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val meta = listOfNotNull(
                    status,
                    if (isCall) "call" else if (route == "tor") "tor" else "direct",
                    if (transferring) "transferring" else null,
                    room?.timer?.takeIf { it.seconds > 0 }?.let { durationText(it.seconds) },
                    if (entry?.history == true || room?.history == true) "history" else null,
                ).joinToString(" · ")
                Text(
                    meta,
                    style = Type.label,
                    color = if (room?.status == Status.FAILED) p.bad else p.faint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val unread = room?.unread ?: 0
            if (unread > 0) {
                val hasMention = (room?.mentions ?: 0) > 0
                Box(
                    Modifier.widthIn(min = 22.dp).height(22.dp).clip(CircleShape)
                        .background(if (hasMention) p.accent else p.raised)
                        .padding(horizontal = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (unread > 99) "99+" else "$unread",
                        style = Type.label,
                        color = if (hasMention) p.onAccent else p.text,
                    )
                }
            }
        }

        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = p.raised) {
            if (room == null && entry != null) {
                DropdownMenuItem(text = { Text("Open", style = Type.small) }, onClick = { menu = false; Hub.open(entry) })
            }
            if (room?.info != null && !isCall) {
                DropdownMenuItem(text = { Text("Invite", style = Type.small) }, onClick = { menu = false; onInvite() })
            }
            if (!isCall) {
                DropdownMenuItem(text = { Text("Rename", style = Type.small) }, onClick = { menu = false; onRename() })
            }
            if (room != null) {
                DropdownMenuItem(
                    text = { Text(if (entry != null) "Go offline" else "Leave", style = Type.small) },
                    onClick = { menu = false; Hub.close(room) },
                )
            }
            if (entry != null) {
                DropdownMenuItem(
                    text = { Text("Forget", style = Type.small, color = p.bad) },
                    onClick = { menu = false; onForget() },
                )
            }
        }
    }
}

@Composable
private fun RenameDialog(initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    val p = LocalPalette.current
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = p.surface,
        title = { Text("Name for this room", style = Type.title.copy(fontSize = Type.body.fontSize * 1.15f), color = p.text) },
        text = {
            Field("Only shown on this phone") {
                PrivateField(name, { name = it }, Modifier.fillMaxWidth(), placeholder = "blank for room id", focus = true, onDone = { onSave(name.trim()) })
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name.trim()) }) {
                Text("Save", color = p.accent, style = Type.small.copy(fontWeight = FontWeight.SemiBold))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = p.muted, style = Type.small) }
        },
    )
}

@Composable
private fun CallDialog(
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit,
    onStarted: (Room) -> Unit,
) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    var route by remember { mutableStateOf(Hub.settings.route) }
    val hasKey = Hub.identity != null

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = p.surface,
        title = { Text("Call a verified peer", style = Type.title.copy(fontSize = Type.body.fontSize * 1.15f), color = p.text) },
        text = {
            if (!hasKey) {
                Text(
                    "Calling works out a shared daily room from your signing key and theirs, with no invite needed. Make or import a signing key in Settings first.",
                    style = Type.small,
                    color = p.muted,
                )
            } else {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Opens today's shared room with them (:call @nick). When they do the same, you meet there automatically.",
                        style = Type.small,
                        color = p.muted,
                    )
                    Choice(listOf("udp" to "Direct", "tor" to "Tor"), route, { route = it })
                    for (peer in Hub.verifiedKeys) {
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(p.bg)
                                .border(1.dp, p.line, RoundedCornerShape(10.dp))
                                .clickable {
                                    val r = Hub.call(peer, route)
                                    if (r != null) onStarted(r)
                                    else Toast.makeText(ctx, "Couldn't start call", Toast.LENGTH_SHORT).show()
                                }
                                .padding(horizontal = 14.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Phone, null, tint = p.accent, modifier = Modifier.size(18.dp))
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text("@${peer.nick}", style = Type.body.copy(fontWeight = FontWeight.Medium), color = p.text)
                                Text("${peer.source} · ${peer.fingerprint.ifBlank { peer.pub.take(16) }}", style = Type.label, color = p.faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (!hasKey) {
                TextButton(onClick = onOpenSettings) {
                    Text("Settings", color = p.accent, style = Type.small.copy(fontWeight = FontWeight.SemiBold))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close", color = p.muted, style = Type.small) }
        },
    )
}

@Composable
private fun RoomOptions(
    route: String,
    onRoute: (String) -> Unit,
    keep: Boolean,
    onKeep: (Boolean) -> Unit,
    history: Boolean,
    onHistory: (Boolean) -> Unit,
    port: String,
    onPort: (String) -> Unit,
    peers: String,
    onPeers: (String) -> Unit,
) {
    val p = LocalPalette.current
    var showAdvanced by remember { mutableStateOf(port.isNotBlank() || peers.isNotBlank()) }
    Text("HOW IT REACHES PEERS", style = Type.section, color = p.faint, modifier = Modifier.padding(horizontal = 20.dp))
    Spacer(Modifier.height(10.dp))
    Choice(listOf("udp" to "Direct", "tor" to "Tor"), route, onRoute, Modifier.padding(horizontal = 20.dp))
    Spacer(Modifier.height(8.dp))
    Text(
        if (route == "udp") "Peers see your IP address." else "Onion services hide your IP address; connecting takes longer.",
        style = Type.small,
        color = p.muted,
        modifier = Modifier.padding(horizontal = 20.dp),
    )
    Spacer(Modifier.height(10.dp))
    Toggle(
        "Keep this room",
        "In the vault, to come back to. Peers are told you went offline when you close it, not that you left.",
        keep,
        onKeep,
    )
    if (keep) {
        Toggle("Keep its history", "Sealed in the vault. Everyone in the room is told you keep one.", history, onHistory)
    }
    if (route == "udp") {
        Text(
            if (showAdvanced) "Hide direct UDP options" else "Direct UDP port & known peers…",
            style = Type.small,
            color = p.accent,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp).clickable { showAdvanced = !showAdvanced },
        )
        if (showAdvanced) {
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Field("Local UDP port (optional)") {
                    PrivateField(port, onPort, Modifier.fillMaxWidth(), placeholder = "blank: random port", mono = true)
                }
                Field("Known peer addresses (optional)") {
                    PrivateField(peers, onPeers, Modifier.fillMaxWidth(), placeholder = "host:port, one per line", multiLine = true, mono = true)
                }
            }
        }
    }
}

@Composable
fun NewRoomScreen(nav: Nav) {
    val sidebar = LocalSidebar.current
    val showBack = !(sidebar?.listVisible == true && nav.stack.size <= 2)
    var label by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var route by remember { mutableStateOf(Hub.settings.route) }
    var keep by remember { mutableStateOf(true) }
    var history by remember { mutableStateOf(false) }
    var port by remember { mutableStateOf("") }
    var peers by remember { mutableStateOf("") }
    Page("New room", onBack = if (showBack) nav::back else null) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).imePadding()) {
            Spacer(Modifier.height(18.dp))
            Field("Name, for you only", Modifier.padding(horizontal = 20.dp)) {
                PrivateField(label, { label = it }, Modifier.fillMaxWidth(), placeholder = "nobody else sees it")
            }
            Spacer(Modifier.height(14.dp))
            Field("Password (optional)", Modifier.padding(horizontal = 20.dp)) {
                PrivateField(password, { password = it }, Modifier.fillMaxWidth(), placeholder = "blank: random words in the invite")
            }
            Spacer(Modifier.height(22.dp))
            RoomOptions(
                route = route,
                onRoute = { route = it },
                keep = keep,
                onKeep = { keep = it },
                history = history,
                onHistory = { history = it },
                port = port,
                onPort = { port = it.filter(Char::isDigit) },
                peers = peers,
                onPeers = { peers = it },
            )
            Spacer(Modifier.height(14.dp))
            Text(
                "chat makes up the room's id and a password in words unless you set your own. Its invite holds both: send it over a channel you trust, as anyone who has it can join.",
                style = Type.small,
                color = LocalPalette.current.muted,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Spacer(Modifier.height(16.dp))
        }
        BottomBar {
            Button("Make the room", {
                val portNum = port.trim().toIntOrNull()?.takeIf { it in 1..65535 } ?: 0
                val room = Hub.start(
                    room = null,
                    password = password.trim().ifEmpty { null },
                    invite = null,
                    label = label.trim(),
                    route = route,
                    history = keep && history,
                    keep = keep,
                    port = portNum,
                    peers = peers.trim(),
                )
                nav.replace(Screen.Invite(room.id))
            }, Modifier.fillMaxWidth())
        }
    }
}

@Composable
fun JoinScreen(nav: Nav, start: String) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val sidebar = LocalSidebar.current
    val showBack = !(sidebar?.listVisible == true && nav.stack.size <= 2)
    var invite by remember { mutableStateOf(start) }
    var byId by remember { mutableStateOf(false) }
    var id by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    var route by remember { mutableStateOf(Hub.settings.route) }
    var keep by remember { mutableStateOf(true) }
    var history by remember { mutableStateOf(false) }
    var port by remember { mutableStateOf("") }
    var peers by remember { mutableStateOf("") }
    val trimmedInvite = invite.trim()
    val ok = if (byId) id.isNotBlank() else trimmedInvite.startsWith("chat:")
    Page("Join a room", onBack = if (showBack) nav::back else null) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).imePadding()) {
            Spacer(Modifier.height(18.dp))
            if (!byId) {
                Field(
                    "Invite",
                    Modifier.padding(horizontal = 20.dp),
                    trailing = {
                        Text(
                            "Paste",
                            style = Type.label,
                            color = p.accent,
                            modifier = Modifier.clickable {
                                val clip = readClipboard(ctx)
                                if (clip != null) {
                                    val found = Regex("chat:[^\\n]+").find(clip)?.value?.trim() ?: clip.trim()
                                    invite = found
                                } else {
                                    Toast.makeText(ctx, "Clipboard is empty", Toast.LENGTH_SHORT).show()
                                }
                            },
                        )
                    },
                ) {
                    PrivateField(invite, { invite = it }, Modifier.fillMaxWidth(), placeholder = "chat:…", mono = true)
                }
                Text(
                    "Use the room's id and password instead",
                    style = Type.small,
                    color = p.accent,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp).clickable { byId = true },
                )
            } else {
                Field("Room id", Modifier.padding(horizontal = 20.dp)) {
                    PrivateField(id, { next ->
                        if (next.trim().startsWith("chat:")) {
                            invite = next.trim()
                            byId = false
                        } else {
                            id = next
                        }
                    }, Modifier.fillMaxWidth(), mono = true)
                }
                Spacer(Modifier.height(12.dp))
                Field("Password", Modifier.padding(horizontal = 20.dp)) {
                    PrivateField(password, { password = it }, Modifier.fillMaxWidth(), placeholder = "blank for none")
                }
                Text(
                    "Use an invite instead",
                    style = Type.small,
                    color = p.accent,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp).clickable { byId = false },
                )
            }
            Spacer(Modifier.height(10.dp))
            Field("Name, for you only", Modifier.padding(horizontal = 20.dp)) {
                PrivateField(label, { label = it }, Modifier.fillMaxWidth(), placeholder = "nobody else sees it")
            }
            Spacer(Modifier.height(22.dp))
            RoomOptions(
                route = route,
                onRoute = { route = it },
                keep = keep,
                onKeep = { keep = it },
                history = history,
                onHistory = { history = it },
                port = port,
                onPort = { port = it.filter(Char::isDigit) },
                peers = peers,
                onPeers = { peers = it },
            )
            Spacer(Modifier.height(16.dp))
        }
        BottomBar {
            Button("Join", {
                val portNum = port.trim().toIntOrNull()?.takeIf { it in 1..65535 } ?: 0
                val room = if (byId) {
                    Hub.start(id.trim(), password, null, label.trim(), route, keep && history, keep, portNum, peers.trim())
                } else {
                    Hub.start(null, null, trimmedInvite, label.trim(), route, keep && history, keep, portNum, peers.trim())
                }
                nav.replace(Screen.Chat(room.id))
            }, Modifier.fillMaxWidth(), enabled = ok)
        }
    }
}

@Composable
fun InviteScreen(nav: Nav, roomId: String) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val room = Hub.rooms[roomId]
    val info = room?.info
    Page("Invite", room?.title?.takeIf { it.isNotBlank() }, onBack = nav::back) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp)) {
            if (info == null) {
                Text(
                    if (room?.status == Status.FAILED) "The room didn't open: ${room.error}"
                    else "The invite shows once the room is open…",
                    style = Type.body,
                    color = p.muted,
                )
                return@Column
            }
            if (info.qr.isNotEmpty()) {
                QrCode(
                    info.qr,
                    Modifier.sizeIn(maxWidth = 220.dp, maxHeight = 220.dp).fillMaxWidth().align(Alignment.CenterHorizontally),
                )
                Spacer(Modifier.height(18.dp))
            }
            val invite = info.invite.orEmpty()
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(p.surface)
                    .border(1.dp, p.line, RoundedCornerShape(10.dp)).padding(14.dp),
            ) {
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
                style = Type.small,
                color = p.muted,
            )
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("ROOM", style = Type.section, color = p.faint)
                Text("Copy", style = Type.label, color = p.accent, modifier = Modifier.clickable { copySecret(ctx, "room id", info.room) })
            }
            Text(info.room, style = Type.code, color = p.text)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("PASSWORD", style = Type.section, color = p.faint)
                if (info.password.isNotEmpty()) {
                    Text("Copy", style = Type.label, color = p.accent, modifier = Modifier.clickable { copySecret(ctx, "room password", info.password) })
                }
            }
            Text(info.password.ifEmpty { "(none)" }, style = Type.code, color = p.text)
        }
        BottomBar {
            Button("Go to the room", { nav.replace(Screen.Chat(roomId)) }, Modifier.fillMaxWidth())
        }
    }
}
