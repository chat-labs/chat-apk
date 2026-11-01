package moe.vin.chat.ui

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PersistableBundle
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import moe.vin.chat.engine.Room
import moe.vin.chat.engine.Status

// Marked sensitive, so Android doesn't show it in its clipboard preview.
fun copySecret(ctx: Context, label: String, text: String) {
    val clip = ClipData.newPlainText(label, text)
    clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
    Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
}

fun copyText(ctx: Context, text: String) {
    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("message", text))
    Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
}

fun shareText(ctx: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    ctx.startActivity(Intent.createChooser(send, null))
}

// A file a peer sent, handed to the app the user picks to open it with.
fun openReceived(ctx: Context, path: String) {
    val f = File(path)
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
    val type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "application/octet-stream"
    val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        ctx.startActivity(Intent.createChooser(view, null))
    } catch (e: Exception) {
        Toast.makeText(ctx, "No app here opens it", Toast.LENGTH_SHORT).show()
    }
}

// A copy of a file picked to send, where chat can read it: it reads the file again as each peer
// fetches it, so the copy stays until the room closes.
fun copyToOutbox(ctx: Context, room: Room, uri: Uri): File? = try {
    val name = ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }?.replace('/', '_')?.takeIf { it.isNotBlank() } ?: "file"
    val dir = moe.vin.chat.Hub.outboxDir(room.id).apply { mkdirs() }
    var f = File(dir, name)
    var n = 2
    while (f.exists()) f = File(dir, "${name.substringBeforeLast('.')} ($n)${if ('.' in name) "." + name.substringAfterLast('.') else ""}").also { n++ }
    ctx.contentResolver.openInputStream(uri)?.use { input -> f.outputStream().use { input.copyTo(it) } }
    f
} catch (e: Exception) {
    null
}

fun roomState(room: Room): String {
    val net = room.net
    return when (room.status) {
        Status.STARTING -> if (room.info == null && moe.vin.chat.Hub.tor.state.phase == moe.vin.chat.engine.Tor.Phase.STARTING)
            "waiting for tor ${moe.vin.chat.Hub.tor.state.progress}%" else "starting…"
        Status.OPEN -> when {
            net == null -> "opening…"
            !net.ready -> if (room.info?.created == true) "open · nobody here yet" else "connecting…"
            net.online == 0 -> "nobody else here"
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
