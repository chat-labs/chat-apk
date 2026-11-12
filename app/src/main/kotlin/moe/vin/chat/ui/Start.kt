package moe.vin.chat.ui

import android.app.Activity
import android.app.KeyguardManager
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
import androidx.compose.ui.platform.LocalContext
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
        Vault.Mode.BOTH -> "Passphrase and hardware key" to
            "Passphrase on every open, and a key in this phone's secure hardware. A copy of the files can't be opened on another device."
        Vault.Mode.DEVICE -> "Hardware key only" to
            "No passphrase. Opens whenever the phone is unlocked."
        Vault.Mode.PASSPHRASE -> "Passphrase only" to
            "Passphrase on every open. A copy of the files opens anywhere with it."
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
    var route by remember { mutableStateOf("tor") }
    var busy by remember { mutableStateOf(false) }
    val problem = when {
        !mode.needsPassphrase -> null
        pass.length < PASSPHRASE_MIN -> "at least $PASSPHRASE_MIN characters"
        pass != again -> "doesn't match"
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
            "Serverless encrypted chat, direct or over Tor. Nothing is stored unless you keep it; what you keep is encrypted.",
            style = Type.body, color = p.muted,
        )
        Spacer(Modifier.height(28.dp))
        Text("VAULT", style = Type.section, color = p.faint)
        Spacer(Modifier.height(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for (m in Vault.Mode.entries) Protection(m, m == mode) { mode = m }
        }
        if (mode.needsPassphrase) {
            Spacer(Modifier.height(22.dp))
            Field("Passphrase") { PrivateField(pass, { pass = it }, Modifier.fillMaxWidth(), password = true) }
            Spacer(Modifier.height(12.dp))
            Field("Again") { PrivateField(again, { again = it }, Modifier.fillMaxWidth(), password = true) }
            if (problem != null && pass.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(problem, style = Type.small, color = p.faint)
            }
            Spacer(Modifier.height(6.dp))
            Text("Unrecoverable if forgotten.", style = Type.small, color = p.faint)
        }
        Spacer(Modifier.height(22.dp))
        Field("Nick") { PrivateField(nick, { nick = it }, Modifier.fillMaxWidth(), placeholder = "blank: random per room") }
        Spacer(Modifier.height(22.dp))
        Text("ROUTE", style = Type.section, color = p.faint)
        Spacer(Modifier.height(10.dp))
        Choice(listOf("udp" to "Direct", "tor" to "Tor"), route, { route = it })
        Spacer(Modifier.height(8.dp))
        Text(
            if (route == "udp") "UDP, peers found via the BitTorrent DHT and LAN. Peers see your IP address."
            else "Tor onion services. Hides your IP address; slower to connect.",
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
fun UnlockScreen(uiLock: Boolean = false) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val mode = remember(uiLock) { Hub.vault.mode }
    var pass by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var erasing by remember { mutableStateOf(false) }

    val deviceCredLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            scope.launch { runCatching { Hub.unlockUi(null) } }
        }
    }

    fun open() {
        if (uiLock && mode?.needsPassphrase != true) {
            val km = ctx.getSystemService(KeyguardManager::class.java)
            @Suppress("DEPRECATION")
            val intent = if (km?.isDeviceSecure == true) km.createConfirmDeviceCredentialIntent("chat", "Unlock chat") else null
            if (intent != null) {
                deviceCredLauncher.launch(intent)
                return
            }
        }
        busy = true
        error = null
        scope.launch {
            try {
                val chars = if (mode?.needsPassphrase == true) pass.toCharArray() else null
                if (uiLock) Hub.unlockUi(chars) else Hub.unlock(chars)
            } catch (e: Vault.WrongPassphrase) {
                // The tries left before self-destruct stay unsaid: they'd tell whoever is typing it's on.
                error = "Wrong passphrase."
            } catch (e: Vault.VaultDestroyed) {
                // Setup follows, with nothing saying why.
            } catch (e: Exception) {
                error = "Hardware key failed: ${e.javaClass.simpleName}${e.message?.let { " ($it)" } ?: ""}"
            }
            pass = ""
            busy = false
        }
    }

    LaunchedEffect(uiLock) {
        if (!uiLock && mode?.needsPassphrase == false) open()
    }

    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .imePadding()
            .wrapContentWidth()
            .widthIn(max = 400.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("chat", style = Type.title.copy(fontSize = Type.title.fontSize * 1.4f), color = p.text)
        Spacer(Modifier.height(8.dp))
        Text(
            when {
                uiLock -> "Locked."
                mode?.needsPassphrase == true -> "Sealed."
                else -> "Opening…"
            },
            style = Type.body,
            color = p.muted,
        )
        if (mode?.needsPassphrase == true) {
            Spacer(Modifier.height(22.dp))
            Field("Passphrase") {
                PrivateField(
                    pass,
                    { pass = it },
                    Modifier.fillMaxWidth(),
                    password = true,
                    onDone = { if (pass.isNotEmpty()) open() },
                    focus = true,
                )
            }
        }
        error?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, style = Type.small, color = p.bad)
        }
        Spacer(Modifier.height(18.dp))
        Button(
            if (busy) "Opening…" else if (uiLock) "Unlock" else "Open",
            enabled = !busy && (mode?.needsPassphrase != true || pass.isNotEmpty()),
            modifier = Modifier.fillMaxWidth(),
            onClick = ::open,
        )
        Spacer(Modifier.height(10.dp))
        Row(Modifier.align(Alignment.CenterHorizontally), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (uiLock) {
                TextButton(onClick = { Hub.quit() }) {
                    Text("Quit & seal", style = Type.small, color = p.muted)
                }
                if (Hub.settings.panicButton) {
                    TextButton(onClick = { Hub.panic() }) {
                        Text("Panic", style = Type.small, color = p.bad)
                    }
                }
            }
            TextButton(onClick = { erasing = true }) {
                Text("Erase everything", style = Type.small, color = p.faint)
            }
        }
    }
    if (erasing) Confirm(
        title = "Erase everything?",
        text = "Deletes the vault (kept rooms, histories, settings, signing key) and received files.",
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
