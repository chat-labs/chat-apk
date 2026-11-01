package moe.vin.chat.engine

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject

// A line on screen: seq orders and keys it, time is when it came, old that it's from the history.
class Item(val seq: Long, val time: Long, val line: Line, val old: Boolean)

enum class Status { STARTING, OPEN, CLOSED, FAILED }

enum class Tick { SENT, DELIVERED, MISSED }

interface RoomHost {
    fun keep(room: Room, line: Line, time: Long)
    fun notify(room: Room, n: Notify)
    fun opened(room: Room, info: RoomInfo)
    fun timerChanged(room: Room, seconds: Int)
    fun closed(room: Room)
}

// One open room: what its chat process has said, for the screens to show.
class Room(val id: String, var label: String, private val host: RoomHost) {
    var status by mutableStateOf(Status.STARTING)
    var info by mutableStateOf<RoomInfo?>(null)
    val items = mutableStateListOf<Item>()
    val logs = mutableStateListOf<String>()
    var peers by mutableStateOf(emptyList<Peer>())
    var net by mutableStateOf<Net?>(null)
    var files by mutableStateOf(emptyMap<Int, FileItem>())
    val pictures = mutableStateMapOf<Int, Picture>()
    val ticks = mutableStateMapOf<Int, Tick>()
    var timer by mutableStateOf<Timer?>(null)
    var moveAsk by mutableStateOf<MoveAsk?>(null)
    var movePlan by mutableStateOf<MovePlan?>(null)
    var unread by mutableIntStateOf(0)
    var mentions by mutableIntStateOf(0)
    var error by mutableStateOf<String?>(null)
    var history by mutableStateOf(false)
    var visible = false
    var process: ChatProcess? = null
    private var seq = 0L

    val title get() = label.ifBlank { info?.room ?: "" }

    fun peer(id: String?) = peers.firstOrNull { it.id == id }

    fun loadHistory(old: List<Pair<Line, Long>>) {
        for ((line, time) in old) items += Item(seq++, time, line, old = true)
    }

    fun handle(e: Event) {
        when (e) {
            is RoomInfo -> {
                if (e.movedFrom != null)
                    items += Item(seq++, now(), Line("", LineKind.NOTE, "moved here from the room ${e.movedFrom}", null), false)
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
            is Net -> net = e
            is Files -> files = e.list.associateBy { it.num }
            is Picture -> pictures[e.num] = e
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
        items += Item(seq++, time, line, old = false)
        if (items.size > ITEMS_MAX) items.removeRange(0, items.size - ITEMS_MAX)
        if (line.own != null) ticks.putIfAbsent(line.own, Tick.SENT)
        if (line.kind == LineKind.MESSAGE && !line.mine && !visible) {
            unread++
            if (line.mention) mentions++
        }
        if (history && line.kind != LineKind.NOTE) host.keep(this, line, time)
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

    private fun send(cmd: JSONObject) {
        process?.send(cmd)
    }

    fun say(text: String) = send(Cmd.send(text))
    fun reply(mid: String, text: String) = send(Cmd.reply(mid, text))
    fun dm(peer: String, text: String) = send(Cmd.dm(peer, text))
    fun verify(peer: String, same: Boolean) = send(Cmd.verify(peer, same))
    fun ignore(peer: String, on: Boolean) = send(Cmd.ignore(peer, on))
    fun show(num: Int, anyway: Boolean = false) = send(Cmd.fetch(num, show = true, anyway = anyway))
    fun save(num: Int, anyway: Boolean = false) = send(Cmd.fetch(num, show = false, anyway = anyway))
    fun offer(path: String) = send(Cmd.offer(path))
    fun cancel(num: Int) = send(Cmd.cancel(num))
    fun setTimer(seconds: Int) = send(Cmd.timer(seconds))
    fun newCircuits() = send(Cmd.newtor())
    fun planMove() = send(Cmd.move())
    fun answerMove(go: Boolean) = send(Cmd.move(go))

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
