package moe.vin.chat.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import moe.vin.chat.Hub
import moe.vin.chat.engine.FileItem
import moe.vin.chat.engine.Room
import moe.vin.chat.engine.Status

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FilesScreen(nav: Nav, roomId: String) {
    val room = Hub.rooms[roomId]
    if (room == null) {
        LaunchedEffect(Unit) { nav.back() }
        return
    }
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var sendAsIs by remember { mutableStateOf(false) }
    val pickSend = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val asIs = sendAsIs
            scope.launch {
                val f = copyToOutbox(ctx, room, uri)
                if (f == null) Toast.makeText(ctx, "Couldn't read that file", Toast.LENGTH_SHORT).show()
                else room.offer(f.path, asis = asIs)
            }
        }
    }

    var exportSource by remember { mutableStateOf<String?>(null) }
    var waitingExportNum by remember { mutableStateOf<Int?>(null) }
    val createDoc = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val src = exportSource
        exportSource = null
        if (uri != null && src != null) {
            scope.launch {
                val ok = exportFileToUri(ctx, src, uri)
                Toast.makeText(ctx, if (ok) "Saved" else "Couldn't save file", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val list = remember(room.files) { room.files.values.sortedByDescending { it.num } }

    // If the user tapped "Save as…" before the file was fetched, launch SAF as soon as chat finishes saving it.
    val waitNum = waitingExportNum
    val waitedSaved = waitNum?.let { room.files[it]?.saved }
    LaunchedEffect(waitNum, waitedSaved) {
        if (waitNum != null && waitedSaved != null) {
            val f = room.files[waitNum]
            waitingExportNum = null
            exportSource = waitedSaved
            createDoc.launch(f?.name?.ifBlank { "file" } ?: "file")
        }
    }

    val activeCount = list.count { it.state == "fetching" || it.state == "queued" || it.sendingCount > 0 }
    val subtitle = when {
        activeCount > 0 -> "${list.size} files · $activeCount transferring"
        list.isNotEmpty() -> "${list.size}"
        else -> "none"
    }

    Page("Files", subtitle, onBack = nav::back) {
        if (list.isEmpty()) {
            Column(
                Modifier.weight(1f).fillMaxWidth().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Folder, null, tint = p.faint, modifier = Modifier.size(32.dp))
                Spacer(Modifier.height(10.dp))
                Text("No files", style = Type.body.copy(fontWeight = FontWeight.Medium), color = p.text)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Pictures are re-encoded without metadata; Send as-is keeps the original bytes.",
                    style = Type.small, color = p.muted, textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(list, key = { it.num }) { f ->
                    FileRow(
                        room = room,
                        f = f,
                        onOpenPicture = { nav.go(Screen.PictureView(roomId, f.num)) },
                        onSaveAs = {
                            if (f.saved != null) {
                                exportSource = f.saved
                                createDoc.launch(f.name.ifBlank { "file" })
                            } else {
                                val over = f.size > Hub.settings.fileLimitMb * 1024L * 1024L
                                waitingExportNum = f.num
                                room.save(f.num, over)
                                Toast.makeText(ctx, "Fetching first…", Toast.LENGTH_SHORT).show()
                            }
                        },
                    )
                    Hairline()
                }
            }
        }
        BottomBar {
            Button(
                "Send file",
                onClick = { sendAsIs = false; pickSend.launch(arrayOf("*/*")) },
                modifier = Modifier.weight(1f),
                enabled = room.status == Status.OPEN,
                icon = Icons.Attach,
            )
            Button(
                "Send as-is",
                onClick = { sendAsIs = true; pickSend.launch(arrayOf("*/*")) },
                modifier = Modifier.weight(1f),
                primary = false,
                enabled = room.status == Status.OPEN,
                icon = Icons.File,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FileRow(
    room: Room,
    f: FileItem,
    onOpenPicture: () -> Unit,
    onSaveAs: () -> Unit,
) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val pic = room.pictures[f.num]
    val hidden = room.hiddenPictures[f.num] == true
    val bitmap = if (!hidden) pic?.bitmap else null
    val over = f.size > Hub.settings.fileLimitMb * 1024L * 1024L

    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (bitmap != null) {
                val img = remember(bitmap) { bitmap.asImageBitmap() }
                Image(
                    img, f.name, contentScale = ContentScale.Crop,
                    modifier = Modifier.size(52.dp).clip(RoundedCornerShape(8.dp))
                        .border(1.dp, p.line, RoundedCornerShape(8.dp))
                        .clickable(onClick = onOpenPicture),
                )
            } else {
                Box(
                    Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(p.surface)
                        .border(1.dp, p.line, RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(if (f.image) Icons.Image else Icons.File, null, tint = p.muted, modifier = Modifier.size(20.dp))
                }
            }
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        f.name.ifBlank { "file #${f.num}" },
                        style = Type.body.copy(fontWeight = FontWeight.Medium),
                        color = p.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(8.dp))
                    Tag(if (f.mine) "yours" else f.from.ifBlank { "peer" }, if (f.mine) p.accent else p.muted)
                }
                Spacer(Modifier.height(3.dp))
                val state = fileStatusText(f)
                Text(
                    listOfNotNull("#${f.num}", sizeText(f.size), state, f.at.takeIf { it.isNotBlank() }).joinToString(" · "),
                    style = Type.label,
                    color = if (f.state == "failed") p.bad else p.muted,
                )
                pic?.error?.let { Text("can't show it: $it", style = Type.label, color = p.bad) }
            }
        }

        if (f.state == "fetching" && f.got != null && f.size > 0) {
            LinearProgressIndicator(
                progress = { (f.got.toFloat() / f.size).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp).height(3.dp),
                color = p.accent,
                trackColor = p.line,
            )
        } else if (f.mine && f.sendingCount > 0 && f.permille != null) {
            LinearProgressIndicator(
                progress = { (f.permille / 1000f).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp).height(3.dp),
                color = p.accent,
                trackColor = p.line,
            )
        }

        FlowRow(
            modifier = Modifier.padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            @Composable
            fun chip(label: String, danger: Boolean = false, onClick: () -> Unit) {
                Text(
                    label,
                    style = Type.small.copy(fontWeight = FontWeight.SemiBold),
                    color = if (danger) p.bad else p.accent,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(p.surface)
                        .border(1.dp, p.line, RoundedCornerShape(8.dp))
                        .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }

            if (f.image) {
                if (bitmap != null) {
                    chip("View", onClick = onOpenPicture)
                    chip("Hide") { room.hidePicture(f.num) }
                } else {
                    chip(if (over && !f.mine) "Show anyway" else "Show") { room.show(f.num, over || f.mine) }
                }
            }

            when {
                f.mine -> chip("Withdraw", danger = true) { room.cancel(f.num) }
                f.state == "fetching" || f.state == "queued" -> chip("Stop", danger = true) { room.cancel(f.num) }
                else -> {
                    if (f.saved == null) {
                        chip(if (over) "Save anyway" else "Save") { room.save(f.num, over) }
                        chip("Save as…", onClick = onSaveAs)
                    } else {
                        chip("Open") { openReceived(ctx, f.saved) }
                        chip("Save as…", onClick = onSaveAs)
                    }
                }
            }
        }
    }
}

fun fileStatusText(f: FileItem): String? = when {
    f.mine && f.sendingCount > 0 ->
        "sending to ${f.sendingName}${if (f.sendingCount > 1) " +${f.sendingCount - 1}" else ""} · ${(f.permille ?: 0) / 10}%"
    f.mine && f.sentTo != null -> "sent to ${f.sentTo}"
    f.mine -> "offered"
    f.state == "fetching" -> when (f.waiting) {
        "verify" -> "needs verify code"
        "sender" -> "waiting for sender"
        else -> "${sizeText(f.got ?: 0)} of ${sizeText(f.size)}" + (f.eta?.let { " · ${it}s left" } ?: "")
    }
    f.state == "queued" -> "queued after #${f.after ?: ""}"
    f.state == "failed" -> "failed" + (f.why?.let { ": $it" } ?: "")
    f.saved != null -> "saved"
    else -> null
}

@Composable
fun PictureScreen(nav: Nav, roomId: String, initialNum: Int) {
    val room = Hub.rooms[roomId]
    if (room == null) {
        LaunchedEffect(Unit) { nav.back() }
        return
    }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    val shownNums = remember(room.pictures.keys.toList(), room.hiddenPictures.toMap()) {
        room.pictures.entries
            .filter { (k, v) -> v.bitmap != null && room.hiddenPictures[k] != true }
            .map { it.key }
            .sorted()
    }
    var curNum by remember(initialNum) { mutableIntStateOf(initialNum) }
    val pic = room.pictures[curNum]
    val f = room.files[curNum]
    val bitmap = pic?.bitmap

    var scale by remember(curNum) { mutableFloatStateOf(1f) }
    var offset by remember(curNum) { mutableStateOf(Offset.Zero) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }

    var waitingSaveAs by remember { mutableStateOf(false) }
    val createDoc = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val saved = room.files[curNum]?.saved
        if (uri != null && saved != null) {
            scope.launch {
                val ok = exportFileToUri(ctx, saved, uri)
                Toast.makeText(ctx, if (ok) "Saved" else "Couldn't save picture", Toast.LENGTH_SHORT).show()
            }
        }
    }
    val curSaved = f?.saved
    LaunchedEffect(waitingSaveAs, curSaved) {
        if (waitingSaveAs && curSaved != null) {
            waitingSaveAs = false
            createDoc.launch(f.name.ifBlank { "picture.jpg" })
        }
    }

    val bg = Color(0xFF090A0C)
    val fg = Color(0xFFE7E5E4)
    val muted = Color(0xFF9CA3AF)

    Column(Modifier.fillMaxSize().background(bg)) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().height(56.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconAction(Icons.Back, "Back", nav::back, tint = fg)
            Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                Text(
                    pic?.name?.ifBlank { f?.name } ?: "Picture #$curNum",
                    style = Type.body.copy(fontWeight = FontWeight.SemiBold),
                    color = fg,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val meta = listOfNotNull(
                    f?.let { if (it.mine) "yours" else "from ${it.from}" },
                    pic?.takeIf { it.srcW > 0 && it.srcH > 0 }?.let { "${it.srcW}×${it.srcH}" },
                    f?.let { sizeText(it.size) },
                ).joinToString(" · ")
                if (meta.isNotEmpty()) Text(meta, style = Type.label, color = muted, maxLines = 1)
            }
            if (f != null && !f.mine) {
                IconAction(Icons.Download, "Save as…", {
                    if (f.saved != null) {
                        createDoc.launch(f.name.ifBlank { "picture.jpg" })
                    } else {
                        waitingSaveAs = true
                        room.save(curNum, anyway = true)
                        Toast.makeText(ctx, "Fetching original…", Toast.LENGTH_SHORT).show()
                    }
                }, tint = fg)
            }
            IconAction(Icons.EyeOff, "Hide picture", {
                room.hidePicture(curNum)
                nav.back()
            }, tint = fg)
        }

        Box(
            Modifier.weight(1f).fillMaxWidth().clipToBounds()
                .onSizeChanged { boxSize = it }
                .pointerInput(curNum) {
                    detectTapGestures(
                        onDoubleTap = {
                            if (scale > 1.05f) {
                                scale = 1f
                                offset = Offset.Zero
                            } else {
                                scale = 2.5f
                            }
                        },
                    )
                }
                .pointerInput(curNum) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val nextScale = (scale * zoom).coerceIn(1f, 5f)
                        if (nextScale <= 1.01f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            val maxX = (boxSize.width * (nextScale - 1f)) / 2f
                            val maxY = (boxSize.height * (nextScale - 1f)) / 2f
                            scale = nextScale
                            offset = Offset(
                                (offset.x + pan.x).coerceIn(-maxX, maxX),
                                (offset.y + pan.y).coerceIn(-maxY, maxY),
                            )
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap != null) {
                val image = remember(bitmap) { bitmap.asImageBitmap() }
                Image(
                    image,
                    contentDescription = pic.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
                )
            } else {
                Text(pic?.error ?: "Picture unavailable", style = Type.body, color = muted)
            }
        }

        if (shownNums.size > 1) {
            val idx = shownNums.indexOf(curNum)
            Row(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Previous",
                    style = Type.small,
                    color = if (idx > 0) fg else muted.copy(alpha = 0.35f),
                    modifier = Modifier.clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = idx > 0) { curNum = shownNums[idx - 1] }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
                Text("${idx + 1} / ${shownNums.size}", style = Type.label, color = muted)
                Text(
                    "Next",
                    style = Type.small,
                    color = if (idx in 0 until shownNums.lastIndex) fg else muted.copy(alpha = 0.35f),
                    modifier = Modifier.clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = idx in 0 until shownNums.lastIndex) { curNum = shownNums[idx + 1] }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        } else {
            Spacer(Modifier.navigationBarsPadding())
        }
    }
}

