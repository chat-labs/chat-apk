package moe.vin.chat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.io.File
import kotlin.concurrent.thread
import kotlinx.coroutines.launch
import moe.vin.chat.BuildConfig
import moe.vin.chat.Hub
import moe.vin.chat.engine.parseColour
import moe.vin.chat.store.Settings
import moe.vin.chat.store.Vault

private val COLOURS = listOf("#5fafd7", "#87d787", "#d7875f", "#d787d7", "#d7d75f", "#ff8787", "#87afff", "#5fd7af")

@Composable
fun SettingsScreen(nav: Nav) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val s = Hub.settings
    fun set(n: Settings) = Hub.updateSettings(n)
    var nick by remember { mutableStateOf(s.nick) }
    var bridges by remember { mutableStateOf(s.customBridges) }
    var keyText by remember { mutableStateOf("") }
    var importing by remember { mutableStateOf(false) }
    var identityError by remember { mutableStateOf<String?>(null) }
    var dropping by remember { mutableStateOf(false) }
    var erasing by remember { mutableStateOf(false) }
    var protecting by remember { mutableStateOf(false) }
    var versions by remember { mutableStateOf<String?>(null) }
    fun leave() {
        if (nick.trim() != s.nick) set(Hub.settings.copy(nick = nick.trim()))
        if (bridges != s.customBridges) set(Hub.settings.copy(customBridges = bridges))
        nav.back()
    }
    Page("Settings", onBack = ::leave) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).imePadding()) {
            Spacer(Modifier.height(18.dp))
            Field("Nick", Modifier.padding(horizontal = 20.dp)) {
                PrivateField(nick, { nick = it }, Modifier.fillMaxWidth(), placeholder = "blank: a random one in each room",
                    onDone = { set(Hub.settings.copy(nick = nick.trim())) })
            }
            Spacer(Modifier.height(14.dp))
            Text("COLOUR", style = Type.section, color = p.faint, modifier = Modifier.padding(horizontal = 20.dp))
            Spacer(Modifier.height(8.dp))
            Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Swatch(null, s.colour.isBlank()) { set(Hub.settings.copy(colour = "")) }
                for (c in COLOURS) Swatch(c, s.colour == c) { set(Hub.settings.copy(colour = c)) }
            }
            Section("New rooms reach peers")
            Choice(listOf("udp" to "Direct", "tor" to "Tor"), s.route, { set(Hub.settings.copy(route = it)) }, Modifier.padding(horizontal = 20.dp))
            Spacer(Modifier.height(10.dp))
            Text("NOSTR RELAYS", style = Type.section, color = p.faint, modifier = Modifier.padding(horizontal = 20.dp))
            Spacer(Modifier.height(8.dp))
            Choice(listOf("off" to "Off", "fallback" to "When needed", "always" to "Always"), s.nostr, { set(Hub.settings.copy(nostr = it)) }, Modifier.padding(horizontal = 20.dp))
            Text(
                "Public relays carry the encrypted, padded traffic a route can't, and are where direct and Tor members meet. With Tor, they're reached through it.",
                style = Type.small, color = p.muted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            Toggle("BitTorrent DHT", "How direct rooms find their peers", s.dht) { set(Hub.settings.copy(dht = it)) }
            Toggle("Local network", "Find peers on the same Wi-Fi", s.lan) { set(Hub.settings.copy(lan = it)) }
            Toggle("Router port mapping", "Ask the router to let peers connect in", s.portmap) { set(Hub.settings.copy(portmap = it)) }

            Section("Tor")
            Text("BRIDGES", style = Type.section, color = p.faint, modifier = Modifier.padding(horizontal = 20.dp))
            Spacer(Modifier.height(8.dp))
            Choice(listOf("none" to "None", "obfs4" to "obfs4", "snowflake" to "Snowflake", "custom" to "Own"), s.bridges,
                { set(Hub.settings.copy(bridges = it)); Hub.restartTor() }, Modifier.padding(horizontal = 20.dp))
            Text(
                when (s.bridges) {
                    "obfs4", "snowflake" -> "Tor Browser's own bridges, for where Tor is blocked."
                    "custom" -> "Bridge lines, one a line, as bridges.torproject.org gives them."
                    else -> "Straight to the Tor network."
                },
                style = Type.small, color = p.muted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            if (s.bridges == "custom") Field("Bridge lines", Modifier.padding(horizontal = 20.dp)) {
                PrivateField(bridges, { bridges = it }, Modifier.fillMaxWidth(), multiLine = true, mono = true, placeholder = "obfs4 1.2.3.4:443 FINGERPRINT cert=… iat-mode=0")
            }
            Toggle("Keep Tor's state", "Its guards and directory, so it connects faster. Leaves them on the phone.", s.torKeepState) {
                set(Hub.settings.copy(torKeepState = it))
            }
            RowItem("Restart tor", "With the settings above", onClick = {
                if (bridges != s.customBridges) set(Hub.settings.copy(customBridges = bridges))
                Hub.restartTor()
            }, leading = Icons.Onion)

            Section("Notifications")
            Choice(listOf("none" to "None", "mentions" to "For me", "all" to "All"), s.notify, { set(Hub.settings.copy(notify = it)) }, Modifier.padding(horizontal = 20.dp))
            Spacer(Modifier.height(10.dp))
            Text("WHAT THEY SHOW", style = Type.section, color = p.faint, modifier = Modifier.padding(horizontal = 20.dp))
            Spacer(Modifier.height(8.dp))
            Choice(listOf("off" to "Nothing", "nick" to "Who", "message" to "Who and what"), s.preview, { set(Hub.settings.copy(preview = it)) }, Modifier.padding(horizontal = 20.dp))
            Text("Never the room. Android keeps what a notification showed, and the lock screen only ever says a message came.",
                style = Type.small, color = p.muted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))

            Section("Privacy")
            Toggle("Require peer verification", "Send nothing to a peer until you've compared verify codes with them", s.verify == "required") {
                set(Hub.settings.copy(verify = if (it) "required" else "optional"))
            }
            Toggle("Noise in pictures", "A JPEG you send also gets noise nobody can see, on top of what's always taken out", s.noise) { set(Hub.settings.copy(noise = it)) }
            Toggle("Stay connected with the screen off", "Uses more battery; without it, peers may time you out", s.stayAwake) { set(Hub.settings.copy(stayAwake = it)) }
            Toggle("Open kept rooms at start", "Every kept room, once chat's open", s.reopenKept) { set(Hub.settings.copy(reopenKept = it)) }

            Section("Files")
            Choice(listOf("8" to "8 MB", "64" to "64 MB", "256" to "256 MB", "1024" to "1 GB"), s.fileLimitMb.toString(),
                { set(Hub.settings.copy(fileLimitMb = it.toInt())) }, Modifier.padding(horizontal = 20.dp))
            Text("The largest file fetched without asking again", style = Type.small, color = p.muted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            Toggle("Fast transfers", "Seconds instead of minutes, but a transfer shows on the network", s.fastFiles) { set(Hub.settings.copy(fastFiles = it)) }

            Section("Signing key")
            val id = Hub.identity
            Column(Modifier.padding(horizontal = 20.dp)) {
                if (id == null) Text("Rooms are joined without one. A signing key lets peers recognise you across rooms once they've compared codes with you once.",
                    style = Type.small, color = p.muted)
                else {
                    Text("${id.kind} · ${id.fingerprint}", style = Type.label, color = p.text)
                    if (id.public.isNotBlank() && id.kind == "age") Text(id.public, style = Type.label, color = p.muted)
                    Text("New rooms sign with it.", style = Type.small, color = p.faint)
                }
                identityError?.let { Text(it, style = Type.small, color = p.bad) }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button("Make a key", {
                        identityError = null
                        scope.launch { identityError = Hub.makeIdentity("age", null) }
                    }, Modifier.weight(1f), primary = id == null, icon = Icons.Key)
                    Button("Import", { importing = !importing }, Modifier.weight(1f), primary = false)
                }
                if (importing) {
                    Spacer(Modifier.height(10.dp))
                    Field("AGE identity or unencrypted PGP key") {
                        PrivateField(keyText, { keyText = it }, Modifier.fillMaxWidth(), multiLine = true, mono = true, placeholder = "AGE-SECRET-KEY-1…")
                    }
                    Spacer(Modifier.height(8.dp))
                    Button("Use this key", {
                        val t = keyText.trim()
                        val kind = if (t.startsWith("-----BEGIN PGP")) "pgp" else "age"
                        scope.launch {
                            identityError = Hub.makeIdentity(kind, t)
                            if (identityError == null) { keyText = ""; importing = false }
                        }
                    }, Modifier.fillMaxWidth(), enabled = keyText.isNotBlank())
                }
                if (id != null) TextButton(onClick = { dropping = true }) { Text("Stop signing", style = Type.small, color = p.faint) }
            }

            Section("Vault")
            RowItem("How it's sealed", when (Hub.vault.mode) {
                Vault.Mode.BOTH -> "Passphrase and this phone"
                Vault.Mode.DEVICE -> "This phone only"
                Vault.Mode.PASSPHRASE -> "Passphrase only"
                null -> ""
            }, onClick = { protecting = true }, leading = Icons.Lock)
            RowItem("Quit chat", "Every room goes offline or leaves, tor stops, and the vault is sealed again", onClick = { Hub.quit() }, leading = Icons.Close)
            RowItem("Erase everything", "Every kept room, history, file and the signing key", onClick = { erasing = true }, leading = Icons.Trash)

            Section("About")
            Column(Modifier.padding(horizontal = 20.dp)) {
                Text("chat-gui ${BuildConfig.VERSION_NAME}", style = Type.label, color = p.muted)
                val v = versions
                if (v == null) Text("versions of chat and tor", style = Type.small, color = p.accent, modifier = Modifier.clickable {
                    val lib = File(ctx.applicationInfo.nativeLibraryDir)
                    thread {
                        val out = listOf("libchat.so", "libtor.so").joinToString("\n") { exe ->
                            runCatching {
                                ProcessBuilder(File(lib, exe).path, "--version").redirectErrorStream(true).start().inputStream.bufferedReader().readLine()
                            }.getOrNull() ?: "$exe: ?"
                        }
                        versions = out
                    }
                }) else Text(v, style = Type.label, color = p.muted)
                Text("GPL-3.0. tor and lyrebird are Tor Project's own builds.", style = Type.small, color = p.faint)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    if (dropping) Confirm("Stop signing?", "New rooms are joined without a key. The key is deleted from the vault; make sure you have a copy if you want it back.", "Stop signing", danger = true,
        onConfirm = { dropping = false; Hub.dropIdentity() }, onDismiss = { dropping = false })
    if (erasing) Confirm("Erase everything?", "Every kept room, history, file and the signing key go for good, and chat starts again from the beginning.", "Erase", danger = true,
        onConfirm = { erasing = false; Hub.eraseEverything() }, onDismiss = { erasing = false })
    if (protecting) ProtectionDialog { protecting = false }
}

@Composable
private fun Swatch(hex: String?, selected: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    val c = hex?.let { parseColour(it) }?.let { Color(it) }
    Box(
        Modifier.size(28.dp).clip(CircleShape).background(c ?: p.raised)
            .border(if (selected) 2.dp else 1.dp, if (selected) p.text else p.line, CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { if (c == null) Text("?", style = Type.label, color = p.muted) }
}

@Composable
private fun ProtectionDialog(onDone: () -> Unit) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf(Hub.vault.mode ?: Vault.Mode.BOTH) }
    var pass by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val ok = !mode.needsPassphrase || (pass.length >= 8 && pass == again)
    AlertDialog(
        onDismissRequest = onDone,
        containerColor = p.bg,
        title = { Text("How it's sealed", style = Type.title.copy(fontWeight = FontWeight.SemiBold), color = p.text) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for (m in Vault.Mode.entries) Protection(m, m == mode) { mode = m }
                if (mode.needsPassphrase) {
                    Field("New passphrase") { PrivateField(pass, { pass = it }, Modifier.fillMaxWidth(), password = true) }
                    Field("Again") { PrivateField(again, { again = it }, Modifier.fillMaxWidth(), password = true) }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = ok && !busy, onClick = {
                busy = true
                scope.launch {
                    Hub.changeProtection(mode, if (mode.needsPassphrase) pass.toCharArray() else null)
                    onDone()
                }
            }) { Text(if (busy) "Sealing…" else "Seal it this way", color = p.accent, style = Type.small.copy(fontWeight = FontWeight.SemiBold)) }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel", color = p.muted, style = Type.small) } },
    )
}
