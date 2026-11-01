package moe.vin.chat.store

import java.util.UUID
import moe.vin.chat.engine.Line
import moe.vin.chat.engine.LineKind
import org.json.JSONArray
import org.json.JSONObject

// What the settings page sets, for every room opened from then on.
data class Settings(
    val nick: String = "",
    val colour: String = "",
    val route: String = "udp",
    val nostr: String = "fallback",
    val dht: Boolean = true,
    val lan: Boolean = true,
    val portmap: Boolean = true,
    val bridges: String = "none",
    val customBridges: String = "",
    val torKeepState: Boolean = false,
    val notify: String = "mentions",
    val preview: String = "off",
    val verify: String = "optional",
    val fileLimitMb: Int = 8,
    val fastFiles: Boolean = false,
    val noise: Boolean = true,
    val stayAwake: Boolean = true,
    val reopenKept: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("nick", nick).put("colour", colour).put("route", route).put("nostr", nostr)
        .put("dht", dht).put("lan", lan).put("portmap", portmap)
        .put("bridges", bridges).put("customBridges", customBridges).put("torKeepState", torKeepState)
        .put("notify", notify).put("preview", preview).put("verify", verify)
        .put("fileLimitMb", fileLimitMb).put("fastFiles", fastFiles).put("noise", noise)
        .put("stayAwake", stayAwake).put("reopenKept", reopenKept)

    companion object {
        fun from(o: JSONObject?): Settings {
            val d = Settings()
            if (o == null) return d
            return Settings(
                nick = o.optString("nick", d.nick),
                colour = o.optString("colour", d.colour),
                route = o.optString("route", d.route),
                nostr = o.optString("nostr", d.nostr),
                dht = o.optBoolean("dht", d.dht),
                lan = o.optBoolean("lan", d.lan),
                portmap = o.optBoolean("portmap", d.portmap),
                bridges = o.optString("bridges", d.bridges),
                customBridges = o.optString("customBridges", d.customBridges),
                torKeepState = o.optBoolean("torKeepState", d.torKeepState),
                notify = o.optString("notify", d.notify),
                preview = o.optString("preview", d.preview),
                verify = o.optString("verify", d.verify),
                fileLimitMb = o.optInt("fileLimitMb", d.fileLimitMb),
                fastFiles = o.optBoolean("fastFiles", d.fastFiles),
                noise = o.optBoolean("noise", d.noise),
                stayAwake = o.optBoolean("stayAwake", d.stayAwake),
                reopenKept = o.optBoolean("reopenKept", d.reopenKept),
            )
        }
    }
}

// The signing key every room is joined with: its secret, as chat made or read it.
data class IdentityEntry(val kind: String, val secret: String, val public: String, val fingerprint: String) {
    fun toJson(): JSONObject = JSONObject().put("kind", kind).put("secret", secret).put("public", public).put("fingerprint", fingerprint)

    companion object {
        fun from(o: JSONObject?) = o?.let {
            IdentityEntry(it.getString("kind"), it.getString("secret"), it.optString("public"), it.optString("fingerprint"))
        }
    }
}

// A room kept to come back to. id is this app's own name for it, which stays the same after :move.
data class RoomEntry(
    val id: String = UUID.randomUUID().toString(),
    val room: String,
    val password: String,
    val label: String,
    val route: String,
    val history: Boolean,
    val created: Boolean,
    val addedAt: Long = System.currentTimeMillis(),
) {
    val title get() = label.ifBlank { room }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("room", room).put("password", password).put("label", label)
        .put("route", route).put("history", history).put("created", created).put("addedAt", addedAt)

    companion object {
        fun from(o: JSONObject) = RoomEntry(
            id = o.getString("id"),
            room = o.getString("room"),
            password = o.optString("password"),
            label = o.optString("label"),
            route = o.optString("route", "udp"),
            history = o.optBoolean("history"),
            created = o.optBoolean("created"),
            addedAt = o.optLong("addedAt"),
        )
    }
}

// What's kept apart from the histories: settings, the signing key and the kept rooms.
data class Saved(val settings: Settings, val identity: IdentityEntry?, val rooms: List<RoomEntry>) {
    fun toJson(): JSONObject = JSONObject()
        .put("settings", settings.toJson())
        .put("identity", identity?.toJson() ?: JSONObject.NULL)
        .put("rooms", JSONArray().apply { rooms.forEach { put(it.toJson()) } })

    companion object {
        fun from(o: JSONObject?) = Saved(
            Settings.from(o?.optJSONObject("settings")),
            IdentityEntry.from(o?.optJSONObject("identity")),
            o?.optJSONArray("rooms")?.let { a -> List(a.length()) { RoomEntry.from(a.getJSONObject(it)) } } ?: emptyList(),
        )
    }
}

// A line of a kept history, with when it came.
fun lineToJson(line: Line, time: Long): JSONObject = JSONObject()
    .put("time", time).put("at", line.at).put("kind", line.kind.name).put("text", line.text)
    .apply {
        line.colour?.let { put("colour", it) }
        line.mid?.let { put("mid", it) }
        line.from?.let { put("from", it) }
        line.nick?.let { put("nick", it) }
        line.peer?.let { put("peer", it) }
        line.file?.let { put("file", it) }
        if (line.mine) put("mine", true)
        if (line.direct) put("direct", true)
        if (line.mention) put("mention", true)
        if (line.quote > 0) put("quote", line.quote)
    }

fun lineFromJson(o: JSONObject): Pair<Line, Long> = Line(
    at = o.optString("at"),
    kind = runCatching { LineKind.valueOf(o.getString("kind")) }.getOrDefault(LineKind.NOTE),
    text = o.optString("text"),
    colour = if (o.has("colour")) o.getInt("colour") else null,
    mid = o.optString("mid").ifEmpty { null },
    from = o.optString("from").ifEmpty { null },
    mine = o.optBoolean("mine"),
    nick = o.optString("nick").ifEmpty { null },
    peer = o.optString("peer").ifEmpty { null },
    direct = o.optBoolean("direct"),
    mention = o.optBoolean("mention"),
    quote = o.optInt("quote"),
    file = if (o.has("file")) o.getInt("file") else null,
) to o.optLong("time")
