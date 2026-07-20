package com.wdtt.client.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowForwardIos
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.HeadsetMic
import androidx.compose.material.icons.outlined.Language

import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text

import androidx.compose.runtime.Composable

import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

import com.wdtt.client.AppLinks
import com.wdtt.client.R

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxHeight


private data class WelcomeItem(
    val icon: ImageVector,
    val title: String,
    val description: String,
    val onClick: () -> Unit,
)

@Composable
private fun WelcomeCard(
    item: WelcomeItem,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { item.onClick() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 2.dp
        )
    ) {

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {

            Icon(
                imageVector = item.icon,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.primary
            )

            Spacer(Modifier.size(16.dp))

            Column(
                modifier = Modifier.weight(1f)
            ) {

                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(Modifier.height(4.dp))

                Text(
                    text = item.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Icon(
                imageVector = Icons.Outlined.ArrowForwardIos,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.outline
            )
        }
    }
}

@Composable
fun WelcomeDialog(
    onDismiss: () -> Unit,
) {

    val context = LocalContext.current

    fun openUrl(url: String) {
        context.startActivity(
            Intent(
                Intent.ACTION_VIEW,
                Uri.parse(url)
            )
        )
    }

    val items = listOf(

        WelcomeItem(
            icon = Icons.Outlined.Language,
            title = "Личный кабинет",
            description = "Управление подпиской, устройствами и скачивание приложения.",
            onClick = {
                openUrl(AppLinks.WEBSITE)
            }
        ),

        WelcomeItem(
            icon = Icons.Outlined.Campaign,
            title = "Новости",
            description = "Новости проекта, обновления и важные объявления.",
            onClick = {
                openUrl(AppLinks.TELEGRAM_CHANNEL)
            }
        ),

        WelcomeItem(
            icon = Icons.Outlined.HeadsetMic,
            title = "Поддержка",
            description = "Если возникнут вопросы — мы всегда готовы помочь.",
            onClick = {

                if (AppLinks.SUPPORT_BOT.isBlank()) {

                    Toast.makeText(
                        context,
                        "Раздел поддержки скоро будет доступен.",
                        Toast.LENGTH_SHORT
                    ).show()

                } else {

                    openUrl(AppLinks.SUPPORT_BOT)

                }

            }
        )
    )
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false
        )
    ) {

        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.90f),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface
        ) {

            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {

                Image(
                    painter = painterResource(R.drawable.hoplet_logo),
                    contentDescription = null,
                    modifier = Modifier.size(64.dp)
                )

                Text(
                    text = "Добро пожаловать в Hoplet!",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )

                Text(
                    text =
                        "Всё готово к работе.\n\n" +
                                "Добавьте профиль во вкладке «Профили», " +
                                "после чего можно подключиться одним нажатием.\n\n" +
                                "Личный кабинет, новости проекта и поддержка " +
                                "всегда доступны ниже.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Spacer(
                    modifier = Modifier.height(4.dp)
                )

                items.forEachIndexed { index, item ->

                    WelcomeCard(item)

                    if (index != items.lastIndex) {

                        Spacer(
                            modifier = Modifier.height(10.dp)
                        )

                    }
                }

                Spacer(
                    modifier = Modifier.height(6.dp)
                )
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Text("Начать работу")
                }
            }
        }
    }
}