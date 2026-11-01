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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import moe.vin.chat.Hub
import moe.vin.chat.store.Settings
import moe.vin.chat.store.Vault

private const val PASSPHRASE_MIN = 8

@Composable
fun Protection(mode: Vault.Mode, selected: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    val (title, body) = when (mode) {
        Vault.Mode.BOTH -> "Passphrase and this phone" to
            "A passphrase each time chat opens, and a key in this phone's secure hardware that never leaves it. A copy of chat's files can't be opened anywhere else, even with the passphrase."
        Vault.Mode.DEVICE -> "This phone only" to
            "Opens whenever the phone is unlocked, with no passphrase to type. Anyone who gets into the unlocked phone gets your rooms and histories."
        Vault.Mode.PASSPHRASE -> "Passphrase only" to
            "A passphrase each time chat opens. A copy of chat's files could be opened on another phone with it."
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (selected) p.surface else Color.Transparent)
            .border(1.dp, if (selected) p.accent else p.line, RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(16.dp),
    ) {
        Box(
            Modifier.padding(top = 2.dp).size(18.dp).clip(CircleShape).border(1.5.dp, if (selected) p.accent else p.faint, CircleShape),
            contentAlignment = Alignment.Center,
        ) { if (selected) Box(Modifier.size(9.dp).clip(CircleShape).background(p.accent)) }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = Type.body.copy(fontWeight = FontWeight.SemiBold), color = p.text)
            Spacer(Modifier.height(4.dp))
            Text(body, style = Type.small, color = p.muted)
        }
    }
}

@Composable
fun SetupScreen() {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf(Vault.Mode.BOTH) }
    var pass by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var nick by remember { mutableStateOf("") }
    var route by remember { mutableStateOf("udp") }
    var busy by remember { mutableStateOf(false) }
    val problem = when {
        !mode.needsPassphrase -> null
        pass.length < PASSPHRASE_MIN -> "at least $PASSPHRASE_MIN characters"
        pass != again -> "the two don't match yet"
        else -> null
    }
    Column(
        // Unfolded or on a tablet, a readable width in the middle rather than lines across the screen.
        Modifier.fillMaxSize().systemBarsPadding().imePadding().wrapContentWidth().widthIn(max = 560.dp)
            .verticalScroll(rememberScrollState()).padding(horizontal = 22.dp),
    ) {
        Spacer(Modifier.height(36.dp))
        Text("chat", style = Type.title.copy(fontSize = Type.title.fontSize * 1.4f), color = p.text)
        Spacer(Modifier.height(8.dp))
        Text(
            "Encrypted chat without a server: rooms that peers reach directly, or through Tor. Nothing is kept on this phone unless you keep it, and what you keep is sealed.",
            style = Type.body, color = p.muted,
        )
        Spacer(Modifier.height(28.dp))
        Text("HOW CHAT SEALS WHAT IT KEEPS", style = Type.section, color = p.faint)
        Spacer(Modifier.height(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for (m in Vault.Mode.entries) Protection(m, m == mode) { mode = m }
        }
        if (mode.needsPassphrase) {
            Spacer(Modifier.height(22.dp))
            Field("Passphrase") { PrivateField(pass, { pass = it }, Modifier.fillMaxWidth(), placeholder = "a long one, only you know", password = true) }
            Spacer(Modifier.height(12.dp))
            Field("Again") { PrivateField(again, { again = it }, Modifier.fillMaxWidth(), password = true) }
            if (problem != null && pass.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(problem, style = Type.small, color = p.faint)
            }
            Spacer(Modifier.height(6.dp))
            Text("There's no way to get it back: forget it and what's kept is gone.", style = Type.small, color = p.faint)
        }
        Spacer(Modifier.height(22.dp))
        Field("Nick") { PrivateField(nick, { nick = it }, Modifier.fillMaxWidth(), placeholder = "blank: a random one in each room") }
        Spacer(Modifier.height(22.dp))
        Text("HOW ROOMS REACH PEERS", style = Type.section, color = p.faint)
        Spacer(Modifier.height(10.dp))
        Choice(listOf("udp" to "Direct", "tor" to "Tor"), route, { route = it })
        Spacer(Modifier.height(8.dp))
        Text(
            if (route == "udp") "Straight to peers over UDP, found through the BitTorrent DHT and your network. Faster, but peers see your IP address."
            else "Through Tor onion services, which hide your IP address from peers and everyone else. Connecting takes longer.",
            style = Type.small, color = p.muted,
        )
        Spacer(Modifier.height(28.dp))
        Button(
            if (busy) "Sealing…" else "Start", enabled = !busy && problem == null, modifier = Modifier.fillMaxWidth(),
            onClick = {
                busy = true
                scope.launch {
                    Hub.setup(mode, if (mode.needsPassphrase) pass.toCharArray() else null, Settings(nick = nick.trim(), route = route))
                    pass = ""
                    again = ""
                }
            },
        )
        Spacer(Modifier.height(30.dp))
    }
}

@Composable
fun UnlockScreen() {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    val mode = remember { Hub.vault.mode }
    var pass by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var erasing by remember { mutableStateOf(false) }
    fun open() {
        busy = true
        error = null
        scope.launch {
            try {
                Hub.unlock(if (mode?.needsPassphrase == true) pass.toCharArray() else null)
            } catch (e: Vault.WrongPassphrase) {
                error = "That passphrase doesn't open it."
            } catch (e: Exception) {
                error = "This phone's key can't open it: ${e.javaClass.simpleName}${e.message?.let { " ($it)" } ?: ""}"
            }
            pass = ""
            busy = false
        }
    }
    LaunchedEffect(Unit) { if (mode?.needsPassphrase == false) open() }
    Column(
        Modifier.fillMaxSize().systemBarsPadding().imePadding().wrapContentWidth().widthIn(max = 560.dp).padding(horizontal = 22.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("chat", style = Type.title.copy(fontSize = Type.title.fontSize * 1.4f), color = p.text)
        Spacer(Modifier.height(8.dp))
        Text(if (mode?.needsPassphrase == true) "Sealed. Its passphrase opens it." else "Opening with this phone's key…", style = Type.body, color = p.muted)
        if (mode?.needsPassphrase == true) {
            Spacer(Modifier.height(22.dp))
            Field("Passphrase") { PrivateField(pass, { pass = it }, Modifier.fillMaxWidth(), password = true, onDone = { if (pass.isNotEmpty()) open() }, focus = true) }
        }
        error?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, style = Type.small, color = p.bad)
        }
        Spacer(Modifier.height(18.dp))
        Button(if (busy) "Opening…" else "Open", enabled = !busy && (mode?.needsPassphrase != true || pass.isNotEmpty()), modifier = Modifier.fillMaxWidth(), onClick = ::open)
        Spacer(Modifier.height(10.dp))
        TextButton(onClick = { erasing = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text("Erase everything", style = Type.small, color = p.faint)
        }
    }
    if (erasing) Confirm(
        title = "Erase everything?",
        text = "Every kept room, history, file and the signing key go for good, and chat starts again from the beginning.",
        action = "Erase",
        danger = true,
        onConfirm = { erasing = false; Hub.eraseEverything() },
        onDismiss = { erasing = false },
    )
}

@Composable
fun Confirm(title: String, text: String, action: String, danger: Boolean = false, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val p = LocalPalette.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = p.surface,
        title = { Text(title, style = Type.title.copy(fontSize = Type.body.fontSize * 1.15f), color = p.text) },
        text = { Text(text, style = Type.body, color = p.muted) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(action, color = if (danger) p.bad else p.accent, style = Type.small.copy(fontWeight = FontWeight.SemiBold)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = p.muted, style = Type.small) } },
    )
}
