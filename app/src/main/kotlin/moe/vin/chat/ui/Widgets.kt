package moe.vin.chat.ui

import android.graphics.Rect
import android.text.Editable
import android.text.GetChars
import android.text.InputType
import android.text.Spanned
import android.text.TextWatcher
import android.text.method.SingleLineTransformationMethod
import android.text.method.TransformationMethod
import android.util.TypedValue
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import java.util.Arrays
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

val CONTROL_HEIGHT = 44.dp
val BOTTOM_BAR_HEIGHT = 68.dp

// Masks every character immediately without Android's 1.5s last-character reveal,
// and survives single-line EditText configuration.
private object HiddenPasswordMethod : TransformationMethod {
    private const val DOT = '\u2022'

    private open class Masked(private val src: CharSequence) : CharSequence, GetChars {
        override val length: Int get() = src.length
        override fun get(index: Int): Char = DOT
        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
            Masked(src.subSequence(startIndex, endIndex))
        override fun toString(): String = String(CharArray(src.length) { DOT })
        override fun getChars(start: Int, end: Int, dest: CharArray, destoff: Int) {
            val count = (end - start).coerceAtLeast(0)
            Arrays.fill(dest, destoff, destoff + count, DOT)
        }
    }

    private class MaskedSpanned(private val sp: Spanned) : Masked(sp), Spanned by sp

    override fun getTransformation(source: CharSequence, view: View?): CharSequence =
        if (source is Spanned) MaskedSpanned(source) else Masked(source)

    override fun onFocusChanged(
        view: View?,
        sourceText: CharSequence?,
        focused: Boolean,
        direction: Int,
        previouslyFocusedRect: Rect?,
    ) {}
}

// A text field the keyboard doesn't learn from: Android's incognito flag, which Compose's own fields
// can't set, and nothing offered to autofill.
@Composable
fun PrivateField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    password: Boolean = false,
    multiLine: Boolean = false,
    mono: Boolean = false,
    onDone: (() -> Unit)? = null,
    focus: Boolean = false,
) {
    val p = LocalPalette.current
    val change by rememberUpdatedState(onValueChange)
    val done by rememberUpdatedState(onDone)
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            EditText(ctx).apply {
                background = null
                setPadding(0, 0, 0, 0)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                    (if (onDone != null && !multiLine) EditorInfo.IME_ACTION_DONE else EditorInfo.IME_ACTION_NONE)
                // Set single-line before inputType & transformationMethod: Android's setSingleLine(true)
                // replaces transformationMethod with SingleLineTransformationMethod.
                if (multiLine) {
                    minLines = 1
                    maxLines = 5
                } else {
                    isSingleLine = true
                }
                inputType = when {
                    password -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    multiLine -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    mono -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                }
                // A password field's typeface is monospace unless it's set again after its input type.
                typeface = if (mono) android.graphics.Typeface.MONOSPACE else android.graphics.Typeface.DEFAULT
                if (password) {
                    transformationMethod = HiddenPasswordMethod
                }
                setHint(placeholder)
                setText(value)
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                    override fun afterTextChanged(s: Editable?) {
                        val next = s?.toString().orEmpty()
                        change(next)
                    }
                })
                setOnEditorActionListener { _, _, _ -> done?.invoke(); done != null }
                if (focus) post { requestFocus() }
            }
        },
        update = { et ->
            et.setTextColor(p.text.toArgb())
            et.setHintTextColor(p.faint.toArgb())
            et.highlightColor = p.accent.copy(alpha = 0.35f).toArgb()
            if (password && et.transformationMethod !== HiddenPasswordMethod) {
                et.transformationMethod = HiddenPasswordMethod
            } else if (!password && et.transformationMethod === HiddenPasswordMethod) {
                et.transformationMethod = if (multiLine) null else SingleLineTransformationMethod.getInstance()
            }
            if (et.hint?.toString().orEmpty() != placeholder) et.hint = placeholder
            if (et.text.toString() != value) {
                et.setText(value)
                et.setSelection(value.length)
            }
        },
    )
}

@Composable
fun Field(label: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null, content: @Composable () -> Unit) {
    val p = LocalPalette.current
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label.uppercase(), style = Type.section, color = p.faint)
            trailing?.invoke()
        }
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(p.surface)
                .border(1.dp, p.line, RoundedCornerShape(10.dp)).padding(horizontal = 14.dp, vertical = 12.dp),
        ) { content() }
    }
}

@Composable
fun SearchBar(query: String, onQueryChange: (String) -> Unit, placeholder: String = "Search settings…", modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.surface)
            .border(1.dp, p.line, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Search, null, tint = p.faint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        PrivateField(query, onQueryChange, Modifier.weight(1f), placeholder = placeholder)
        if (query.isNotEmpty()) {
            Spacer(Modifier.width(6.dp))
            Icon(
                Icons.Close, "Clear search", tint = p.muted,
                modifier = Modifier.size(18.dp).clip(CircleShape).clickable { onQueryChange("") },
            )
        }
    }
}

// Beside the rooms, the button on a page's top bar that hides or shows them.
class SidebarButton(val label: String, val listVisible: Boolean, val onClick: () -> Unit)

val LocalSidebar = compositionLocalOf<SidebarButton?> { null }

@Composable
fun TopBar(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    onTitleClick: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val p = LocalPalette.current
    val sidebar = LocalSidebar.current
    Column(Modifier.background(p.bg).statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (sidebar != null) IconAction(Icons.Sidebar, sidebar.label, sidebar.onClick)
            if (onBack != null) IconAction(Icons.Back, "Back", onBack)
            else if (sidebar == null) Spacer(Modifier.width(10.dp))
            Column(
                Modifier.weight(1f)
                    .then(
                        if (onTitleClick != null) Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onTitleClick)
                        else Modifier,
                    )
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            ) {
                Text(title, style = Type.title.copy(fontSize = 18.sp), color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) Text(subtitle, style = Type.label, color = p.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            actions()
        }
        HorizontalDivider(color = p.line, thickness = 1.dp)
    }
}

@Composable
fun IconAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    tint: Color = LocalPalette.current.text,
    enabled: Boolean = true,
    badge: Boolean = false,
) {
    val p = LocalPalette.current
    Box(
        Modifier.size(40.dp).clip(CircleShape).clickable(enabled = enabled, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = if (enabled) tint else tint.copy(alpha = 0.35f), modifier = Modifier.size(20.dp))
        if (badge) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 8.dp)
                    .size(7.dp).clip(CircleShape).background(p.accent),
            )
        }
    }
}

@Composable
fun Button(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = true,
    icon: ImageVector? = null,
) {
    val p = LocalPalette.current
    val bg = if (primary) p.accent else p.surface
    val fg = if (primary) p.onAccent else p.text
    Row(
        modifier.height(CONTROL_HEIGHT).clip(RoundedCornerShape(10.dp))
            .background(if (enabled) bg else bg.copy(alpha = 0.45f))
            .then(if (primary) Modifier else Modifier.border(1.dp, p.line, RoundedCornerShape(10.dp)))
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = if (enabled) fg else fg.copy(alpha = 0.55f), modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(7.dp))
        }
        Text(
            text,
            style = Type.small.copy(fontWeight = FontWeight.SemiBold),
            color = if (enabled) fg else fg.copy(alpha = 0.55f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// Shared bottom bar geometry (Hairline + 68dp row with 44dp controls) so left and right panes
// meet in one unbroken horizontal line in two-pane mode.
@Composable
fun BottomBar(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Hairline()
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = BOTTOM_BAR_HEIGHT)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

@Composable
fun Tag(text: String, color: Color = LocalPalette.current.muted, modifier: Modifier = Modifier) {
    Text(
        text, style = Type.label, color = color, maxLines = 1,
        modifier = modifier.clip(RoundedCornerShape(6.dp)).border(1.dp, color.copy(alpha = 0.45f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
fun Section(title: String, modifier: Modifier = Modifier) {
    Text(title.uppercase(), style = Type.section, color = LocalPalette.current.faint, modifier = modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 8.dp))
}

@Composable
fun RowItem(
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    leading: ImageVector? = null,
    titleColor: Color = LocalPalette.current.text,
    trailing: @Composable (() -> Unit)? = null,
) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 20.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            Icon(leading, null, tint = if (titleColor == p.bad) p.bad else p.muted, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.body, color = titleColor)
            if (subtitle != null) Text(subtitle, style = Type.small, color = p.muted)
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

@Composable
fun Toggle(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    val p = LocalPalette.current
    RowItem(title, subtitle, onClick = { onChange(!checked) }) {
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = p.accent, checkedThumbColor = p.onAccent, uncheckedTrackColor = p.raised,
                uncheckedThumbColor = p.muted, uncheckedBorderColor = p.line),
        )
    }
}

// Several choices, one picked: a row of segments (or two balanced rows when > 4 choices).
@Composable
fun Choice(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val rows = if (options.size <= 4) listOf(options) else options.chunked((options.size + 1) / 2)
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).border(1.dp, p.line, RoundedCornerShape(10.dp)).padding(3.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        for (row in rows) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                for ((key, label) in row) {
                    val on = key == selected
                    Box(
                        Modifier.weight(1f).height(38.dp).clip(RoundedCornerShape(8.dp))
                            .background(if (on) p.raised else Color.Transparent)
                            .clickable { onSelect(key) }.padding(horizontal = 4.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            label,
                            style = Type.small.copy(fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal),
                            color = if (on) p.text else p.muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun Hairline() = HorizontalDivider(color = LocalPalette.current.line, thickness = 1.dp)

// chat's own QR code of an invite, dark on light whatever the theme, with the border scanners need.
@Composable
fun QrCode(rows: List<String>, modifier: Modifier = Modifier) {
    Canvas(modifier.aspectRatio(1f).clip(RoundedCornerShape(12.dp)).background(Color.White)) {
        val n = rows.size + 8
        val cell = size.width / n
        rows.forEachIndexed { y, row ->
            row.forEachIndexed { x, c ->
                if (c == '1') drawRect(Color.Black, Offset((x + 4) * cell, (y + 4) * cell), Size(cell + 0.6f, cell + 0.6f))
            }
        }
    }
}
