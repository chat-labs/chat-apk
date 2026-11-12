package moe.vin.chat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import moe.vin.chat.Hub
import moe.vin.chat.engine.LineKind
import moe.vin.chat.engine.Peer
import moe.vin.chat.engine.Room

// The commands a room's composer takes, typed as "/" (or ":") and a name, with chat's names for them.

class Command(val name: String, val aliases: List<String>, val args: String, val help: String) {
    val names get() = listOf(name) + aliases
}

private fun c(name: String, vararg aliases: String, args: String = "", help: String) =
    Command(name, aliases.toList(), args, help)

// In the menu's order.
val COMMANDS = listOf(
    c("new", help = "create a room"),
    c("join", "j", args = "[INVITE]", help = "join by invite, or id and password"),
    c("invite", help = "this room's invite"),
    c("peers", "who", help = "peers"),
    c("files", help = "files"),
    c("msg", "dm", args = "NICK TEXT", help = "direct message"),
    c("reply", "re", args = "NICK TEXT", help = "reply to NICK's last message"),
    c("verify", "v", args = "NICK [ok|no]", help = "compare verify codes"),
    c("ignore", args = "NICK [off]", help = "hide NICK's messages; off shows them"),
    c("call", args = "NICK", help = "daily room with a verified key"),
    c("timer", args = "[off|5m|10m|1h|1d|1w]", help = "message timer"),
    c("nick", "name", args = "NAME", help = "set nick"),
    c("net", help = "network report"),
    c("newtor", help = "new Tor circuits"),
    c("set", help = "settings"),
    c("lock", help = "lock the screen"),
    c("leave", "part", help = "leave this room"),
)

fun findCommand(word: String) = COMMANDS.firstOrNull { word in it.names }

// What the menu over the composer lists for a draft: the commands its first word can still become,
// or, once arguments are being typed, the one it names.
fun commandsFor(draft: String): List<Command> {
    if (draft.isEmpty() || draft[0] !in "/:") return emptyList()
    val word = draft.substring(1).substringBefore(' ')
    if (' ' in draft) return listOfNotNull(findCommand(word))
    return COMMANDS.filter { cmd -> cmd.names.any { it.startsWith(word) } }
}

// A command that can't be done as typed: what to say instead, and the draft stays to fix.
class Refused(message: String) : Exception(message)

private fun refuse(why: String): Nothing = throw Refused(why)

// Runs a composer line that's a command. False: it isn't one, so it goes as a message. A "/" line
// whose first word no command starts with ("/tmp/x") is text; a ":" line no command here names is
// chat's own, whose answer comes to the console.
fun runCommand(line: String, room: Room, nav: Nav): Boolean {
    if (line.length < 2 || line[0] !in "/:" || !line[1].isLetter()) return false
    val word = line.substring(1).substringBefore(' ')
    val arg = line.substring(1).substringAfter(' ', "").trim()
    val cmd = findCommand(word)
    if (cmd == null) {
        if (line[0] == ':') {
            room.runCommand(line)
            nav.go(Screen.Console(room.id))
            return true
        }
        if (COMMANDS.none { c -> c.names.any { it.startsWith(word) } }) return false
        refuse("unknown command /$word")
    }
    if (arg.isEmpty() && cmd.args.isNotEmpty() && !cmd.args.startsWith("[")) refuse("usage: /${cmd.name} ${cmd.args}")
    when (cmd.name) {
        "new" -> nav.go(Screen.New)
        "join" -> nav.go(Screen.Join(arg))
        "invite" -> if (room.isCall || room.info == null) refuse("no invite") else nav.go(Screen.Invite(room.id))
        "peers" -> nav.go(Screen.Peers(room.id))
        "files" -> nav.go(Screen.Files(room.id))
        "msg" -> peerAndText(room, arg).let { (peer, text) -> room.dm(peer.id, text) }
        "reply" -> {
            val (peer, text) = peerAndText(room, arg)
            val last = room.items.lastOrNull {
                it.line.kind == LineKind.MESSAGE && it.line.from == peer.id && !it.line.direct && it.line.mid != null
            } ?: refuse("no message from ${peer.name}")
            room.reply(last.line.mid!!, text)
        }
        "verify" -> {
            val answer = arg.substringAfterLast(' ', "").takeIf { it == "ok" || it == "no" }
            val peer = findPeer(room, if (answer == null) arg else arg.substringBeforeLast(' '))
            when {
                peer.code.hex == null -> refuse("no verify code yet")
                answer == null -> nav.go(Screen.PeerInfo(room.id, peer.id))
                else -> room.verify(peer.id, answer == "ok")
            }
        }
        "ignore" -> {
            val who = arg.removeSuffix(" off")
            room.ignore(findPeer(room, who).id, on = who == arg)
        }
        "call" -> {
            val who = arg.removePrefix("@")
            if (Hub.identity == null) refuse("no signing key")
            val keys = Hub.verifiedKeys.filter { it.nick.equals(who, ignoreCase = true) }
            val key = keys.singleOrNull() ?: refuse(
                if (keys.isEmpty()) "no verified key: $who"
                else "several verified keys: $who",
            )
            val call = Hub.call(key, room.info?.route ?: Hub.settings.route) ?: refuse("call failed")
            nav.home(Screen.Chat(call.id))
        }
        "timer" -> {
            if (arg.isEmpty()) {
                nav.go(Screen.RoomSettings(room.id))
            } else {
                val t = TIMERS.firstOrNull { it.second.equals(arg, ignoreCase = true) }
                    ?: refuse("usage: /timer ${TIMERS.joinToString("|") { it.second.lowercase() }}")
                room.setTimer(t.first.toInt())
            }
        }
        "nick" -> Hub.updateSettings(Hub.settings.copy(nick = arg))
        "net" -> {
            room.runCommand(":net")
            nav.go(Screen.Console(room.id))
        }
        "newtor" -> {
            room.newCircuits()
            nav.go(Screen.Console(room.id))
        }
        "set" -> nav.go(Screen.Settings)
        "lock" -> Hub.lockUi()
        "leave" -> {
            Hub.close(room)
            nav.home()
        }
    }
    return true
}

// The peer a typed name means: their name as Peers shows it, or their nick and the start of their
// id ("sam#1a2b") when more than one goes by it.
private fun findPeer(room: Room, typed: String): Peer {
    val t = typed.trim().removePrefix("@")
    val found = called(room, t)
    return found.singleOrNull() ?: refuse(
        if (found.isEmpty()) "no peer: $t"
        else "several peers: $t - use nick#id",
    )
}

private fun called(room: Room, typed: String): List<Peer> {
    val t = typed.trim().removePrefix("@")
    val nick = t.substringBefore('#')
    val id = t.substringAfter('#', "").lowercase()
    return room.peers.filter { it.name.equals(t, ignoreCase = true) || (it.nick.equals(nick, ignoreCase = true) && it.id.startsWith(id)) }
}

// "NICK TEXT", where a nick can hold spaces: the longest start that names one peer.
private fun peerAndText(room: Room, arg: String): Pair<Peer, String> =
    arg.indices.filter { arg[it] == ' ' }
        .mapNotNull { i -> called(room, arg.substring(0, i)).singleOrNull()?.let { it to arg.substring(i + 1).trim() } }
        .lastOrNull()
        ?: run {
            findPeer(room, arg.substringBefore(' '))
            refuse("usage: NICK TEXT")
        }

// Over the composer while a command is typed: what it can be, and what each takes.
@Composable
fun CommandMenu(draft: String, onPick: (Command) -> Unit) {
    val cmds = commandsFor(draft)
    if (cmds.isEmpty()) return
    val p = LocalPalette.current
    Hairline()
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 220.dp).background(p.surface)) {
        items(cmds, key = { it.name }) { cmd ->
            Column(Modifier.fillMaxWidth().clickable { onPick(cmd) }.padding(horizontal = 16.dp, vertical = 7.dp)) {
                Text("${draft[0]}${cmd.name} ${cmd.args}".trimEnd(), style = Type.label, color = p.accent)
                Text(cmd.help, style = Type.small, color = p.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
