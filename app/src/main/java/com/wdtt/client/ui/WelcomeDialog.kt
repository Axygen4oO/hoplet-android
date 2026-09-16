package com.wdtt.client.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.wdtt.client.HopletTheme
import com.wdtt.client.R

internal const val INITIAL_WELCOME_PAGE = 0
internal const val WELCOME_PAGE_COUNT = 3

enum class WelcomeDialogContext {
    FIRST_RUN,
    SETTINGS,
}

enum class WelcomeDialogCompletion {
    OPEN_SUBSCRIPTION,
    CLOSE,
}

internal fun nextWelcomePage(page: Int): Int = (page + 1).coerceAtMost(WELCOME_PAGE_COUNT - 1)

internal fun previousWelcomePage(page: Int): Int = (page - 1).coerceAtLeast(0)

internal fun welcomeDialogCompletion(context: WelcomeDialogContext): WelcomeDialogCompletion =
    if (context == WelcomeDialogContext.FIRST_RUN) {
        WelcomeDialogCompletion.OPEN_SUBSCRIPTION
    } else {
        WelcomeDialogCompletion.CLOSE
    }

internal fun shouldOpenSubscriptionAfterWelcome(context: WelcomeDialogContext): Boolean =
    welcomeDialogCompletion(context) == WelcomeDialogCompletion.OPEN_SUBSCRIPTION

@Composable
fun WelcomeDialog(
    context: WelcomeDialogContext,
    onDismiss: () -> Unit,
    onFinish: (() -> Unit)? = null,
) {
    var currentPage by rememberSaveable { mutableIntStateOf(INITIAL_WELCOME_PAGE) }
    var navigationDirection by remember { mutableIntStateOf(1) }
    var horizontalDrag by remember { mutableFloatStateOf(0f) }

    fun goNext() {
        if (currentPage < WELCOME_PAGE_COUNT - 1) {
            navigationDirection = 1
            currentPage = nextWelcomePage(currentPage)
        } else {
            (onFinish ?: onDismiss)()
        }
    }

    fun goBack() {
        if (currentPage > 0) {
            navigationDirection = -1
            currentPage = previousWelcomePage(currentPage)
        }
    }

    HopletDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        HopletModalSurface(
            modifier = Modifier
                .fillMaxWidth(0.91f)
                .fillMaxHeight(0.84f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
            verticalArrangement = Arrangement.Top,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                                Color.Transparent,
                            ),
                        ),
                    )
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragStart = { horizontalDrag = 0f },
                            onDragCancel = { horizontalDrag = 0f },
                            onDragEnd = {
                                when {
                                    horizontalDrag <= -72f -> goNext()
                                    horizontalDrag >= 72f -> goBack()
                                }
                                horizontalDrag = 0f
                            },
                        ) { change, dragAmount ->
                            change.consume()
                            horizontalDrag += dragAmount
                        }
                    },
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 22.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        contentAlignment = Alignment.CenterEnd,
                    ) {
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Закрыть onboarding",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    AnimatedContent(
                        targetState = currentPage,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        transitionSpec = {
                            (slideInHorizontally { width -> navigationDirection * width / 5 } + fadeIn()) togetherWith
                                (slideOutHorizontally { width -> -navigationDirection * width / 5 } + fadeOut())
                        },
                        label = "welcome_page_transition",
                    ) { page ->
                        WelcomePage(
                            page = page,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }

                    WelcomePagerIndicator(
                        currentPage = currentPage,
                        modifier = Modifier.padding(top = 8.dp, bottom = 14.dp),
                    )

                    when (currentPage) {
                        0 -> HopletPrimaryButton(
                            onClick = ::goNext,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Далее")
                        }

                        1 -> WelcomeNavigationRow(
                            onBack = ::goBack,
                            onNext = ::goNext,
                            nextLabel = "Далее",
                        )

                        else -> WelcomeNavigationRow(
                            onBack = ::goBack,
                            onNext = { (onFinish ?: onDismiss)() },
                            nextLabel = if (shouldOpenSubscriptionAfterWelcome(context)) {
                                "Начать работу"
                            } else {
                                "Готово"
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WelcomePage(
    page: Int,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top,
    ) {
        when (page) {
            0 -> WelcomeIntroPage()
            1 -> WelcomeSubscriptionPage()
            else -> WelcomeReadyPage()
        }
    }
}

@Composable
private fun WelcomeIntroPage() {
    WelcomeBrandMark()
    Spacer(Modifier.height(14.dp))
    Text(
        text = "Hoplet",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = "Приватное подключение\nв один клик",
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(6.dp))
    Text(
        text = "Быстро. Просто. Без лишнего.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(18.dp))
    ConnectionIllustration()
}

@Composable
private fun WelcomeSubscriptionPage() {
    WelcomeBrandMark()
    Spacer(Modifier.height(20.dp))
    Text(
        text = "Добавьте подписку",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(10.dp))
    Text(
        text = "Импортируйте ссылку wdtt://\nи выберите нужный профиль.",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(24.dp))
    SubscriptionFlowIllustration()
}

@Composable
private fun WelcomeReadyPage() {
    WelcomeBrandMark()
    Spacer(Modifier.height(20.dp))
    Text(
        text = "Готово к подключению",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(10.dp))
    Text(
        text = "Выберите профиль и нажмите\nкнопку подключения.",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(24.dp))
    ConnectButtonIllustration()
}

@Composable
private fun WelcomeBrandMark() {
    Image(
        painter = painterResource(R.drawable.hoplet_logo),
        contentDescription = "Логотип Hoplet",
        modifier = Modifier.size(58.dp),
    )
}

@Composable
private fun ConnectionIllustration() {
    val accent = MaterialTheme.colorScheme.primary
    val softAccent = accent.copy(alpha = 0.18f)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(184.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(
                brush = Brush.radialGradient(listOf(softAccent, Color.Transparent)),
                radius = size.minDimension * 0.44f,
                center = center,
            )
            drawLine(
                color = accent.copy(alpha = 0.45f),
                start = androidx.compose.ui.geometry.Offset(size.width * 0.27f, size.height * 0.5f),
                end = androidx.compose.ui.geometry.Offset(size.width * 0.73f, size.height * 0.5f),
                strokeWidth = 3.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IllustrationNode(
                icon = Icons.Rounded.Smartphone,
                label = "Смартфон",
                tint = MaterialTheme.colorScheme.onSurface,
            )
            IllustrationNode(
                icon = HopletTheme.icons.vpn,
                label = "Защита",
                tint = accent,
                emphasized = true,
            )
            IllustrationNode(
                icon = Icons.Rounded.Hub,
                label = "Сеть",
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun SubscriptionFlowIllustration() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FlowNode(text = "wdtt://", icon = Icons.Rounded.Wifi)
        FlowArrow()
        FlowNode(text = "Профиль", icon = HopletTheme.icons.wrap)
        FlowArrow()
        FlowNode(text = "Hoplet", icon = HopletTheme.icons.vpn, emphasized = true)
    }
}

@Composable
private fun ConnectButtonIllustration() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(116.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f))
                .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.52f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(82.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.PowerSettingsNew,
                    contentDescription = null,
                    modifier = Modifier.size(38.dp),
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
        Text(
            text = "Подключиться",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun IllustrationNode(
    icon: ImageVector,
    label: String,
    tint: Color,
    emphasized: Boolean = false,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(if (emphasized) 72.dp else 60.dp)
                .clip(CircleShape)
                .background(tint.copy(alpha = if (emphasized) 0.20f else 0.12f))
                .border(1.dp, tint.copy(alpha = 0.42f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier.size(if (emphasized) 34.dp else 28.dp),
                tint = tint,
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FlowNode(
    text: String,
    icon: ImageVector,
    emphasized: Boolean = false,
) {
    Row(
        modifier = Modifier
            .width(184.dp)
            .height(48.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (emphasized) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f),
            )
            .border(
                1.dp,
                if (emphasized) MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
                else HopletModalDefaults.borderColor(),
                RoundedCornerShape(16.dp),
            )
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = if (emphasized) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = if (emphasized) FontWeight.SemiBold else FontWeight.Medium,
        )
    }
}

@Composable
private fun FlowArrow() {
    Text(
        text = "↓",
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.75f),
    )
}

@Composable
private fun WelcomePagerIndicator(
    currentPage: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.semantics {
            contentDescription = "Страница ${currentPage + 1} из $WELCOME_PAGE_COUNT"
        },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(WELCOME_PAGE_COUNT) { page ->
            val selected = page == currentPage
            val indicatorWidth by animateDpAsState(
                targetValue = if (selected) 22.dp else 8.dp,
                animationSpec = tween(220),
                label = "welcome_indicator_width_$page",
            )
            val indicatorColor by animateColorAsState(
                targetValue = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.30f),
                animationSpec = tween(220),
                label = "welcome_indicator_color_$page",
            )
            Box(
                modifier = Modifier
                    .size(width = indicatorWidth, height = 8.dp)
                    .clip(CircleShape)
                    .background(indicatorColor),
            )
        }
    }
}

@Composable
private fun WelcomeNavigationRow(
    onBack: () -> Unit,
    onNext: () -> Unit,
    nextLabel: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        HopletSecondaryButton(
            onClick = onBack,
            modifier = Modifier.weight(1f),
        ) {
            Text("Назад")
        }
        HopletPrimaryButton(
            onClick = onNext,
            modifier = Modifier.weight(1f),
        ) {
            Text(nextLabel)
        }
    }
}
