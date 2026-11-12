package moe.vin.chat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import moe.vin.chat.Hub

// A room's console, apart from its chat: chat's notes, its log and its warnings, newest at the
// bottom, and a line for chat's commands, whose answers come here.
@Composable
fun ConsoleScreen(nav: Nav, roomId: String) {
    val room = Hub.rooms[roomId]
    if (room == null) {
        LaunchedEffect(Unit) { nav.back() }
        return
    }
    val p = LocalPalette.current
    var command by remember { mutableStateOf("") }
    val run = {
        val c = command.trim().removePrefix(":")
        if (c.isNotEmpty()) {
            room.runCommand(":$c")
            command = ""
        }
    }
    Page("Console", room.title.ifBlank { null }, onBack = nav::back) {
        val lines = room.console
        if (lines.isEmpty()) {
            Text(
                "Nothing yet. What chat says about this room, Tor and the network comes here.",
                style = Type.small,
                color = p.faint,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(20.dp),
            )
        } else {
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                reverseLayout = true,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            ) {
                items(lines.size) { i ->
                    val line = lines[lines.size - 1 - i]
                    Row(Modifier.padding(vertical = 2.dp)) {
                        Text(line.at, style = Type.label, color = p.faint)
                        Spacer(Modifier.width(10.dp))
                        Text(line.text, style = Type.label, color = if (line.warn) p.warn else p.muted)
                    }
                }
            }
        }
        BottomBar(Modifier.imePadding()) {
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(p.surface)
                    .border(1.dp, p.line, RoundedCornerShape(10.dp)).padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                PrivateField(command, { command = it }, Modifier.fillMaxWidth(), placeholder = "a command: net or peers", mono = true, onDone = run)
            }
            IconAction(Icons.Send, "Run", run, enabled = command.isNotBlank())
        }
    }
}
