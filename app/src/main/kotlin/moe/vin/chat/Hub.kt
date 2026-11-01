package moe.vin.chat

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import moe.vin.chat.engine.ChatProcess
import moe.vin.chat.engine.Cmd
import moe.vin.chat.engine.Failure
import moe.vin.chat.engine.IdentityKey
import moe.vin.chat.engine.Line
import moe.vin.chat.engine.Notify
import moe.vin.chat.engine.Room
import moe.vin.chat.engine.RoomHost
import moe.vin.chat.engine.RoomInfo
import moe.vin.chat.engine.Status
import moe.vin.chat.engine.Tor
import moe.vin.chat.service.ChatService
import moe.vin.chat.service.Notifications
import moe.vin.chat.store.IdentityEntry
import moe.vin.chat.store.RoomEntry
import moe.vin.chat.store.Saved
import moe.vin.chat.store.Settings
import moe.vin.chat.store.Vault
import moe.vin.chat.store.lineFromJson
import moe.vin.chat.store.lineToJson
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

    var phase by mutableStateOf(Phase.SETUP)
        private set
    var settings by mutableStateOf(Settings())
        private set
    var identity by mutableStateOf<IdentityEntry?>(null)
        private set
    val kept = mutableStateListOf<RoomEntry>()
    val rooms = mutableStateMapOf<String, Room>()
    var foreground = false
    var pictureMax = 1024

    private val exe get() = File(app.applicationInfo.nativeLibraryDir, "libchat.so")

    fun init(context: Context) {
        app = context.applicationContext
        vault = Vault(File(app.noBackupFilesDir, "vault"))
        tor = Tor(app)
        phase = if (vault.exists) Phase.LOCKED else Phase.SETUP
        Notifications.channels(app)
    }

    // ---- the vault ----

    suspend fun setup(mode: Vault.Mode, passphrase: CharArray?, first: Settings) {
        withContext(Dispatchers.Default) { vault.create(mode, passphrase) }
        passphrase?.fill('\u0000')
        settings = first
        save()
        phase = Phase.READY
    }

    // A passphrase that doesn't open it throws Vault.WrongPassphrase.
    suspend fun unlock(passphrase: CharArray?) {
        try {
            withContext(Dispatchers.Default) { vault.unlock(passphrase) }
        } finally {
            passphrase?.fill('\u0000')
        }
        val saved = Saved.from(vault.read(DATA)?.let { JSONObject(String(it)) })
        settings = saved.settings
        identity = saved.identity
        kept.clear()
        kept += saved.rooms
        phase = Phase.READY
        if (settings.reopenKept) kept.forEach { open(it) }
    }

    suspend fun changeProtection(mode: Vault.Mode, passphrase: CharArray?) {
        withContext(Dispatchers.Default) { vault.changeMode(mode, passphrase) }
        passphrase?.fill('\u0000')
    }

    private fun save() {
        vault.write(DATA, Saved(settings, identity, kept.toList()).toJson().toString().toByteArray())
    }

    fun updateSettings(s: Settings) {
        val old = settings
        settings = s
        save()
        val change = JSONObject()
        if (s.nick != old.nick && s.nick.isNotBlank()) change.put("nick", s.nick)
        if (s.colour != old.colour && s.colour.isNotBlank()) change.put("colour", s.colour)
        if (s.verify != old.verify) change.put("verify", s.verify)
        if (s.notify != old.notify || s.preview != old.preview)
            change.put("notify", JSONObject().put("mode", s.notify).put("preview", s.preview))
        if (change.length() > 0) rooms.values.forEach { it.process?.send(Cmd.settings(JSONObject(change.toString()))) }
    }

    // Quits every room, stops tor once they've said goodbye over it, and seals the vault again.
    fun quit() {
        rooms.values.toList().forEach { it.quit(kept.any { k -> k.id == it.id }) }
        scope.launch {
            withTimeoutOrNull(5000) { while (rooms.values.any { it.process != null }) delay(100) }
            rooms.values.forEach { it.process?.kill() }
            rooms.clear()
            tor.stop()
            vault.lock()
            kept.clear()
            identity = null
            phase = if (vault.exists) Phase.LOCKED else Phase.SETUP
            app.stopService(Intent(app, ChatService::class.java))
        }
    }

    fun eraseEverything() {
        rooms.values.forEach { it.process?.kill() }
        rooms.clear()
        tor.stop()
        vault.erase()
        File(app.filesDir, "received").deleteRecursively()
        File(app.noBackupFilesDir, "rooms").deleteRecursively()
        File(app.cacheDir, "outbox").deleteRecursively()
        kept.clear()
        identity = null
        settings = Settings()
        phase = Phase.SETUP
        app.stopService(Intent(app, ChatService::class.java))
    }

    // ---- the signing identity ----

    // Asks chat for a new key, or to read one, so the app never handles the crypto itself.
    suspend fun makeIdentity(kind: String, key: String?): String? {
        val home = File(app.cacheDir, "identity").apply { deleteRecursively(); mkdirs() }
        val result = suspendCancellableCoroutine { cont ->
            var done = false
            lateinit var p: ChatProcess
            p = ChatProcess(exe, home, onEvent = { e ->
                if (!done && (e is IdentityKey || e is Failure)) {
                    done = true
                    p.send(Cmd.quit(false))
                    p.close()
                    cont.resume(e)
                }
            }, onExit = { _, err ->
                if (!done) {
                    done = true
                    cont.resume(Failure(err.lastOrNull() ?: "chat didn't start"))
                }
            })
            p.start(listOf(if (key == null) Cmd.newIdentity(kind) else Cmd.identity(kind, key)))
        }
        home.deleteRecursively()
        return when (result) {
            is IdentityKey -> {
                identity = IdentityEntry(kind, key ?: result.secret.orEmpty(), result.public.orEmpty(), result.fingerprint)
                save()
                null
            }
            is Failure -> result.text
            else -> "no answer"
        }
    }

    fun dropIdentity() {
        identity = null
        save()
    }

    // ---- rooms ----

    fun roomFor(entry: RoomEntry) = rooms[entry.id]

    // Invites waiting for their room's start, by room: chat checks and reads them.
    private val invites = HashMap<String, String>()

    // A room to create, or join by its id and password or an invite. keep: it goes in the vault.
    fun start(room: String?, password: String?, invite: String?, label: String, route: String, history: Boolean, keep: Boolean): Room {
        val entry = RoomEntry(room = room.orEmpty(), password = password.orEmpty(), label = label, route = route,
            history = history, created = room == null && invite == null)
        if (invite != null) invites[entry.id] = invite
        return open(entry, keepIt = keep)
    }

    fun open(entry: RoomEntry, keepIt: Boolean = true): Room {
        rooms[entry.id]?.let { if (it.status == Status.OPEN || it.status == Status.STARTING) return it }
        val room = Room(entry.id, entry.label, this)
        room.history = entry.history
        if (entry.history && vault.unlocked) room.loadHistory(loadHistory(entry.id))
        rooms[entry.id] = room
        if (keepIt && kept.none { it.id == entry.id }) {
            kept += entry
            save()
        }
        ChatService.start(app)
        scope.launch {
            if (entry.route == "tor") {
                startTor()
                // chat logs in to tor's control port, which has to be listening first.
                withTimeoutOrNull(30_000) { while (!tor.state.listening && tor.state.phase != Tor.Phase.FAILED) delay(100) }
            }
            val home = File(app.noBackupFilesDir, "rooms/${entry.id}")
            val p = ChatProcess(exe, home, onEvent = room::handle, onExit = room::exited)
            room.process = p
            p.start(listOf(startCommand(entry)))
        }
        return room
    }

    fun close(room: Room) {
        val isKept = kept.any { it.id == room.id }
        room.quit(isKept)
    }

    fun forget(entry: RoomEntry) {
        rooms[entry.id]?.quit(false)
        kept.removeAll { it.id == entry.id }
        save()
        vault.delete(historyName(entry.id))
        File(app.filesDir, "received/${entry.id}").deleteRecursively()
        File(app.noBackupFilesDir, "rooms/${entry.id}").deleteRecursively()
    }

    fun keepRoom(room: Room, history: Boolean) {
        val info = room.info ?: return
        if (kept.any { it.id == room.id }) return
        kept += RoomEntry(id = room.id, room = info.room, password = info.password, label = room.label,
            route = info.route, history = history, created = info.created)
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
        if (!on) vault.delete(historyName(room.id))
    }

    fun receivedDir(id: String) = File(app.filesDir, "received/$id")
    fun outboxDir(id: String) = File(app.cacheDir, "outbox/$id")

    private fun startCommand(entry: RoomEntry): JSONObject {
        val s = settings
        val o = JSONObject().put("cmd", "start").put("create", entry.created)
        val invite = invites.remove(entry.id)
        if (invite != null) o.put("invite", invite)
        else if (entry.room.isNotEmpty()) o.put("room", entry.room).put("password", entry.password)
        if (s.nick.isNotBlank()) o.put("nick", s.nick)
        if (s.colour.isNotBlank()) o.put("colour", s.colour)
        val route = JSONObject().put("mode", entry.route).put("nostr", s.nostr)
        if (entry.route == "tor") {
            route.put("tor", JSONObject().put("socks", tor.state.socks).put("control", tor.state.control).put("launch", "never"))
        } else {
            route.put("dht", s.dht).put("lan", s.lan).put("portmap", s.portmap)
        }
        o.put("route", route)
        o.put("verify", s.verify)
        o.put("notify", JSONObject().put("mode", s.notify).put("preview", s.preview))
        o.put("files", JSONObject().put("dir", receivedDir(entry.id).apply { mkdirs() }.path)
            .put("limit", s.fileLimitMb * 1024L * 1024L).put("fast", s.fastFiles).put("noise", s.noise))
        o.put("picture", JSONObject().put("max", pictureMax).put("background", "#000000"))
        o.put("history", entry.history)
        o.put("kept", kept.any { it.id == entry.id })
        identity?.let { o.put("identity", JSONObject().put("kind", it.kind).put("key", it.secret)) }
        return o
    }

    private fun startTor() {
        if (tor.state.phase == Tor.Phase.STARTING || tor.state.phase == Tor.Phase.READY) return
        val s = settings
        val bridges = when (s.bridges) {
            "custom" -> s.customBridges.lines().map { it.trim().removePrefix("Bridge ").trim() }.filter { it.isNotEmpty() }
            "none" -> emptyList()
            else -> tor.builtInBridges(s.bridges)
        }
        tor.start(bridges, s.torKeepState)
    }

    fun restartTor() {
        tor.stop()
        if (rooms.values.any { it.info?.route == "tor" }) startTor()
    }

    // ---- what rooms tell the hub ----

    private fun historyName(id: String) = "history/$id"

    private fun loadHistory(id: String): List<Pair<Line, Long>> =
        vault.records(historyName(id)).mapNotNull { runCatching { lineFromJson(JSONObject(String(it))) }.getOrNull() }

    override fun keep(room: Room, line: Line, time: Long) {
        if (vault.unlocked && kept.any { it.id == room.id }) vault.append(historyName(room.id), lineToJson(line, time).toString().toByteArray())
    }

    override fun notify(room: Room, n: Notify) {
        if (foreground && room.visible) return
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
        if (seconds <= 0 || !vault.unlocked) return
        val cutoff = System.currentTimeMillis() - seconds * 1000L
        val name = historyName(room.id)
        val records = vault.records(name)
        val keep = records.filter { runCatching { JSONObject(String(it)).optLong("time") >= cutoff }.getOrDefault(false) }
        if (keep.size != records.size) vault.rewrite(name, keep)
        room.items.removeAll { it.time < cutoff }
    }

    override fun closed(room: Room) {
        if (room.status == Status.CLOSED) {
            rooms.remove(room.id)
            outboxDir(room.id).deleteRecursively()
            if (kept.none { it.id == room.id }) {
                receivedDir(room.id).deleteRecursively()
                File(app.noBackupFilesDir, "rooms/${room.id}").deleteRecursively()
            }
        }
        if (rooms.values.none { it.info?.route == "tor" || it.status == Status.STARTING }) tor.stop()
    }

    private const val DATA = "data"
}
