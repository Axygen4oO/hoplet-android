package com.wdtt.client.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.wdtt.client.HopletTheme

private const val HopletModalAnimationMillis = 220

object HopletModalDefaults {
    val panelShape: Shape = RoundedCornerShape(28.dp)
    val sheetShape: Shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    val menuShape: Shape = RoundedCornerShape(22.dp)
    val actionShape: Shape = RoundedCornerShape(18.dp)
    val fieldShape: Shape = RoundedCornerShape(18.dp)
    val listItemShape: Shape = RoundedCornerShape(18.dp)
    val iconShellShape: Shape = RoundedCornerShape(18.dp)

    @Composable
    fun scrimColor(): Color = HopletTheme.colors.overlay.copy(alpha = 0.84f)

    @Composable
    fun containerColor(): Color = AppCardDefaults.containerColor()

    @Composable
    fun softContainerColor(): Color {
        val colors = MaterialTheme.colorScheme
        val isDark = colors.background.luminance() < 0.22f
        return if (isDark) {
            colors.surfaceVariant.copy(alpha = 0.44f)
        } else {
            colors.surfaceVariant.copy(alpha = 0.72f)
        }
    }

    @Composable
    fun borderColor(): Color = appSectionCardBorderColor()

    @Composable
    fun border(): BorderStroke = BorderStroke(1.dp, borderColor())

    @Composable
    fun shadowElevation() = if (MaterialTheme.colorScheme.background.luminance() < 0.22f) 12.dp else 14.dp
}

@Composable
fun HopletDialog(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    properties: DialogProperties = DialogProperties(
        usePlatformDefaultWidth = false,
        decorFitsSystemWindows = false
    ),
    alignment: Alignment = Alignment.Center,
    widthFraction: Float = 0.94f,
    maxWidth: androidx.compose.ui.unit.Dp = 560.dp,
    surfaceModifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    var entered by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (entered) 1f else 0.96f,
        animationSpec = tween(durationMillis = HopletModalAnimationMillis),
        label = "hoplet_dialog_scale"
    )
    val alpha by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(durationMillis = HopletModalAnimationMillis),
        label = "hoplet_dialog_alpha"
    )
    val dismissOnOutside = properties.dismissOnClickOutside
    val interactionSource = remember { MutableInteractionSource() }

    LaunchedEffect(Unit) {
        entered = true
    }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = properties
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(HopletModalDefaults.scrimColor())
                .then(
                    if (dismissOnOutside) {
                        Modifier.clickable(
                            interactionSource = interactionSource,
                            indication = null,
                            onClick = onDismissRequest
                        )
                    } else {
                        Modifier
                    }
                )
                .navigationBarsPadding()
                .imePadding(),
            contentAlignment = alignment
        ) {
            Box(
                modifier = modifier
                    .fillMaxWidth(widthFraction)
                    .padding(horizontal = 12.dp, vertical = 12.dp)
                    .widthIn(max = maxWidth)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        this.alpha = alpha
                    }
                    .then(surfaceModifier)
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = {}
                    )
            ) {
                content()
            }
        }
    }
}

@Composable
fun HopletModalSurface(
    modifier: Modifier = Modifier,
    shape: Shape = HopletModalDefaults.panelShape,
    contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(16.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier,
        shape = shape,
        color = HopletModalDefaults.containerColor(),
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = HopletModalDefaults.border(),
        shadowElevation = HopletModalDefaults.shadowElevation(),
        tonalElevation = 0.dp
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

@Composable
fun HopletAlertDialog(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    properties: DialogProperties = DialogProperties(
        usePlatformDefaultWidth = false,
        decorFitsSystemWindows = false
    ),
    alignment: Alignment = Alignment.Center,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    confirmButton: (@Composable RowScope.() -> Unit)? = null,
    dismissButton: (@Composable RowScope.() -> Unit)? = null,
    surfaceModifier: Modifier = Modifier,
) {
    HopletDialog(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        properties = properties,
        alignment = alignment,
        surfaceModifier = surfaceModifier
    ) {
        HopletModalSurface(modifier = Modifier.fillMaxWidth()) {
            if (icon != null) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                            shape = HopletModalDefaults.iconShellShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    icon()
                }
            }

            if (title != null) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    title()
                }
            }

            if (text != null) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    text()
                }
            }

            if (confirmButton != null || dismissButton != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    dismissButton?.invoke(this)
                    confirmButton?.invoke(this)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HopletModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    dragHandle: @Composable (() -> Unit)? = { HopletBottomSheetHandle() },
    content: @Composable ColumnScope.() -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        shape = HopletModalDefaults.sheetShape,
        containerColor = HopletModalDefaults.containerColor(),
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp,
        scrimColor = HopletModalDefaults.scrimColor(),
        dragHandle = dragHandle
    ) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
            content = content
        )
    }
}

@Composable
fun HopletBottomSheetHandle() {
    Box(
        modifier = Modifier
            .padding(top = 10.dp, bottom = 6.dp)
            .fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .width(44.dp)
                .height(5.dp)
                .background(
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.28f),
                    shape = CircleShape
                )
        )
    }
}

@Composable
fun HopletPrimaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
    content: @Composable RowScope.() -> Unit
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(52.dp),
        enabled = enabled,
        shape = HopletModalDefaults.actionShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            contentColor = if (destructive) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        ),
        contentPadding = contentPadding,
        content = content
    )
}

@Composable
fun HopletSecondaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
    content: @Composable RowScope.() -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(52.dp),
        enabled = enabled,
        shape = HopletModalDefaults.actionShape,
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = HopletModalDefaults.softContainerColor(),
            contentColor = MaterialTheme.colorScheme.onSurface
        ),
        border = BorderStroke(1.dp, HopletModalDefaults.borderColor()),
        contentPadding = contentPadding,
        content = content
    )
}

@Composable
fun HopletTextActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {
    TextButton(
        onClick = onClick,
        modifier = modifier.height(44.dp),
        enabled = enabled,
        shape = RoundedCornerShape(14.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        colors = ButtonDefaults.textButtonColors(
            contentColor = MaterialTheme.colorScheme.primary,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        ),
        content = content
    )
}

@Composable
fun HopletSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
            checkedTrackColor = MaterialTheme.colorScheme.primary,
            checkedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.82f),
            uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
            uncheckedTrackColor = HopletModalDefaults.softContainerColor(),
            uncheckedBorderColor = HopletModalDefaults.borderColor(),
            disabledCheckedThumbColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f),
            disabledCheckedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
            disabledUncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            disabledUncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        )
    )
}

@Composable
fun hopletOutlinedTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.78f),
    unfocusedBorderColor = HopletModalDefaults.borderColor(),
    disabledBorderColor = HopletModalDefaults.borderColor().copy(alpha = 0.7f),
    errorBorderColor = MaterialTheme.colorScheme.error.copy(alpha = 0.86f),
    focusedContainerColor = HopletModalDefaults.softContainerColor(),
    unfocusedContainerColor = HopletModalDefaults.softContainerColor(),
    disabledContainerColor = HopletModalDefaults.softContainerColor().copy(alpha = 0.82f),
    errorContainerColor = HopletModalDefaults.softContainerColor(),
    focusedTextColor = MaterialTheme.colorScheme.onSurface,
    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
    disabledTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
    focusedLabelColor = MaterialTheme.colorScheme.primary,
    unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
    focusedSupportingTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
    unfocusedSupportingTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
    cursorColor = MaterialTheme.colorScheme.primary
)

@Composable
fun HopletDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset.Zero,
    content: @Composable ColumnScope.() -> Unit
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        offset = offset,
        modifier = modifier
            .border(1.dp, HopletModalDefaults.borderColor(), HopletModalDefaults.menuShape)
            .background(HopletModalDefaults.containerColor(), HopletModalDefaults.menuShape),
        containerColor = Color.Transparent,
        shape = HopletModalDefaults.menuShape,
        tonalElevation = 0.dp,
        shadowElevation = HopletModalDefaults.shadowElevation(),
        content = content
    )
}

@Composable
fun HopletDropdownMenuItem(
    text: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 10.dp)
) {
    DropdownMenuItem(
        text = text,
        onClick = onClick,
        modifier = modifier,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        enabled = enabled,
        contentPadding = contentPadding
    )
}

@Composable
fun HopletSectionTitle(
    text: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface
    )
}

@Composable
fun HopletSectionCaption(
    text: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
fun HopletDialogBodyText(
    text: String,
    modifier: Modifier = Modifier,
    textAlign: TextAlign? = null,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.bodyMedium,
        color = color,
        textAlign = textAlign
    )
}

@Composable
fun HopletDialogCloseButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    TextButton(
        onClick = onClick,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Icon(
            imageVector = Icons.Filled.Close,
            contentDescription = "Закрыть",
            modifier = Modifier.size(18.dp)
        )
    }
}
