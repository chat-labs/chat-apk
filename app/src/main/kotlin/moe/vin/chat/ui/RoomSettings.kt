package moe.vin.chat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import moe.vin.chat.Hub

private val TIMERS = listOf(
    "0" to "Off",
    "300" to "5m",
    "600" to "10m",
    "3600" to "1h",
    "86400" to "1d",
    "604800" to "1w",
)

@Composable
fun RoomSettingsScreen(nav: Nav, roomId: String) {
    val room = Hub.rooms[roomId]
    if (room == null) {
        LaunchedEffect(Unit) { nav.back() }
        return
    }
    val p = LocalPalette.current
    val entry = Hub.kept.firstOrNull { it.id == roomId }
    var label by remember { mutableStateOf(room.label) }
    var forgetting by remember { mutableStateOf(false) }
    var clearingHistory by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }
    var showLinks by remember { mutableStateOf(false) }
    var pendingLink by remember { mutableStateOf<Pair<String, String?>?>(null) }
    var portDialog by remember { mutableStateOf(false) }
    val info = room.info
    val net = room.net

    val roomLinks = remember(room.items.size) {
        room.items.asReversed()
            .flatMap { item -> item.links.map { span -> span.url to (if (item.line.mine) "you" else item.line.nick) } }
            .distinctBy { it.first }
    }

    Page(
        "Room",
        room.title.ifBlank { null },
        onBack = {
            if (!room.isCall && label != room.label) Hub.rename(room, label.trim())
            nav.back()
        },
    ) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).imePadding()) {
            if (!room.isCall) {
                Spacer(Modifier.height(18.dp))
                Field("Name, for you only", Modifier.padding(horizontal = 20.dp)) {
                    PrivateField(
                        label,
                        { label = it },
                        Modifier.fillMaxWidth(),
                        placeholder = info?.room ?: "",
                        onDone = { Hub.rename(room, label.trim()) },
                    )
                }

                Section("Kept")
                if (entry == null) {
                    Toggle("Keep this room", "In the vault, to come back to", false) { Hub.keepRoom(room, history = false) }
                } else {
                    Toggle("Keep its history", "Sealed in the vault. Everyone here is told you keep one.", room.history) { Hub.setHistory(room, it) }
                    if (room.history || room.items.any { it.old }) {
                        RowItem(
                            "Clear kept history",
                            "Deletes saved messages for this room while keeping the room itself",
                            onClick = { clearingHistory = true },
                            leading = Icons.Trash,
                        )
                    }
                    RowItem(
                        "Forget this room",
                        "Leaves it, and deletes its history and files",
                        onClick = { forgetting = true },
                        leading = Icons.Trash,
                        titleColor = p.bad,
                    )
                }
            }

            Section("Chat")
            Toggle(
                "Follow new messages",
                "Stay at the bottom as messages arrive until you scroll up",
                Hub.settings.followChat,
            ) { Hub.updateSettings(Hub.settings.copy(followChat = it)) }
            RowItem(
                "Files in this room",
                if (room.files.isEmpty()) "None offered yet" else "${room.files.size} offered · send, view or save",
                onClick = { nav.go(Screen.Files(roomId)) },
                leading = Icons.Folder,
            )
            RowItem(
                "Links in this room",
                if (roomLinks.isEmpty()) "No links shared yet" else "${roomLinks.size} shared",
                onClick = if (roomLinks.isNotEmpty()) ({ showLinks = true }) else null,
                leading = Icons.Link,
            )

            Section("Message timer")
            Column(Modifier.padding(horizontal = 20.dp)) {
                val t = room.timer
                val curSec = (t?.seconds ?: 0).toString()
                val options = if (TIMERS.any { it.first == curSec }) TIMERS else TIMERS + (curSec to durationText(t?.seconds ?: 0))
                Choice(options, curSec, { room.setTimer(it.toInt()) })
                Spacer(Modifier.height(8.dp))
                Text(
                    if (t != null && t.seconds > 0)
                        "Messages go from every screen that keeps to it after ${durationText(t.seconds)}; ${t.who ?: "someone"} asked for it. It's a request: a screenshot or a modified chat can keep anything."
                    else
                        "Messages here stay until the room closes, or for as long as a history keeps them.",
                    style = Type.small,
                    color = p.muted,
                )
            }

            if (!room.isCall) {
                Section("Invite")
                RowItem(
                    "Show the invite",
                    "Its QR code and text, to send to someone",
                    onClick = { nav.go(Screen.Invite(roomId)) },
                    leading = Icons.Qr,
                )
            }

            Section("Network")
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val lines = buildList {
                    add("room " + (info?.room ?: "…"))
                    add("route " + (if (info?.route == "tor") "tor onion services" else "direct udp" + (info?.port?.let { ", port $it" } ?: "")))
                    net?.tor?.let { add("tor $it") }
                    net?.onion?.let { add("onion " + if (it) "published" else "not yet") }
                    net?.dht?.let { add("dht " + if (it) "up, ${net.dhtFound ?: 0} found" else "starting") }
                    net?.portmap?.let { add("router port " + if (it) "mapped" else "not mapped") }
                    net?.relays?.let { add("relays ${it.up} of ${it.total}" + if (it.used) ", in use" else ", not needed") }
                    net?.let { add("${it.online} online, ${it.pending} connecting · ${it.rx} packets in") }
                }
                for (l in lines) Text(l, style = Type.label, color = p.muted)
            }
            Spacer(Modifier.height(6.dp))
            RowItem(
                "Console",
                "What chat says about this room, Tor and the network, and its commands",
                onClick = { nav.go(Screen.Console(roomId)) },
                leading = Icons.Console,
            )
            if (info?.route == "tor") {
                RowItem(
                    "New Tor circuits",
                    "And a new onion address for peers to reach you at",
                    onClick = { room.newCircuits() },
                    leading = Icons.Onion,
                )
            } else if (info != null) {
                RowItem(
                    "Change UDP port",
                    info.port?.let { "Listening on UDP port $it" } ?: "Rebind local UDP socket",
                    onClick = { portDialog = true },
                    leading = Icons.Sliders,
                )
            }

            if (!room.isCall) {
                Section("Move")
                RowItem(
                    "Move to a new room",
                    "With the peers whose codes you compared. Everyone else stays here, and isn't told where you went.",
                    onClick = { room.planMove(); moving = true },
                    leading = Icons.Move,
                )
            }
            Spacer(Modifier.height(10.dp))
            Hairline()
            RowItem(
                if (entry != null) "Go offline" else "Leave the room",
                if (entry != null) "Peers are told you went offline; open it again from the list"
                else "It's gone once you leave, unless someone sends you the invite again",
                onClick = { Hub.close(room); nav.home() },
                leading = Icons.Close,
            )
            Spacer(Modifier.height(20.dp))
        }
    }

    if (clearingHistory) Confirm(
        "Clear kept history?",
        "Saved messages from earlier sessions of this room are deleted from the vault.",
        "Clear",
        danger = true,
        onConfirm = { clearingHistory = false; Hub.clearRoomHistory(room) },
        onDismiss = { clearingHistory = false },
    )

    if (forgetting && entry != null) Confirm(
        "Forget this room?",
        "You leave it, and its history and the files you saved from it are deleted.",
        "Forget",
        danger = true,
        onConfirm = { forgetting = false; Hub.forget(entry); nav.home() },
        onDismiss = { forgetting = false },
    )

    val plan = room.movePlan
    if (moving && plan != null) Confirm(
        "Move the room?",
        if (plan.coming == 0) "Nobody can come yet: compare verify codes with them first." +
            (if (plan.leftNames.isNotBlank()) " Not coming: ${plan.leftNames}." else "")
        else "To a new room, with ${plan.comingNames}, each asked first." +
            (if (plan.left > 0) " Staying behind: ${plan.leftNames}." else ""),
        if (plan.coming == 0) "OK" else "Move",
        onConfirm = { moving = false; room.movePlan = null; if (plan.coming > 0) room.answerMove(true) },
        onDismiss = { moving = false; room.movePlan = null },
    )

    if (showLinks) {
        AlertDialog(
            onDismissRequest = { showLinks = false },
            containerColor = p.surface,
            title = { Text("Links in this room", style = Type.title.copy(fontSize = Type.body.fontSize * 1.15f), color = p.text) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((url, who) in roomLinks) {
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(p.bg)
                                .border(1.dp, p.line, RoundedCornerShape(8.dp))
                                .clickable { showLinks = false; pendingLink = url to who }
                                .padding(10.dp),
                        ) {
                            Text(url, style = Type.small, color = p.accent)
                            if (who != null) Text("from $who", style = Type.label, color = p.faint)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLinks = false }) { Text("Close", color = p.muted, style = Type.small) }
            },
        )
    }

    pendingLink?.let { (url, who) ->
        LinkDialog(url = url, sender = who, onDismiss = { pendingLink = null })
    }

    if (portDialog) {
        var portText by remember { mutableStateOf(info?.port?.toString().orEmpty()) }
        AlertDialog(
            onDismissRequest = { portDialog = false },
            containerColor = p.surface,
            title = { Text("UDP port", style = Type.title.copy(fontSize = Type.body.fontSize * 1.15f), color = p.text) },
            text = {
                Field("Port (0 for any free port)") {
                    PrivateField(portText, { portText = it.filter(Char::isDigit) }, Modifier.fillMaxWidth(), mono = true, focus = true)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val n = portText.toIntOrNull() ?: 0
                    if (n in 0..65535) room.runCommand("port $n")
                    portDialog = false
                }) { Text("Apply", color = p.accent, style = Type.small.copy(fontWeight = FontWeight.SemiBold)) }
            },
            dismissButton = {
                TextButton(onClick = { portDialog = false }) { Text("Cancel", color = p.muted, style = Type.small) }
            },
        )
    }
}
