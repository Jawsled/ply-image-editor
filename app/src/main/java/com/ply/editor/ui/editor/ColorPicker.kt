package com.ply.editor.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.roundToInt

/** #RRGGBB for [color], ignoring alpha. */
fun hexOf(color: Int): String = String.format(Locale.US, "#%06X", 0xFFFFFF and color)

/** Parse #RGB or #RRGGBB (the leading # is optional). Returns an opaque ARGB color. */
fun parseHex(text: String): Int? {
    val clean = text.trim().removePrefix("#")
    if (clean.isEmpty() || clean.length > 6) return null
    if (clean.any { it !in "0123456789abcdefABCDEF" }) return null
    if (clean.length == 3) {
        val expanded = buildString {
            clean.forEach { append(it).append(it) }
        }
        val value = expanded.toLongOrNull(16) ?: return null
        return (0xFF000000L or value).toInt()
    }
    if (clean.length != 6) return null
    val value = clean.toLongOrNull(16) ?: return null
    return (0xFF000000L or value).toInt()
}

@Composable
fun ColorPickerSheet(
    initial: Int,
    recent: List<Int>,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
    onPickFromCanvas: (() -> Unit)? = null
) {
    val hsv = remember { FloatArray(3).also { android.graphics.Color.colorToHSV(initial, it) } }
    var hue by remember { mutableFloatStateOf(hsv[0]) }
    var saturation by remember { mutableFloatStateOf(hsv[1]) }
    var brightness by remember { mutableFloatStateOf(hsv[2]) }

    fun current(): Int = android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, brightness))

    fun applyPoint(x: Float, y: Float, width: Float, height: Float) {
        saturation = (x / width.coerceAtLeast(1f)).coerceIn(0f, 1f)
        brightness = 1f - (y / height.coerceAtLeast(1f)).coerceIn(0f, 1f)
    }

    val clipboard = LocalClipboardManager.current

    Column(Modifier.padding(horizontal = 20.dp, vertical = 2.dp)) {
        Text(
            "Color",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(14.dp))

        Canvas(
            Modifier
                .fillMaxWidth()
                .height(190.dp)
                .clip(RoundedCornerShape(18.dp))
                .positionGestures { point, size ->
                    applyPoint(point.x, point.y, size.width.toFloat(), size.height.toFloat())
                }
        ) {
            drawRect(Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 1f, 1f))))
            drawRect(Brush.horizontalGradient(listOf(Color.White, Color.White.copy(alpha = 0f))))
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
            val px = saturation * size.width
            val py = (1f - brightness) * size.height
            drawCircle(Color.Black.copy(alpha = 0.5f), 11f, Offset(px, py), style = Stroke(width = 3f))
            drawCircle(Color.White, 9f, Offset(px, py), style = Stroke(width = 2f))
        }

        Spacer(Modifier.height(14.dp))

        Canvas(
            Modifier
                .fillMaxWidth()
                .height(26.dp)
                .clip(RoundedCornerShape(50))
                .positionGestures { point, size ->
                    hue = (point.x / size.width.toFloat().coerceAtLeast(1f)).coerceIn(0f, 0.999f) * 360f
                }
        ) {
            drawRect(
                Brush.horizontalGradient(
                    listOf(
                        Color.Red, Color.Yellow, Color.Green, Color.Cyan,
                        Color.Blue, Color.Magenta, Color.Red
                    )
                )
            )
            val x = (hue / 360f) * size.width
            drawCircle(Color.White, 9f, Offset(x, size.height / 2f), style = Stroke(width = 3f))
        }

        Spacer(Modifier.height(16.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(Color(current()))
            )
            Spacer(Modifier.width(12.dp))
            HexColorField(
                value = current(),
                onValueChange = { hexValue ->
                    val out = FloatArray(3)
                    android.graphics.Color.colorToHSV(hexValue, out)
                    hue = out[0]
                    saturation = out[1]
                    brightness = out[2]
                },
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = {
                    clipboard.setText(AnnotatedString(hexOf(current())))
                }
            ) {
                Icon(Icons.Filled.ContentCopy, contentDescription = "Copy hex")
            }
        }

        Spacer(Modifier.height(8.dp))

        Text(
            "S ${(saturation * 100).roundToInt()}  ·  V ${(brightness * 100).roundToInt()}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(10.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onPickFromCanvas != null) {
                TextButton(onClick = onPickFromCanvas) {
                    Icon(
                        Icons.Filled.Colorize,
                        contentDescription = null,
                        modifier = Modifier.size(17.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Pick from canvas")
                }
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text("Cancel") }
            Button(
                onClick = { onPick(current()) },
                shape = RoundedCornerShape(12.dp)
            ) { Text("Use") }
        }

        Spacer(Modifier.height(14.dp))
        Text(
            "RECENT",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            recent.take(8).forEach { value ->
                val swatch = remember(value) { FloatArray(3).also { android.graphics.Color.colorToHSV(value, it) } }
                Box(
                    Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color(value))
                        .clickable {
                            hue = swatch[0]
                            saturation = swatch[1]
                            brightness = swatch[2]
                        }
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** Editable #RRGGBB field that keeps itself in sync with [value]. */
@Composable
fun HexColorField(
    value: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var text by remember { mutableStateOf(hexOf(value)) }
    var focused by remember { mutableStateOf(false) }

    // Only mirror the external color while the field is not being edited; otherwise
    // fast typing would be overwritten by an in-flight value update.
    LaunchedEffect(value, focused) {
        if (!focused) {
            val target = hexOf(value)
            if (text != target) text = target
        }
    }

    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            val digits = raw.uppercase(Locale.US).filter { it in "0123456789ABCDEF" }.take(6)
            text = if (raw.startsWith("#")) "#$digits" else digits
            parseHex(text)?.let(onValueChange)
        },
        label = { Text("Hex") },
        singleLine = true,
        isError = parseHex(text) == null,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Characters,
            keyboardType = KeyboardType.Ascii
        ),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.onFocusChanged { focused = it.isFocused }
    )
}

/** Responds on touch-down as well as during a drag, so taps pick a color too. */
private fun Modifier.positionGestures(
    onPosition: (Offset, androidx.compose.ui.unit.IntSize) -> Unit
): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        onPosition(down.position, size)
        while (true) {
            val event = awaitPointerEvent()
            if (event.changes.none { it.pressed }) break
            val change = event.changes.firstOrNull { it.pressed } ?: break
            onPosition(change.position, size)
            change.consume()
        }
    }
}
