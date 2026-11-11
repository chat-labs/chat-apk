package moe.vin.chat.engine

import android.graphics.Bitmap
import android.util.Base64
import moe.vin.chat.store.TrustedKey
import org.json.JSONArray
import org.json.JSONObject

// What chat-json says, in chat's JSON interface (chat-core's json.rs).
sealed interface Event

data class Hello(val version: String) : Event

data class IdentityKey(
    val kind: String,
    val fingerprint: String,
    val public: String?,
    val secret: String?,
    val created: Long = 0L,
) : Event

data class Me(val id: String, val nick: String, val colour: Int)

data class RoomInfo(
    val room: String,
    val password: String,
    val created: Boolean,
    val invite: String?,
    val qr: List<String>,
    val me: Me,
    val route: String,
    val port: Int?,
    val history: Boolean,
    val movedFrom: String?,
    val call: Boolean = false,
    val day: Long? = null,
) : Event

enum class LineKind { MESSAGE, NOTE, WARN }

data class Line(
    val at: String,
    val kind: LineKind,
    val text: String,
    val colour: Int?,
    val mid: String? = null,
    val from: String? = null,
    val mine: Boolean = false,
    val nick: String? = null,
    val peer: String? = null,
    val own: Int? = null,
    val direct: Boolean = false,
    val mention: Boolean = false,
    val quote: Int = 0,
    val file: Int? = null,
) : Event

data class Log(val at: String, val text: String) : Event
data class Notify(val nick: String?, val text: String?, val mention: Boolean) : Event
data class Delivery(val own: Int, val missed: String?, val settled: Boolean) : Event

data class Code(val state: String, val words: String?, val hex: String?)

data class Peer(
    val id: String,
    val name: String,
    val nick: String,
    val colour: Int,
    val identity: String,
    val signed: String?,
    val fingerprint: String?,
    val code: Code,
    val buildState: String,
    val buildLabel: String,
    val history: Boolean,
    val ignored: Boolean,
    val dm: Boolean,
)

data class Peers(val list: List<Peer>) : Event

data class TrustList(val list: List<TrustedKey>) : Event
data class IgnoredList(val list: List<TrustedKey>) : Event

data class Relays(val used: Boolean, val up: Int, val total: Int)

data class Net(
    val ready: Boolean,
    val online: Int,
    val pending: Int,
    val rx: Long,
    val tor: String?,
    val onion: Boolean?,
    val dht: Boolean?,
    val dhtFound: Int?,
    val portmap: Boolean?,
    val relays: Relays?,
) : Event

data class FileItem(
    val num: Int,
    val name: String,
    val size: Long,
    val mine: Boolean,
    val from: String,
    val image: Boolean,
    val at: String,
    val state: String,
    val show: Boolean,
    val got: Long?,
    val eta: Int?,
    val waiting: String?,
    val after: Int?,
    val why: String?,
    val saved: String?,
    val sendingCount: Int,
    val sendingName: String?,
    val permille: Int?,
    val sentTo: String?,
)

data class Files(val list: List<FileItem>) : Event

class Picture(val num: Int, val name: String, val bitmap: Bitmap?, val srcW: Int, val srcH: Int, val error: String?) : Event

data class Timer(val seconds: Int, val who: String?) : Event
data class MoveAsk(val seq: Int, val from: String?, val coming: Int, val names: String?, val at: String?) : Event
data class MovePlan(val coming: Int, val comingNames: String, val left: Int, val leftNames: String) : Event
data class Failure(val text: String) : Event
data object Bye : Event

private fun JSONObject.str(key: String): String? = if (has(key) && !isNull(key)) getString(key) else null
private fun JSONObject.int(key: String): Int? = if (has(key) && !isNull(key)) getInt(key) else null
private fun JSONObject.long(key: String): Long? = if (has(key) && !isNull(key)) getLong(key) else null
private fun JSONObject.bool(key: String): Boolean = optBoolean(key, false)

fun parseColour(s: String?): Int? =
    if (s != null && s.length == 7 && s[0] == '#') s.substring(1).toIntOrNull(16)?.let { it or 0xFF000000.toInt() } else null

private fun strings(a: JSONArray?): List<String> = if (a == null) emptyList() else List(a.length()) { a.getString(it) }

private fun me(o: JSONObject) = Me(o.getString("id"), o.getString("nick"), parseColour(o.str("colour")) ?: 0xFF888888.toInt())

private fun roomInfo(o: JSONObject, moved: Boolean) = RoomInfo(
    room = o.getString("room"),
    password = o.optString("password"),
    created = o.bool("created"),
    invite = o.str("invite"),
    qr = strings(o.optJSONArray("qr")),
    me = me(o.getJSONObject("me")),
    route = o.optString("route", "udp"),
    port = o.int("port"),
    history = o.bool("history"),
    movedFrom = if (moved) o.str("from") else null,
    call = o.bool("call"),
    day = o.long("day"),
)

private fun line(o: JSONObject) = Line(
    at = o.optString("at"),
    kind = when (o.optString("kind")) {
        "message" -> LineKind.MESSAGE
        "warn" -> LineKind.WARN
        else -> LineKind.NOTE
    },
    text = o.optString("text"),
    colour = parseColour(o.str("colour")),
    mid = o.str("mid"),
    from = o.str("from"),
    mine = o.bool("mine"),
    nick = o.str("nick"),
    peer = o.str("peer"),
    own = o.int("own"),
    direct = o.bool("direct"),
    mention = o.bool("mention"),
    quote = o.optInt("quote", 0),
    file = o.int("file"),
)

private fun peer(o: JSONObject): Peer {
    val code = o.optJSONObject("code") ?: JSONObject()
    val build = o.optJSONObject("build") ?: JSONObject()
    return Peer(
        id = o.getString("id"),
        name = o.optString("name"),
        nick = o.optString("nick"),
        colour = parseColour(o.str("colour")) ?: 0xFF888888.toInt(),
        identity = o.optString("identity", "none"),
        signed = o.str("signed"),
        fingerprint = o.str("fingerprint"),
        code = Code(code.optString("state", "nothing"), code.str("words"), code.str("hex")),
        buildState = build.optString("state", "unknown"),
        buildLabel = build.optString("label"),
        history = o.bool("history"),
        ignored = o.bool("ignored"),
        dm = o.bool("dm"),
    )
}

private fun trustedKeys(a: JSONArray?): List<TrustedKey> =
    if (a == null) emptyList() else List(a.length()) { TrustedKey.from(a.getJSONObject(it)) }

private fun net(o: JSONObject) = Net(
    ready = o.bool("ready"),
    online = o.optInt("online"),
    pending = o.optInt("pending"),
    rx = o.optLong("rx", 0L),
    tor = o.str("tor"),
    onion = if (o.has("onion")) o.bool("onion") else null,
    dht = if (o.has("dht")) o.bool("dht") else null,
    dhtFound = o.int("dht_found"),
    portmap = if (o.has("portmap")) o.bool("portmap") else null,
    relays = o.optJSONObject("relays")?.let { Relays(it.bool("used"), it.optInt("up"), it.optInt("total")) },
)

private fun file(o: JSONObject): FileItem {
    val sending = o.optJSONObject("sending")
    return FileItem(
        num = o.getInt("num"),
        name = o.optString("name"),
        size = o.optLong("size"),
        mine = o.bool("mine"),
        from = o.optString("from"),
        image = o.bool("image"),
        at = o.optString("at"),
        state = o.optString("state"),
        show = o.bool("show"),
        got = o.long("got"),
        eta = o.int("eta"),
        waiting = o.str("waiting"),
        after = o.int("after"),
        why = o.str("why"),
        saved = o.str("saved"),
        sendingCount = sending?.optInt("count") ?: 0,
        sendingName = sending?.str("name"),
        permille = sending?.int("permille"),
        sentTo = o.str("sent_to"),
    )
}

// chat sends a picture as its own readers decoded it: rows of red, green and blue. They go
// straight into a bitmap, so no image decoder on this side ever reads what a peer sent.
private fun picture(o: JSONObject): Picture {
    val num = o.getInt("num")
    val name = o.optString("name")
    val error = o.str("error")
    val rgb = o.str("rgb")
    if (error != null || rgb == null) return Picture(num, name, null, 0, 0, error ?: "no picture")
    val w = o.getInt("w")
    val h = o.getInt("h")
    val bytes = Base64.decode(rgb, Base64.DEFAULT)
    if (w <= 0 || h <= 0 || bytes.size != w * h * 3) return Picture(num, name, null, 0, 0, "a picture of the wrong size")
    val px = IntArray(w * h) { i ->
        0xFF000000.toInt() or ((bytes[3 * i].toInt() and 0xFF) shl 16) or
            ((bytes[3 * i + 1].toInt() and 0xFF) shl 8) or (bytes[3 * i + 2].toInt() and 0xFF)
    }
    bytes.fill(0)
    val bitmap = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    return Picture(num, name, bitmap, o.optInt("src_w", w), o.optInt("src_h", h), null)
}

// null for a line that isn't one of chat's objects, or a type this app doesn't use.
fun parseEvent(text: String): Event? {
    val o = try { JSONObject(text) } catch (e: Exception) { return null }
    return when (o.optString("t")) {
        "hello" -> Hello(o.optString("version"))
        "identity" -> IdentityKey(
            o.optString("kind"),
            o.optString("fingerprint"),
            o.str("public"),
            o.str("secret"),
            o.optLong("created", 0L),
        )
        "ready" -> roomInfo(o, moved = false)
        "moved" -> roomInfo(o, moved = true)
        "line" -> line(o)
        "log" -> Log(o.optString("at"), o.optString("text"))
        "notify" -> Notify(o.str("nick"), o.str("text"), o.bool("mention"))
        "delivery" -> Delivery(o.getInt("own"), o.str("missed"), o.bool("settled"))
        "peers" -> o.getJSONArray("list").let { a -> Peers(List(a.length()) { peer(a.getJSONObject(it)) }) }
        "trust" -> TrustList(trustedKeys(o.optJSONArray("list")))
        "ignored" -> IgnoredList(trustedKeys(o.optJSONArray("list")))
        "net" -> net(o)
        "files" -> o.getJSONArray("list").let { a -> Files(List(a.length()) { file(a.getJSONObject(it)) }) }
        "picture" -> picture(o)
        "timer" -> Timer(o.optInt("seconds"), o.str("who"))
        "move" -> MoveAsk(o.optInt("seq"), o.str("from"), o.optInt("coming"), o.str("names"), o.str("at"))
        "move_plan" -> MovePlan(o.optInt("coming"), o.optString("coming_names"), o.optInt("left"), o.optString("left_names"))
        "error" -> Failure(o.optString("text"))
        "bye" -> Bye
        else -> null
    }
}

// The commands chat takes.
object Cmd {
    private fun cmd(name: String) = JSONObject().put("cmd", name)

    fun send(text: String) = cmd("send").put("text", text)
    fun reply(mid: String, text: String) = cmd("reply").put("mid", mid).put("text", text)
    fun dm(peer: String, text: String) = cmd("dm").put("peer", peer).put("text", text)
    fun verify(peer: String, same: Boolean) = cmd("verify").put("peer", peer).put("same", same)
    fun ignore(peer: String, on: Boolean) = cmd("ignore").put("peer", peer).put("on", on)
    fun fetch(num: Int, show: Boolean, anyway: Boolean = false) = cmd("fetch").put("num", num).put("show", show).put("anyway", anyway)
    fun offer(path: String, asis: Boolean = false) = cmd("offer").put("path", path).also { if (asis) it.put("asis", true) }
    fun cancel(num: Int) = cmd("cancel").put("num", num)
    fun timer(seconds: Int) = cmd("timer").put("seconds", seconds)
    fun history(on: Boolean) = cmd("history").put("on", on)
    fun move(answer: Boolean? = null) = cmd("move").also { if (answer != null) it.put("answer", answer) }
    fun newtor() = cmd("newtor")
    fun command(line: String) = cmd("command").put("line", line)
    fun identity(kind: String, key: String, created: Long = 0L, export: Boolean = false) =
        cmd("identity").put("kind", kind).put("key", key).also {
            if (created > 0L) it.put("created", created)
            if (export) it.put("export", true)
        }
    fun newIdentity(kind: String) = cmd("identity").put("kind", kind).put("new", true)
    fun deriveIdentity(kind: String, password: String, device: String, withNick: Boolean, nick: String) =
        cmd("identity").put("kind", kind).put("password", password).put("device", device)
            .put("with_nick", withNick).put("nick", nick)
    fun dropIdentity() = cmd("identity").put("kind", "none")
    fun trust(list: List<TrustedKey>) = cmd("trust").put("list", JSONArray().apply { list.forEach { put(it.toJson()) } })
    fun ignored(list: List<TrustedKey>) = cmd("ignored").put("list", JSONArray().apply { list.forEach { put(it.toJson()) } })
    fun settings(o: JSONObject) = o.put("cmd", "settings")
    fun quit(kept: Boolean) = cmd("quit").put("kept", kept)
}
