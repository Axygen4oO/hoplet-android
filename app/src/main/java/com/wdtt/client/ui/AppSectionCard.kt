package com.wdtt.client.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp

object AppCardDefaults {
    @Composable
    fun containerColor(): Color {
        val colors = MaterialTheme.colorScheme
        return remember(colors.background, colors.surface) {
            val isDark = colors.background.luminance() < 0.22f
            if (isDark) {
                colors.surface.copy(alpha = 0.4f)
            } else {
                Color.White.copy(alpha = 0.5f)
            }
        }
    }
}

@Composable
internal fun appSectionCardBorderColor(): Color {
    val colors = MaterialTheme.colorScheme
    val isDark = colors.background.luminance() < 0.22f
    return if (isDark) {
        colors.outlineVariant.copy(alpha = 0.26f)
    } else {
        colors.outlineVariant.copy(alpha = 0.24f)
    }
}

@Composable
fun AppSectionCard(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 18.dp, vertical = 18.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(16.dp),
    border: BorderStroke? = null,
    color: Color? = null,
    shadowElevation: Dp? = null,
    tonalElevation: Dp? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = color ?: AppCardDefaults.containerColor(),
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = border ?: BorderStroke(1.dp, appSectionCardBorderColor()),
        shadowElevation = shadowElevation ?: if (MaterialTheme.colorScheme.background.luminance() < 0.22f) 2.dp else 10.dp,
        tonalElevation = tonalElevation ?: if (MaterialTheme.colorScheme.background.luminance() < 0.22f) 0.dp else 2.dp,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(contentPadding),
            verticalArrangement = verticalArrangement,
            content = content
        )
    }
}
