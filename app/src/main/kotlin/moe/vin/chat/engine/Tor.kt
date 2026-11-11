package moe.vin.chat.engine

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.json.JSONObject

// The one tor every Tor room uses, from Tor Project's Android bundle (lib/<abi>/libtor.so). Rooms
// log in to its control port with its cookie, which only this app's user can read.
class Tor(private val ctx: Context) {
    enum class Phase { OFF, STARTING, READY, FAILED }

    data class State(
        val phase: Phase = Phase.OFF,
        val progress: Int = 0,
        val listening: Boolean = false,
        val socks: String? = null,
        val control: String? = null,
        val message: String? = null,
    )

    var state by mutableStateOf(State())
        private set

    private val main = Handler(Looper.getMainLooper())
    private var process: Process? = null
    private var controlPort = 0
    private var dataDir: File? = null
    private var keep = false
    @Volatile private var stopping = false
    @Volatile private var discardState = false

    private val lib get() = File(ctx.applicationInfo.nativeLibraryDir)

    // The bridges Tor Browser comes with, by transport, from the bundle's pt_config.json.
    fun builtInBridges(kind: String): List<String> = try {
        val o = JSONObject(ctx.assets.open("pt_config.json").bufferedReader().use { it.readText() })
        val a = o.getJSONObject("bridges").optJSONArray(kind)
        if (a == null) emptyList() else List(a.length()) { a.getString(it) }
    } catch (e: Exception) {
        emptyList()
    }

    fun start(
        bridges: List<String>,
        keepState: Boolean,
        savedState: ByteArray? = null,
        onSaveState: ((ByteArray) -> Unit)? = null,
    ) {
        if (process != null) return
        stopping = false
        discardState = false
        keep = keepState
        // Clean up any older unsealed state folder and run purely out of cacheDir, wiped on exit.
        File(ctx.noBackupFilesDir, "tor").deleteRecursively()
        val data = File(ctx.cacheDir, "tor").apply { deleteRecursively(); mkdirs() }
        if (keepState && savedState != null) unpackState(data, savedState)
        dataDir = data
        val socks = freePort()
        val control = freePort()
        controlPort = control
        // Empty torrc: bridges never go on disk; SETCONF sends them once the control port opens.
        val torrc = File(data, "torrc").apply { writeText("") }
        val args = mutableListOf(
            File(lib, "libtor.so").path,
            "-f", torrc.path,
            "--defaults-torrc", torrc.path,
            "--DataDirectory", data.path,
            "--SocksPort", "127.0.0.1:$socks",
            "--ControlPort", "127.0.0.1:$control",
            "--CookieAuthentication", "1",
            "--SafeSocks", "1",
            "--AvoidDiskWrites", "1",
            "--ClientOnly", "1",
            "--Log", "notice stdout",
            // tor quits when this app's process is gone, however it went.
            "--__OwningControllerProcess", "${android.os.Process.myPid()}",
        )
        if (bridges.isNotEmpty()) {
            args += "--DisableNetwork"
            args += "1"
        }
        val pb = ProcessBuilder(args).directory(data).redirectErrorStream(true)
        pb.environment().clear()
        pb.environment()["HOME"] = data.path
        val p = try { pb.start() } catch (e: Exception) {
            data.deleteRecursively()
            state = State(Phase.FAILED, message = "tor didn't start: ${e.message}")
            return
        }
        process = p
        state = State(Phase.STARTING, 0, false, "127.0.0.1:$socks", "127.0.0.1:$control", "starting")
        val boot = Regex("Bootstrapped (\\d+)%[^:]*: (.*)")
        val myKeep = keepState
        thread(name = "tor-out", isDaemon = true) {
            var last: String? = null
            var configured = false
            p.inputStream.eachLine { line ->
                val m = boot.find(line)
                when {
                    m != null -> {
                        val pct = m.groupValues[1].toInt()
                        val what = m.groupValues[2]
                        main.post {
                            if (process === p) state = state.copy(
                                phase = if (pct >= 100) Phase.READY else Phase.STARTING,
                                progress = pct,
                                message = what,
                            )
                        }
                    }
                    !configured && "Opened Control listener" in line -> {
                        configured = true
                        val err = if (bridges.isNotEmpty()) configureBridges(data, control, bridges) else null
                        if (err != null) {
                            last = err
                            discardState = true
                            p.destroy()
                        } else {
                            main.post { if (process === p) state = state.copy(listening = true) }
                        }
                    }
                    "[err]" in line || "[warn]" in line -> last = line.substringAfter("] ").take(200)
                }
            }
            val code = p.waitFor()
            val packed = if (myKeep && !discardState && onSaveState != null) packState(data) else null
            data.deleteRecursively()
            if (packed != null && packed.isNotEmpty()) runCatching { onSaveState?.invoke(packed) }
            main.post {
                if (process === p) {
                    process = null
                    controlPort = 0
                }
                if (process == null) {
                    state = if (stopping) State() else State(Phase.FAILED, message = last ?: "tor stopped ($code)")
                }
            }
        }
    }

    fun stop(immediate: Boolean = false) {
        stopping = true
        val p = process
        val ctl = controlPort
        val data = dataDir
        val shouldHalt = !immediate && keep && ctl > 0 && data != null
        if (immediate) discardState = true
        process = null
        controlPort = 0
        state = State()
        if (p == null) return
        if (immediate) {
            p.destroyForcibly()
            data?.deleteRecursively()
        } else if (shouldHalt && data != null) {
            thread(name = "tor-halt", isDaemon = true) {
                runControl(data, ctl, listOf("SIGNAL HALT"))
                if (!p.waitFor(2000, TimeUnit.MILLISECONDS)) p.destroy()
            }
        } else {
            p.destroy()
        }
    }

    private fun configureBridges(data: File, port: Int, bridges: List<String>): String? {
        val pt = "meek_lite,obfs2,obfs3,obfs4,scramblesuit,webtunnel,snowflake exec ${File(lib, "liblyrebird.so").path}"
        val set = buildString {
            append("SETCONF UseBridges=1 ClientTransportPlugin=")
            append(quoteTor(pt))
            for (b in bridges) {
                append(" Bridge=")
                append(quoteTor(b))
            }
        }
        return runControl(data, port, listOf(set, "SETCONF DisableNetwork=0"))
    }

    private fun runControl(data: File, port: Int, cmds: List<String>): String? {
        val cookieFile = File(data, "control_auth_cookie")
        var waited = 0
        while ((!cookieFile.exists() || cookieFile.length() < 32L) && waited < 2000) {
            Thread.sleep(25)
            waited += 25
        }
        val cookie = runCatching { cookieFile.readBytes() }.getOrNull()
            ?: return "couldn't read tor's control cookie"
        val hex = cookie.joinToString("") { "%02x".format(it) }
        cookie.fill(0)
        return try {
            Socket().use { sock ->
                sock.soTimeout = 8000
                sock.connect(InetSocketAddress("127.0.0.1", port), 5000)
                val out = sock.getOutputStream().bufferedWriter(Charsets.US_ASCII)
                val inp = sock.getInputStream().bufferedReader(Charsets.US_ASCII)
                fun sendAndCheck(cmd: String, step: String): String? {
                    out.write(cmd)
                    out.write("\r\n")
                    out.flush()
                    while (true) {
                        val line = inp.readLine() ?: return "couldn't $step"
                        if (line.length >= 4 && line[3] == ' ') {
                            return if (line.startsWith("250")) null else "couldn't $step: $line"
                        }
                    }
                }
                sendAndCheck("AUTHENTICATE $hex", "log in to tor")?.let { return it }
                for (c in cmds) {
                    val step = if (c.startsWith("SETCONF UseBridges")) "configure bridges" else "talk to tor"
                    sendAndCheck(c, step)?.let { return it }
                }
                runCatching {
                    out.write("QUIT\r\n")
                    out.flush()
                }
                null
            }
        } catch (e: Exception) {
            "couldn't talk to tor: ${e.message}"
        }
    }

    private fun quoteTor(s: String): String = buildString {
        append('"')
        for (ch in s) when (ch) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\r', '\n' -> {}
            else -> append(ch)
        }
        append('"')
    }

    private fun packState(data: File): ByteArray? = runCatching {
        val existing = KEPT_FILES.mapNotNull { name ->
            val f = File(data, name)
            if (f.isFile && f.length() in 1..(16L * 1024 * 1024)) name to f.readBytes() else null
        }
        if (existing.isEmpty()) return null
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).use { out ->
            out.writeInt(STATE_VERSION)
            out.writeInt(existing.size)
            for ((name, bytes) in existing) {
                out.writeUTF(name)
                out.writeInt(bytes.size)
                out.write(bytes)
            }
        }
        bos.toByteArray()
    }.getOrNull()

    private fun unpackState(data: File, blob: ByteArray) {
        runCatching {
            DataInputStream(ByteArrayInputStream(blob)).use { inp ->
                if (inp.readInt() != STATE_VERSION) return
                val count = inp.readInt()
                if (count !in 1..16) return
                repeat(count) {
                    val name = inp.readUTF()
                    val len = inp.readInt()
                    if (len < 0 || len > 16 * 1024 * 1024) return
                    val bytes = ByteArray(len)
                    inp.readFully(bytes)
                    if (name in KEPT_FILES) File(data, name).writeBytes(bytes)
                }
            }
        }
    }

    private fun freePort(): Int = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }

    companion object {
        private const val STATE_VERSION = 1
        private val KEPT_FILES = setOf(
            "state",
            "cached-certs",
            "cached-microdesc-consensus",
            "cached-microdescs",
            "cached-microdescs.new",
        )
    }
}
