package com.ply.editor.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ply.editor.io.ExportFormat
import com.ply.editor.model.TextSpec
import kotlin.math.roundToInt

private val DialogShape = RoundedCornerShape(28.dp)

@Composable
fun RenameDialog(
    title: String,
    initial: String,
    label: String = "Name",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var value by remember { mutableStateOf(initial) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = DialogShape,
        title = { Text(title, style = MaterialTheme.typography.headlineMedium) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it.take(60) },
                singleLine = true,
                label = { Text(label) },
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(value) },
                enabled = value.isNotBlank()
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun CanvasSizeDialog(
    initialWidth: Int,
    initialHeight: Int,
    onDismiss: () -> Unit,
    onApply: (Int, Int) -> Unit
) {
    var width by remember { mutableStateOf(initialWidth.toString()) }
    var height by remember { mutableStateOf(initialHeight.toString()) }
    val w = width.toIntOrNull()
    val h = height.toIntOrNull()
    val valid = w != null && h != null && w in 32..4096 && h in 32..4096

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = DialogShape,
        title = { Text("Canvas size", style = MaterialTheme.typography.headlineMedium) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Changing the canvas keeps your artwork where it is. Use Scale content to resize the picture itself.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = width,
                        onValueChange = { width = it.filter(Char::isDigit).take(4) },
                        label = { Text("Width") },
                        singleLine = true,
                        isError = !valid,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = height,
                        onValueChange = { height = it.filter(Char::isDigit).take(4) },
                        label = { Text("Height") },
                        singleLine = true,
                        isError = !valid,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    "PRESETS",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        "1:1" to (2048 to 2048),
                        "9:16" to (1080 to 1920),
                        "4:5" to (1080 to 1350),
                        "16:9" to (1920 to 1080)
                    ).forEach { (name, dims) ->
                        FilterChip(
                            selected = w == dims.first && h == dims.second,
                            onClick = {
                                width = dims.first.toString()
                                height = dims.second.toString()
                            },
                            label = { Text(name) }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "32 – 4096 px per side",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (valid) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.error
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (w != null && h != null) onApply(w, h) },
                enabled = valid
            ) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExportDialog(
    initialFormat: ExportFormat,
    initialQuality: Int,
    onDismiss: () -> Unit,
    onExport: (ExportFormat, Int, Boolean) -> Unit
) {
    var format by remember { mutableStateOf(initialFormat) }
    var quality by remember { mutableIntStateOf(initialQuality) }
    var shareAfter by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = DialogShape,
        title = { Text("Save image", style = MaterialTheme.typography.headlineMedium) },
        text = {
            Column {
                Text(
                    "Flattened copy saved to Pictures/Ply. Your layered project stays editable.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(14.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    ExportFormat.entries.forEach { option ->
                        FilterChip(
                            selected = format == option,
                            onClick = { format = option },
                            label = { Text(option.label) }
                        )
                    }
                }
                if (format.supportsQuality) {
                    Spacer(Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Quality",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            "$quality%",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Slider(
                        value = quality.toFloat(),
                        onValueChange = { quality = it.roundToInt() },
                        valueRange = 40f..100f
                    )
                }
                if (format == ExportFormat.HEIC || format == ExportFormat.JXL) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        when (format) {
                            ExportFormat.HEIC -> "No transparency — transparent areas export white"
                            else -> "Keeps transparency, smaller files than PNG"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { shareAfter = !shareAfter }
                        .padding(vertical = 6.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    androidx.compose.material3.Checkbox(
                        checked = shareAfter,
                        onCheckedChange = { shareAfter = it }
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Share straight after export",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onExport(format, quality, shareAfter) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun AdjustDialog(
    onDismiss: () -> Unit,
    onApply: (Float, Float, Float) -> Unit
) {
    var brightness by remember { mutableFloatStateOf(0f) }
    var contrast by remember { mutableFloatStateOf(1f) }
    var saturation by remember { mutableFloatStateOf(1f) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = DialogShape,
        title = { Text("Adjust", style = MaterialTheme.typography.headlineMedium) },
        text = {
            Column {
                Text(
                    "Applied to the active layer. Undo is always available.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                AdjustRow("Brightness", brightness, -0.5f..0.5f) { brightness = it }
                AdjustRow("Contrast", contrast, 0.2f..2f) { contrast = it }
                AdjustRow("Saturation", saturation, 0f..2f) { saturation = it }
                Spacer(Modifier.height(6.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(6.dp))
                TextButton(
                    onClick = {
                        brightness = 0f
                        contrast = 1f
                        saturation = 1f
                    },
                    modifier = Modifier.align(Alignment.End)
                ) { Text("Reset") }
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(brightness, contrast, saturation) }) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun AdjustRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.width(92.dp)
        )
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            modifier = Modifier.weight(1f)
        )
        Text(
            String.format(java.util.Locale.US, "%.2f", value),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .width(48.dp)
                .padding(start = 8.dp)
        )
    }
}

private val textPalette = listOf(
    0xFFFFFFFF.toInt(),
    0xFF292A2F.toInt(),
    0xFFB6C4FF.toInt(),
    0xFF34457A.toInt(),
    0xFFE3E8FF.toInt(),
    0xFFE4572E.toInt(),
    0xFF4A7BD8.toInt(),
    0xFF9B6BD6.toInt()
)

@Composable
fun TextDialog(
    initial: TextSpec?,
    fallbackSize: Float,
    fallbackColor: Int,
    fallbackBold: Boolean,
    fallbackItalic: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (TextSpec) -> Unit
) {
    var text by remember { mutableStateOf(initial?.text ?: "") }
    var size by remember { mutableFloatStateOf(initial?.sizePx ?: fallbackSize) }
    var color by remember { mutableIntStateOf(initial?.color ?: fallbackColor) }
    var bold by remember { mutableStateOf(initial?.bold ?: fallbackBold) }
    var italic by remember { mutableStateOf(initial?.italic ?: fallbackItalic) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = DialogShape,
        title = {
            Text(
                if (initial != null) "Edit text" else "Add text",
                style = MaterialTheme.typography.headlineMedium
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(600) },
                    label = { Text("Text") },
                    minLines = 3,
                    maxLines = 6,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = bold,
                        onClick = { bold = !bold },
                        label = { Text("Bold") }
                    )
                    FilterChip(
                        selected = italic,
                        onClick = { italic = !italic },
                        label = { Text("Italic") }
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Size",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        "${size.roundToInt()} px",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Slider(
                    value = size,
                    onValueChange = { size = it },
                    valueRange = 24f..320f
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "COLOR",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    textPalette.forEach { swatch ->
                        Box(
                            Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(Color(swatch))
                                .then(
                                    if (color == swatch) Modifier.border(
                                        2.dp,
                                        MaterialTheme.colorScheme.onSurface,
                                        CircleShape
                                    ) else Modifier
                                )
                                .clickable { color = swatch }
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                HexColorField(
                    value = color,
                    onValueChange = { color = it },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        TextSpec(
                            text = text,
                            sizePx = size,
                            color = color,
                            bold = bold,
                            italic = italic
                        )
                    )
                },
                enabled = text.isNotBlank()
            ) { Text("Done") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
