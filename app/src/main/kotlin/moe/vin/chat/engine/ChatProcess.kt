package moe.vin.chat.engine

import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread
import org.json.JSONObject

// One `chat --json`: one room. Events come back on the main thread.
class ChatProcess(
    private val exe: File,
    private val home: File,
    private val onEvent: (Event) -> Unit,
    private val onExit: (code: Int, stderr: List<String>) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val outbox = LinkedBlockingQueue<String>()
    private val stderr = ArrayDeque<String>()
    @Volatile private var process: Process? = null

    fun start(commands: List<JSONObject>) {
        val tmp = File(home, "tmp")
        tmp.mkdirs()
        // chat's temporary folders have to be its user's alone.
        home.setReadable(false, false); home.setReadable(true, true)
        tmp.setReadable(false, false); tmp.setReadable(true, true)
        val pb = ProcessBuilder(exe.path, "--json").directory(home)
        pb.environment().clear()
        pb.environment()["HOME"] = home.path
        pb.environment()["TMPDIR"] = tmp.path
        val p = pb.start()
        process = p
        commands.forEach { outbox.put(it.toString()) }
        thread(name = "chat-out", isDaemon = true) {
            p.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    val event = try { parseEvent(line) } catch (e: Exception) { null } ?: continue
                    main.post { onEvent(event) }
                }
            }
        }
        thread(name = "chat-err", isDaemon = true) {
            p.errorStream.bufferedReader().useLines { lines ->
                for (line in lines) synchronized(stderr) {
                    stderr.addLast(line)
                    if (stderr.size > 20) stderr.removeFirst()
                }
            }
        }
        thread(name = "chat-in", isDaemon = true) {
            val w = p.outputStream.bufferedWriter()
            try {
                while (true) {
                    val s = outbox.take()
                    if (s === CLOSE) break
                    w.write(s)
                    w.write("\n")
                    w.flush()
                }
            } catch (e: Exception) {
            } finally {
                try { w.close() } catch (e: Exception) {}
            }
        }
        thread(name = "chat-wait", isDaemon = true) {
            val code = p.waitFor()
            val err = synchronized(stderr) { stderr.toList() }
            main.post { onExit(code, err) }
        }
    }

    fun send(cmd: JSONObject) {
        if (process != null) outbox.put(cmd.toString())
    }

    // Closing stdin quits too, after "quit" if it was sent.
    fun close() = outbox.put(CLOSE)

    fun kill() {
        process?.destroy()
    }

    private companion object {
        val CLOSE = String(charArrayOf('\u0000'))
    }
}
