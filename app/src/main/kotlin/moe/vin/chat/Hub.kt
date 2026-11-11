package moe.vin.chat

import android.content.Context
import android.content.Intent
import android.provider.Settings as AndroidSettings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import moe.vin.chat.engine.ChatProcess
import moe.vin.chat.engine.Cmd
import moe.vin.chat.engine.Event
import moe.vin.chat.engine.Failure
import moe.vin.chat.engine.IdentityKey
import moe.vin.chat.engine.Line
import moe.vin.chat.engine.Notify
import moe.vin.chat.engine.Room
import moe.vin.chat.engine.RoomHost
import moe.vin.chat.engine.RoomInfo
import moe.vin.chat.engine.Status
import moe.vin.chat.engine.Suggestion
import moe.vin.chat.engine.Tor
import moe.vin.chat.service.ChatService
import moe.vin.chat.service.Notifications
import moe.vin.chat.store.IdentityEntry
import moe.vin.chat.store.RoomEntry
import moe.vin.chat.store.Saved
import moe.vin.chat.store.Settings
import moe.vin.chat.store.TrustedKey
import moe.vin.chat.store.Vault
import moe.vin.chat.store.lineFromJson
import moe.vin.chat.store.lineToJson
import moe.vin.chat.ui.clearClipboard
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume

// The app's state: the vault and what it keeps, the rooms open now, and the tor they share.
object Hub : RoomHost {
    enum class Phase { SETUP, LOCKED, READY }

    lateinit var app: Context
        private set
    lateinit var vault: Vault
        private set
    lateinit var tor: Tor
        private set

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @OptIn(ExperimentalCoroutinesApi::class)
    private val io = Dispatchers.IO.limitedParallelism(1)
    private val ioScope = CoroutineScope(SupervisorJob() + io)
    private var tickerJob: Job? = null

    var phase by mutableStateOf(Phase.SETUP)
        private set
    var uiLocked by mutableStateOf(false)
        private set
    var settings by mutableStateOf(Settings())
        private set
    var identity by mutableStateOf<IdentityEntry?>(null)
        private set
    val kept = mutableStateListOf<RoomEntry>()
    val verifiedKeys = mutableStateListOf<TrustedKey>()
    val ignoredKeys = mutableStateListOf<TrustedKey>()
    val rooms = mutableStateMapOf<String, Room>()
    var foreground = false
    var pictureMax = 1024

    @Volatile private var lastActiveAt = System.currentTimeMillis()

    private val exe get() = File(app.applicationInfo.nativeLibraryDir, "libchat.so")

    fun init(context: Context) {
        app = context.applicationContext
        vault = Vault(File(app.noBackupFilesDir, "vault"))
        tor = Tor(app)
        phase = if (vault.exists) Phase.LOCKED else Phase.SETUP
        Notifications.channels(app)
        startTicker()
    }

    fun touchActivity() {
        lastActiveAt = System.currentTimeMillis()
    }

    private fun startTicker() {
        if (tickerJob != null) return
        tickerJob = scope.launch {
            while (true) {
                delay(5_000L)
                if (phase != Phase.READY) continue
                val now = System.currentTimeMillis()
                val autoLock = settings.autoLockSeconds
                if (!uiLocked && autoLock > 0 && now - lastActiveAt >= autoLock * 1000L) {
                    lockUi()
                }
                val today = currentUtcDay()
                for (room in rooms.values.toList()) {
                    val secs = room.timer?.seconds ?: 0
                    if (secs > 0) {
                        val cutoff = now - secs * 1000L
                        room.pruneBefore(cutoff)
                        if (room.history && kept.any { it.id == room.id }) {
                            pruneVaultHistory(room.id, cutoff)
                        }
                    }
                    val peer = room.callPeer
                    if (peer != null && room.status == Status.OPEN && room.callDay > 0L &&
                        room.callDay != today && (room.net?.online ?: 0) == 0
                    ) {
                        rolloverCall(room, peer, today)
                    }
                }
            }
        }
    }

    // ---- the vault ----

    suspend fun setup(mode: Vault.Mode, passphrase: CharArray?, first: Settings) {
        val snapshot = Saved(first, null, emptyList(), emptyList(), emptyList()).toJson().toString().toByteArray()
        try {
            withContext(io) {
                vault.create(mode, passphrase)
                if (first.destroyTries > 0) vault.setDestroyLimit(first.destroyTries)
                vault.write(DATA, snapshot)
            }
        } finally {
            passphrase?.fill('\u0000')
        }
        settings = first
        identity = null
        kept.clear()
        verifiedKeys.clear()
        ignoredKeys.clear()
        uiLocked = false
        touchActivity()
        phase = Phase.READY
    }

    // Throws Vault.WrongPassphrase (with triesLeft) or Vault.VaultDestroyed.
    suspend fun unlock(passphrase: CharArray?) {
        val (outcome, saved) = try {
            withContext(io) {
                val out = vault.unlock(passphrase)
                val s = Saved.from(vault.read(DATA)?.let { JSONObject(String(it)) })
                if (out == Vault.UnlockOutcome.SHADOW) {
                    wipeSideDirs()
                }
                out to s
            }
        } catch (destroyed: Vault.VaultDestroyed) {
            wipeAfterDestroy()
            throw destroyed
        } finally {
            passphrase?.fill('\u0000')
        }
        settings = saved.settings
        identity = saved.identity
        kept.clear()
        kept += saved.rooms
        verifiedKeys.clear()
        verifiedKeys += saved.verified
        ignoredKeys.clear()
        ignoredKeys += saved.ignored
        uiLocked = false
        touchActivity()
        phase = Phase.READY
        if (outcome == Vault.UnlockOutcome.NORMAL && settings.reopenKept) {
            kept.toList().forEach { open(it) }
        }
    }

    // Locks the screens without disconnecting open rooms or Tor.
    fun lockUi() {
        if (phase != Phase.READY || uiLocked) return
        uiLocked = true
        clearClipboard(app)
    }

    suspend fun unlockUi(passphrase: CharArray?) {
        val outcome = try {
            withContext(io) { vault.checkUiUnlock(passphrase) }
        } catch (destroyed: Vault.VaultDestroyed) {
            eraseEverything()
            throw destroyed
        } finally {
            passphrase?.fill('\u0000')
        }
        when (outcome) {
            Vault.UnlockOutcome.NORMAL -> {
                uiLocked = false
                touchActivity()
            }
            Vault.UnlockOutcome.SHADOW -> {
                // Duress while running: kill active sessions immediately and present the empty decoy vault.
                rooms.values.forEach { it.process?.kill() }
                rooms.clear()
                tor.stop(immediate = true)
                Notifications.clearAll(app)
                clearClipboard(app)
                withContext(io) { wipeSideDirs() }
                val decoy = withContext(io) { Saved.from(vault.read(DATA)?.let { JSONObject(String(it)) }) }
                settings = decoy.settings
                identity = decoy.identity
                kept.clear()
                verifiedKeys.clear()
                ignoredKeys.clear()
                uiLocked = false
                touchActivity()
                app.stopService(Intent(app, ChatService::class.java))
            }
        }
    }

    suspend fun changeProtection(mode: Vault.Mode, passphrase: CharArray?) {
        try {
            withContext(io) { vault.changeMode(mode, passphrase) }
        } finally {
            passphrase?.fill('\u0000')
        }
    }

    suspend fun setShadowPassphrase(passphrase: CharArray?) {
        try {
            withContext(io) { vault.setShadow(passphrase) }
        } finally {
            passphrase?.fill('\u0000')
        }
    }

    val hasShadowPassphrase: Boolean
        get() = vault.hasShadow

    private fun save() {
        val bytes = Saved(
            settings = settings,
            identity = identity,
            rooms = kept.toList(),
            verified = verifiedKeys.toList(),
            ignored = ignoredKeys.toList(),
        ).toJson().toString().toByteArray()
        ioScope.launch {
            if (vault.unlocked) vault.write(DATA, bytes)
        }
    }

    fun updateSettings(s: Settings) {
        val old = settings
        settings = s
        save()
        if (s.destroyTries != old.destroyTries) {
            ioScope.launch { if (vault.unlocked) vault.setDestroyLimit(s.destroyTries) }
        }
        if (!s.torKeepState && old.torKeepState) {
            ioScope.launch { if (vault.unlocked) vault.delete(TOR_STATE) }
        }
        val change = JSONObject()
        if (s.nick != old.nick && s.nick.isNotBlank()) change.put("nick", s.nick)
        if (s.colour != old.colour && s.colour.isNotBlank()) change.put("colour", s.colour)
        if (s.verify != old.verify) change.put("verify", s.verify)
        if (s.netLog != old.netLog) change.put("net_log", s.netLog)
        if (s.notify != old.notify || s.preview != old.preview) {
            change.put("notify", JSONObject().put("mode", s.notify).put("preview", s.preview))
        }
        if (s.fileLimitMb != old.fileLimitMb || s.fastFiles != old.fastFiles || s.noise != old.noise) {
            change.put("files", JSONObject()
                .put("limit", s.fileLimitMb * 1024L * 1024L)
                .put("fast", s.fastFiles)
                .put("noise", s.noise))
        }
        if (s.dht != old.dht || s.ipv6 != old.ipv6 || s.lan != old.lan ||
            s.portmap != old.portmap || s.nostr != old.nostr || s.relays != old.relays
        ) {
            val route = JSONObject()
                .put("dht", s.dht)
                .put("ipv6", s.ipv6)
                .put("lan", s.lan)
                .put("portmap", s.portmap)
                .put("nostr", s.nostr)
                .put("relays", JSONArray(s.relayList()))
            change.put("route", route)
        }
        if (change.length() > 0) {
            val text = change.toString()
            rooms.values.forEach { it.process?.send(Cmd.settings(JSONObject(text))) }
        }
    }

    // Quits every room, stops tor once they've said goodbye over it, and seals the vault again.
    fun quit() {
        clearClipboard(app)
        Notifications.clearAll(app)
        rooms.values.toList().forEach { it.quit(kept.any { k -> k.id == it.id }) }
        scope.launch {
            withTimeoutOrNull(4000) { while (rooms.values.any { it.process != null }) delay(80) }
            rooms.values.forEach { it.process?.kill() }
            rooms.clear()
            tor.stop()
            // Give Tor a moment to pack state into the vault if torKeepState is enabled before sealing.
            if (settings.torKeepState) delay(350)
            withContext(io) { vault.lock() }
            kept.clear()
            verifiedKeys.clear()
            ignoredKeys.clear()
            identity = null
            uiLocked = false
            phase = if (vault.exists) Phase.LOCKED else Phase.SETUP
            app.stopService(Intent(app, ChatService::class.java))
        }
    }

    // Emergency shutoff: kills processes on the spot without network goodbyes, clears clipboard &
    // notifications, and either locks or erases the vault depending on settings.panicWipe.
    fun panic() {
        val wipe = settings.panicWipe
        clearClipboard(app)
        Notifications.clearAll(app)
        rooms.values.forEach { it.process?.kill() }
        rooms.clear()
        tor.stop(immediate = true)
        File(app.cacheDir, "outbox").deleteRecursively()
        File(app.cacheDir, "ask").deleteRecursively()
        if (wipe) {
            eraseEverything()
        } else {
            ioScope.launch { vault.lock() }
            kept.clear()
            verifiedKeys.clear()
            ignoredKeys.clear()
            identity = null
            uiLocked = false
            phase = if (vault.exists) Phase.LOCKED else Phase.SETUP
            app.stopService(Intent(app, ChatService::class.java))
        }
    }

    fun eraseEverything() {
        clearClipboard(app)
        Notifications.clearAll(app)
        rooms.values.forEach { it.process?.kill() }
        rooms.clear()
        tor.stop(immediate = true)
        ioScope.launch {
            vault.erase()
            wipeSideDirs()
        }
        wipeSideDirs()
        kept.clear()
        verifiedKeys.clear()
        ignoredKeys.clear()
        identity = null
        settings = Settings()
        uiLocked = false
        phase = Phase.SETUP
        app.stopService(Intent(app, ChatService::class.java))
    }

    private fun wipeAfterDestroy() {
        clearClipboard(app)
        Notifications.clearAll(app)
        wipeSideDirs()
        kept.clear()
        verifiedKeys.clear()
        ignoredKeys.clear()
        identity = null
        settings = Settings()
        uiLocked = false
        phase = Phase.SETUP
    }

    private fun wipeSideDirs() {
        File(app.filesDir, "received").deleteRecursively()
        File(app.noBackupFilesDir, "rooms").deleteRecursively()
        File(app.noBackupFilesDir, "tor").deleteRecursively()
        File(app.cacheDir, "outbox").deleteRecursively()
        File(app.cacheDir, "tor").deleteRecursively()
        File(app.cacheDir, "ask").deleteRecursively()
    }

    // ---- the signing identity ----

    private fun androidDeviceId(): String =
        runCatching { AndroidSettings.Secure.getString(app.contentResolver, AndroidSettings.Secure.ANDROID_ID) }
            .getOrNull().orEmpty().ifBlank { "android-device" }

    // One command to a chat-json of its own, outside any room: its answer, the first event `pick`
    // takes, or what went wrong.
    private suspend fun <T : Any> ask(cmd: JSONObject, pick: (Event) -> T?): Answer<T> {
        val home = File(app.cacheDir, "ask/${UUID.randomUUID()}").apply { mkdirs() }
        return try {
            withContext(Dispatchers.IO) {
                suspendCancellableCoroutine { cont ->
                    var done = false
                    lateinit var p: ChatProcess
                    p = ChatProcess(exe, home, onEvent = { e ->
                        val got = if (done) null else pick(e)
                        if (!done && (got != null || e is Failure)) {
                            done = true
                            p.send(Cmd.quit(false))
                            p.close()
                            cont.resume(if (got != null) Answer.Ok(got) else Answer.Err((e as Failure).text))
                        }
                    }, onExit = { _, err ->
                        if (!done) {
                            done = true
                            cont.resume(Answer.Err(err.lastOrNull()?.removePrefix("chat: ") ?: "chat didn't start"))
                        }
                    })
                    p.start(listOf(cmd))
                }
            }
        } finally {
            home.deleteRecursively()
        }
    }

    private suspend fun runIdentityCmd(cmd: JSONObject) = ask(cmd) { it as? IdentityKey }

    private sealed interface Answer<out T> {
        data class Ok<T>(val value: T) : Answer<T>
        data class Err(val message: String) : Answer<Nothing>
    }

    // Asks chat for a new key, a password-derived key, or to read an imported one.
    suspend fun makeIdentity(
        kind: String,
        key: String? = null,
        password: String? = null,
        withNick: Boolean = true,
    ): String? {
        val nick = settings.nick
        val cmd = when {
            password != null -> Cmd.deriveIdentity(kind, password, androidDeviceId(), withNick, nick)
            key != null -> Cmd.identity(kind, key)
            else -> Cmd.newIdentity(kind)
        }
        return when (val r = runIdentityCmd(cmd)) {
            is Answer.Ok -> {
                val k = r.value
                val entry = IdentityEntry(
                    kind = k.kind,
                    secret = k.secret ?: key.orEmpty(),
                    public = k.public.orEmpty(),
                    fingerprint = k.fingerprint,
                    created = k.created,
                    derived = password != null,
                    withNick = withNick,
                    derivedNick = if (password != null && withNick) nick else "",
                )
                identity = entry
                save()
                val live = Cmd.identity(entry.kind, entry.secret, entry.created).toString()
                rooms.values.forEach { it.process?.send(JSONObject(live)) }
                null
            }
            is Answer.Err -> r.message
        }
    }

    suspend fun exportIdentitySecret(): Pair<String?, String?> {
        val id = identity ?: return null to "no signing key"
        if (id.secret.startsWith("AGE-SECRET-KEY-") || id.secret.startsWith("-----BEGIN PGP PRIVATE KEY BLOCK-----")) {
            return id.secret to null
        }
        return when (val r = runIdentityCmd(Cmd.identity(id.kind, id.secret, id.created, export = true))) {
            is Answer.Ok -> (r.value.secret ?: id.secret) to null
            is Answer.Err -> null to r.message
        }
    }

    fun dropIdentity() {
        identity = null
        save()
        rooms.values.forEach { it.process?.send(Cmd.dropIdentity()) }
    }

    // ---- verified & ignored keys ----

    override fun trustChanged(room: Room, list: List<TrustedKey>) {
        if (sameKeys(verifiedKeys, list)) return
        verifiedKeys.clear()
        verifiedKeys += list
        save()
        val msg = Cmd.trust(list).toString()
        rooms.values.forEach { if (it !== room) it.process?.send(JSONObject(msg)) }
    }

    override fun ignoredChanged(room: Room, list: List<TrustedKey>) {
        if (sameKeys(ignoredKeys, list)) return
        ignoredKeys.clear()
        ignoredKeys += list
        save()
        val msg = Cmd.ignored(list).toString()
        rooms.values.forEach { if (it !== room) it.process?.send(JSONObject(msg)) }
    }

    fun forgetVerified(pub: String) {
        if (!verifiedKeys.removeAll { it.pub == pub }) return
        save()
        val list = verifiedKeys.toList()
        val msg = Cmd.trust(list).toString()
        rooms.values.forEach { it.process?.send(JSONObject(msg)) }
    }

    fun forgetAllVerified() {
        if (verifiedKeys.isEmpty()) return
        verifiedKeys.clear()
        save()
        val msg = Cmd.trust(emptyList()).toString()
        rooms.values.forEach { it.process?.send(JSONObject(msg)) }
    }

    fun unignoreKey(pub: String) {
        if (!ignoredKeys.removeAll { it.pub == pub }) return
        save()
        val list = ignoredKeys.toList()
        val msg = Cmd.ignored(list).toString()
        rooms.values.forEach { it.process?.send(JSONObject(msg)) }
    }

    private fun sameKeys(a: List<TrustedKey>, b: List<TrustedKey>): Boolean {
        if (a.size != b.size) return false
        for (i in a.indices) {
            if (a[i].source != b[i].source || a[i].pub != b[i].pub || a[i].nick != b[i].nick) return false
        }
        return true
    }

    // ---- rooms & calls ----

    fun roomFor(entry: RoomEntry) = rooms[entry.id]

    // Invites waiting for their room's start, by room: chat checks and reads them.
    private val invites = HashMap<String, String>()

    // A room to create, or join by its id and password or an invite. keep: it goes in the vault.
    // A new room's id and password, as chat makes them up, for the new room form; null if chat
    // didn't answer.
    suspend fun suggestRoom(): Suggestion? = (ask(Cmd.suggest()) { it as? Suggestion } as? Answer.Ok)?.value

    fun start(
        room: String?,
        password: String?,
        invite: String?,
        label: String,
        route: String,
        history: Boolean,
        keep: Boolean,
        port: Int = 0,
        peers: String = "",
        create: Boolean = room == null && invite == null,
    ): Room {
        val entry = RoomEntry(
            room = room.orEmpty(),
            password = password.orEmpty(),
            label = label,
            route = route,
            history = history,
            created = create,
            port = port,
            peers = peers,
        )
        if (invite != null) invites[entry.id] = invite
        return open(entry, keepIt = keep)
    }

    fun open(entry: RoomEntry, keepIt: Boolean = true): Room {
        rooms[entry.id]?.let { if (it.status == Status.OPEN || it.status == Status.STARTING) return it }
        val room = Room(entry.id, entry.label, this)
        room.history = entry.history
        rooms[entry.id] = room
        if (keepIt && kept.none { it.id == entry.id }) {
            kept += entry
            save()
        }
        ChatService.start(app)
        scope.launch {
            if (entry.history && vault.unlocked) {
                val old = withContext(io) { loadHistory(entry.id) }
                if (old.isNotEmpty()) room.loadHistory(old)
            }
            if (entry.route == "tor") {
                startTor()
                withTimeoutOrNull(30_000) {
                    while (!tor.state.listening && tor.state.phase != Tor.Phase.FAILED) delay(100)
                }
            }
            val home = File(app.noBackupFilesDir, "rooms/${entry.id}")
            val cmd = startCommand(entry, callPeer = null, callDay = 0L)
            val p = ChatProcess(exe, home, onEvent = room::handle, onExit = room::exited)
            room.process = p
            p.start(listOf(cmd))
        }
        return room
    }

    // Shared daily contact room derived from our signing key and a verified peer's public key (:call).
    fun call(peer: TrustedKey, route: String = "udp"): Room? {
        if (identity == null) return null
        rooms.values.firstOrNull {
            it.callPeer?.pub == peer.pub && (it.status == Status.OPEN || it.status == Status.STARTING)
        }?.let { return it }
        val id = UUID.randomUUID().toString()
        val day = currentUtcDay()
        val room = Room(id, "@${peer.nick}", this)
        room.callPeer = peer
        room.callDay = day
        rooms[id] = room
        ChatService.start(app)
        val entry = RoomEntry(id = id, room = "", password = "", label = "@${peer.nick}", route = route, history = false, created = false)
        scope.launch {
            if (route == "tor") {
                startTor()
                withTimeoutOrNull(30_000) {
                    while (!tor.state.listening && tor.state.phase != Tor.Phase.FAILED) delay(100)
                }
            }
            val home = File(app.noBackupFilesDir, "rooms/$id")
            val cmd = startCommand(entry, callPeer = peer, callDay = day)
            val p = ChatProcess(exe, home, onEvent = room::handle, onExit = room::exited)
            room.process = p
            p.start(listOf(cmd))
        }
        return room
    }

    private fun rolloverCall(old: Room, peer: TrustedKey, newDay: Long) {
        val route = old.info?.route ?: "udp"
        old.callPeer = null
        old.quit(false)
        call(peer, route)
    }

    fun close(room: Room) {
        val isKept = kept.any { it.id == room.id }
        room.quit(isKept)
    }

    fun forget(entry: RoomEntry) {
        rooms[entry.id]?.quit(false)
        kept.removeAll { it.id == entry.id }
        save()
        ioScope.launch {
            if (vault.unlocked) vault.delete(historyName(entry.id))
            File(app.filesDir, "received/${entry.id}").deleteRecursively()
            File(app.noBackupFilesDir, "rooms/${entry.id}").deleteRecursively()
        }
    }

    fun clearRoomHistory(room: Room) {
        room.clearHistoryItems()
        ioScope.launch {
            if (vault.unlocked) vault.delete(historyName(room.id))
        }
    }

    fun keepRoom(room: Room, history: Boolean) {
        val info = room.info ?: return
        if (room.isCall || kept.any { it.id == room.id }) return
        kept += RoomEntry(
            id = room.id,
            room = info.room,
            password = info.password,
            label = room.label,
            route = info.route,
            history = history,
            created = info.created,
            port = info.port ?: 0,
        )
        save()
        if (history != room.history) room.changeHistory(history)
    }

    fun rename(room: Room, label: String) {
        room.label = label
        val i = kept.indexOfFirst { it.id == room.id }
        if (i >= 0) {
            kept[i] = kept[i].copy(label = label)
            save()
        }
    }

    fun setHistory(room: Room, on: Boolean) {
        room.changeHistory(on)
        val i = kept.indexOfFirst { it.id == room.id }
        if (i >= 0) {
            kept[i] = kept[i].copy(history = on)
            save()
        }
        if (!on) ioScope.launch { if (vault.unlocked) vault.delete(historyName(room.id)) }
    }

    fun receivedDir(id: String) = File(app.filesDir, "received/$id")
    fun outboxDir(id: String) = File(app.cacheDir, "outbox/$id")

    private fun startCommand(entry: RoomEntry, callPeer: TrustedKey?, callDay: Long): JSONObject {
        val s = settings
        val o = JSONObject().put("cmd", "start")
        val invite = invites.remove(entry.id)
        when {
            callPeer != null -> o.put("call", callPeer.pub).put("day", callDay)
            invite != null -> o.put("invite", invite)
            entry.room.isNotEmpty() -> o.put("create", entry.created).put("room", entry.room).apply {
                // None for a new room: chat makes one up.
                if (entry.password.isNotEmpty()) put("password", entry.password)
            }
            else -> o.put("create", true).apply { if (entry.password.isNotEmpty()) put("password", entry.password) }
        }
        if (s.nick.isNotBlank()) o.put("nick", s.nick)
        if (s.colour.isNotBlank()) o.put("colour", s.colour)
        val route = JSONObject().put("mode", entry.route).put("nostr", s.nostr)
        val relays = s.relayList()
        if (relays.isNotEmpty()) route.put("relays", JSONArray(relays))
        if (entry.route == "tor") {
            route.put("tor", JSONObject().put("socks", tor.state.socks).put("control", tor.state.control).put("launch", "never"))
        } else {
            route.put("dht", s.dht).put("ipv6", s.ipv6).put("lan", s.lan).put("portmap", s.portmap)
            if (entry.port in 1..65535) route.put("port", entry.port)
            val peers = entry.peerList()
            if (peers.isNotEmpty()) route.put("peers", JSONArray(peers))
        }
        o.put("route", route)
        o.put("verify", if (callPeer != null) "required" else s.verify)
        o.put("net_log", s.netLog)
        o.put("notify", JSONObject().put("mode", s.notify).put("preview", s.preview))
        o.put("files", JSONObject()
            .put("dir", receivedDir(entry.id).apply { mkdirs() }.path)
            .put("limit", s.fileLimitMb * 1024L * 1024L)
            .put("fast", s.fastFiles)
            .put("noise", s.noise))
        o.put("picture", JSONObject().put("max", pictureMax).put("background", "#000000"))
        o.put("history", entry.history)
        o.put("kept", callPeer == null && kept.any { it.id == entry.id })
        o.put("trust", JSONArray().apply { verifiedKeys.forEach { put(it.toJson()) } })
        o.put("ignored", JSONArray().apply { ignoredKeys.forEach { put(it.toJson()) } })
        identity?.let {
            o.put("identity", JSONObject().put("kind", it.kind).put("key", it.secret).also { j ->
                if (it.created > 0L) j.put("created", it.created)
            })
        }
        return o
    }

    private suspend fun startTor() {
        if (tor.state.phase == Tor.Phase.STARTING || tor.state.phase == Tor.Phase.READY) return
        val s = settings
        val bridges = when (s.bridges) {
            "custom" -> s.customBridges.split(Regex("[\\r\\n;]+"))
                .map { it.trim().removePrefix("Bridge ").trim() }
                .filter { it.isNotEmpty() }
            "none" -> emptyList()
            else -> tor.builtInBridges(s.bridges)
        }
        val savedState = if (s.torKeepState && vault.unlocked) {
            withContext(io) { vault.read(TOR_STATE) }
        } else null
        tor.start(
            bridges = bridges,
            keepState = s.torKeepState,
            savedState = savedState,
            onSaveState = { packed ->
                ioScope.launch { if (vault.unlocked && settings.torKeepState) vault.write(TOR_STATE, packed) }
            },
        )
    }

    fun restartTor() {
        tor.stop()
        if (rooms.values.any { it.info?.route == "tor" }) {
            scope.launch { startTor() }
        }
    }

    // ---- what rooms tell the hub ----

    private fun historyName(id: String) = "history/$id"

    private fun loadHistory(id: String): List<Pair<Line, Long>> =
        vault.records(historyName(id)).mapNotNull { runCatching { lineFromJson(JSONObject(String(it))) }.getOrNull() }

    private fun pruneVaultHistory(id: String, cutoff: Long) {
        ioScope.launch {
            if (!vault.unlocked) return@launch
            val name = historyName(id)
            val records = vault.records(name)
            if (records.isEmpty()) return@launch
            val keep = records.filter { runCatching { JSONObject(String(it)).optLong("time") >= cutoff }.getOrDefault(false) }
            if (keep.size != records.size) vault.rewrite(name, keep)
        }
    }

    override fun keep(room: Room, line: Line, time: Long) {
        if (kept.none { it.id == room.id }) return
        val bytes = lineToJson(line, time).toString().toByteArray()
        ioScope.launch {
            if (vault.unlocked) vault.append(historyName(room.id), bytes)
        }
    }

    override fun notify(room: Room, n: Notify) {
        if (foreground && !uiLocked && room.visible) return
        Notifications.message(app, room, n)
    }

    // A kept room is joined, not made, from then on, and follows a :move to its new id and password.
    override fun opened(room: Room, info: RoomInfo) {
        val i = kept.indexOfFirst { it.id == room.id }
        if (i >= 0 && (kept[i].room != info.room || kept[i].password != info.password || kept[i].created)) {
            kept[i] = kept[i].copy(room = info.room, password = info.password, created = false)
            save()
        }
    }

    override fun timerChanged(room: Room, seconds: Int) {
        if (seconds <= 0) return
        val cutoff = System.currentTimeMillis() - seconds * 1000L
        room.pruneBefore(cutoff)
        pruneVaultHistory(room.id, cutoff)
    }

    override fun closed(room: Room) {
        if (room.status == Status.CLOSED) {
            rooms.remove(room.id)
            ioScope.launch {
                outboxDir(room.id).deleteRecursively()
                if (kept.none { it.id == room.id }) {
                    receivedDir(room.id).deleteRecursively()
                    File(app.noBackupFilesDir, "rooms/${room.id}").deleteRecursively()
                }
            }
        }
        if (rooms.values.none { it.info?.route == "tor" || it.status == Status.STARTING }) tor.stop()
    }

    private fun currentUtcDay(): Long = System.currentTimeMillis() / 86_400_000L

    private const val DATA = "data"
    private const val TOR_STATE = "tor-state"
}
