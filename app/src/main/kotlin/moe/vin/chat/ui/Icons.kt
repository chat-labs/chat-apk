package moe.vin.chat.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

// Line icons, drawn as SVG paths on a 24 by 24 grid and tinted where they're used.
object Icons {
    private fun icon(name: String, d: String, width: Float = 1.8f) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        .addPath(addPathNodes(d), stroke = SolidColor(Color.Black), strokeLineWidth = width,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        .build()

    val Back = icon("back", "M15 5 L8 12 L15 19")
    val Send = icon("send", "M12 19 V5 M6 11 L12 5 L18 11", 2.2f)
    val Plus = icon("plus", "M12 5 V19 M5 12 H19")
    val Attach = icon("attach", "M17 7.5 L10 14.5 a2 2 0 0 0 2.8 2.8 L19.5 10.6 a4 4 0 0 0 -5.6 -5.6 L7 11.9 a6 6 0 0 0 8.5 8.5 L20 15.9")
    val More = icon("more", "M12 5.5 v0.01 M12 12 v0.01 M12 18.5 v0.01", 2.8f)
    val Check = icon("check", "M5 12.5 L10 17.5 L19 7")
    val Checks = icon("checks", "M2.5 12.5 L7 17 L15.5 8 M11.5 16 L12.5 17 L21.5 8")
    val Close = icon("close", "M6 6 L18 18 M18 6 L6 18")
    val People = icon("people", "M9 11 a3.2 3.2 0 1 0 0 -6.4 a3.2 3.2 0 1 0 0 6.4 Z M3 19.5 c0 -3.3 2.7 -5.5 6 -5.5 s6 2.2 6 5.5 M16 4.8 a3.2 3.2 0 0 1 0 6.2 M18 14.3 c1.8 0.7 3 2.6 3 5.2")
    val Sliders = icon("sliders", "M4 7 H13 M17 7 H20 M15 5 V9 M4 17 H7 M11 17 H20 M9 15 V19")
    val Lock = icon("lock", "M6 11 H18 V20 H6 Z M8.5 11 V8 a3.5 3.5 0 0 1 7 0 V11")
    val Unlock = icon("unlock", "M6 11 H18 V20 H6 Z M8.5 11 V8 a3.5 3.5 0 0 1 6.7 -1.4")
    val Shield = icon("shield", "M12 3.5 L19 6.5 V11.5 C19 16 16 19 12 20.5 C8 19 5 16 5 11.5 V6.5 Z")
    val ShieldCheck = icon("shield-check", "M12 3.5 L19 6.5 V11.5 C19 16 16 19 12 20.5 C8 19 5 16 5 11.5 V6.5 Z M9 12 L11.2 14.2 L15.5 9.8")
    val Qr = icon("qr", "M4 4 H10 V10 H4 Z M14 4 H20 V10 H14 Z M4 14 H10 V20 H4 Z M14 14 H16.5 M19.5 14 H20 V16.5 M14 17.5 V20 H16.5 M20 20 v0.01")
    val Share = icon("share", "M12 15 V4 M8 8 L12 4 L16 8 M5 13 V19 H19 V13")
    val Copy = icon("copy", "M9 9 H19 V19 H9 Z M5 15 V5 H15")
    val Reply = icon("reply", "M9 6 L4 11 L9 16 M4 11 H14 C17 11 20 13 20 17 V18")
    val Image = icon("image", "M4 5 H20 V19 H4 Z M4 16 L9 11 L13 15 L15 13 L20 18 M15.5 8.5 v0.01")
    val File = icon("file", "M7 3.5 H14 L18 7.5 V20.5 H7 Z M14 3.5 V7.5 H18")
    val Folder = icon("folder", "M3.5 6.5 H9.5 L11.5 8.5 H20.5 V18.5 H3.5 Z")
    val Download = icon("download", "M12 4 V15 M7.5 10.5 L12 15 L16.5 10.5 M5 19.5 H19")
    val Warn = icon("warn", "M12 4 L21 19.5 H3 Z M12 10 V14 M12 17 v0.01")
    val Onion = icon("onion", "M12 20.5 a8.5 8.5 0 1 1 0 -17 a8.5 8.5 0 1 1 0 17 Z M12 16 a4 4 0 1 1 0 -8 a4 4 0 1 1 0 8 Z")
    val Clock = icon("clock", "M12 20.5 a8.5 8.5 0 1 1 0 -17 a8.5 8.5 0 1 1 0 17 Z M12 7.5 V12 L15 14")
    val Move = icon("move", "M4 12 H16 M12 7 L17 12 L12 17 M20 5 V19")
    val Trash = icon("trash", "M5 7 H19 M10 7 V5 H14 V7 M7 7 L8 20 H16 L17 7")
    val Join = icon("join", "M10 17 L15 12 L10 7 M15 12 H3 M14 4 H20 V20 H14")
    val Key = icon("key", "M8 16 a4 4 0 1 1 0 -8 a4 4 0 1 1 0 8 Z M12 12 H21 M18 12 V15 M15 12 V14")
    val Open = icon("open", "M14 4 H20 V10 M20 4 L11 13 M18 14 V20 H4 V6 H10")
    val Sidebar = icon("sidebar", "M4 5 H20 V19 H4 Z M9.5 5 V19")
    val Mail = icon("dm", "M4 6 H20 V18 H4 Z M4 7 L12 13 L20 7")
    val Search = icon("search", "M11 17.5 a6.5 6.5 0 1 1 0 -13 a6.5 6.5 0 1 1 0 13 Z M15.8 15.8 L20 20")
    val Phone = icon("phone", "M6.5 4.5 H9.5 L11 8.5 L8.8 10.2 a11 11 0 0 0 5 5 L15.5 13 L19.5 14.5 V17.5 a2 2 0 0 1 -2.2 2 A15 15 0 0 1 4.5 6.7 a2 2 0 0 1 2 -2.2 Z")
    val Link = icon("link", "M10 14 L14 10 M8.5 16.5 L7 18 a3.5 3.5 0 0 1 -5 -5 L4.5 10.5 a3.5 3.5 0 0 1 5 0 M15.5 7.5 L17 6 a3.5 3.5 0 0 1 5 5 L19.5 13.5 a3.5 3.5 0 0 1 -5 0")
    val Eye = icon("eye", "M2.5 12 C5 7 8.5 5.5 12 5.5 S19 7 21.5 12 C19 17 15.5 18.5 12 18.5 S5 17 2.5 12 Z M12 14.5 a2.5 2.5 0 1 1 0 -5 a2.5 2.5 0 1 1 0 5 Z")
    val EyeOff = icon("eye-off", "M4 4 L20 20 M9.9 5.8 C10.6 5.6 11.3 5.5 12 5.5 C15.5 5.5 19 7 21.5 12 C20.6 13.8 19.5 15.2 18.2 16.2 M6.2 8.2 C4.8 9.2 3.6 10.5 2.5 12 C5 17 8.5 18.5 12 18.5 C13.6 18.5 15.1 18.1 16.5 17.3")
    val ArrowDown = icon("arrow-down", "M12 5 V19 M6 13 L12 19 L18 13", 2.0f)
    val Bolt = icon("bolt", "M13 3 L5.5 13.5 H12 L11 21 L18.5 10.5 H12 Z")
    val Edit = icon("edit", "M15.5 4.5 L19.5 8.5 L8.5 19.5 H4.5 V15.5 Z")
}
