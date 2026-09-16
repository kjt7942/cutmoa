package com.kjt.cutmoa.overlay

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.LaunchedEffect

val PRESET_COLORS = listOf(
    Color.White,
    Color.Black,
    Color(0xFFFF3B30),
    Color(0xFFFF9500),
    Color(0xFFFFCC00),
    Color(0xFF34C759),
    Color(0xFF0A84FF),
    Color(0xFFAF52DE),
)

private val EMOJIS = listOf(
    "😀", "😂", "🤣", "😍", "🥰", "😎", "🤩", "🥳",
    "😭", "😱", "🤔", "😴", "😡", "🤯", "🙄", "😇",
    "👍", "👏", "🙌", "🙏", "💪", "✌️", "👀", "🫶",
    "❤️", "🧡", "💛", "💚", "💙", "💜", "🖤", "💔",
    "🔥", "✨", "⭐", "🌟", "💯", "🎉", "🎊", "🎁",
    "☀️", "🌈", "⚡", "❄️", "🍀", "🌸", "🍕", "☕",
    "📸", "🎬", "🎵", "🏆", "✅", "❌", "⬆️", "➡️",
)

private val STYLE_LABELS = listOf(
    OverlayTextStyle.OUTLINE to "외곽선",
    OverlayTextStyle.BOX to "배경",
    OverlayTextStyle.SHADOW to "그림자",
)

/** Text + color + style, with a live preview drawn by the same renderer the export uses. */
@Composable
fun TextLayerDialog(
    initial: OverlayItem?,
    onDismiss: () -> Unit,
    onConfirm: (text: String, color: Color, style: OverlayTextStyle) -> Unit,
) {
    var text by remember { mutableStateOf(initial?.text ?: "") }
    var color by remember { mutableStateOf(initial?.color ?: Color.White) }
    var style by remember { mutableStateOf(initial?.style ?: OverlayTextStyle.OUTLINE) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial != null) "텍스트 수정" else "텍스트 추가") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Checkerboard-ish gray so both white and black text stay visible in the preview.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(84.dp)
                        .background(Color(0xFF6E6E6E), RoundedCornerShape(8.dp))
                        .padding(8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (text.isNotBlank()) {
                        val preview = remember(text, color, style) {
                            OverlayRenderer.render(
                                OverlayItem(0, OverlayKind.TEXT, text, color, style, 0, 1, listOf(Keyframe(0))),
                                pixelScale = 1f,
                            ).asImageBitmap()
                        }
                        Image(preview, contentDescription = null, contentScale = ContentScale.Fit)
                    } else {
                        Text("미리보기", color = Color(0xFFBBBBBB))
                    }
                }

                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("내용 (줄바꿈 가능)") },
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                )

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PRESET_COLORS.forEach { c ->
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .background(c, CircleShape)
                                .border(
                                    width = if (c == color) 3.dp else 1.dp,
                                    color = if (c == color) Color(0xFFFFC107) else Color.Gray,
                                    shape = CircleShape,
                                )
                                .clickable { color = c },
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    STYLE_LABELS.forEach { (s, label) ->
                        FilterChip(selected = style == s, onClick = { style = s }, label = { Text(label) })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text.trim(), color, style) }, enabled = text.isNotBlank()) {
                Text(if (initial != null) "수정" else "추가")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmojiPickerSheet(onDismiss: () -> Unit, onPick: (String) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(52.dp),
            modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp).padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            items(EMOJIS) { emoji ->
                Box(
                    modifier = Modifier.size(52.dp).clickable { onPick(emoji) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(emoji, fontSize = 30.sp)
                }
            }
        }
    }
}
