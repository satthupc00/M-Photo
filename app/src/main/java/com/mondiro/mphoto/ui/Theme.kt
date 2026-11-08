package com.mondiro.mphoto.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object Mc {
    val chrome = Color(0xFF1F2126)
    val panel = Color(0xFF26292F)
    val line = Color(0xFF363A42)
    val hover = Color(0xFF2F333A)
    val stage = Color(0xFF2B2D31)
    val text = Color(0xFFE8E9EC)
    val muted = Color(0xFF8D929C)
    val red = Color(0xFFE5383B)
    val redSoft = Color(0xFFFF8A8C)
    val selected = Color(0xFF3A2426)
    val green = Color(0xFF3DDC8A)
    val latest = Color(0xFF2EB872)
    val star = Color(0xFFFFC93C)
    val credit = Color(0xFF7B808A)
}

@Composable
fun MPhotoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Mc.red,
            onPrimary = Color.White,
            secondary = Mc.red,
            background = Mc.chrome,
            surface = Mc.panel,
            surfaceVariant = Mc.panel,
            surfaceContainerHigh = Mc.panel,
            surfaceContainer = Mc.panel,
            onSurface = Mc.text,
            onSurfaceVariant = Mc.muted,
            outline = Mc.line,
            error = Mc.red,
        ),
        content = content,
    )
}
