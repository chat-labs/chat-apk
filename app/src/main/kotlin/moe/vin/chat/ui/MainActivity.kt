package moe.vin.chat.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import kotlinx.coroutines.launch
import moe.vin.chat.BuildConfig
import moe.vin.chat.Hub
import moe.vin.chat.service.Notifications

sealed interface Screen {
    data object Rooms : Screen
    data class Chat(val room: String, val dm: String? = null) : Screen
    data class Peers(val room: String) : Screen
    data class PeerInfo(val room: String, val peer: String) : Screen
    data class Files(val room: String) : Screen
    data class Console(val room: String) : Screen
    data class PictureView(val room: String, val num: Int) : Screen
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

// A fold or hinge across the window: where it is, in window pixels, and which way it runs.
data class Hinge(val bounds: Rect, val vertical: Boolean, val halfOpened: Boolean)

// What outlives the activity: where the user is, the fold the window has now, and whether the
// rooms beside an open room are hidden.
object Ui {
    val nav = Nav()
    var hinge by mutableStateOf<Hinge?>(null)
    var roomsHidden by mutableStateOf(false)
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
    private val nav get() = Ui.nav

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Nothing of chat in screenshots, screen recordings or the recent apps. Debug builds
        // leave it off, so the emulator's screen can be checked.
        if (!BuildConfig.DEBUG) window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        Hub.pictureMax = minOf(1024, resources.displayMetrics.widthPixels)
        handle(intent)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                WindowInfoTracker.getOrCreate(this@MainActivity).windowLayoutInfo(this@MainActivity).collect { info ->
                    Ui.hinge = info.displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull()?.let {
                        Hinge(it.bounds, it.orientation == FoldingFeature.Orientation.VERTICAL, it.state == FoldingFeature.State.HALF_OPENED)
                    }
                }
            }
        }
        setContent { ChatTheme { Root(nav) } }
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        Hub.touchActivity()
        return super.dispatchTouchEvent(ev)
    }

    // Lint takes the override's super call for a use of androidx's restricted method; every key
    // still goes through to it.
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        Hub.touchActivity()
        return super.dispatchKeyEvent(event)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onStart() {
        super.onStart()
        Hub.foreground = true
        Hub.touchActivity()
    }

    override fun onStop() {
        Hub.foreground = false
        Hub.touchActivity()
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
            Hub.Phase.LOCKED -> UnlockScreen(uiLock = false)
            Hub.Phase.READY -> {
                if (Hub.uiLocked) {
                    BackHandler(enabled = true) {}
                    UnlockScreen(uiLock = true)
                } else {
                    AskForNotifications()
                    BackHandler(enabled = nav.stack.size > 1) { nav.back() }
                    Panes(nav)
                }
            }
        }
    }
}

// Narrower than this, one screen at a time.
private val TWO_PANES = 600.dp
private val LIST_WIDTH = 360.dp

// Unfolded, or on a tablet, the rooms on the left and what's open on the right. On a foldable the
// two meet at the fold, so nothing sits across the hinge.
@Composable
private fun Panes(nav: Nav) {
    val p = LocalPalette.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth < TWO_PANES) {
            Crossfade(targetState = nav.top, animationSpec = tween(140), label = "screen") { target ->
                ScreenFor(nav, target)
            }
            return@BoxWithConstraints
        }
        val density = LocalDensity.current
        val fold = Ui.hinge?.takeIf { it.vertical && it.bounds.left > 0 }
        var list: Dp = LIST_WIDTH
        var gap: Dp = 0.dp
        if (fold != null) with(density) {
            list = fold.bounds.left.toDp()
            gap = fold.bounds.width().toDp()
        }
        list = list.coerceIn(280.dp, maxWidth - 320.dp)
        // With nothing open beside them, the rooms are all there is to show.
        val hidden = Ui.roomsHidden && nav.top != Screen.Rooms
        Row(Modifier.fillMaxSize()) {
            AnimatedVisibility(!hidden) {
                Row(Modifier.fillMaxHeight()) {
                    Box(Modifier.width(list).fillMaxHeight()) { RoomsScreen(nav, twoPane = true) }
                    if (gap > 0.dp) Spacer(Modifier.width(gap).fillMaxHeight().background(p.bg))
                    else VerticalDivider(color = p.line)
                }
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                if (nav.top == Screen.Rooms) Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Pick a room, or make one", style = Type.small, color = p.faint, textAlign = TextAlign.Center)
                } else CompositionLocalProvider(
                    LocalSidebar provides SidebarButton(if (hidden) "Show rooms" else "Hide rooms", listVisible = !hidden) { Ui.roomsHidden = !hidden },
                ) {
                    Crossfade(targetState = nav.top, animationSpec = tween(140), label = "detail") { target ->
                        ScreenFor(nav, target)
                    }
                }
            }
        }
    }
}

@Composable
private fun ScreenFor(nav: Nav, s: Screen) {
    when (s) {
        Screen.Rooms -> RoomsScreen(nav)
        is Screen.Chat -> ChatScreen(nav, s.room, s.dm)
        is Screen.Peers -> PeersScreen(nav, s.room)
        is Screen.PeerInfo -> PeerScreen(nav, s.room, s.peer)
        is Screen.Files -> FilesScreen(nav, s.room)
        is Screen.Console -> ConsoleScreen(nav, s.room)
        is Screen.PictureView -> PictureScreen(nav, s.room, s.num)
        is Screen.Invite -> InviteScreen(nav, s.room)
        is Screen.RoomSettings -> RoomSettingsScreen(nav, s.room)
        Screen.New -> NewRoomScreen(nav)
        is Screen.Join -> JoinScreen(nav, s.invite)
        Screen.Settings -> SettingsScreen(nav)
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
    onTitleClick: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize().background(LocalPalette.current.bg)) {
        TopBar(title, subtitle, onBack, onTitleClick, actions)
        Column(Modifier.weight(1f).navigationBarsPadding()) { content() }
    }
}
