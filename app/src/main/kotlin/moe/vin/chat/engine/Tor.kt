package moe.vin.chat.engine

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
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
    private var dataDir: File? = null
    private var keep = false
    private var stopping = false

    private val lib get() = File(ctx.applicationInfo.nativeLibraryDir)

    // The bridges Tor Browser comes with, by transport, from the bundle's pt_config.json.
    fun builtInBridges(kind: String): List<String> = try {
        val o = JSONObject(ctx.assets.open("pt_config.json").bufferedReader().use { it.readText() })
        val a = o.getJSONObject("bridges").optJSONArray(kind)
        if (a == null) emptyList() else List(a.length()) { a.getString(it) }
    } catch (e: Exception) {
        emptyList()
    }

    fun start(bridges: List<String>, keepState: Boolean) {
        if (process != null) return
        stopping = false
        keep = keepState
        val data = File(if (keepState) ctx.noBackupFilesDir else ctx.cacheDir, "tor")
        if (!keepState) data.deleteRecursively()
        data.mkdirs()
        dataDir = data
        val socks = freePort()
        val control = freePort()
        val conf = buildString {
            appendLine("SocksPort 127.0.0.1:$socks")
            appendLine("ControlPort 127.0.0.1:$control")
            appendLine("CookieAuthentication 1")
            appendLine("DataDirectory ${data.path}")
            appendLine("Log notice stdout")
            appendLine("AvoidDiskWrites 1")
            appendLine("ClientOnly 1")
            // tor quits when this app's process is gone, however it went.
            appendLine("__OwningControllerProcess ${android.os.Process.myPid()}")
            if (bridges.isNotEmpty()) {
                appendLine("UseBridges 1")
                appendLine("ClientTransportPlugin meek_lite,obfs2,obfs3,obfs4,scramblesuit,webtunnel,snowflake exec ${File(lib, "liblyrebird.so").path}")
                bridges.forEach { appendLine("Bridge $it") }
            }
        }
        val torrc = File(data, "torrc").apply { writeText(conf) }
        val pb = ProcessBuilder(File(lib, "libtor.so").path, "-f", torrc.path).directory(data).redirectErrorStream(true)
        pb.environment().clear()
        pb.environment()["HOME"] = data.path
        val p = try { pb.start() } catch (e: Exception) {
            state = State(Phase.FAILED, message = "tor didn't start: ${e.message}")
            return
        }
        process = p
        state = State(Phase.STARTING, 0, false, "127.0.0.1:$socks", "127.0.0.1:$control", "starting")
        val boot = Regex("Bootstrapped (\\d+)%[^:]*: (.*)")
        thread(name = "tor-out", isDaemon = true) {
            var last: String? = null
            p.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    val m = boot.find(line)
                    when {
                        m != null -> {
                            val pct = m.groupValues[1].toInt()
                            val what = m.groupValues[2]
                            main.post { state = state.copy(phase = if (pct >= 100) Phase.READY else Phase.STARTING, progress = pct, message = what) }
                        }
                        "Opened Control listener" in line || "Opening Control listener" in line ->
                            main.post { state = state.copy(listening = true) }
                        "[err]" in line || "[warn]" in line -> last = line.substringAfter("] ").take(200)
                    }
                }
            }
            val code = p.waitFor()
            main.post {
                if (process === p) process = null
                state = if (stopping) State() else State(Phase.FAILED, message = last ?: "tor stopped ($code)")
                if (!keep) dataDir?.deleteRecursively()
            }
        }
    }

    fun stop() {
        stopping = true
        process?.destroy()
        process = null
        state = State()
    }

    private fun freePort(): Int = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
}
