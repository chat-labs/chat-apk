package moe.vin.chat.engine

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import moe.vin.chat.store.TrustedKey
import org.json.JSONObject

data class LinkSpan(val start: Int, val end: Int, val url: String)

private fun Char.isAsciiAlnum(): Boolean = (this in '0'..'9') || (this in 'a'..'z') || (this in 'A'..'Z')

private fun isLinkChar(c: Char): Boolean =
    c.code >= 0x80 || (c > ' ' && c.code < 0x7f && c !in "\"<>\\^`{|}")

private fun schemeLen(s: String, at: Int): Int = when {
    s.regionMatches(at, "https://", 0, 8, ignoreCase = true) -> 8
    s.regionMatches(at, "http://", 0, 7, ignoreCase = true) -> 7
    else -> 0
}

// Mirrors chat's domain_len in util.c: 2+ labels of ASCII alnum/'-', last label 2+ letters in one case.
private fun domainLen(s: String, start: Int): Int {
    val n = s.length
    var i = start
    var last = start
    var end = start
    var labels = 0
    while (true) {
        val st = i
        while (i < n && (s[i].isAsciiAlnum() || s[i] == '-')) i++
        if (i == st || s[st] == '-' || s[i - 1] == '-') break
        labels++
        last = st
        end = i
        if (i + 1 < n && s[i] == '.' && s[i + 1].isAsciiAlnum()) i++ else break
    }
    if (labels < 2 || end - last < 2) return 0
    var lower = false
    var upper = false
    for (k in last until end) {
        when (s[k]) {
            in 'a'..'z' -> lower = true
            in 'A'..'Z' -> upper = true
            else -> return 0
        }
    }
    return if (lower && upper) 0 else end - start
}

private fun nameStarts(s: String, i: Int): Boolean {
    if (i == 0) return true
    val c = s[i - 1]
    return c.code < 0x80 && !c.isAsciiAlnum() && c !in "-_.@/\\:~#$%&+="
}

private fun nameEnds(s: String, e: Int): Boolean {
    val n = s.length
    if (e >= n) return true
    if (s[e] in "@_-") return false
    return !(s[e] == '.' && e + 1 < n && (s[e + 1].isAsciiAlnum() || s[e + 1] in "_-"))
}

private fun trimLinkEnd(s: String, start: Int, rawEnd: Int, minEnd: Int): Int {
    var end = rawEnd
    while (end > minEnd) {
        val c = s[end - 1]
        if (c in ".,;:!?'*”’»…。）") {
            end--
            continue
        }
        if (c == ')' || c == ']') {
            val open = if (c == ')') '(' else '['
            var depth = 0
            for (k in start until end) {
                if (s[k] == open) depth++ else if (s[k] == c) depth--
            }
            if (depth < 0) {
                end--
                continue
            }
        }
        break
    }
    return end
}

fun detectLinks(s: String): List<LinkSpan> {
    if (s.length < 4) return emptyList()
    var out: MutableList<LinkSpan>? = null
    val n = s.length
    var i = 0
    while (i < n) {
        val sl = schemeLen(s, i)
        val e: Int
        if (sl > 0 && (i == 0 || !s[i - 1].isAsciiAlnum()) && i + sl < n && (s[i + sl].isAsciiAlnum() || s[i + sl] == '[')) {
            var p = i + sl
            while (p < n && isLinkChar(s[p])) p++
            e = trimLinkEnd(s, i, p, i + sl + 1)
        } else if (s[i].isAsciiAlnum() && nameStarts(s, i)) {
            val d = domainLen(s, i)
            if (d == 0 || !nameEnds(s, i + d)) {
                i++
                continue
            }
            var p = i + d
            if (p < n && s[p] in "/?#") {
                while (p < n && isLinkChar(s[p])) p++
                p = trimLinkEnd(s, i, p, i + d)
            }
            e = p
        } else {
            i++
            continue
        }
        val raw = s.substring(i, e)
        if (raw.length < 1000) {
            val url = if (sl > 0) raw else "https://$raw"
            if (out == null) out = ArrayList(2)
            out.add(LinkSpan(i, e, url))
        }
        i = e
    }
    return out ?: emptyList()
}

private class StampFmt {
    val cal: Calendar = Calendar.getInstance()
    val clock = SimpleDateFormat("HH:mm", Locale.getDefault())
    val day = SimpleDateFormat("EEE, d MMM", Locale.getDefault())
    val fullDay = SimpleDateFormat("d MMM yyyy", Locale.getDefault())
}

private val stampFmt = ThreadLocal.withInitial { StampFmt() }

// A line on screen: seq orders and keys it, time is when it came, old that it's from the history.
// Display slices, timestamps, day keys and links are worked out once here rather than on scroll.
class Item(val seq: Long, val time: Long, val line: Line, val old: Boolean) {
    val quoteText: String?
    val bodyText: String
    val timeText: String
    val dayKey: Int
    val dayLabel: String
    val links: List<LinkSpan>

    init {
        if (line.quote > 0) {
            val b = line.text.toByteArray()
            if (line.quote <= b.size) {
                quoteText = String(b, 0, line.quote).trim().removeSuffix("·").trim()
                bodyText = String(b, line.quote, b.size - line.quote)
            } else {
                quoteText = null
                bodyText = line.text
            }
        } else {
            quoteText = null
            bodyText = line.text
        }
        val f = stampFmt.get()!!
        val d = Date(time)
        timeText = f.clock.format(d)
        f.cal.timeInMillis = System.currentTimeMillis()
        val nowYear = f.cal.get(Calendar.YEAR)
        val nowDay = f.cal.get(Calendar.DAY_OF_YEAR)
        f.cal.timeInMillis = time
        val y = f.cal.get(Calendar.YEAR)
        val m = f.cal.get(Calendar.MONTH) + 1
        val dom = f.cal.get(Calendar.DAY_OF_MONTH)
        val doy = f.cal.get(Calendar.DAY_OF_YEAR)
        dayKey = y * 10000 + m * 100 + dom
        dayLabel = when {
            y == nowYear && doy == nowDay -> "Today"
            y == nowYear && doy == nowDay - 1 -> "Yesterday"
            y == nowYear -> f.day.format(d)
            else -> f.fullDay.format(d)
        }
        links = if (line.kind == LineKind.MESSAGE && line.file == null) detectLinks(bodyText) else emptyList()
    }
}

enum class Status { STARTING, OPEN, CLOSED, FAILED }

enum class Tick { SENT, DELIVERED, MISSED }

interface RoomHost {
    fun keep(room: Room, line: Line, time: Long)
    fun notify(room: Room, n: Notify)
    fun opened(room: Room, info: RoomInfo)
    fun timerChanged(room: Room, seconds: Int)
    fun trustChanged(room: Room, list: List<TrustedKey>)
    fun ignoredChanged(room: Room, list: List<TrustedKey>)
    fun closed(room: Room)
}

// One open room: what its chat process has said, for the screens to show.
class Room(val id: String, label: String, private val host: RoomHost) {
    var label by mutableStateOf(label)
    var status by mutableStateOf(Status.STARTING)
    var info by mutableStateOf<RoomInfo?>(null)
    val items = mutableStateListOf<Item>()
    val logs = mutableStateListOf<String>()
    var peers by mutableStateOf(emptyList<Peer>())
    var net by mutableStateOf<Net?>(null)
    var files by mutableStateOf(emptyMap<Int, FileItem>())
    val pictures = mutableStateMapOf<Int, Picture>()
    val hiddenPictures = mutableStateMapOf<Int, Boolean>()
    val ticks = mutableStateMapOf<Int, Tick>()
    var timer by mutableStateOf<Timer?>(null)
    var moveAsk by mutableStateOf<MoveAsk?>(null)
    var movePlan by mutableStateOf<MovePlan?>(null)
    var unread by mutableIntStateOf(0)
    var mentions by mutableIntStateOf(0)
    var firstUnreadSeq by mutableStateOf<Long?>(null)
    var error by mutableStateOf<String?>(null)
    var history by mutableStateOf(false)
    var callPeer by mutableStateOf<TrustedKey?>(null)
    var callDay by mutableStateOf(0L)
    // What's being typed, by thread ("" the room, else the peer a direct thread is with), and the
    // message being answered: kept here so folding or switching rooms loses neither.
    val drafts = mutableStateMapOf<String, String>()
    var replyTo by mutableStateOf<Item?>(null)
    var visible = false
    var process: ChatProcess? = null
    private var seq = 0L
    private val autoShown = HashSet<Int>()

    val title get() = label.ifBlank { callPeer?.let { "@${it.nick}" } ?: info?.room ?: "" }
    val isCall get() = callPeer != null || info?.call == true

    val lastPreview: String?
        get() {
            val last = items.lastOrNull { it.line.kind == LineKind.MESSAGE } ?: return null
            val who = if (last.line.mine) "you" else last.line.nick ?: "?"
            val what = if (last.line.file != null) "sent a file" else last.bodyText
            return "$who: $what"
        }

    fun peer(id: String?) = peers.firstOrNull { it.id == id }

    fun loadHistory(old: List<Pair<Line, Long>>) {
        val loaded = old.map { (line, time) -> Item(seq++, time, line, old = true) }
        items.addAll(0, loaded)
    }

    fun clearHistoryItems() {
        items.removeAll { it.old }
    }

    fun handle(e: Event) {
        when (e) {
            is RoomInfo -> {
                if (e.movedFrom != null) {
                    callPeer = null
                    callDay = 0L
                    items += Item(seq++, now(), Line("", LineKind.NOTE, "moved here from the room ${e.movedFrom}", null), false)
                }
                if (e.day != null) callDay = e.day
                info = e
                status = Status.OPEN
                host.opened(this, e)
            }
            is Line -> add(e)
            is Log -> {
                logs += e.text
                if (logs.size > LOGS_MAX) logs.removeAt(0)
            }
            is Notify -> host.notify(this, e)
            is Delivery -> ticks[e.own] = when {
                e.missed != null -> Tick.MISSED
                ticks[e.own] == Tick.MISSED -> Tick.MISSED
                else -> Tick.DELIVERED
            }
            is Peers -> peers = e.list
            is TrustList -> host.trustChanged(this, e.list)
            is IgnoredList -> host.ignoredChanged(this, e.list)
            is Net -> net = e
            is Files -> {
                files = e.list.associateBy { it.num }
                for (f in e.list) {
                    if (f.mine && f.image && f.num !in autoShown && hiddenPictures[f.num] != true) {
                        autoShown += f.num
                        show(f.num, anyway = true)
                    }
                }
            }
            is Picture -> {
                pictures[e.num] = e
                hiddenPictures.remove(e.num)
            }
            is Timer -> {
                timer = e
                host.timerChanged(this, e.seconds)
            }
            is MoveAsk -> moveAsk = if (e.seq == 0) null else e
            is MovePlan -> movePlan = e
            is Failure -> error = e.text
            is Bye -> status = Status.CLOSED
            is Hello, is IdentityKey -> {}
        }
    }

    private fun add(line: Line) {
        val time = now()
        val item = Item(seq++, time, line, old = false)
        items += item
        if (items.size > ITEMS_MAX) items.removeRange(0, items.size - ITEMS_MAX)
        if (line.own != null) ticks.putIfAbsent(line.own, Tick.SENT)
        if (line.kind == LineKind.MESSAGE) {
            if (line.mine) {
                firstUnreadSeq = null
            } else if (!visible) {
                if (firstUnreadSeq == null) firstUnreadSeq = item.seq
                unread++
                if (line.mention) mentions++
            }
        }
        if (history && line.kind != LineKind.NOTE) host.keep(this, line, time)
    }

    fun pruneBefore(cutoffMs: Long): Boolean {
        val removed = items.removeAll { it.time < cutoffMs }
        if (removed) {
            val fu = firstUnreadSeq
            if (fu != null && items.none { it.seq >= fu && !it.line.mine && it.line.kind == LineKind.MESSAGE }) {
                firstUnreadSeq = null
            }
            val liveFiles = HashSet<Int>()
            items.forEach { it.line.file?.let(liveFiles::add) }
            files.keys.forEach(liveFiles::add)
            val stalePics = pictures.keys.filter { it !in liveFiles }
            stalePics.forEach { pictures.remove(it) }
        }
        return removed
    }

    fun exited(code: Int, stderr: List<String>) {
        if (status != Status.CLOSED) {
            status = Status.FAILED
            error = stderr.lastOrNull { it.isNotBlank() }?.removePrefix("chat: ") ?: "chat stopped ($code)"
        }
        process = null
        host.closed(this)
    }

    fun seen() {
        unread = 0
        mentions = 0
    }

    fun clearUnreadMarker() {
        firstUnreadSeq = null
    }

    private fun send(cmd: JSONObject) {
        process?.send(cmd)
    }

    fun say(text: String) = send(Cmd.send(text))
    fun reply(mid: String, text: String) = send(Cmd.reply(mid, text))
    fun dm(peer: String, text: String) = send(Cmd.dm(peer, text))
    fun verify(peer: String, same: Boolean) = send(Cmd.verify(peer, same))
    fun ignore(peer: String, on: Boolean) = send(Cmd.ignore(peer, on))
    fun show(num: Int, anyway: Boolean = false) {
        hiddenPictures.remove(num)
        send(Cmd.fetch(num, show = true, anyway = anyway))
    }
    fun hidePicture(num: Int) {
        hiddenPictures[num] = true
    }
    fun save(num: Int, anyway: Boolean = false) = send(Cmd.fetch(num, show = false, anyway = anyway))
    fun offer(path: String, asis: Boolean = false) = send(Cmd.offer(path, asis))
    fun cancel(num: Int) = send(Cmd.cancel(num))
    fun setTimer(seconds: Int) = send(Cmd.timer(seconds))
    fun newCircuits() = send(Cmd.newtor())
    fun planMove() = send(Cmd.move())
    fun answerMove(go: Boolean) = send(Cmd.move(go))
    fun runCommand(line: String) = send(Cmd.command(line))

    fun changeHistory(on: Boolean) {
        history = on
        send(Cmd.history(on))
    }

    fun quit(kept: Boolean) {
        val p = process ?: return
        p.send(Cmd.quit(kept))
        p.close()
    }

    private fun now() = System.currentTimeMillis()

    private companion object {
        const val ITEMS_MAX = 5000
        const val LOGS_MAX = 200
    }
}
