package com.ply.editor.ui.editor

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MergeType
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.FormatColorFill
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LayersClear
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.RotateLeft
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ply.editor.LoadingPane
import com.ply.editor.io.PickImageSource
import com.ply.editor.model.Layer
import com.ply.editor.model.Tool
import com.ply.editor.state.EditorTarget
import com.ply.editor.state.EditorViewModel
import kotlinx.coroutines.launch

private enum class ColorTarget { PAINT, BACKGROUND }

@Composable
fun EditorRoute(
    target: EditorTarget,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as Application
    val factory = remember {
        viewModelFactory {
            initializer { EditorViewModel(app) }
        }
    }

    // A private store per editor visit: leaving the editor clears its ViewModel,
    // releasing layer bitmaps and history instead of keeping every project alive.
    val store = remember(target.key) { ViewModelStore() }
    val owner = remember(store) {
        object : ViewModelStoreOwner {
            override val viewModelStore: ViewModelStore = store
        }
    }
    DisposableEffect(store) {
        onDispose { store.clear() }
    }

    CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
        val vm: EditorViewModel = viewModel(factory = factory)

        LaunchedEffect(target.key) { vm.load(target) }
        DisposableEffect(vm) {
            onDispose { vm.saveBlocking() }
        }

        EditorScreen(vm = vm, onBack = onBack)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    vm: EditorViewModel,
    onBack: () -> Unit
) {
    var showExitConfirm by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun leaveEditor() {
        if (saving) return
        saving = true
        scope.launch {
            vm.saveAndWait()
            showExitConfirm = false
            onBack()
        }
    }

    fun handleBack() {
        when (vm.tool) {
            Tool.CROP, Tool.RESIZE, Tool.PAN, Tool.FILL, Tool.INPAINT -> vm.selectTool(Tool.MOVE)
            else -> showExitConfirm = true
        }
    }

    BackHandler { handleBack() }

    val doc = vm.doc
    if (doc == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (vm.loading) {
                LoadingPane()
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = vm.message ?: "Could not open that file",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(onClick = onBack) { Text("Back to projects") }
                }
            }
        }
        return
    }

    val context = LocalContext.current
    val settings = vm.settings
    val snackbar = remember { SnackbarHostState() }

    var showLayers by remember { mutableStateOf(false) }
    var showColor by remember { mutableStateOf(false) }
    var showAdjust by remember { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }
    var showCanvasSize by remember { mutableStateOf(false) }
    var showRenameDoc by remember { mutableStateOf(false) }
    var layerToRename by remember { mutableStateOf<Layer?>(null) }
    var colorTarget by remember { mutableStateOf(ColorTarget.PAINT) }
    var menuOpen by remember { mutableStateOf(false) }
    var addOpen by remember { mutableStateOf(false) }
    var transformOpen by remember { mutableStateOf(false) }
    var shareAfterExport by remember { mutableStateOf(false) }

    val imagePicker = rememberLauncherForActivityResult(
        PickImageSource()
    ) { uri ->
        uri?.let { vm.addImageFromUri(it) }
    }

    fun pickImage() {
        imagePicker.launch(Unit)
    }

    LaunchedEffect(vm.message) {
        val text = vm.message ?: return@LaunchedEffect
        vm.consumeMessage()
        snackbar.showSnackbar(text)
    }

    // The eyedropper copies every sampled hex so it can be pasted straight away.
    LaunchedEffect(vm.pickPulse) {
        val hex = vm.pickedHex ?: return@LaunchedEffect
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return@LaunchedEffect
        clipboard.setPrimaryClip(ClipData.newPlainText("Ply color", hex))
    }

    LaunchedEffect(vm.lastExport) {
        val result = vm.lastExport ?: return@LaunchedEffect
        if (shareAfterExport) {
            shareAfterExport = false
            shareImage(context, result.uri)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(hostState = snackbar) },
        topBar = {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .height(54.dp)
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BarIcon(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        label = "Back to projects",
                        onClick = { handleBack() }
                    )
                    Column(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { showRenameDoc = true }
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            doc.name,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            "${doc.width} × ${doc.height}  ·  ${doc.layers.size} layer${if (doc.layers.size == 1) "" else "s"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Box {
                        BarIcon(
                            icon = Icons.Filled.Add,
                            label = "Add layer",
                            onClick = { addOpen = true }
                        )
                        DropdownMenu(expanded = addOpen, onDismissRequest = { addOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Image layer") },
                                leadingIcon = {
                                    Icon(Icons.Filled.PhotoLibrary, contentDescription = null)
                                },
                                onClick = {
                                    addOpen = false
                                    pickImage()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Empty layer") },
                                leadingIcon = { Icon(Icons.Filled.Layers, contentDescription = null) },
                                onClick = {
                                    addOpen = false
                                    vm.addEmptyLayer()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Text layer") },
                                leadingIcon = {
                                    Icon(Icons.Filled.TextFields, contentDescription = null)
                                },
                                onClick = {
                                    addOpen = false
                                    vm.requestText(null)
                                }
                            )
                        }
                    }

                    Box {
                        BarIcon(
                            icon = Icons.Filled.Crop,
                            label = "Transform",
                            onClick = { transformOpen = true }
                        )
                        TransformMenu(
                            expanded = transformOpen,
                            onDismiss = { transformOpen = false },
                            vm = vm
                        )
                    }

                    BarIcon(
                        icon = Icons.Filled.Save,
                        label = "Save image",
                        onClick = { showExport = true }
                    )

                    Box {
                        BarIcon(
                            icon = Icons.Filled.MoreVert,
                            label = "More",
                            onClick = { menuOpen = true }
                        )
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Layers…") },
                                leadingIcon = { Icon(Icons.Filled.Layers, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    showLayers = true
                                }
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            DropdownMenuItem(
                                text = { Text("Adjustments") },
                                leadingIcon = { Icon(Icons.Filled.Tune, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    showAdjust = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Canvas size") },
                                leadingIcon = { Icon(Icons.Filled.AspectRatio, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    showCanvasSize = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Canvas color") },
                                leadingIcon = { Icon(Icons.Filled.Palette, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    colorTarget = ColorTarget.BACKGROUND
                                    showColor = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Color…") },
                                leadingIcon = { Icon(Icons.Filled.Palette, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    colorTarget = ColorTarget.PAINT
                                    showColor = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Flood fill") },
                                leadingIcon = { Icon(Icons.Filled.FormatColorFill, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    vm.selectTool(Tool.FILL)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Pan canvas") },
                                leadingIcon = { Icon(Icons.Filled.PanTool, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    vm.selectTool(Tool.PAN)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Scale content to 50%") },
                                leadingIcon = { Icon(Icons.Filled.ZoomOutMap, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    vm.scaleContent(0.5f)
                                }
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            DropdownMenuItem(
                                text = { Text("Rule-of-thirds grid") },
                                leadingIcon = { Icon(Icons.Filled.GridOn, contentDescription = null) },
                                trailingIcon = {
                                    if (settings.showGrid) {
                                        Icon(Icons.Filled.Check, contentDescription = null)
                                    }
                                },
                                onClick = {
                                    menuOpen = false
                                    settings.updateShowGrid(!settings.showGrid)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Rename project") },
                                onClick = {
                                    menuOpen = false
                                    showRenameDoc = true
                                }
                            )
                        }
                    }
                }
            }
        },
        bottomBar = {
            EditorBottomBar(
                vm = vm,
                onPickColor = {
                    colorTarget = ColorTarget.PAINT
                    showColor = true
                }
            )
        }
    ) { inner ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(inner)
        ) {
            EditorCanvas(
                vm = vm,
                doc = doc,
                showGrid = settings.showGrid,
                fitKey = 0,
                modifier = Modifier.fillMaxSize()
            )

            LayerRail(
                doc = doc,
                onSelect = vm::selectLayer,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 10.dp, end = 10.dp)
            )

            if (vm.loading) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }

            vm.busy?.let { label ->
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 12.dp)
                ) {
                    Row(
                        Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            label,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }

    val pendingPoint = vm.pendingTextPoint
    if (pendingPoint != null) {
        val editingId = vm.editingTextLayerId
        val existing = editingId?.let { id -> doc.layers.firstOrNull { it.id == id }?.text }
        TextDialog(
            initial = existing,
            fallbackSize = vm.textSize,
            fallbackColor = vm.color,
            fallbackBold = vm.textBold,
            fallbackItalic = vm.textItalic,
            onDismiss = vm::dismissTextRequest,
            onConfirm = { spec ->
                vm.textSize = spec.sizePx
                vm.textBold = spec.bold
                vm.textItalic = spec.italic
                vm.chooseColor(spec.color)
                if (editingId != null) {
                    vm.updateTextLayer(editingId, spec)
                } else {
                    vm.addText(spec, pendingPoint)
                }
            }
        )
    }

    layerToRename?.let { layer ->
        RenameDialog(
            title = "Rename layer",
            initial = layer.name,
            onDismiss = { layerToRename = null },
            onConfirm = { name ->
                vm.renameLayer(layer.id, name)
                layerToRename = null
            }
        )
    }

    if (showRenameDoc) {
        RenameDialog(
            title = "Rename project",
            initial = doc.name,
            onDismiss = { showRenameDoc = false },
            onConfirm = { name ->
                vm.renameDocument(name)
                showRenameDoc = false
            }
        )
    }

    if (showCanvasSize) {
        CanvasSizeDialog(
            initialWidth = doc.width,
            initialHeight = doc.height,
            onDismiss = { showCanvasSize = false },
            onApply = { w, h ->
                showCanvasSize = false
                vm.resizeCanvas(w, h)
            }
        )
    }

    if (showAdjust) {
        AdjustDialog(
            onDismiss = { showAdjust = false },
            onApply = { brightness, contrast, saturation ->
                showAdjust = false
                vm.applyAdjustments(brightness, contrast, saturation)
            }
        )
    }

    if (showExport) {
        ExportDialog(
            initialFormat = settings.exportFormat,
            initialQuality = settings.jpegQuality,
            onDismiss = { showExport = false },
            onExport = { format, quality, share ->
                showExport = false
                shareAfterExport = share
                settings.updateExportFormat(format)
                settings.updateJpegQuality(quality)
                vm.export(format, quality)
            }
        )
    }

    if (showLayers) {
        ModalBottomSheet(
            onDismissRequest = { showLayers = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            dragHandle = { BottomSheetDefaults.DragHandle() }
        ) {
            LayersSheet(
                vm = vm,
                onRename = { layerToRename = it },
                onAddImage = {
                    showLayers = false
                    pickImage()
                },
                onAddText = {
                    showLayers = false
                    vm.requestText(null)
                }
            )
        }
    }

    if (showColor) {
        ModalBottomSheet(
            onDismissRequest = { showColor = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            dragHandle = { BottomSheetDefaults.DragHandle() }
        ) {
            ColorPickerSheet(
                initial = if (colorTarget == ColorTarget.BACKGROUND) doc.background else vm.color,
                recent = vm.recentColors,
                onPick = { picked ->
                    if (colorTarget == ColorTarget.BACKGROUND) {
                        vm.setBackground(picked)
                    } else {
                        vm.chooseColor(picked)
                    }
                    showColor = false
                },
                onDismiss = { showColor = false },
                onPickFromCanvas = if (colorTarget == ColorTarget.PAINT) {
                    {
                        showColor = false
                        vm.selectTool(Tool.EYEDROPPER)
                        vm.message = "Tap the canvas to pick a color"
                    }
                } else {
                    null
                }
            )
        }
    }

    if (showExitConfirm) {
        AlertDialog(
            onDismissRequest = { if (!saving) showExitConfirm = false },
            shape = RoundedCornerShape(28.dp),
            title = { Text("Leave editor?") },
            text = {
                Text("Your project is saved before the editor closes. You can reopen it from the projects screen.")
            },
            confirmButton = {
                TextButton(onClick = { leaveEditor() }, enabled = !saving) {
                    if (saving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                    }
                    Text(
                        text = if (saving) "Saving…" else "Save & exit",
                        modifier = Modifier.padding(start = if (saving) 8.dp else 0.dp)
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showExitConfirm = false }, enabled = !saving) {
                    Text("Keep editing")
                }
            }
        )
    }
}

@Composable
private fun EditorBottomBar(
    vm: EditorViewModel,
    onPickColor: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 2.dp
    ) {
        Column(Modifier.navigationBarsPadding()) {
            ToolOptions(vm = vm, onPickColor = onPickColor)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ToolDock(selected = vm.tool, onSelect = vm::selectTool)
        }
    }
}

@Composable
private fun BarIcon(
    icon: ImageVector,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
            },
            modifier = Modifier.size(21.dp)
        )
    }
}

@Composable
private fun TransformMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    vm: EditorViewModel
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        MenuAction("Resize layer", Icons.Filled.OpenInFull) {
            onDismiss()
            vm.selectTool(Tool.RESIZE)
        }
        MenuAction("Crop layer", Icons.Filled.Crop) {
            onDismiss()
            vm.selectTool(Tool.CROP)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        MenuAction("Rotate 90° left", Icons.Filled.RotateLeft) {
            onDismiss()
            vm.rotateActive(clockwise = false)
        }
        MenuAction("Rotate 90° right", Icons.Filled.RotateRight) {
            onDismiss()
            vm.rotateActive(clockwise = true)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        MenuAction("Flip horizontal", Icons.Filled.Flip) {
            onDismiss()
            vm.flipActive(horizontal = true)
        }
        MenuAction("Flip vertical", Icons.Filled.Flip, iconRotation = 90f) {
            onDismiss()
            vm.flipActive(horizontal = false)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        MenuAction("Auto-fit to canvas", Icons.Filled.FitScreen) {
            onDismiss()
            vm.autoFitActive()
        }
        MenuAction("Reset stretch", Icons.Filled.AspectRatio) {
            onDismiss()
            vm.resetActiveStretch()
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        MenuAction("Merge down", Icons.AutoMirrored.Filled.MergeType) {
            onDismiss()
            vm.mergeDown()
        }
        MenuAction("Flatten layers", Icons.Filled.Layers) {
            onDismiss()
            vm.flatten()
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        MenuAction("Fill layer", Icons.Filled.FormatColorFill) {
            onDismiss()
            vm.fillActive()
        }
        MenuAction("Clear layer", Icons.Filled.LayersClear) {
            onDismiss()
            vm.clearActiveLayer()
        }
    }
}

@Composable
private fun MenuAction(
    label: String,
    icon: ImageVector,
    iconRotation: Float = 0f,
    onClick: () -> Unit
) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = {
            Icon(
                icon,
                contentDescription = null,
                modifier = if (iconRotation == 0f) Modifier else Modifier.rotate(iconRotation)
            )
        },
        onClick = onClick
    )
}

private fun shareImage(context: Context, uri: Uri) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "image/*"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching {
        context.startActivity(Intent.createChooser(intent, "Share image"))
    }
}
