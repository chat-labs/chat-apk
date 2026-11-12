package moe.vin.chat.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    var search by remember { mutableStateOf("") }
    var nick by remember { mutableStateOf(s.nick) }
    var relays by remember { mutableStateOf(s.relays) }
    var bridges by remember { mutableStateOf(s.customBridges) }
    var keyKind by remember { mutableStateOf(Hub.identity?.kind ?: "age") }
    var keyText by remember { mutableStateOf("") }
    var importing by remember { mutableStateOf(false) }
    var deriving by remember { mutableStateOf(false) }
    var exportedSecret by remember { mutableStateOf<String?>(null) }
    var identityBusy by remember { mutableStateOf(false) }
    var identityError by remember { mutableStateOf<String?>(null) }
    var dropping by remember { mutableStateOf(false) }
    var forgettingAllVerified by remember { mutableStateOf(false) }
    var erasing by remember { mutableStateOf(false) }
    var protecting by remember { mutableStateOf(false) }
    var shadowDialog by remember { mutableStateOf(false) }
    var hasShadow by remember { mutableStateOf(Hub.hasShadowPassphrase) }
    var versions by remember { mutableStateOf<String?>(null) }

    val importKeyFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val content = withContext(Dispatchers.IO) {
                    runCatching { ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } }.getOrNull()
                }
                if (!content.isNullOrBlank()) {
                    keyText = content.trim()
                    importing = true
                } else {
                    Toast.makeText(ctx, "Couldn't read key file", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val saveSecretFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val secret = exportedSecret
        if (uri != null && secret != null) {
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        ctx.contentResolver.openOutputStream(uri)?.use { it.write(secret.toByteArray()) } != null
                    }.getOrDefault(false)
                }
                Toast.makeText(ctx, if (ok) "Saved secret key" else "Couldn't save file", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun flushPendingFields() {
        var next = Hub.settings
        if (nick.trim() != next.nick) next = next.copy(nick = nick.trim())
        if (relays.trim() != next.relays) next = next.copy(relays = relays.trim())
        if (bridges != next.customBridges) next = next.copy(customBridges = bridges)
        if (next != Hub.settings) set(next)
    }

    DisposableEffect(Unit) {
        onDispose { flushPendingFields() }
    }

    fun leave() {
        flushPendingFields()
        nav.back()
    }

    val sidebar = LocalSidebar.current
    val showBack = !(sidebar?.listVisible == true && nav.stack.size <= 2)
    val q = search.trim()
    fun match(vararg terms: String): Boolean = q.isEmpty() || terms.any { it.contains(q, ignoreCase = true) }

    Page("Settings", onBack = if (showBack) ::leave else null) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).imePadding()) {
            Spacer(Modifier.height(14.dp))
            SearchBar(search, { search = it }, modifier = Modifier.padding(horizontal = 20.dp))

            if (match("nick", "name", "colour", "color", "profile")) {
                Spacer(Modifier.height(18.dp))
                Field("Nick", Modifier.padding(horizontal = 20.dp)) {
                    PrivateField(
                        nick,
                        { nick = it },
                        Modifier.fillMaxWidth(),
                        placeholder = "blank: random per room",
                        onDone = { set(Hub.settings.copy(nick = nick.trim())) },
                    )
                }
                Spacer(Modifier.height(14.dp))
                Text("COLOUR", style = Type.section, color = p.faint, modifier = Modifier.padding(horizontal = 20.dp))
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Swatch(null, s.colour.isBlank()) { set(Hub.settings.copy(colour = "")) }
                    for (c in COLOURS) Swatch(c, s.colour == c) { set(Hub.settings.copy(colour = c)) }
                }
            }

            if (match("route", "direct", "udp", "nostr", "relay", "dht", "ipv6", "lan", "local", "port", "upnp", "nat", "network", "log")) {
                Section("Route for new rooms")
                Choice(
                    listOf("udp" to "Direct", "tor" to "Tor"),
                    s.route,
                    { set(Hub.settings.copy(route = it)) },
                    Modifier.padding(horizontal = 20.dp),
                )
                Spacer(Modifier.height(12.dp))
                Text("NOSTR RELAYS", style = Type.section, color = p.faint, modifier = Modifier.padding(horizontal = 20.dp))
                Spacer(Modifier.height(8.dp))
                Choice(
                    listOf("off" to "Off", "fallback" to "When needed", "always" to "Always"),
                    s.nostr,
                    { set(Hub.settings.copy(nostr = it)) },
                    Modifier.padding(horizontal = 20.dp),
                )
                Text(
                    "Nostr relays carry encrypted, padded traffic a route can't, and connect direct and Tor members. Reached over Tor in Tor rooms.",
                    style = Type.small,
                    color = p.muted,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
                if (s.nostr != "off") {
                    Field("Custom relays", Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                        PrivateField(
                            relays,
                            { relays = it },
                            Modifier.fillMaxWidth(),
                            multiLine = true,
                            mono = true,
                            placeholder = "blank: default wss:// relays",
                            onDone = { set(Hub.settings.copy(relays = relays.trim())) },
                        )
                    }
                }
                Toggle("BitTorrent DHT", "Peer discovery for direct rooms", s.dht) { set(Hub.settings.copy(dht = it)) }
                if (s.dht) {
                    Toggle("IPv6 DHT", "Announce and look up over IPv6 too", s.ipv6) { set(Hub.settings.copy(ipv6 = it)) }
                }
                Toggle("Local network", "Discover peers on the LAN", s.lan) { set(Hub.settings.copy(lan = it)) }
                Toggle("Router port mapping", "Map a port on the router for incoming peers", s.portmap) { set(Hub.settings.copy(portmap = it)) }
                Spacer(Modifier.height(6.dp))
                Text("NETWORK NOTES", style = Type.section, color = p.faint, modifier = Modifier.padding(horizontal = 20.dp))
                Spacer(Modifier.height(8.dp))
                Choice(
                    listOf("normal" to "Normal", "verbose" to "Verbose", "off" to "Quiet"),
                    s.netLog,
                    { set(Hub.settings.copy(netLog = it)) },
                    Modifier.padding(horizontal = 20.dp),
                )
            }

            if (match("tor", "onion", "bridge", "obfs4", "snowflake", "meek", "guard")) {
                Section("Tor")
                Text("BRIDGES", style = Type.section, color = p.faint, modifier = Modifier.padding(horizontal = 20.dp))
                Spacer(Modifier.height(8.dp))
                Choice(
                    listOf(
                        "none" to "None",
                        "obfs4" to "obfs4",
                        "snowflake" to "Snowflake",
                        "meek" to "meek",
                        "custom" to "Own",
                    ),
                    s.bridges,
                    { set(Hub.settings.copy(bridges = it)); Hub.restartTor() },
                    Modifier.padding(horizontal = 20.dp),
                )
                Text(
                    when (s.bridges) {
                        "obfs4", "snowflake", "meek" -> "Tor Browser's built-in ${s.bridges} bridges."
                        "custom" -> "Bridge lines, one per line or ';' separated. Set over the control port, not written to torrc."
                        else -> "No bridges."
                    },
                    style = Type.small,
                    color = p.muted,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
                if (s.bridges == "custom") {
                    Field(
                        "Bridge lines",
                        Modifier.padding(horizontal = 20.dp),
                        trailing = {
                            Text(
                                "Paste",
                                style = Type.label,
                                color = p.accent,
                                modifier = Modifier.clickable {
                                    readClipboard(ctx)?.let { bridges = it }
                                },
                            )
                        },
                    ) {
                        PrivateField(
                            bridges,
                            { bridges = it },
                            Modifier.fillMaxWidth(),
                            multiLine = true,
                            mono = true,
                            placeholder = "obfs4 1.2.3.4:443 FINGERPRINT cert=… iat-mode=0",
                        )
                    }
                }
                Toggle(
                    "Keep Tor's state",
                    "Guards and directory cache, stored in the vault.",
                    s.torKeepState,
                ) { set(Hub.settings.copy(torKeepState = it)) }
                RowItem("Restart tor", onClick = {
                    if (bridges != s.customBridges) set(Hub.settings.copy(customBridges = bridges))
                    Hub.restartTor()
                }, leading = Icons.Onion)
            }

            if (match("notify", "notification", "preview", "mention", "alert")) {
                Section("Notifications")
                Choice(
                    listOf("none" to "None", "mentions" to "For me", "all" to "All"),
                    s.notify,
                    { set(Hub.settings.copy(notify = it)) },
                    Modifier.padding(horizontal = 20.dp),
                )
                Spacer(Modifier.height(10.dp))
                Text("PREVIEW", style = Type.section, color = p.faint, modifier = Modifier.padding(horizontal = 20.dp))
                Spacer(Modifier.height(8.dp))
                Choice(
                    listOf("off" to "None", "nick" to "Sender", "message" to "Sender and text"),
                    s.preview,
                    { set(Hub.settings.copy(preview = it)) },
                    Modifier.padding(horizontal = 20.dp),
                )
                Text(
                    "Never the room. Nothing while locked or on the lock screen.",
                    style = Type.small,
                    color = p.muted,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }

            if (match("chat", "follow", "scroll", "bottom", "privacy", "verify", "code", "noise", "picture", "jpeg", "awake", "battery", "reopen", "kept")) {
                Section("Chat & privacy")
                Toggle(
                    "Follow new messages",
                    null,
                    s.followChat,
                ) { set(Hub.settings.copy(followChat = it)) }
                Toggle(
                    "Require peer verification",
                    "Send only to peers whose verify code you compared",
                    s.verify == "required",
                ) { set(Hub.settings.copy(verify = if (it) "required" else "optional")) }
                Toggle(
                    "Noise in pictures",
                    "Invisible noise in cleaned pictures, so no two copies match",
                    s.noise,
                ) { set(Hub.settings.copy(noise = it)) }
                Toggle(
                    "Stay connected with the screen off",
                    "Wake lock; without it, peers may time you out",
                    s.stayAwake,
                ) { set(Hub.settings.copy(stayAwake = it)) }
                Toggle(
                    "Open kept rooms on unlock",
                    null,
                    s.reopenKept,
                ) { set(Hub.settings.copy(reopenKept = it)) }
            }

            if (match("file", "size", "limit", "transfer", "fast")) {
                Section("Files")
                Choice(
                    listOf("8" to "8 MB", "64" to "64 MB", "256" to "256 MB", "1024" to "1 GB"),
                    s.fileLimitMb.toString(),
                    { set(Hub.settings.copy(fileLimitMb = it.toInt())) },
                    Modifier.padding(horizontal = 20.dp),
                )
                Text(
                    "Larger files need confirming",
                    style = Type.small,
                    color = p.muted,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
                Toggle(
                    "Fast transfers",
                    "Faster, but visible as a traffic burst",
                    s.fastFiles,
                ) { set(Hub.settings.copy(fastFiles = it)) }
            }

            if (match("signing", "identity", "key", "age", "pgp", "password", "export", "import", "fingerprint")) {
                Section("Signing key")
                val id = Hub.identity
                Column(Modifier.padding(horizontal = 20.dp)) {
                    if (id == null) {
                        Text(
                            "Optional. Lets peers who verified you recognise you in every room, and enables /call.",
                            style = Type.small,
                            color = p.muted,
                        )
                    } else {
                        Box(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(p.surface)
                                .border(1.dp, p.line, RoundedCornerShape(10.dp)).padding(14.dp),
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "${id.kind.uppercase()}${if (id.derived) " · from password" else ""}",
                                        style = Type.label.copy(fontWeight = FontWeight.SemiBold),
                                        color = p.accent,
                                    )
                                    Text(
                                        "Copy fingerprint",
                                        style = Type.label,
                                        color = p.accent,
                                        modifier = Modifier.clickable { copyText(ctx, id.fingerprint) },
                                    )
                                }
                                Text(id.fingerprint, style = Type.label, color = p.text)
                                if (id.public.isNotBlank()) {
                                    Text(
                                        id.public.lines().firstOrNull().orEmpty(),
                                        style = Type.label,
                                        color = p.muted,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                    if (id.public.isNotBlank()) {
                                        Text(
                                            "Copy public key",
                                            style = Type.small.copy(fontWeight = FontWeight.Medium),
                                            color = p.accent,
                                            modifier = Modifier.clickable { copyText(ctx, id.public) },
                                        )
                                    }
                                    Text(
                                        "Export secret key",
                                        style = Type.small.copy(fontWeight = FontWeight.Medium),
                                        color = p.warn,
                                        modifier = Modifier.clickable {
                                            scope.launch {
                                                val (sec, err) = Hub.exportIdentitySecret()
                                                if (sec != null) exportedSecret = sec
                                                else identityError = err
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }

                    identityError?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it, style = Type.small, color = p.bad)
                    }
                    Spacer(Modifier.height(12.dp))
                    Choice(listOf("age" to "AGE key", "pgp" to "PGP key"), keyKind, { keyKind = it })
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            if (identityBusy) "Working…" else "Random",
                            onClick = {
                                flushPendingFields()
                                identityBusy = true
                                identityError = null
                                scope.launch {
                                    identityError = Hub.makeIdentity(keyKind)
                                    identityBusy = false
                                }
                            },
                            modifier = Modifier.weight(1f),
                            enabled = !identityBusy,
                            primary = id == null,
                            icon = Icons.Key,
                        )
                        Button(
                            "Password",
                            onClick = { flushPendingFields(); deriving = true },
                            modifier = Modifier.weight(1f),
                            enabled = !identityBusy,
                            primary = false,
                        )
                        Button(
                            "Import",
                            onClick = { importing = !importing },
                            modifier = Modifier.weight(1f),
                            enabled = !identityBusy,
                            primary = false,
                        )
                    }

                    if (importing) {
                        Spacer(Modifier.height(10.dp))
                        Field(
                            "AGE secret key or unencrypted PGP key",
                            trailing = {
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text("File", style = Type.label, color = p.accent, modifier = Modifier.clickable {
                                        importKeyFile.launch(arrayOf("*/*"))
                                    })
                                    Text("Paste", style = Type.label, color = p.accent, modifier = Modifier.clickable {
                                        readClipboard(ctx)?.let { keyText = it }
                                    })
                                }
                            },
                        ) {
                            PrivateField(
                                keyText,
                                { keyText = it },
                                Modifier.fillMaxWidth(),
                                multiLine = true,
                                mono = true,
                                placeholder = "AGE-SECRET-KEY-1… or -----BEGIN PGP PRIVATE KEY BLOCK-----",
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Button(
                            "Import",
                            onClick = {
                                val t = keyText.trim()
                                val k = if (t.startsWith("-----BEGIN PGP")) "pgp" else "age"
                                identityBusy = true
                                identityError = null
                                scope.launch {
                                    identityError = Hub.makeIdentity(k, key = t)
                                    identityBusy = false
                                    if (identityError == null) {
                                        keyText = ""
                                        importing = false
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = keyText.isNotBlank() && !identityBusy,
                        )
                    }

                    if (id != null) {
                        TextButton(onClick = { dropping = true }) {
                            Text("Stop signing", style = Type.small, color = p.faint)
                        }
                    }
                }
            }

            if (match("verified", "trust", "peer", "call", "contact", "key")) {
                Section("Verified keys")
                if (Hub.verifiedKeys.isEmpty()) {
                    Text(
                        "None. A peer's signing key is added when you confirm their verify code.",
                        style = Type.small,
                        color = p.muted,
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                } else {
                    for (vk in Hub.verifiedKeys) {
                        RowItem(
                            "@${vk.nick}",
                            "${vk.source} · ${vk.fingerprint.ifBlank { vk.pub.take(20) }}",
                            leading = Icons.ShieldCheck,
                            trailing = {
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    if (Hub.identity != null) {
                                        Text(
                                            "Call",
                                            style = Type.small.copy(fontWeight = FontWeight.SemiBold),
                                            color = p.accent,
                                            modifier = Modifier.clickable {
                                                Hub.call(vk, Hub.settings.route)?.let { r -> nav.home(Screen.Chat(r.id)) }
                                            },
                                        )
                                    }
                                    Text(
                                        "Forget",
                                        style = Type.small,
                                        color = p.bad,
                                        modifier = Modifier.clickable { Hub.forgetVerified(vk.pub) },
                                    )
                                }
                            },
                        )
                    }
                    RowItem(
                        "Forget all verified keys",
                        null,
                        onClick = { forgettingAllVerified = true },
                        leading = Icons.Trash,
                        titleColor = p.bad,
                    )
                }
            }

            if (Hub.ignoredKeys.isNotEmpty() && match("ignored", "ignore", "mute", "block")) {
                Section("Ignored keys")
                for (ik in Hub.ignoredKeys) {
                    RowItem(
                        ik.nick.ifBlank { ik.fingerprint.take(16) },
                        "${ik.source} · ${ik.fingerprint.ifBlank { ik.pub.take(20) }}",
                        trailing = {
                            Text(
                                "Unignore",
                                style = Type.small.copy(fontWeight = FontWeight.SemiBold),
                                color = p.accent,
                                modifier = Modifier.clickable { Hub.unignoreKey(ik.pub) },
                            )
                        },
                    )
                }
            }

            if (match("vault", "seal", "lock", "passphrase", "auto", "destruct", "tries", "shadow", "duress", "panic", "quit", "erase")) {
                Section("Vault & security")
                val vMode = Hub.vault.mode
                RowItem(
                    "How it's sealed",
                    when (vMode) {
                        Vault.Mode.BOTH -> "Passphrase and hardware key"
                        Vault.Mode.DEVICE -> "Hardware key only"
                        Vault.Mode.PASSPHRASE -> "Passphrase only"
                        null -> ""
                    },
                    onClick = { protecting = true },
                    leading = Icons.Lock,
                )
                RowItem(
                    "Lock screen",
                    "Rooms stay connected",
                    onClick = { flushPendingFields(); Hub.lockUi() },
                    leading = Icons.Unlock,
                )
                Spacer(Modifier.height(8.dp))
                Text("AUTO-LOCK INACTIVITY", style = Type.section, color = p.faint, modifier = Modifier.padding(horizontal = 20.dp))
                Spacer(Modifier.height(8.dp))
                Choice(
                    listOf("0" to "Off", "60" to "1m", "300" to "5m", "900" to "15m", "1800" to "30m"),
                    s.autoLockSeconds.toString(),
                    { set(Hub.settings.copy(autoLockSeconds = it.toInt())) },
                    Modifier.padding(horizontal = 20.dp),
                )

                if (vMode?.needsPassphrase == true) {
                    Spacer(Modifier.height(14.dp))
                    Text("SELF-DESTRUCT", style = Type.section, color = p.faint, modifier = Modifier.padding(horizontal = 20.dp))
                    Spacer(Modifier.height(8.dp))
                    Choice(
                        listOf("0" to "Off", "3" to "3 tries", "5" to "5 tries", "10" to "10 tries"),
                        s.destroyTries.toString(),
                        { set(Hub.settings.copy(destroyTries = it.toInt())) },
                        Modifier.padding(horizontal = 20.dp),
                    )
                    Text(
                        if (s.destroyTries > 0) "Erases the vault and received files after ${s.destroyTries} wrong passphrases in a row."
                        else "Off.",
                        style = Type.small,
                        color = p.muted,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                    )
                    RowItem(
                        "Shadow passphrase",
                        if (hasShadow) "Set. Entering it erases the vault and opens an empty one."
                        else "A duress passphrase: erases the vault and opens an empty one.",
                        onClick = { shadowDialog = true },
                        leading = Icons.Shield,
                    )
                }

                Toggle(
                    "Panic button",
                    "On the rooms list and the lock screen",
                    s.panicButton,
                ) { set(Hub.settings.copy(panicButton = it)) }
                if (s.panicButton) {
                    Toggle(
                        "Panic erases the vault",
                        "And received files. Off: panic only seals it.",
                        s.panicWipe,
                    ) { set(Hub.settings.copy(panicWipe = it)) }
                }

                RowItem(
                    "Quit chat",
                    "Leaves or goes offline in every room, stops tor, seals the vault",
                    onClick = { flushPendingFields(); Hub.quit() },
                    leading = Icons.Close,
                )
                RowItem(
                    "Erase everything",
                    "The vault and received files",
                    onClick = { erasing = true },
                    leading = Icons.Trash,
                    titleColor = p.bad,
                )
            }

            if (match("about", "version", "gpl", "license", "tor", "chat")) {
                Section("About")
                Column(Modifier.padding(horizontal = 20.dp)) {
                    Text("chat-apk ${BuildConfig.VERSION_NAME}", style = Type.label, color = p.muted)
                    val v = versions
                    if (v == null) {
                        Text(
                            "versions of chat and tor",
                            style = Type.small,
                            color = p.accent,
                            modifier = Modifier.clickable {
                                val lib = File(ctx.applicationInfo.nativeLibraryDir)
                                scope.launch {
                                    versions = withContext(Dispatchers.IO) {
                                        listOf("libchat.so", "libtor.so").joinToString("\n") { exe ->
                                            runCatching {
                                                ProcessBuilder(File(lib, exe).path, "--version")
                                                    .redirectErrorStream(true).start()
                                                    .inputStream.bufferedReader().readLine()
                                            }.getOrNull() ?: "$exe: ?"
                                        }
                                    }
                                }
                            },
                        )
                    } else {
                        Text(v, style = Type.label, color = p.muted)
                    }
                    Text("GPL-3.0. tor and lyrebird are Tor Project's own builds.", style = Type.small, color = p.faint)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (dropping) Confirm(
        "Stop signing?",
        "Rooms stop signing. The key is deleted from the vault; export it first to keep it.",
        "Stop signing",
        danger = true,
        onConfirm = { dropping = false; Hub.dropIdentity() },
        onDismiss = { dropping = false },
    )

    if (forgettingAllVerified) Confirm(
        "Forget all verified keys?",
        "Verify codes have to be compared again.",
        "Forget all",
        danger = true,
        onConfirm = { forgettingAllVerified = false; Hub.forgetAllVerified() },
        onDismiss = { forgettingAllVerified = false },
    )

    if (erasing) Confirm(
        "Erase everything?",
        "Deletes the vault (kept rooms, histories, settings, signing key) and received files.",
        "Erase",
        danger = true,
        onConfirm = { erasing = false; Hub.eraseEverything() },
        onDismiss = { erasing = false },
    )

    if (protecting) ProtectionDialog {
        protecting = false
        hasShadow = Hub.hasShadowPassphrase
    }

    if (deriving) DeriveKeyDialog(
        kind = keyKind,
        currentNick = nick.trim(),
        onDone = { deriving = false },
        onDerived = { err ->
            deriving = false
            identityError = err
        },
    )

    exportedSecret?.let { sec ->
        ExportKeyDialog(
            secret = sec,
            kind = Hub.identity?.kind ?: "age",
            onSaveFile = {
                val ext = if (Hub.identity?.kind == "pgp") "asc" else "key"
                saveSecretFile.launch("chat-signing-key.$ext")
            },
            onDismiss = { exportedSecret = null },
        )
    }

    if (shadowDialog) ShadowDialog(
        hasExisting = hasShadow,
        onDone = {
            shadowDialog = false
            hasShadow = Hub.hasShadowPassphrase
        },
    )
}

@Composable
private fun DeriveKeyDialog(
    kind: String,
    currentNick: String,
    onDone: () -> Unit,
    onDerived: (String?) -> Unit,
) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    var pass by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var withNick by remember { mutableStateOf(currentNick.isNotBlank()) }
    var busy by remember { mutableStateOf(false) }
    val ok = pass.length >= 8 && pass == again && (!withNick || currentNick.isNotBlank())

    AlertDialog(
        onDismissRequest = onDone,
        containerColor = p.surface,
        title = { Text("Derive ${kind.uppercase()} key from password", style = Type.title.copy(fontSize = Type.body.fontSize * 1.15f), color = p.text) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Argon2id over the password and this phone's ID: the same inputs give the same key.",
                    style = Type.small,
                    color = p.muted,
                )
                Field("Password (at least 8 characters)") {
                    PrivateField(pass, { pass = it }, Modifier.fillMaxWidth(), password = true, focus = true)
                }
                Field("Again") {
                    PrivateField(again, { again = it }, Modifier.fillMaxWidth(), password = true)
                }
                if (currentNick.isNotBlank()) {
                    Toggle("Bind to nick ($currentNick)", "The nick is part of the input", withNick) { withNick = it }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = ok && !busy, onClick = {
                busy = true
                scope.launch {
                    val err = Hub.makeIdentity(kind, password = pass, withNick = withNick)
                    pass = ""
                    again = ""
                    onDerived(err)
                }
            }) {
                Text(if (busy) "Deriving…" else "Derive key", color = p.accent, style = Type.small.copy(fontWeight = FontWeight.SemiBold))
            }
        },
        dismissButton = {
            TextButton(onClick = onDone) { Text("Cancel", color = p.muted, style = Type.small) }
        },
    )
}

@Composable
private fun ExportKeyDialog(
    secret: String,
    kind: String,
    onSaveFile: () -> Unit,
    onDismiss: () -> Unit,
) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = p.surface,
        title = { Text("Secret ${kind.uppercase()} key", style = Type.title.copy(fontSize = Type.body.fontSize * 1.15f), color = p.text) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Unencrypted. Anyone with it can sign as you.",
                    style = Type.small,
                    color = p.warn,
                )
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(p.bg)
                        .border(1.dp, p.line, RoundedCornerShape(8.dp)).padding(10.dp),
                ) {
                    Text(secret, style = Type.label, color = p.text)
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = { copySecret(ctx, "signing secret key", secret) }) {
                    Text("Copy", color = p.accent, style = Type.small.copy(fontWeight = FontWeight.SemiBold))
                }
                TextButton(onClick = onSaveFile) {
                    Text("Save as…", color = p.accent, style = Type.small.copy(fontWeight = FontWeight.SemiBold))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close", color = p.muted, style = Type.small) }
        },
    )
}

@Composable
private fun ShadowDialog(hasExisting: Boolean, onDone: () -> Unit) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    var pass by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val ok = pass.length >= 8 && pass == again

    AlertDialog(
        onDismissRequest = onDone,
        containerColor = p.surface,
        title = { Text("Shadow passphrase", style = Type.title.copy(fontSize = Type.body.fontSize * 1.15f), color = p.text) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Entering it at unlock erases the vault and opens an empty one.",
                    style = Type.small,
                    color = p.muted,
                )
                Field("Shadow passphrase (at least 8 characters)") {
                    PrivateField(pass, { pass = it }, Modifier.fillMaxWidth(), password = true, focus = true)
                }
                Field("Again") {
                    PrivateField(again, { again = it }, Modifier.fillMaxWidth(), password = true)
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (hasExisting) {
                    TextButton(enabled = !busy, onClick = {
                        busy = true
                        scope.launch {
                            Hub.setShadowPassphrase(null)
                            onDone()
                        }
                    }) { Text("Remove", color = p.bad, style = Type.small) }
                }
                TextButton(enabled = ok && !busy, onClick = {
                    busy = true
                    scope.launch {
                        Hub.setShadowPassphrase(pass.toCharArray())
                        pass = ""
                        again = ""
                        onDone()
                    }
                }) {
                    Text(if (busy) "Saving…" else "Save", color = p.accent, style = Type.small.copy(fontWeight = FontWeight.SemiBold))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDone) { Text("Cancel", color = p.muted, style = Type.small) }
        },
    )
}

@Composable
private fun Swatch(hex: String?, selected: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    val c = hex?.let { parseColour(it) }?.let { Color(it) }
    Box(
        Modifier.size(28.dp).clip(CircleShape).background(c ?: p.raised)
            .border(if (selected) 2.dp else 1.dp, if (selected) p.text else p.line, CircleShape)
            .clickable(onClick = onClick),
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
                    pass = ""
                    again = ""
                    onDone()
                }
            }) { Text(if (busy) "Sealing…" else "Seal it this way", color = p.accent, style = Type.small.copy(fontWeight = FontWeight.SemiBold)) }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel", color = p.muted, style = Type.small) } },
    )
}
