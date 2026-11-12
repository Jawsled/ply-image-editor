package com.ply.editor.state

import android.net.Uri
import java.util.UUID

sealed interface EditorTarget {
    val key: String

    data class Project(val id: String) : EditorTarget {
        override val key: String get() = "project:$id"
    }

    data class Image(val uri: Uri) : EditorTarget {
        override val key: String get() = "image:$uri"
    }

    data class Blank(
        val width: Int,
        val height: Int,
        val background: Int,
        val name: String = "Untitled",
        val token: String = UUID.randomUUID().toString()
    ) : EditorTarget {
        override val key: String get() = "blank:$token"
    }
}
