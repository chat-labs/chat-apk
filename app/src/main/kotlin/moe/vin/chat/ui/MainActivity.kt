package moe.vin.chat.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import moe.vin.chat.BuildConfig
import moe.vin.chat.Hub
import moe.vin.chat.service.Notifications

sealed interface Screen {
    data object Rooms : Screen
    data class Chat(val room: String, val dm: String? = null) : Screen
    data class Peers(val room: String) : Screen
    data class PeerInfo(val room: String, val peer: String) : Screen
    data class Invite(val room: String) : Screen
    data class RoomSettings(val room: String) : Screen
    data object New : Screen
    data class Join(val invite: String = "") : Screen
    data object Settings : Screen
}

class Nav {
    val stack = mutableStateListOf<Screen>(Screen.Rooms)
    val top get() = stack.last()

    fun go(s: Screen) {
        if (stack.last() != s) stack += s
    }

    fun back() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }

    fun replace(s: Screen) {
        stack[stack.lastIndex] = s
    }

    fun home(then: Screen? = null) {
        stack.removeRange(1, stack.size)
        if (then != null) stack += then
    }
}

// What the share sheet handed over, waiting for a room to go to.
object Shared {
    var text by mutableStateOf<String?>(null)
    var file by mutableStateOf<Uri?>(null)

    fun clear() {
        text = null
        file = null
    }
}

class MainActivity : ComponentActivity() {
    private val nav = Nav()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Nothing of chat in screenshots, screen recordings or the recent apps. Debug builds
        // leave it off, so the emulator's screen can be checked.
        if (!BuildConfig.DEBUG) window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        Hub.pictureMax = minOf(1024, resources.displayMetrics.widthPixels)
        handle(intent)
        setContent { ChatTheme { Root(nav) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onStart() {
        super.onStart()
        Hub.foreground = true
    }

    override fun onStop() {
        Hub.foreground = false
        super.onStop()
    }

    private fun handle(intent: Intent?) {
        intent ?: return
        intent.getStringExtra(Notifications.EXTRA_ROOM)?.let { id -> nav.home(Screen.Chat(id)) }
        if (intent.action == Intent.ACTION_SEND) {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT)
            @Suppress("DEPRECATION")
            val file = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else intent.getParcelableExtra(Intent.EXTRA_STREAM)
            // An invite's password is words, so it runs to the end of its line; chat's check catches
            // anything after it on the same line.
            val invite = text?.let { Regex("chat:[^\\n]+").find(it)?.value?.trim() }
            when {
                invite != null -> nav.home(Screen.Join(invite))
                file != null -> { Shared.file = file; nav.home() }
                text != null -> { Shared.text = text; nav.home() }
            }
        }
    }
}

@Composable
fun Root(nav: Nav) {
    val p = LocalPalette.current
    Box(Modifier.fillMaxSize().background(p.bg)) {
        when (Hub.phase) {
            Hub.Phase.SETUP -> SetupScreen()
            Hub.Phase.LOCKED -> UnlockScreen()
            Hub.Phase.READY -> {
                AskForNotifications()
                BackHandler(enabled = nav.stack.size > 1) { nav.back() }
                when (val s = nav.top) {
                    Screen.Rooms -> RoomsScreen(nav)
                    is Screen.Chat -> ChatScreen(nav, s.room, s.dm)
                    is Screen.Peers -> PeersScreen(nav, s.room)
                    is Screen.PeerInfo -> PeerScreen(nav, s.room, s.peer)
                    is Screen.Invite -> InviteScreen(nav, s.room)
                    is Screen.RoomSettings -> RoomSettingsScreen(nav, s.room)
                    Screen.New -> NewRoomScreen(nav)
                    is Screen.Join -> JoinScreen(nav, s.invite)
                    Screen.Settings -> SettingsScreen(nav)
                }
            }
        }
    }
}

@Composable
private fun AskForNotifications() {
    if (Build.VERSION.SDK_INT < 33) return
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) { ask.launch(Manifest.permission.POST_NOTIFICATIONS) }
}

// A screen with a top bar.
@Composable
fun Page(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize().background(LocalPalette.current.bg)) {
        TopBar(title, subtitle, onBack, actions)
        Column(Modifier.weight(1f).navigationBarsPadding()) { content() }
    }
}
