package com.ply.editor.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.AutoFixOff
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ply.editor.engine.onnx.BackgroundModelId
import com.ply.editor.engine.onnx.InpaintModelId
import com.ply.editor.engine.onnx.SmartSelector
import com.ply.editor.model.RemoveMode
import com.ply.editor.model.ShapeKind
import com.ply.editor.model.Tool
import com.ply.editor.state.EditorViewModel
import com.ply.editor.state.LamaDownloadState
import kotlin.math.roundToInt

private data class ToolSpec(val tool: Tool, val icon: ImageVector)

private val tools = listOf(
    ToolSpec(Tool.MOVE, Icons.Filled.OpenWith),
    ToolSpec(Tool.BRUSH, Icons.Filled.Brush),
    ToolSpec(Tool.ERASER, Icons.Filled.AutoFixOff),
    ToolSpec(Tool.SHAPE, Icons.Filled.Category),
    ToolSpec(Tool.TEXT, Icons.Filled.TextFields),
    ToolSpec(Tool.INPAINT, Icons.Filled.AutoFixHigh),
    ToolSpec(Tool.EYEDROPPER, Icons.Filled.Colorize)
)

@Composable
fun ToolDock(
    selected: Tool,
    onSelect: (Tool) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        tools.forEach { spec ->
            val active = spec.tool == selected
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
                    )
                    .clickable { onSelect(spec.tool) }
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    spec.icon,
                    contentDescription = spec.tool.label,
                    tint = if (active) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(21.dp)
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    spec.tool.label,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    color = if (active) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium
                )
            }
        }
    }
}

@Composable
fun ToolOptions(
    vm: EditorViewModel,
    onPickColor: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            when (vm.tool) {
                Tool.BRUSH, Tool.ERASER -> BrushOptions(vm, onPickColor)
                Tool.FILL -> FillOptions(vm, onPickColor)
                Tool.SHAPE -> ShapeOptions(vm, onPickColor)
                Tool.TEXT -> TextOptions(vm, onPickColor)
                Tool.CROP -> CropOptions(vm)
                Tool.RESIZE -> ResizeOptions(vm)
                Tool.INPAINT -> RemoveOptions(vm)
                Tool.MOVE -> MoveOptions(vm)
                Tool.EYEDROPPER -> EyedropperOptions(vm, onPickColor)
                Tool.PAN -> Hint("Drag to pan · pinch to zoom")
            }
        }
    }
}

@Composable
private fun BrushOptions(vm: EditorViewModel, onPickColor: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ColorSwatch(vm.color, onPickColor)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            ValueSlider(
                label = "Size",
                value = vm.brushSize,
                valueText = "${vm.brushSize.roundToInt()} px",
                range = 1f..240f,
                onChange = { vm.brushSize = it }
            )
            ValueSlider(
                label = "Flow",
                value = vm.brushOpacity,
                valueText = "${(vm.brushOpacity * 100).roundToInt()}%",
                range = 0.05f..1f,
                onChange = { vm.brushOpacity = it }
            )
        }
    }
}

@Composable
private fun FillOptions(vm: EditorViewModel, onPickColor: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ColorSwatch(vm.color, onPickColor)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            ValueSlider(
                label = "Tolerance",
                value = vm.fillTolerance.toFloat(),
                valueText = "${vm.fillTolerance}",
                range = 0f..120f,
                onChange = { vm.fillTolerance = it.roundToInt() }
            )
        }
    }
}

@Composable
private fun ShapeOptions(vm: EditorViewModel, onPickColor: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ColorSwatch(vm.color, onPickColor)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ShapeKind.entries.forEach { kind ->
                    FilterChip(
                        selected = vm.shapeKind == kind,
                        onClick = { vm.shapeKind = kind },
                        label = {
                            Text(
                                when (kind) {
                                    ShapeKind.RECT -> "Rect"
                                    ShapeKind.ELLIPSE -> "Oval"
                                    ShapeKind.LINE -> "Line"
                                }
                            )
                        }
                    )
                }
                AssistChip(
                    onClick = { vm.shapeFilled = !vm.shapeFilled },
                    label = { Text(if (vm.shapeFilled) "Filled" else "Outline") }
                )
            }
            if (!vm.shapeFilled) {
                ValueSlider(
                    label = "Stroke",
                    value = vm.shapeStrokeWidth,
                    valueText = "${vm.shapeStrokeWidth.roundToInt()} px",
                    range = 1f..60f,
                    onChange = { vm.shapeStrokeWidth = it }
                )
            }
        }
    }
}

@Composable
private fun TextOptions(vm: EditorViewModel, onPickColor: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ColorSwatch(vm.color, onPickColor)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = { vm.requestText(null) },
                    shape = RoundedCornerShape(12.dp)
                ) { Text("Add text") }
                Spacer(Modifier.width(10.dp))
                Text(
                    "Tap canvas to place",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            ValueSlider(
                label = "Size",
                value = vm.textSize,
                valueText = "${vm.textSize.roundToInt()} sp",
                range = 24f..320f,
                onChange = { vm.textSize = it }
            )
        }
    }
}

@Composable
private fun RemoveOptions(vm: EditorViewModel) {
    Column {
        // Top row: the two main modes plus the mask reset.
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilterChip(
                selected = vm.removeMode != RemoveMode.BACKGROUND,
                onClick = {
                    if (vm.removeMode == RemoveMode.BACKGROUND) {
                        vm.selectRemoveMode(RemoveMode.BRUSH)
                    }
                },
                label = { Text("Object removal") },
                leadingIcon = {
                    Icon(Icons.Filled.AutoFixHigh, contentDescription = null, modifier = Modifier.size(16.dp))
                }
            )
            Spacer(Modifier.width(6.dp))
            FilterChip(
                selected = vm.removeMode == RemoveMode.BACKGROUND,
                onClick = {
                    if (vm.removeMode != RemoveMode.BACKGROUND) {
                        vm.selectRemoveMode(RemoveMode.BACKGROUND)
                    }
                },
                label = { Text("Background removal") },
                leadingIcon = {
                    Icon(Icons.Filled.Landscape, contentDescription = null, modifier = Modifier.size(16.dp))
                }
            )
            Spacer(Modifier.weight(1f))
            IconButton(
                onClick = {
                    vm.clearSmartTaps()
                    vm.clearInpaintMask()
                },
                enabled = vm.hasInpaintMask,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    Icons.Filled.RestartAlt,
                    contentDescription = "Clear mask",
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        // Second row: model dropdown + action button.
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (vm.removeMode == RemoveMode.BACKGROUND) {
                BgModelDropdown(vm, Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = vm::applyBackground,
                    enabled = vm.busy == null && vm.isBgModelReady(vm.bgModel),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.height(48.dp)
                ) { Text("Remove background") }
            } else {
                InpaintModelDropdown(vm, Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = vm::applyInpaint,
                    enabled = vm.hasInpaintMask && vm.busy == null,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.height(48.dp)
                ) { Text("Remove") }
            }
        }
        // Third row: a fixed-height strip so switching modes or models never
        // moves the panel up or down.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
        ) {
            if (vm.removeMode == RemoveMode.BACKGROUND) {
                DownloadControl(
                    state = vm.bgDownload,
                    error = vm.bgError.takeIf { !vm.isBgModelReady(vm.bgModel) },
                    sizeBytes = vm.bgModel.sizeBytes,
                    onStart = vm::startBgDownload,
                    onCancel = vm::cancelBgDownload,
                    modifier = Modifier.weight(1f)
                )
            } else {
                FilterChip(
                    selected = vm.removeMode == RemoveMode.BRUSH,
                    onClick = { vm.selectRemoveMode(RemoveMode.BRUSH) },
                    label = { Text("Brush") },
                    leadingIcon = {
                        Icon(Icons.Filled.Brush, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                )
                Spacer(Modifier.width(6.dp))
                FilterChip(
                    selected = vm.removeMode == RemoveMode.LASSO,
                    onClick = { vm.selectRemoveMode(RemoveMode.LASSO) },
                    label = { Text("Lasso") },
                    leadingIcon = {
                        Icon(Icons.Filled.Gesture, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                )
                Spacer(Modifier.width(6.dp))
                FilterChip(
                    selected = vm.removeMode == RemoveMode.TAP,
                    onClick = { vm.selectRemoveMode(RemoveMode.TAP) },
                    label = { Text("Tap") },
                    leadingIcon = {
                        Icon(Icons.Filled.TouchApp, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                )
                Spacer(Modifier.width(10.dp))
                when {
                    vm.removeMode == RemoveMode.TAP && vm.smartDownload != null -> {
                        DownloadControl(
                            state = vm.smartDownload,
                            error = null,
                            sizeBytes = SmartSelector.ENCODER_BYTES + SmartSelector.DECODER_BYTES,
                            onStart = vm::startSmartDownload,
                            onCancel = vm::cancelSmartDownload,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    vm.removeMode == RemoveMode.TAP && !vm.smartReady -> {
                        DownloadControl(
                            state = null,
                            error = vm.smartError,
                            sizeBytes = SmartSelector.ENCODER_BYTES + SmartSelector.DECODER_BYTES,
                            onStart = vm::startSmartDownload,
                            onCancel = vm::cancelSmartDownload,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    vm.inpaintModel == InpaintModelId.LAMA && vm.lamaDownload != null -> {
                        DownloadControl(
                            state = vm.lamaDownload,
                            error = null,
                            sizeBytes = InpaintModelId.LAMA.approximateBytes,
                            onStart = vm::startLamaDownload,
                            onCancel = vm::cancelLamaDownload,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    vm.inpaintModel == InpaintModelId.LAMA && !vm.lamaReady -> {
                        DownloadControl(
                            state = null,
                            error = vm.lamaError,
                            sizeBytes = InpaintModelId.LAMA.approximateBytes,
                            onStart = vm::startLamaDownload,
                            onCancel = vm::cancelLamaDownload,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    vm.removeMode == RemoveMode.BRUSH -> ValueSlider(
                        label = "Size",
                        value = vm.inpaintBrushSize,
                        valueText = "${vm.inpaintBrushSize.roundToInt()} px",
                        range = 8f..320f,
                        onChange = { vm.inpaintBrushSize = it },
                        modifier = Modifier.weight(1f)
                    )
                    vm.removeMode == RemoveMode.TAP -> Box(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun InpaintModelDropdown(vm: EditorViewModel, modifier: Modifier = Modifier) {
    ModelDropdown(
        entries = InpaintModelId.entries.toList(),
        selected = vm.inpaintModel,
        itemLabel = { it.label },
        enabled = vm.busy == null && vm.lamaDownload == null,
        onSelect = { vm.selectInpaintModel(it) },
        modifier = modifier
    )
}

@Composable
private fun BgModelDropdown(vm: EditorViewModel, modifier: Modifier = Modifier) {
    ModelDropdown(
        entries = BackgroundModelId.entries.toList(),
        selected = vm.bgModel,
        itemLabel = { it.label },
        enabled = vm.busy == null && vm.bgDownloadModel == null,
        onSelect = { vm.selectBgModel(it) },
        modifier = modifier
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> ModelDropdown(
    entries: List<T>,
    selected: T,
    itemLabel: (T) -> String,
    enabled: Boolean,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = it },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = itemLabel(selected),
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text("Model") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            entries.forEach { model ->
                DropdownMenuItem(
                    text = { Text(itemLabel(model)) },
                    onClick = {
                        onSelect(model)
                        expanded = false
                    }
                )
            }
        }
    }
}

/**
 * The download affordance for a model that is not on the device yet: progress
 * with a cancel while streaming, a size-labelled button otherwise, and the
 * failure message only when one exists.
 */
@Composable
private fun DownloadControl(
    state: LamaDownloadState?,
    error: String?,
    sizeBytes: Long,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    when {
        state != null -> Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = modifier
        ) {
            LinearProgressIndicator(
                progress = { state.fraction },
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (state.indeterminate) {
                    "…"
                } else {
                    "${megabytes(state.downloadedBytes)} / ${megabytes(state.totalBytes)} MB"
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            IconButton(onClick = onCancel, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Cancel download",
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        else -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
            modifier = modifier
        ) {
            if (error != null) {
                Text(
                    error,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(6.dp))
            }
            Button(
                onClick = onStart,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.height(40.dp)
            ) {
                Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("${megabytes(sizeBytes)} MB")
            }
        }
    }
}

private fun megabytes(bytes: Long): String = String.format(java.util.Locale.US, "%.0f", bytes / 1048576.0)

@Composable
private fun CropOptions(vm: EditorViewModel) {
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf<Pair<String, Float?>>(
                "Free" to null,
                "1:1" to 1f,
                "4:5" to 0.8f,
                "3:2" to 1.5f,
                "16:9" to 16f / 9f
            ).forEach { (label, ratio) ->
                FilterChip(
                    selected = vm.cropAspect == ratio,
                    onClick = { vm.cropAspect = ratio },
                    label = { Text(label) }
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            NudgeArrows { dx, dy -> vm.nudgeCrop(dx, dy) }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { vm.resetCropRect() }, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Filled.RestartAlt,
                    contentDescription = "Reset crop area",
                    modifier = Modifier.size(19.dp)
                )
            }
            IconButton(
                onClick = { vm.selectTool(Tool.MOVE) },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Cancel crop",
                    modifier = Modifier.size(19.dp)
                )
            }
            androidx.compose.material3.Button(
                onClick = { vm.applyCrop() },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.height(38.dp)
            ) { Text("Crop") }
        }
    }
}

@Composable
private fun MoveOptions(vm: EditorViewModel) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "1 px",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        NudgeArrows { dx, dy -> vm.nudgeActive(dx, dy) }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = vm::centerActive) { Text("Center") }
    }
}

@Composable
private fun EyedropperOptions(vm: EditorViewModel, onPickColor: () -> Unit) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val hex = String.format(java.util.Locale.US, "#%06X", 0xFFFFFF and vm.color)
    Row(verticalAlignment = Alignment.CenterVertically) {
        ColorSwatch(vm.color, onPickColor)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "Tap the canvas to sample a color",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    hex,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                IconButton(
                    onClick = {
                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(hex))
                        vm.message = "Copied $hex"
                    },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Filled.ContentCopy,
                        contentDescription = "Copy hex",
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun NudgeArrows(onNudge: (Int, Int) -> Unit) {
    NudgeButton(Icons.Filled.KeyboardArrowLeft, "Nudge left 1 px") { onNudge(-1, 0) }
    NudgeButton(Icons.Filled.KeyboardArrowUp, "Nudge up 1 px") { onNudge(0, -1) }
    NudgeButton(Icons.Filled.KeyboardArrowDown, "Nudge down 1 px") { onNudge(0, 1) }
    NudgeButton(Icons.Filled.KeyboardArrowRight, "Nudge right 1 px") { onNudge(1, 0) }
}

@Composable
private fun NudgeButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    IconButton(onClick = onClick, modifier = Modifier.size(30.dp)) {
        Icon(
            icon,
            contentDescription = label,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun ResizeOptions(vm: EditorViewModel) {
    val layer = vm.doc?.active
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (layer == null) {
                "Select a layer to resize"
            } else {
                val w = (layer.width * layer.scale * layer.scaleX).roundToInt()
                val h = (layer.height * layer.scale * layer.scaleY).roundToInt()
                "$w × $h px · drag a corner to keep the ratio, an edge to stretch one axis"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = vm::resetActiveStretch) { Text("Reset") }
        TextButton(onClick = vm::autoFitActive) { Text("Fit") }
    }
}

@Composable
private fun Hint(text: String) {
    Box(Modifier.heightIn(min = 56.dp), contentAlignment = Alignment.CenterStart) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ValueSlider(
    label: String,
    value: Float,
    valueText: String,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.weight(1f))
            Text(
                valueText,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest
            ),
            modifier = Modifier.height(30.dp)
        )
    }
}

@Composable
fun ColorSwatch(color: Int, onClick: () -> Unit) {
    Box(
        Modifier
            .size(42.dp)
            .clip(CircleShape)
            .background(Color(color))
            .clickable(onClick = onClick)
    )
}
