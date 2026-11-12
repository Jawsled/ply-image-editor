package com.ply.editor.settings

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ply.editor.engine.onnx.BackgroundModelId
import com.ply.editor.engine.onnx.InpaintModelId
import com.ply.editor.io.ExportFormat

enum class ThemeMode { SYSTEM, LIGHT, DARK }

class Settings private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("ply_settings", Context.MODE_PRIVATE)

    companion object {
        @Volatile
        private var cached: Settings? = null

        fun get(context: Context): Settings = cached ?: synchronized(this) {
            cached ?: Settings(context.applicationContext).also { cached = it }
        }
    }

    var themeMode by mutableStateOf(
        runCatching { ThemeMode.valueOf(prefs.getString("theme", "DARK")!!) }
            .getOrDefault(ThemeMode.DARK)
    )
        private set
    var dynamicColor by mutableStateOf(prefs.getBoolean("dynamic_color", false))
        private set
    var showGrid by mutableStateOf(prefs.getBoolean("grid", false))
        private set
    var exportFormat by mutableStateOf(
        runCatching { ExportFormat.valueOf(prefs.getString("export_format", "PNG")!!) }
            .getOrDefault(ExportFormat.PNG)
    )
        private set
    var jpegQuality by mutableIntStateOf(prefs.getInt("quality", 92))
        private set
    var inpaintModel by mutableStateOf(
        InpaintModelId.fromStored(prefs.getString("inpaint_model", null))
    )
        private set
    var bgModel by mutableStateOf(
        BackgroundModelId.fromStored(prefs.getString("bg_model", null))
    )
        private set

    fun setTheme(mode: ThemeMode) {
        themeMode = mode
        prefs.edit().putString("theme", mode.name).apply()
    }

    fun updateInpaintModel(model: InpaintModelId) {
        inpaintModel = model
        prefs.edit().putString("inpaint_model", model.name).apply()
    }

    fun updateBgModel(model: BackgroundModelId) {
        bgModel = model
        prefs.edit().putString("bg_model", model.name).apply()
    }

    fun updateDynamicColor(enabled: Boolean) {
        dynamicColor = enabled
        prefs.edit().putBoolean("dynamic_color", enabled).apply()
    }

    fun updateShowGrid(enabled: Boolean) {
        showGrid = enabled
        prefs.edit().putBoolean("grid", enabled).apply()
    }

    fun updateExportFormat(format: ExportFormat) {
        exportFormat = format
        prefs.edit().putString("export_format", format.name).apply()
    }

    fun updateJpegQuality(value: Int) {
        jpegQuality = value.coerceIn(40, 100)
        prefs.edit().putInt("quality", jpegQuality).apply()
    }
}
