package moe.vin.chat.ui

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.vin.chat.Hub
import moe.vin.chat.engine.Room
import moe.vin.chat.engine.Status
import moe.vin.chat.engine.Tor

private val clipHandler = Handler(Looper.getMainLooper())
private var clipToken = 0
private var clearRunnable: Runnable? = null

// Marked sensitive so Android hides it in its clipboard preview, and cleared after 30 seconds.
fun copySecret(ctx: Context, label: String, text: String, clearAfterMs: Long = 30_000L) {
    val appCtx = ctx.applicationContext
    val cm = appCtx.getSystemService(ClipboardManager::class.java)
    val token = ++clipToken
    val clip = ClipData.newPlainText(label, text)
    clip.description.extras = PersistableBundle().apply {
        putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
        putInt("moe.vin.chat.token", token)
    }
    cm.setPrimaryClip(clip)
    clearRunnable?.let(clipHandler::removeCallbacks)
    if (clearAfterMs > 0L) {
        val r = Runnable {
            if (clipToken == token) {
                val cur = cm.primaryClipDescription?.extras?.getInt("moe.vin.chat.token", -1) ?: -1
                if (cur == token || cur == -1) clearClipboardNow(cm)
            }
        }
        clearRunnable = r
        clipHandler.postDelayed(r, clearAfterMs)
    }
    Toast.makeText(ctx, "Copied (clears in 30s)", Toast.LENGTH_SHORT).show()
}

fun clearClipboard(ctx: Context) {
    clipToken++
    clearRunnable?.let(clipHandler::removeCallbacks)
    clearRunnable = null
    val cm = ctx.applicationContext.getSystemService(ClipboardManager::class.java)
    clearClipboardNow(cm)
}

private fun clearClipboardNow(cm: ClipboardManager) {
    runCatching { cm.clearPrimaryClip() }.onFailure {
        runCatching { cm.setPrimaryClip(ClipData.newPlainText("", "")) }
    }
}

fun copyText(ctx: Context, text: String) {
    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("message", text))
    Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
}

fun readClipboard(ctx: Context): String? {
    val cm = ctx.getSystemService(ClipboardManager::class.java)
    val item = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0) ?: return null
    return item.coerceToText(ctx)?.toString()?.takeIf { it.isNotBlank() }
}

fun shareText(ctx: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    ctx.startActivity(Intent.createChooser(send, null))
}

fun openUrl(ctx: Context, url: String) {
    try {
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(view)
    } catch (e: Exception) {
        Toast.makeText(ctx, "No browser here opens it", Toast.LENGTH_SHORT).show()
    }
}

// A file a peer sent, handed to the app the user picks to open it with.
fun openReceived(ctx: Context, path: String) {
    val f = File(path)
    if (!f.isFile) {
        Toast.makeText(ctx, "File is no longer there", Toast.LENGTH_SHORT).show()
        return
    }
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
    val type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "application/octet-stream"
    val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        ctx.startActivity(Intent.createChooser(view, null))
    } catch (e: Exception) {
        Toast.makeText(ctx, "No app here opens it", Toast.LENGTH_SHORT).show()
    }
}

suspend fun exportFileToUri(ctx: Context, sourcePath: String, destUri: Uri): Boolean = withContext(Dispatchers.IO) {
    try {
        val src = File(sourcePath)
        if (!src.isFile) return@withContext false
        ctx.contentResolver.openOutputStream(destUri)?.use { out ->
            src.inputStream().use { inp -> inp.copyTo(out) }
        } != null
    } catch (e: Exception) {
        false
    }
}

// A copy of a file picked to send, where chat can read it: it reads the file again as each peer
// fetches it, so the copy stays until the room closes.
suspend fun copyToOutbox(ctx: Context, room: Room, uri: Uri): File? = withContext(Dispatchers.IO) {
    try {
        val name = ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }?.replace('/', '_')?.takeIf { it.isNotBlank() } ?: "file"
        val dir = Hub.outboxDir(room.id).apply { mkdirs() }
        var f = File(dir, name)
        var n = 2
        while (f.exists()) {
            val stem = name.substringBeforeLast('.')
            val ext = if ('.' in name) "." + name.substringAfterLast('.') else ""
            f = File(dir, "$stem ($n)$ext")
            n++
        }
        val ok = ctx.contentResolver.openInputStream(uri)?.use { input ->
            f.outputStream().use { input.copyTo(it) }
            true
        } ?: false
        if (ok) f else null
    } catch (e: Exception) {
        null
    }
}

fun roomState(room: Room): String {
    val net = room.net
    return when (room.status) {
        Status.STARTING -> if (room.info == null && Hub.tor.state.phase == Tor.Phase.STARTING)
            "waiting for tor ${Hub.tor.state.progress}%" else "starting…"
        Status.OPEN -> when {
            net == null -> "opening…"
            !net.ready -> if (room.info?.created == true) "open · nobody here yet" else "connecting…"
            net.online == 0 -> if (room.isCall) "waiting for ${room.callPeer?.nick ?: "peer"}" else "nobody else here"
            else -> if (net.online == 1) "1 peer" else "${net.online} peers"
        }
        Status.CLOSED -> "closed"
        Status.FAILED -> "stopped" + (room.error?.let { ": $it" } ?: "")
    }
}

fun sizeText(n: Long): String = when {
    n < 1024 -> "$n B"
    n < 1024 * 1024 -> "${(n + 512) / 1024} KB"
    n < 1024L * 1024 * 1024 -> "%.1f MB".format(n / 1048576.0)
    else -> "%.2f GB".format(n / 1073741824.0)
}

fun durationText(seconds: Int): String = when {
    seconds <= 0 -> "off"
    seconds % 604800 == 0 -> "${seconds / 604800}w"
    seconds % 86400 == 0 -> "${seconds / 86400}d"
    seconds % 3600 == 0 -> "${seconds / 3600}h"
    seconds % 60 == 0 -> "${seconds / 60}m"
    else -> "${seconds}s"
}
