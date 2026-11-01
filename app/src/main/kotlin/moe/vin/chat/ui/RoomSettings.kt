package moe.vin.chat.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import moe.vin.chat.Hub

private val TIMERS = listOf("0" to "Off", "3600" to "1 hour", "86400" to "1 day", "604800" to "1 week")

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
    var moving by remember { mutableStateOf(false) }
    var showLog by remember { mutableStateOf(false) }
    val info = room.info
    val net = room.net
    Page("Room", room.title.ifBlank { null }, onBack = { if (label != room.label) Hub.rename(room, label.trim()); nav.back() }) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).imePadding()) {
            Spacer(Modifier.height(18.dp))
            Field("Name, for you only", Modifier.padding(horizontal = 20.dp)) {
                PrivateField(label, { label = it }, Modifier.fillMaxWidth(), placeholder = info?.room ?: "",
                    onDone = { Hub.rename(room, label.trim()) })
            }
            Section("Kept")
            if (entry == null) {
                Toggle("Keep this room", "In the vault, to come back to", false) { Hub.keepRoom(room, history = false) }
            } else {
                Toggle("Keep its history", "Sealed in the vault. Everyone here is told you keep one.", room.history) { Hub.setHistory(room, it) }
                RowItem("Forget this room", "Leaves it, and deletes its history and files", onClick = { forgetting = true }, leading = Icons.Trash)
            }
            Section("Message timer")
            Column(Modifier.padding(horizontal = 20.dp)) {
                val t = room.timer
                Choice(TIMERS, (t?.seconds ?: 0).toString(), { room.setTimer(it.toInt()) })
                Spacer(Modifier.height(8.dp))
                Text(
                    if (t != null && t.seconds > 0) "Messages go from every screen that keeps to it after this long; ${t.who ?: "someone"} asked for it. It's a request: a screenshot or a modified chat can keep anything."
                    else "Messages here stay until the room closes, or for as long as a history keeps them.",
                    style = Type.small, color = p.muted,
                )
            }
            Section("Invite")
            RowItem("Show the invite", "Its QR code and text, to send to someone", onClick = { nav.go(Screen.Invite(roomId)) }, leading = Icons.Qr)
            Section("Network")
            Column(Modifier.padding(horizontal = 20.dp)) {
                val lines = buildList {
                    add("room " + (info?.room ?: "…"))
                    add("route " + (if (info?.route == "tor") "tor onion services" else "direct udp" + (info?.port?.let { ", port $it" } ?: "")))
                    net?.tor?.let { add("tor $it") }
                    net?.onion?.let { add("onion " + if (it) "published" else "not yet") }
                    net?.dht?.let { add("dht " + if (it) "up, ${net.dhtFound ?: 0} found" else "starting") }
                    net?.portmap?.let { add("router port " + if (it) "mapped" else "not mapped") }
                    net?.relays?.let { add("relays ${it.up} of ${it.total}" + if (it.used) ", in use" else ", not needed") }
                    net?.let { add("${it.online} online, ${it.pending} connecting") }
                }
                for (l in lines) Text(l, style = Type.label, color = p.muted)
            }
            Spacer(Modifier.height(6.dp))
            RowItem(if (showLog) "Hide chat's notes" else "Show chat's notes", "The sandbox, tor and the network, as chat logs them", onClick = { showLog = !showLog })
            if (showLog) Column(Modifier.padding(horizontal = 20.dp)) {
                for (l in room.logs) Text(l, style = Type.label, color = p.faint)
                for (i in room.items.filter { it.line.kind == moe.vin.chat.engine.LineKind.NOTE }.takeLast(40)) Text(i.line.text, style = Type.label, color = p.faint)
            }
            if (info?.route == "tor") RowItem("New Tor circuits", "And a new onion address for peers to reach you at", onClick = { room.newCircuits() }, leading = Icons.Onion)
            Section("Move")
            RowItem("Move to a new room", "With the peers whose codes you compared. Everyone else stays here, and isn't told where you went.",
                onClick = { room.planMove(); moving = true }, leading = Icons.Move)
            Spacer(Modifier.height(10.dp))
            Hairline()
            RowItem(
                if (entry != null) "Go offline" else "Leave the room",
                if (entry != null) "Peers are told you went offline; open it again from the list" else "It's gone once you leave, unless someone sends you the invite again",
                onClick = { Hub.close(room); nav.home() }, leading = Icons.Close,
            )
            Spacer(Modifier.height(20.dp))
        }
    }
    if (forgetting && entry != null) Confirm(
        "Forget this room?", "You leave it, and its history and the files you saved from it are deleted.", "Forget", danger = true,
        onConfirm = { forgetting = false; Hub.forget(entry); nav.home() }, onDismiss = { forgetting = false },
    )
    val plan = room.movePlan
    if (moving && plan != null) Confirm(
        "Move the room?",
        if (plan.coming == 0) "Nobody can come yet: compare verify codes with them first." +
            (if (plan.leftNames.isNotBlank()) " Not coming: ${plan.leftNames}." else "")
        else "To a new room, with ${plan.comingNames}, each asked first." + (if (plan.left > 0) " Staying behind: ${plan.leftNames}." else ""),
        if (plan.coming == 0) "OK" else "Move",
        onConfirm = { moving = false; room.movePlan = null; if (plan.coming > 0) room.answerMove(true) },
        onDismiss = { moving = false; room.movePlan = null },
    )
}
