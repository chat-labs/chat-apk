package moe.vin.chat.ui

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import moe.vin.chat.Hub
import moe.vin.chat.engine.FileItem
import moe.vin.chat.engine.Item
import moe.vin.chat.engine.LineKind
import moe.vin.chat.engine.Peer
import moe.vin.chat.engine.Room
import moe.vin.chat.engine.Status
import moe.vin.chat.engine.Tick
import moe.vin.chat.service.Notifications

// chat's own limit on a message, in bytes.
private const val TEXT_MAX = 880
private const val PICTURE_W = 260f
private const val PICTURE_H_MAX = 360f
private const val GROUP_WINDOW_MS = 120_000L

private fun offerUri(scope: CoroutineScope, ctx: Context, room: Room, uri: Uri, asis: Boolean = false) {
    scope.launch {
        val f = copyToOutbox(ctx, room, uri)
        if (f == null) Toast.makeText(ctx, "Couldn't read that file", Toast.LENGTH_SHORT).show()
        else room.offer(f.path, asis = asis)
    }
}

@Composable
fun ChatScreen(nav: Nav, roomId: String, dm: String?) {
    val room = Hub.rooms[roomId]
    if (room == null) {
        LaunchedEffect(Unit) { nav.back() }
        return
    }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    DisposableEffect(room) {
        room.visible = true
        room.seen()
        Notifications.clear(ctx, room.id)
        onDispose {
            room.visible = false
            room.clearUnreadMarker()
        }
    }

    val thread = dm.orEmpty()
    LaunchedEffect(room, thread) {
        Shared.text?.let {
            room.drafts[thread] = it
            Shared.text = null
        }
    }
    LaunchedEffect(room.status) {
        if (room.status == Status.OPEN) {
            Shared.file?.let { uri ->
                Shared.file = null
                offerUri(scope, ctx, room, uri, asis = false)
            }
        }
    }

    var exportPath by remember { mutableStateOf<String?>(null) }
    val saveAsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val src = exportPath
        exportPath = null
        if (uri != null && src != null) {
            scope.launch {
                val ok = exportFileToUri(ctx, src, uri)
                Toast.makeText(ctx, if (ok) "Saved" else "Couldn't save file", Toast.LENGTH_SHORT).show()
            }
        }
    }

    var pendingLink by remember { mutableStateOf<Pair<String, String?>?>(null) }

    val dmPeer = dm?.let { room.peer(it) }
    val title = when {
        dm != null -> dmPeer?.nick ?: "direct"
        else -> room.title.ifBlank { "new room" }
    }
    val route = if (room.info?.route == "tor") "tor" else "direct"
    val timerPart = room.timer?.takeIf { it.seconds > 0 }?.let { "${durationText(it.seconds)} timer" }
    val subtitle = if (dm != null) {
        "direct · only they get these"
    } else {
        listOfNotNull(roomState(room), route, timerPart).joinToString(" · ")
    }

    val p = LocalPalette.current
    val sidebar = LocalSidebar.current
    val showBack = !(dm == null && sidebar?.listVisible == true && nav.stack.size <= 2)
    val activeTransfer = room.files.values.any { it.state == "fetching" || it.state == "queued" || it.sendingCount > 0 }
    var moreMenu by remember { mutableStateOf(false) }

    Page(
        title,
        subtitle,
        onBack = if (showBack) nav::back else null,
        onTitleClick = if (dm == null) ({ nav.go(Screen.RoomSettings(roomId)) }) else ({ nav.go(Screen.PeerInfo(roomId, dm)) }),
        actions = {
            if (dm == null) {
                if (room.files.isNotEmpty()) {
                    IconAction(
                        Icons.Folder,
                        "Files",
                        { nav.go(Screen.Files(roomId)) },
                        badge = activeTransfer,
                    )
                }
                IconAction(Icons.People, "Peers", { nav.go(Screen.Peers(roomId)) })
                Box {
                    IconAction(Icons.More, "More", { moreMenu = true })
                    DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }, containerColor = p.raised) {
                        val followingOn = Hub.settings.followChat
                        DropdownMenuItem(
                            text = { Text("Follow new messages", style = Type.small, color = p.text) },
                            leadingIcon = {
                                Icon(
                                    Icons.ArrowDown,
                                    null,
                                    tint = if (followingOn) p.accent else p.muted,
                                    modifier = Modifier.size(18.dp),
                                )
                            },
                            trailingIcon = if (followingOn) ({
                                Icon(Icons.Check, null, tint = p.accent, modifier = Modifier.size(17.dp))
                            }) else null,
                            onClick = {
                                moreMenu = false
                                Hub.updateSettings(Hub.settings.copy(followChat = !followingOn))
                            },
                        )
                        if (!room.isCall && room.info != null) {
                            DropdownMenuItem(
                                text = { Text("Invite", style = Type.small, color = p.text) },
                                leadingIcon = { Icon(Icons.Qr, null, tint = p.muted, modifier = Modifier.size(18.dp)) },
                                onClick = { moreMenu = false; nav.go(Screen.Invite(roomId)) },
                            )
                        }
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (room.files.isEmpty()) "Files" else "Files (${room.files.size})",
                                    style = Type.small,
                                    color = p.text,
                                )
                            },
                            leadingIcon = { Icon(Icons.Folder, null, tint = p.muted, modifier = Modifier.size(18.dp)) },
                            onClick = { moreMenu = false; nav.go(Screen.Files(roomId)) },
                        )
                        DropdownMenuItem(
                            text = { Text("Room settings", style = Type.small, color = p.text) },
                            leadingIcon = { Icon(Icons.Sliders, null, tint = p.muted, modifier = Modifier.size(18.dp)) },
                            onClick = { moreMenu = false; nav.go(Screen.RoomSettings(roomId)) },
                        )
                        val isKept = Hub.kept.any { it.id == room.id }
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (isKept) "Go offline" else "Leave room",
                                    style = Type.small,
                                    color = if (isKept) p.text else p.bad,
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Close,
                                    null,
                                    tint = if (isKept) p.muted else p.bad,
                                    modifier = Modifier.size(18.dp),
                                )
                            },
                            onClick = {
                                moreMenu = false
                                Hub.close(room)
                                nav.home()
                            },
                        )
                    }
                }
            } else {
                IconAction(Icons.Shield, "Verify code", { nav.go(Screen.PeerInfo(roomId, dm)) })
            }
        },
    ) {
        room.moveAsk?.let { ask ->
            Banner(
                "${ask.from} asks you to move this room to a new one" +
                    (if (ask.coming > 0) ", with ${ask.names}" else "") +
                    ". Nobody left behind is told where it went.",
                listOf("Move" to { room.answerMove(true) }, "Stay" to { room.answerMove(false) }),
            )
        }
        if (room.status == Status.FAILED) {
            val entry = Hub.kept.firstOrNull { it.id == room.id }
            Banner(
                "chat stopped: ${room.error}",
                if (entry == null) emptyList() else listOf("Try again" to {
                    Hub.rooms.remove(room.id)
                    Hub.open(entry)
                }),
                bad = true,
            )
        } else room.error?.let { err ->
            Banner(err, listOf("OK" to { room.error = null }))
            LaunchedEffect(err) { delay(6000); if (room.error == err) room.error = null }
        }

        Messages(
            room = room,
            dm = dm,
            modifier = Modifier.weight(1f),
            onReply = { room.replyTo = it },
            onDm = { nav.go(Screen.Chat(roomId, it)) },
            onOpenPicture = { num -> nav.go(Screen.PictureView(roomId, num)) },
            onSaveAs = { name, savedPath ->
                exportPath = savedPath
                saveAsLauncher.launch(name.ifBlank { "file" })
            },
            onOpenLink = { url, sender -> pendingLink = url to sender },
        )

        ComposerBar(room = room, dm = dm, dmPeer = dmPeer)
    }

    pendingLink?.let { (url, sender) ->
        LinkDialog(
            url = url,
            sender = sender,
            onDismiss = { pendingLink = null },
        )
    }
}

// Isolated from Messages so typing into drafts never triggers list recomposition.
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ComposerBar(room: Room, dm: String?, dmPeer: Peer?) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val thread = dm.orEmpty()
    val text = room.drafts[thread].orEmpty()
    val replyTo = if (dm == null) room.replyTo else null

    var attachAsIs by remember { mutableStateOf(false) }
    var attachMenu by remember { mutableStateOf(false) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) offerUri(scope, ctx, room, uri, asis = attachAsIs)
    }

    // @mention autocomplete when typing @prefix at the end of the draft.
    val mentionCandidates = remember(text, room.peers, dm) {
        if (dm != null || room.peers.isEmpty()) emptyList()
        else {
            val at = text.lastIndexOf('@')
            if (at < 0 || (at > 0 && !text[at - 1].isWhitespace())) emptyList()
            else {
                val query = text.substring(at + 1)
                if (query.any { it.isWhitespace() }) emptyList()
                else room.peers.filter {
                    it.nick.isNotBlank() && it.nick.startsWith(query, ignoreCase = true) && !it.nick.equals(query, ignoreCase = true)
                }
            }
        }
    }

    if (mentionCandidates.isNotEmpty()) {
        Hairline()
        Row(
            Modifier.fillMaxWidth().background(p.surface).horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (peer in mentionCandidates) {
                Row(
                    Modifier.clip(RoundedCornerShape(14.dp)).background(p.raised)
                        .border(1.dp, p.line, RoundedCornerShape(14.dp))
                        .clickable {
                            val at = text.lastIndexOf('@')
                            if (at >= 0) {
                                room.drafts[thread] = text.substring(0, at) + "@${peer.nick} "
                            }
                        }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(readable(peer.colour, p)))
                    Spacer(Modifier.width(6.dp))
                    Text("@${peer.nick}", style = Type.small.copy(fontWeight = FontWeight.Medium), color = p.text)
                }
            }
        }
    }

    replyTo?.let { r ->
        Hairline()
        Row(
            Modifier.fillMaxWidth().background(p.surface).padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Reply, null, tint = p.accent, modifier = Modifier.size(16.dp))
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text("Answering ${r.line.nick ?: ""}", style = Type.label, color = p.accent)
                Text(r.bodyText, style = Type.small, color = p.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconAction(Icons.Close, "Don't answer", { room.replyTo = null }, tint = p.muted)
        }
    }

    Hairline()
    val bytes = remember(text) { text.toByteArray().size }
    val nearLimit = bytes > TEXT_MAX - 120
    val open = room.status == Status.OPEN
    val canSend = text.isNotBlank() && bytes <= TEXT_MAX && open

    Row(
        Modifier
            .fillMaxWidth()
            .imePadding()
            .heightIn(min = BOTTOM_BAR_HEIGHT)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (dm == null) {
            Box {
                Box(
                    Modifier
                        .size(CONTROL_HEIGHT)
                        .clip(CircleShape)
                        .background(p.surface)
                        .border(1.dp, p.line, CircleShape)
                        .combinedClickable(
                            enabled = open,
                            onClick = { attachAsIs = false; pick.launch(arrayOf("*/*")) },
                            onLongClick = { attachMenu = true },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Attach,
                        contentDescription = "Send a file",
                        tint = if (open) p.muted else p.faint.copy(alpha = 0.5f),
                        modifier = Modifier.size(19.dp),
                    )
                }
                DropdownMenu(expanded = attachMenu, onDismissRequest = { attachMenu = false }, containerColor = p.raised) {
                    DropdownMenuItem(
                        text = { Text("Send file (clean pictures)", style = Type.small) },
                        onClick = { attachMenu = false; attachAsIs = false; pick.launch(arrayOf("*/*")) },
                    )
                    DropdownMenuItem(
                        text = { Text("Send as-is (original bytes)", style = Type.small) },
                        onClick = { attachMenu = false; attachAsIs = true; pick.launch(arrayOf("*/*")) },
                    )
                }
            }
        }

        Row(
            Modifier
                .weight(1f)
                .heightIn(min = CONTROL_HEIGHT)
                .clip(RoundedCornerShape(22.dp))
                .background(p.surface)
                .border(1.dp, p.line, RoundedCornerShape(22.dp))
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = if (nearLimit) Alignment.Bottom else Alignment.CenterVertically,
        ) {
            PrivateField(
                text,
                { room.drafts[thread] = it },
                Modifier.weight(1f),
                placeholder = if (dmPeer != null) "Only ${dmPeer.nick} gets this" else "Message",
                multiLine = true,
            )
            if (nearLimit) {
                Spacer(Modifier.width(8.dp))
                Text(
                    "${TEXT_MAX - bytes}",
                    style = Type.label,
                    color = if (bytes > TEXT_MAX) p.bad else p.faint,
                )
            }
        }

        Box(
            Modifier
                .size(CONTROL_HEIGHT)
                .clip(CircleShape)
                .background(if (canSend) p.accent else p.surface)
                .then(if (canSend) Modifier else Modifier.border(1.dp, p.line, CircleShape))
                .clickable(enabled = canSend, onClickLabel = "Send") {
                    val t = text.trim()
                    val mid = replyTo?.line?.mid
                    when {
                        dm != null -> room.dm(dm, t)
                        mid == null && t.length >= 2 && t[0] == ':' && t[1].isLetter() -> room.runCommand(t)
                        mid != null -> room.reply(mid, t)
                        else -> room.say(t)
                    }
                    room.drafts.remove(thread)
                    room.replyTo = null
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Send,
                contentDescription = "Send",
                tint = if (canSend) p.onAccent else p.faint,
                modifier = Modifier.size(19.dp),
            )
        }
    }
}

@Composable
fun Banner(text: String, actions: List<Pair<String, () -> Unit>>, bad: Boolean = false) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().background(p.surface).padding(horizontal = 20.dp, vertical = 12.dp)) {
        Text(text, style = Type.small, color = if (bad) p.bad else p.text)
        if (actions.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                for ((label, action) in actions) Text(
                    label,
                    style = Type.small.copy(fontWeight = FontWeight.SemiBold),
                    color = p.accent,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = action)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
    }
    Hairline()
}

private sealed interface DisplayRow {
    val key: String

    data class DayRule(val dayKey: Int, val label: String) : DisplayRow {
        override val key get() = "day-$dayKey"
    }

    data object HistoryRule : DisplayRow {
        override val key get() = "history-rule"
    }

    data class UnreadRule(val count: Int) : DisplayRow {
        override val key get() = "unread-rule"
    }

    data class LineRow(
        val item: Item,
        val groupedTop: Boolean = false,
        val groupedBottom: Boolean = false,
    ) : DisplayRow {
        override val key get() = "msg-${item.seq}"
    }
}

private fun sameGroup(a: Item, b: Item): Boolean {
    val la = a.line
    val lb = b.line
    if (la.kind != LineKind.MESSAGE || lb.kind != LineKind.MESSAGE) return false
    if (a.old != b.old || a.dayKey != b.dayKey) return false
    if (la.direct != lb.direct || la.peer != lb.peer) return false
    if (b.time - a.time > GROUP_WINDOW_MS) return false
    return if (la.mine && lb.mine) true
    else !la.mine && !lb.mine && la.from != null && la.from == lb.from && la.nick == lb.nick
}

private fun buildDisplayRows(items: List<Item>, firstUnreadSeq: Long?): List<DisplayRow> {
    if (items.isEmpty()) return emptyList()
    val rows = ArrayList<DisplayRow>(items.size + 8)
    var prevDay = -1
    var sawOld = false
    var historyDivided = false
    var unreadDivided = false
    val unreadCount = if (firstUnreadSeq != null) {
        items.count { it.seq >= firstUnreadSeq && !it.line.mine && it.line.kind == LineKind.MESSAGE }
    } else 0

    for (idx in items.indices) {
        val cur = items[idx]
        var brokeGroup = false
        if (cur.old) {
            sawOld = true
        } else if (sawOld && !historyDivided) {
            historyDivided = true
            brokeGroup = true
            rows += DisplayRow.HistoryRule
        }
        if (cur.dayKey != prevDay) {
            prevDay = cur.dayKey
            brokeGroup = true
            rows += DisplayRow.DayRule(cur.dayKey, cur.dayLabel)
        }
        if (!unreadDivided && firstUnreadSeq != null && unreadCount > 0 && cur.seq >= firstUnreadSeq) {
            unreadDivided = true
            brokeGroup = true
            rows += DisplayRow.UnreadRule(unreadCount)
        }

        val prev = items.getOrNull(idx - 1)
        val next = items.getOrNull(idx + 1)
        val nextBreaks = next != null && (
            (!next.old && cur.old) ||
                next.dayKey != cur.dayKey ||
                (!unreadDivided && firstUnreadSeq != null && unreadCount > 0 && next.seq >= firstUnreadSeq)
            )
        val gTop = !brokeGroup && prev != null && sameGroup(prev, cur)
        val gBottom = !nextBreaks && next != null && sameGroup(cur, next)
        rows += DisplayRow.LineRow(cur, groupedTop = gTop, groupedBottom = gBottom)
    }
    return rows.asReversed()
}

@Composable
private fun Messages(
    room: Room,
    dm: String?,
    modifier: Modifier,
    onReply: (Item) -> Unit,
    onDm: (String) -> Unit,
    onOpenPicture: (Int) -> Unit,
    onSaveAs: (String, String) -> Unit,
    onOpenLink: (String, String?) -> Unit,
) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    val state = rememberLazyListState()
    val inRoom = dm == null
    val followEnabled = Hub.settings.followChat

    val filteredItems by remember(room, dm) {
        derivedStateOf {
            if (dm != null) room.items.filter { it.line.direct && it.line.peer == dm } else room.items.toList()
        }
    }
    val rows by remember(room, dm) {
        derivedStateOf {
            buildDisplayRows(filteredItems, if (inRoom) room.firstUnreadSeq else null)
        }
    }

    // Explicit bottom-follow state: only manual user scrolling detaches from the bottom, so
    // Compose's LazyColumn key-anchoring on incoming message bursts never breaks auto-follow.
    var isAtBottom by remember(room.id, dm) { mutableStateOf(true) }
    var unseenBelow by remember(room.id, dm) { mutableIntStateOf(0) }
    var userScrolling by remember(room.id, dm) { mutableStateOf(false) }
    var programmaticScroll by remember(room.id, dm) { mutableIntStateOf(0) }
    var lastMaxSeq by remember(room.id, dm) { mutableStateOf<Long?>(null) }

    suspend fun scrollToLatest(smooth: Boolean) {
        programmaticScroll++
        try {
            if (!smooth || state.firstVisibleItemIndex > 8) {
                state.scrollToItem(0)
            } else {
                state.animateScrollToItem(0)
            }
            if (state.firstVisibleItemIndex != 0 || state.firstVisibleItemScrollOffset != 0) {
                state.scrollToItem(0)
            }
        } finally {
            programmaticScroll--
        }
    }

    val scrollConnection = remember(room.id, dm, state) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput) {
                    userScrolling = true
                }
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (source == NestedScrollSource.UserInput && programmaticScroll == 0) {
                    val atBottomNow = state.firstVisibleItemIndex == 0 && state.firstVisibleItemScrollOffset <= 24
                    isAtBottom = atBottomNow
                    if (atBottomNow) unseenBelow = 0
                }
                return Offset.Zero
            }
        }
    }

    LaunchedEffect(room.id, dm, state) {
        snapshotFlow {
            Triple(state.isScrollInProgress, state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset)
        }.collect { (inProgress, idx, off) ->
            val atBottomNow = idx == 0 && off <= 24
            if (atBottomNow && programmaticScroll == 0) {
                isAtBottom = true
                unseenBelow = 0
            } else if (userScrolling && !atBottomNow) {
                isAtBottom = false
            }
            if (!inProgress) {
                userScrolling = false
            }
        }
    }

    var prevFollowEnabled by remember(room.id, dm) { mutableStateOf(followEnabled) }
    LaunchedEffect(followEnabled) {
        if (followEnabled && !prevFollowEnabled && rows.isNotEmpty()) {
            userScrolling = false
            isAtBottom = true
            unseenBelow = 0
            scrollToLatest(smooth = true)
        }
        prevFollowEnabled = followEnabled
    }

    val curMaxSeq = filteredItems.lastOrNull()?.seq
    val picCount = room.pictures.size
    LaunchedEffect(curMaxSeq, rows.size, picCount) {
        if (rows.isEmpty()) {
            lastMaxSeq = null
            isAtBottom = true
            unseenBelow = 0
            return@LaunchedEffect
        }
        val prevSeq = lastMaxSeq
        lastMaxSeq = curMaxSeq
        if (prevSeq == null) {
            isAtBottom = true
            unseenBelow = 0
            scrollToLatest(smooth = false)
            return@LaunchedEffect
        }
        val newItems = if (curMaxSeq != null && curMaxSeq > prevSeq) {
            filteredItems.filter { it.seq > prevSeq && !it.old }
        } else {
            emptyList()
        }
        val sentByMe = newItems.any { it.line.mine }
        val newIncoming = newItems.count { !it.line.mine && it.line.kind == LineKind.MESSAGE }
        val shouldFollow = sentByMe || (followEnabled && isAtBottom)
        if (shouldFollow) {
            userScrolling = false
            isAtBottom = true
            unseenBelow = 0
            scrollToLatest(smooth = newItems.size <= 1 && state.firstVisibleItemIndex <= 2)
        } else {
            if (newIncoming > 0) unseenBelow += newIncoming
            if (state.firstVisibleItemIndex > 0 || state.firstVisibleItemScrollOffset > 24) {
                isAtBottom = false
            }
        }
    }

    if (rows.isEmpty()) {
        Box(modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
            Text(
                when {
                    room.status == Status.OPEN && inRoom && room.isCall ->
                        "Shared call room for today. Once ${room.callPeer?.nick ?: "they"} open :call for you too, you meet here."
                    room.status == Status.OPEN && inRoom ->
                        "Nothing here yet. Compare verify codes with whoever joins before you trust them."
                    inRoom -> "Opening the room…"
                    else -> "No direct messages yet."
                },
                style = Type.small,
                color = p.faint,
                textAlign = TextAlign.Center,
            )
        }
        return
    }

    Box(modifier.fillMaxWidth().nestedScroll(scrollConnection)) {
        LazyColumn(
            Modifier.fillMaxSize(),
            state = state,
            reverseLayout = true,
            contentPadding = PaddingValues(vertical = 10.dp),
        ) {
            items(rows, key = { it.key }) { row ->
                when (row) {
                    is DisplayRow.DayRule -> DividerPill(row.label, accent = false)
                    DisplayRow.HistoryRule -> DividerPill("kept history", accent = false)
                    is DisplayRow.UnreadRule -> DividerPill("${row.count} new", accent = true)
                    is DisplayRow.LineRow -> {
                        val item = row.item
                        when (item.line.kind) {
                            LineKind.NOTE -> Text(
                                item.line.text.removePrefix("* "),
                                style = Type.label,
                                color = p.faint,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 5.dp),
                            )
                            LineKind.WARN -> Row(
                                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .border(1.dp, p.warn.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                                    .padding(12.dp),
                            ) {
                                Icon(Icons.Warn, null, tint = p.warn, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(10.dp))
                                Text(item.line.text.removePrefix("* "), style = Type.small, color = p.text)
                            }
                            LineKind.MESSAGE -> Bubble(
                                room = room,
                                item = item,
                                groupedTop = row.groupedTop,
                                groupedBottom = row.groupedBottom,
                                inRoom = inRoom,
                                onReply = onReply,
                                onDm = onDm,
                                onOpenPicture = onOpenPicture,
                                onSaveAs = onSaveAs,
                                onOpenLink = onOpenLink,
                            )
                        }
                    }
                }
            }
        }

        if (!isAtBottom || unseenBelow > 0) {
            val hasNew = unseenBelow > 0
            Row(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 14.dp)
                    .shadow(4.dp, CircleShape)
                    .height(38.dp)
                    .then(if (hasNew) Modifier else Modifier.width(38.dp))
                    .clip(CircleShape)
                    .background(p.raised)
                    .border(1.dp, if (hasNew) p.accent.copy(alpha = 0.55f) else p.line, CircleShape)
                    .clickable(onClickLabel = "Jump to latest messages") {
                        userScrolling = false
                        isAtBottom = true
                        unseenBelow = 0
                        scope.launch { scrollToLatest(smooth = true) }
                    }
                    .then(if (hasNew) Modifier.padding(horizontal = 13.dp) else Modifier),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Icon(
                    Icons.ArrowDown,
                    contentDescription = if (hasNew) "$unseenBelow new messages" else "Scroll to bottom",
                    tint = if (hasNew) p.accent else p.text,
                    modifier = Modifier.size(if (hasNew) 15.dp else 18.dp),
                )
                if (hasNew) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "$unseenBelow new",
                        style = Type.label.copy(fontWeight = FontWeight.SemiBold),
                        color = p.accent,
                    )
                }
            }
        }
    }
}

@Composable
private fun DividerPill(label: String, accent: Boolean) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).height(1.dp).background(if (accent) p.accent.copy(alpha = 0.45f) else p.line))
        Text(
            label,
            style = Type.label.copy(fontWeight = if (accent) FontWeight.SemiBold else FontWeight.Normal),
            color = if (accent) p.accent else p.faint,
            modifier = Modifier.padding(horizontal = 10.dp),
        )
        Box(Modifier.weight(1f).height(1.dp).background(if (accent) p.accent.copy(alpha = 0.45f) else p.line))
    }
}

// As chat's mention_len matches them: any case, and not run on into a longer name.
private fun mentionAt(s: String, i: Int, nick: String): Boolean {
    if (!s.regionMatches(i + 1, nick, 0, nick.length, ignoreCase = true)) return false
    val end = i + 1 + nick.length
    if (end >= s.length) return true
    val c = s.codePointAt(end)
    return !(if (c < 0x80) c.toChar().isLetterOrDigit() || c == '-'.code || c == '_'.code else c in 0xc0..0x24f && c != 0xd7 && c != 0xf7)
}

private fun richMessageText(
    item: Item,
    selfNick: String?,
    mentionStyle: SpanStyle,
    linkStyle: SpanStyle,
    onLinkClick: (String) -> Unit,
): AnnotatedString = buildAnnotatedString {
    val text = item.bodyText
    append(text)
    if (!selfNick.isNullOrEmpty()) {
        var i = text.indexOf('@')
        while (i >= 0) {
            val insideLink = item.links.any { i >= it.start && i < it.end }
            if (!insideLink && mentionAt(text, i, selfNick)) {
                addStyle(mentionStyle, i, i + 1 + selfNick.length)
            }
            i = text.indexOf('@', i + 1)
        }
    }
    for (link in item.links) {
        addLink(
            LinkAnnotation.Clickable(
                tag = link.url,
                styles = TextLinkStyles(style = linkStyle),
                linkInteractionListener = { onLinkClick(link.url) },
            ),
            start = link.start,
            end = link.end,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Bubble(
    room: Room,
    item: Item,
    groupedTop: Boolean,
    groupedBottom: Boolean,
    inRoom: Boolean,
    onReply: (Item) -> Unit,
    onDm: (String) -> Unit,
    onOpenPicture: (Int) -> Unit,
    onSaveAs: (String, String) -> Unit,
    onOpenLink: (String, String?) -> Unit,
) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val l = item.line
    val mine = l.mine
    val sender = room.peer(l.from)
    val via = l.peer?.takeIf { it != l.from && !l.direct }?.let { room.peer(it) }
    var menu by remember { mutableStateOf(false) }

    val canSwipeReply = inRoom && l.mid != null && !l.direct
    var dragPx by remember { mutableFloatStateOf(0f) }
    var triggered by remember { mutableStateOf(false) }
    val thresholdPx = with(density) { 52.dp.toPx() }

    val topCorner = if (groupedTop) 5.dp else 15.dp
    val bottomCorner = if (groupedBottom) 5.dp else 15.dp
    val bubbleShape = if (mine) {
        RoundedCornerShape(topStart = 15.dp, bottomStart = 15.dp, topEnd = topCorner, bottomEnd = bottomCorner)
    } else {
        RoundedCornerShape(topStart = topCorner, bottomStart = bottomCorner, topEnd = 15.dp, bottomEnd = 15.dp)
    }

    Column(
        Modifier.fillMaxWidth()
            .padding(
                start = 12.dp,
                end = 12.dp,
                top = if (groupedTop) 1.dp else 5.dp,
                bottom = if (groupedBottom) 1.dp else 4.dp,
            )
            .then(
                if (canSwipeReply) Modifier.pointerInput(item.seq) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            dragPx = 0f
                            triggered = false
                        },
                        onDragCancel = {
                            dragPx = 0f
                            triggered = false
                        },
                    ) { change, dragAmount ->
                        val next = (dragPx + (if (mine) -dragAmount else dragAmount)).coerceIn(0f, thresholdPx * 1.25f)
                        if (next > 4f) change.consume()
                        dragPx = next
                        if (!triggered && dragPx >= thresholdPx) {
                            triggered = true
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onReply(item)
                        }
                    }
                } else Modifier,
            ),
        horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
    ) {
        if (!mine && !groupedTop) {
            Row(Modifier.padding(start = 6.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    l.nick ?: "?",
                    style = Type.small.copy(fontWeight = FontWeight.SemiBold),
                    color = readable(l.colour ?: sender?.colour, p),
                )
                if (via != null) Text("  via ${via.nick}", style = Type.label, color = p.faint)
                val code = sender?.code?.state
                if (code == "compare" || code == "differs" || code == "key-changed") {
                    Tag(
                        when (code) {
                            "differs" -> "codes differ"
                            "key-changed" -> "key changed"
                            else -> "code not compared"
                        },
                        if (code == "compare") p.faint else p.bad,
                        Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
        if (l.direct && !groupedTop) {
            val dmTarget = l.peer
            Text(
                if (mine) "direct to ${room.peer(dmTarget)?.nick ?: "them"}" else "direct to you",
                style = Type.label,
                color = p.accent,
                modifier = Modifier
                    .then(if (inRoom && dmTarget != null) Modifier.clickable { onDm(dmTarget) } else Modifier)
                    .padding(horizontal = 6.dp, vertical = 1.dp),
            )
        }

        Box(contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
            if (dragPx > 8f) {
                Icon(
                    Icons.Reply,
                    null,
                    tint = if (dragPx >= thresholdPx) p.accent else p.faint,
                    modifier = Modifier.padding(horizontal = 8.dp).size(18.dp),
                )
            }
            Column(
                Modifier
                    .offset { IntOffset((if (mine) -dragPx else dragPx).roundToInt(), 0) }
                    .widthIn(max = 320.dp)
                    .clip(bubbleShape)
                    .background(if (mine) p.mine else p.surface)
                    .then(if (mine) Modifier else Modifier.border(1.dp, p.line, bubbleShape))
                    .combinedClickable(onClick = {}, onLongClick = { menu = true })
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                if (item.quoteText != null) {
                    Row(
                        Modifier.clip(RoundedCornerShape(6.dp))
                            .background(p.bg.copy(alpha = 0.35f))
                            .padding(horizontal = 8.dp, vertical = 5.dp)
                            .padding(bottom = 2.dp),
                    ) {
                        Box(Modifier.width(2.dp).heightIn(min = 16.dp).background(p.accent.copy(alpha = 0.7f)))
                        Text(
                            item.quoteText.removePrefix("↪").trim(),
                            style = Type.small,
                            color = p.muted,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                    Spacer(Modifier.height(5.dp))
                }
                val file = l.file
                when {
                    // A file's number is only good for the session that offered it.
                    file != null && item.old -> Text(item.bodyText.substringBefore(" - :"), style = Type.small, color = p.muted)
                    file != null -> FileCard(
                        room = room,
                        num = file,
                        mine = l.mine,
                        onOpenPicture = { onOpenPicture(file) },
                        onSaveAs = onSaveAs,
                    )
                    else -> {
                        val selfNick = room.info?.me?.nick
                        val mentionStyle = SpanStyle(
                            color = p.accent,
                            fontWeight = FontWeight.SemiBold,
                            background = p.accent.copy(alpha = 0.14f),
                        )
                        val linkStyle = SpanStyle(
                            color = p.accent,
                            textDecoration = TextDecoration.Underline,
                        )
                        val styled = remember(item.seq, selfNick, p.dark) {
                            richMessageText(item, selfNick, mentionStyle, linkStyle) { url ->
                                onOpenLink(url, if (mine) null else l.nick)
                            }
                        }
                        Text(styled, style = Type.body, color = p.text)
                    }
                }
            }

            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = p.raised) {
                if (l.mid != null && !l.direct && inRoom) {
                    DropdownMenuItem(text = { Text("Answer", style = Type.small) }, onClick = { menu = false; onReply(item) })
                }
                if (l.file == null) {
                    DropdownMenuItem(text = { Text("Copy", style = Type.small) }, onClick = { menu = false; copyText(ctx, item.bodyText) })
                }
                for (link in item.links) {
                    DropdownMenuItem(
                        text = { Text("Open ${link.url.take(32)}${if (link.url.length > 32) "…" else ""}", style = Type.small) },
                        onClick = { menu = false; onOpenLink(link.url, if (mine) null else l.nick) },
                    )
                }
                if (!mine && sender?.dm == true && inRoom) {
                    DropdownMenuItem(
                        text = { Text("Message ${sender.nick} directly", style = Type.small) },
                        onClick = { menu = false; onDm(sender.id) },
                    )
                }
                if (!mine && sender != null) {
                    DropdownMenuItem(
                        text = { Text(if (sender.ignored) "Unignore ${sender.nick}" else "Ignore ${sender.nick}", style = Type.small) },
                        onClick = { menu = false; room.ignore(sender.id, !sender.ignored) },
                    )
                }
            }
        }

        val own = l.own
        val tick = if (mine && own != null && !item.old) room.ticks[own] else null
        if (!groupedBottom || tick == Tick.MISSED) {
            Row(Modifier.padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(item.timeText, style = Type.label, color = p.faint)
                if (tick != null) {
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        when (tick) {
                            Tick.DELIVERED -> Icons.Checks
                            Tick.MISSED -> Icons.Warn
                            else -> Icons.Check
                        },
                        when (tick) {
                            Tick.DELIVERED -> "Everyone has it"
                            Tick.MISSED -> "Someone didn't get it"
                            else -> "Sent"
                        },
                        tint = when (tick) {
                            Tick.DELIVERED -> p.accent
                            Tick.MISSED -> p.warn
                            else -> p.faint
                        },
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun FileCard(
    room: Room,
    num: Int,
    mine: Boolean,
    onOpenPicture: () -> Unit,
    onSaveAs: (String, String) -> Unit,
) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val f: FileItem? = room.files[num]
    val pic = room.pictures[num]
    val hidden = room.hiddenPictures[num] == true
    val bitmap = if (!hidden) pic?.bitmap else null

    if (bitmap != null) {
        val image = remember(bitmap) { bitmap.asImageBitmap() }
        // As wide as a bubble allows, and no taller than a screenful's half.
        val ratio = bitmap.width.toFloat() / bitmap.height
        val h = minOf(PICTURE_W / ratio, PICTURE_H_MAX)
        Image(
            image,
            f?.name,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .padding(bottom = 6.dp)
                .size((h * ratio).dp, h.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onOpenPicture),
        )
    }
    if (f == null) {
        Text("file $num, no longer offered", style = Type.small, color = p.faint)
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(if (f.image) Icons.Image else Icons.File, null, tint = p.muted, modifier = Modifier.size(20.dp))
        Column(Modifier.padding(start = 10.dp).widthIn(max = 240.dp)) {
            Text(f.name, style = Type.small.copy(fontWeight = FontWeight.Medium), color = p.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val state = fileStatusText(f)
            Text(
                listOfNotNull(sizeText(f.size), state).joinToString(" · "),
                style = Type.label,
                color = if (f.state == "failed") p.bad else p.muted,
            )
            pic?.error?.let { Text("can't show it: $it", style = Type.label, color = p.bad) }
        }
    }
    if (f.state == "fetching" && f.got != null && f.size > 0) {
        LinearProgressIndicator(
            progress = { (f.got.toFloat() / f.size).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(3.dp),
            color = p.accent,
            trackColor = p.line,
        )
    }
    val over = f.size > Hub.settings.fileLimitMb * 1024L * 1024L
    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        @Composable
        fun act(label: String, onClick: () -> Unit) = Text(
            label,
            style = Type.small.copy(fontWeight = FontWeight.SemiBold),
            color = p.accent,
            modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onClick)
                .padding(horizontal = 6.dp, vertical = 4.dp),
        )
        when {
            mine -> {
                if (f.image && bitmap != null) act("Hide") { room.hidePicture(num) }
                else if (f.image) act("Show") { room.show(num, anyway = true) }
                act("Withdraw") { room.cancel(num) }
            }
            f.state == "fetching" || f.state == "queued" -> act("Stop") { room.cancel(num) }
            else -> {
                if (f.image) {
                    if (bitmap != null) act("Hide") { room.hidePicture(num) }
                    else act(if (over) "Show anyway" else "Show") { room.show(num, over) }
                }
                if (f.saved == null) {
                    act(if (over) "Save anyway" else "Save") { room.save(num, over) }
                } else {
                    act("Open") { openReceived(ctx, f.saved) }
                    act("Save as…") { onSaveAs(f.name, f.saved) }
                }
            }
        }
    }
}

@Composable
fun LinkDialog(url: String, sender: String?, onDismiss: () -> Unit) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = p.surface,
        title = {
            Text(
                if (sender != null) "Open link from $sender?" else "Open link?",
                style = Type.title.copy(fontSize = Type.body.fontSize * 1.15f),
                color = p.text,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(p.bg)
                        .border(1.dp, p.line, RoundedCornerShape(8.dp)).padding(10.dp),
                ) {
                    Text(url, style = Type.label, color = p.text)
                }
                Text(
                    "Your browser opens this directly — not through chat's Tor or relays — so the site sees your IP address.",
                    style = Type.small,
                    color = p.warn,
                )
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { copyText(ctx, url); onDismiss() }) {
                    Text("Copy", color = p.muted, style = Type.small)
                }
                TextButton(onClick = { onDismiss(); openUrl(ctx, url) }) {
                    Text("Open", color = p.accent, style = Type.small.copy(fontWeight = FontWeight.SemiBold))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = p.muted, style = Type.small)
            }
        },
    )
}
