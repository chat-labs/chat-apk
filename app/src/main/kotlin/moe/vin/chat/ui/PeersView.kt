package moe.vin.chat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import moe.vin.chat.Hub
import moe.vin.chat.engine.Peer

@Composable
private fun codeTag(peer: Peer) {
    val p = LocalPalette.current
    when (peer.code.state) {
        "compared" -> Tag("code compared", p.good)
        "compare" -> Tag("compare code", p.warn)
        "differs" -> Tag("codes differ", p.bad)
        "key-changed" -> Tag("key changed", p.bad)
        else -> Tag("code not compared", p.faint)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PeersScreen(nav: Nav, roomId: String) {
    val room = Hub.rooms[roomId]
    if (room == null) {
        LaunchedEffect(Unit) { nav.back() }
        return
    }
    val p = LocalPalette.current
    val me = room.info?.me
    Page("Peers", "${room.peers.size} connected", onBack = nav::back) {
        LazyColumn(Modifier.weight(1f)) {
            if (me != null) item {
                Section("You")
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Dot(me.colour)
                    Column(Modifier.padding(start = 14.dp)) {
                        Text(me.nick, style = Type.body.copy(fontWeight = FontWeight.Medium), color = readable(me.colour, p))
                        Text(me.id.take(8), style = Type.label, color = p.faint)
                        Hub.identity?.let { Text("signs with ${it.kind} · ${it.fingerprint}", style = Type.label, color = p.muted) }
                    }
                }
                Section("Here")
            }
            if (room.peers.isEmpty()) item {
                Text(
                    "No peers.",
                    style = Type.small,
                    color = p.faint,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
            items(room.peers, key = { it.id }) { peer ->
                val hasDm = room.items.any { it.line.direct && it.line.peer == peer.id }
                Row(
                    Modifier.fillMaxWidth()
                        .clickable { nav.go(Screen.PeerInfo(roomId, peer.id)) }
                        .padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Dot(peer.colour)
                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                        Text(
                            peer.name,
                            style = Type.body.copy(fontWeight = FontWeight.Medium),
                            color = readable(peer.colour, p),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.padding(top = 4.dp),
                        ) {
                            codeTag(peer)
                            if (peer.identity != "none") {
                                Tag("${peer.identity} ${peer.signed ?: ""}".trim(), if (peer.signed == "failed") p.bad else p.muted)
                            }
                            if (peer.buildState == "modified") Tag("modified build", p.warn)
                            if (peer.history) Tag("keeps history", p.muted)
                            if (peer.ignored) Tag("ignored", p.faint)
                        }
                    }
                    if (peer.dm) {
                        IconAction(
                            Icons.Mail,
                            "Message ${peer.nick} directly",
                            { nav.go(Screen.Chat(roomId, peer.id)) },
                            tint = if (hasDm) p.accent else p.muted,
                            badge = hasDm,
                        )
                    }
                }
                Hairline()
            }
        }
    }
}

@Composable
private fun Dot(colour: Int) {
    val p = LocalPalette.current
    Box(Modifier.size(12.dp).clip(CircleShape).background(readable(colour, p)))
}

@Composable
fun PeerScreen(nav: Nav, roomId: String, peerId: String) {
    val room = Hub.rooms[roomId]
    val peer = room?.peer(peerId)
    val p = LocalPalette.current
    val ctx = LocalContext.current
    if (room == null || peer == null) {
        Page("Peer", onBack = nav::back) {
            Text("Disconnected.", style = Type.body, color = p.muted, modifier = Modifier.padding(20.dp))
        }
        return
    }

    val trusted = peer.fingerprint?.let { fp ->
        Hub.verifiedKeys.firstOrNull { it.fingerprint == fp || (it.nick == peer.nick && it.source == peer.identity) }
    }

    Page(peer.name, peer.id.take(8), onBack = nav::back) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Section("Verify code")
            Column(Modifier.padding(horizontal = 20.dp)) {
                val words = peer.code.words
                if (words == null) {
                    Text("No code yet: handshake in progress.", style = Type.small, color = p.muted)
                } else {
                    Box(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.surface)
                            .border(1.dp, p.line, RoundedCornerShape(12.dp)).padding(16.dp),
                    ) {
                        Column {
                            for (group in words.split(" · ")) {
                                Text(group, style = Type.code.copy(fontSize = Type.code.fontSize * 1.2f), color = p.text)
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text(peer.code.hex.orEmpty(), style = Type.label, color = p.faint)
                                Text(
                                    "Copy",
                                    style = Type.label,
                                    color = p.accent,
                                    modifier = Modifier.clickable { copyText(ctx, "$words (${peer.code.hex.orEmpty()})") },
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    codeTag(peer)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Compare it with ${peer.nick} over another channel. If it matches, nobody is in between; until then, anyone with the room's password could be.",
                        style = Type.small,
                        color = p.muted,
                    )
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            "They match",
                            { room.verify(peer.id, true) },
                            Modifier.weight(1f),
                            icon = Icons.ShieldCheck,
                            enabled = peer.code.state != "compared",
                        )
                        Button(
                            "They differ",
                            { room.verify(peer.id, false) },
                            Modifier.weight(1f),
                            primary = false,
                            enabled = peer.code.state != "differs",
                        )
                    }
                }
            }
            Section("Signing identity")
            Column(Modifier.padding(horizontal = 20.dp)) {
                if (peer.identity == "none") {
                    Text("No signing key.", style = Type.small, color = p.muted)
                } else {
                    Text(
                        "${peer.identity} · ${peer.signed ?: "unverified"}",
                        style = Type.small,
                        color = if (peer.signed == "failed") p.bad else p.text,
                    )
                    peer.fingerprint?.let { fp ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(fp, style = Type.label, color = p.muted, modifier = Modifier.weight(1f))
                            Text("Copy", style = Type.label, color = p.accent, modifier = Modifier.clickable { copyText(ctx, fp) })
                        }
                    }
                    Text(
                        "A matching code verifies their key in every room.",
                        style = Type.small,
                        color = p.faint,
                    )
                }
            }
            Section("Build")
            Text(
                peer.buildLabel.ifBlank { peer.buildState },
                style = Type.small,
                color = if (peer.buildState == "modified") p.warn else p.text,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Spacer(Modifier.height(10.dp))
            Hairline()
            if (peer.dm) {
                RowItem(
                    "Message directly",
                    "Only ${peer.nick} receives it",
                    onClick = { nav.go(Screen.Chat(roomId, peer.id)) },
                    leading = Icons.Mail,
                )
            }
            if (trusted != null && Hub.identity != null && !room.isCall) {
                RowItem(
                    "Call @${trusted.nick}",
                    "Today's room derived from both signing keys",
                    onClick = {
                        Hub.call(trusted, room.info?.route ?: Hub.settings.route)?.let { callRoom ->
                            nav.home(Screen.Chat(callRoom.id))
                        }
                    },
                    leading = Icons.Phone,
                )
            }
            RowItem(
                if (peer.ignored) "Unignore" else "Ignore",
                if (peer.ignored) null else "Hidden on this phone only; still relayed to others",
                onClick = { room.ignore(peer.id, !peer.ignored) },
                leading = Icons.Close,
            )
        }
    }
}
