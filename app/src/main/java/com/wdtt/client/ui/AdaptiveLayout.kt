package com.wdtt.client.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class AppLayoutMode {
    Normal,
    Compact,
    Accessibility
}

@Composable
fun rememberAppLayoutMode(maxWidth: Dp? = null): AppLayoutMode {
    val fontScale = LocalDensity.current.fontScale
    return remember(fontScale, maxWidth) {
        resolveAppLayoutMode(fontScale = fontScale, maxWidth = maxWidth)
    }
}

fun resolveAppLayoutMode(fontScale: Float, maxWidth: Dp? = null): AppLayoutMode {
    return when {
        maxWidth != null && maxWidth < 330.dp -> {
            AppLayoutMode.Accessibility
        }

        fontScale >= 1.7f || (fontScale >= 1.45f && maxWidth != null && maxWidth < 390.dp) -> {
            AppLayoutMode.Accessibility
        }

        fontScale >= 1.3f || (maxWidth != null && maxWidth < 420.dp) -> {
            AppLayoutMode.Compact
        }

        else -> AppLayoutMode.Normal
    }
}
